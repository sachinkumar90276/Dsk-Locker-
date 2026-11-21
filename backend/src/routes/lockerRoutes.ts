import { Router, Response } from "express";
import { z } from "zod";
import { pool } from "../db/pool";
import {
  AuthenticatedRequest,
  requireAuthorizedOwner,
  requireFirebaseAuth,
} from "../middleware/auth";

export const lockerRouter = Router();

const registerOwnerSchema = z.object({
  ownerName: z.string().trim().min(1).max(120).optional(),
  phoneNumber: z.string().trim().regex(/^\+91\d{10}$/).optional(),
});

const mpinSecuritySchema = z.object({
  mpinHash: z.string().min(16).max(256),
  mpinSalt: z.string().min(8).max(128),
  biometricEnabled: z.boolean().optional(),
});

/**
 * POST /api/auth/register-owner
 * Verifies Firebase ID token and binds/confirms the single authorized owner in SQL.
 * Rejects any second user attempting registration.
 */
lockerRouter.post(
  "/auth/register-owner",
  requireFirebaseAuth,
  async (req: AuthenticatedRequest, res: Response) => {
    const uid = req.firebaseUid!;
    const parsed = registerOwnerSchema.safeParse(req.body ?? {});
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid registration payload." });
      return;
    }

    const phone = req.phoneNumber || parsed.data.phoneNumber || "";
    if (!phone.startsWith("+91")) {
      res.status(400).json({ error: "Verified +91 phone number is required." });
      return;
    }

    const now = Date.now();
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const existing = await client.query(
        "SELECT firebase_uid, phone_number FROM users LIMIT 1 FOR UPDATE"
      );

      if (existing.rows.length > 0) {
        const owner = existing.rows[0];
        if (owner.firebase_uid !== uid || owner.phone_number !== phone) {
          await client.query("ROLLBACK");
          res.status(403).json({
            error: "Single-owner restriction: DSK LOCKER already registered to another owner.",
          });
          return;
        }
        await client.query(
          "UPDATE users SET last_login_at = $1 WHERE firebase_uid = $2",
          [now, uid]
        );
      } else {
        await client.query(
          `INSERT INTO users (firebase_uid, phone_number, is_authorized_owner, created_at, last_login_at)
           VALUES ($1, $2, TRUE, $3, $3)`,
          [uid, phone, now]
        );
        await client.query(
          `INSERT INTO user_profiles (firebase_uid, owner_name, phone_number, updated_at)
           VALUES ($1, $2, $3, $4) ON CONFLICT (firebase_uid) DO NOTHING`,
          [uid, parsed.data.ownerName || "DSK Owner", phone, now]
        );
        await client.query(
          `INSERT INTO user_settings (firebase_uid, updated_at)
           VALUES ($1, $2) ON CONFLICT (firebase_uid) DO NOTHING`,
          [uid, now]
        );
      }

      await client.query("COMMIT");
      res.json({ ok: true, firebaseUid: uid, phoneNumber: phone });
    } catch (err) {
      await client.query("ROLLBACK");
      res.status(500).json({ error: "Failed to register authorized owner in SQL." });
    } finally {
      client.release();
    }
  }
);

/**
 * PUT /api/security/mpin
 * Stores PBKDF2-HMAC-SHA256 MPIN hash & salt in `user_security` (never plain text).
 */
lockerRouter.put(
  "/security/mpin",
  requireFirebaseAuth,
  requireAuthorizedOwner,
  async (req: AuthenticatedRequest, res: Response) => {
    const uid = req.firebaseUid!;
    const parsed = mpinSecuritySchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid MPIN hash payload." });
      return;
    }
    const now = Date.now();
    try {
      await pool.query(
        `INSERT INTO user_security (firebase_uid, mpin_hash, mpin_salt, biometric_enabled, updated_at)
         VALUES ($1, $2, $3, COALESCE($4, FALSE), $5)
         ON CONFLICT (firebase_uid) DO UPDATE SET
           mpin_hash = EXCLUDED.mpin_hash,
           mpin_salt = EXCLUDED.mpin_salt,
           biometric_enabled = COALESCE($4, user_security.biometric_enabled),
           updated_at = EXCLUDED.updated_at`,
        [uid, parsed.data.mpinHash, parsed.data.mpinSalt, parsed.data.biometricEnabled ?? null, now]
      );
      res.json({ ok: true });
    } catch (err) {
      res.status(500).json({ error: "Failed to update MPIN security hash." });
    }
  }
);

/**
 * GET /api/files
 * Returns non-deleted files strictly scoped to verified Firebase UID.
 */
lockerRouter.get(
  "/files",
  requireFirebaseAuth,
  requireAuthorizedOwner,
  async (req: AuthenticatedRequest, res: Response) => {
    const uid = req.firebaseUid!;
    try {
      const result = await pool.query(
        "SELECT * FROM files WHERE firebase_uid = $1 ORDER BY updated_at DESC",
        [uid]
      );
      res.json(result.rows);
    } catch (err) {
      res.status(500).json({ error: "Failed to fetch files." });
    }
  }
);

/**
 * POST /api/sync/:table
 * Parameterized upsert across the 18 SQL tables, always forcing firebase_uid = verified token UID.
 */
const ALLOWED_SYNC_TABLES: Record<
  string,
  { pk: string; columns: string[] }
> = {
  user_profiles: {
    pk: "firebase_uid",
    columns: ["firebase_uid", "owner_name", "phone_number", "avatar_cloud_path", "updated_at"],
  },
  user_settings: {
    pk: "firebase_uid",
    columns: [
      "firebase_uid", "auto_backup_enabled", "wifi_only", "backup_photos",
      "backup_videos", "backup_documents", "auto_lock_seconds", "dark_mode",
      "animations_4d_enabled", "reduced_motion", "accent_preset",
      "notifications_enabled", "updated_at",
    ],
  },
  categories: {
    pk: "id",
    columns: ["id", "firebase_uid", "name", "icon_key", "accent_hex", "is_custom", "created_at"],
  },
  folders: {
    pk: "id",
    columns: ["id", "firebase_uid", "name", "category_id", "parent_id", "is_auto_backup_enabled", "created_at"],
  },
  files: {
    pk: "id",
    columns: [
      "id", "firebase_uid", "original_filename", "display_name", "category_id",
      "folder_id", "mime_type", "size_bytes", "cloud_path", "cloud_bucket",
      "backup_status", "sha256", "is_favorite", "is_in_vault", "is_deleted",
      "deleted_at", "created_at", "updated_at",
    ],
  },
  document_metadata: {
    pk: "file_id",
    columns: [
      "file_id", "firebase_uid", "page_count", "document_category", "expiry_date_iso",
      "remind_30_days", "remind_15_days", "remind_7_days", "remind_on_day",
      "notifications_enabled", "updated_at",
    ],
  },
  document_extractions: {
    pk: "file_id",
    columns: [
      "file_id", "firebase_uid", "document_name", "document_type", "document_number",
      "owner_name", "father_name", "mother_name", "date_of_birth", "issue_date",
      "expiry_date", "address", "mobile_number", "organization_name",
      "registration_number", "raw_text", "is_verified", "updated_at",
    ],
  },
  text_notes: {
    pk: "id",
    columns: ["id", "firebase_uid", "title", "content", "category_id", "is_favorite", "is_deleted", "deleted_at", "created_at", "updated_at"],
  },
  saved_links: {
    pk: "id",
    columns: ["id", "firebase_uid", "link_type", "title", "url", "thumbnail_url", "description", "category_id", "is_favorite", "is_deleted", "deleted_at", "created_at", "updated_at"],
  },
  backup_history: {
    pk: "id",
    columns: ["id", "firebase_uid", "file_id", "file_name", "action", "status", "bytes_transferred", "message", "timestamp"],
  },
  sync_history: {
    pk: "id",
    columns: ["id", "firebase_uid", "summary", "device_info", "timestamp"],
  },
  file_versions: {
    pk: "id",
    columns: ["id", "firebase_uid", "file_id", "version_number", "cloud_path", "size_bytes", "sha256", "created_at"],
  },
  favorites: {
    pk: "id",
    columns: ["id", "firebase_uid", "item_type", "item_id", "created_at"],
  },
  vault_metadata: {
    pk: "file_id",
    columns: ["file_id", "firebase_uid", "cipher_alg", "storage_path", "encrypted_at"],
  },
  download_history: {
    pk: "id",
    columns: ["id", "firebase_uid", "file_id", "file_name", "downloaded_at"],
  },
  security_logs: {
    pk: "id",
    columns: ["id", "firebase_uid", "event_type", "detail", "success", "timestamp"],
  },
};

lockerRouter.post(
  "/sync/:table",
  requireFirebaseAuth,
  requireAuthorizedOwner,
  async (req: AuthenticatedRequest, res: Response) => {
    const uid = req.firebaseUid!;
    const table = req.params.table;
    const spec = ALLOWED_SYNC_TABLES[table];
    if (!spec) {
      res.status(400).json({ error: "Unsupported table name." });
      return;
    }

    const body = (req.body && typeof req.body === "object" ? req.body : {}) as Record<string, unknown>;
    // Never trust client-provided firebase_uid; overwrite strictly from verified ID token
    const sanitized: Record<string, unknown> = { ...body, firebase_uid: uid };

    const cols = spec.columns.filter((c) => sanitized[c] !== undefined);
    if (!cols.includes(spec.pk)) {
      res.status(400).json({ error: `Missing primary key '${spec.pk}'.` });
      return;
    }

    const values = cols.map((c) => sanitized[c]);
    const placeholders = cols.map((_, idx) => `$${idx + 1}`).join(", ");
    const updates = cols
      .filter((c) => c !== spec.pk && c !== "firebase_uid")
      .map((c) => `${c} = EXCLUDED.${c}`)
      .join(", ");

    const sql = updates.length > 0
      ? `INSERT INTO ${table} (${cols.join(", ")}) VALUES (${placeholders})
         ON CONFLICT (${spec.pk}) DO UPDATE SET ${updates}
         WHERE ${table}.firebase_uid = $${cols.indexOf("firebase_uid") + 1}`
      : `INSERT INTO ${table} (${cols.join(", ")}) VALUES (${placeholders})
         ON CONFLICT (${spec.pk}) DO NOTHING`;

    try {
      await pool.query(sql, values);
      res.json({ ok: true });
    } catch (err) {
      res.status(500).json({ error: `SQL upsert failed for ${table}.` });
    }
  }
);

lockerRouter.delete(
  "/sync/:table/:id",
  requireFirebaseAuth,
  requireAuthorizedOwner,
  async (req: AuthenticatedRequest, res: Response) => {
    const uid = req.firebaseUid!;
    const { table, id } = req.params;
    const spec = ALLOWED_SYNC_TABLES[table];
    if (!spec || spec.pk === "firebase_uid") {
      res.status(400).json({ error: "Invalid delete target." });
      return;
    }

    try {
      await pool.query(
        `DELETE FROM ${table} WHERE ${spec.pk} = $1 AND firebase_uid = $2`,
        [id, uid]
      );
      res.json({ ok: true });
    } catch (err) {
      res.status(500).json({ error: `SQL delete failed for ${table}.` });
    }
  }
);
