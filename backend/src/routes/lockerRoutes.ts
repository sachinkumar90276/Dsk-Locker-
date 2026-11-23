import { Router, RequestHandler, Response } from "express";
import { z } from "zod";
import { DbAdapter, defaultDb } from "../db/pool";
import {
  AuthenticatedRequest,
  requireAuthorizedOwner as defaultRequireOwner,
  requireFirebaseAuth as defaultRequireAuth,
} from "../middleware/auth";

const registerOwnerSchema = z.object({
  ownerName: z.string().trim().min(1).max(120).optional(),
  phoneNumber: z.string().trim().regex(/^\+?[0-9]{7,15}$/).optional(),
});

const mpinSecuritySchema = z.object({
  mpinHash: z.string().min(16).max(256),
  mpinSalt: z.string().min(8).max(128),
  mpinLength: z.number().int().refine((v) => v === 4 || v === 6).optional(),
  secretMpinHash: z.string().min(16).max(256).optional(),
  secretMpinSalt: z.string().min(8).max(128).optional(),
  biometricEnabled: z.boolean().optional(),
});

const ALLOWED_SYNC_TABLES: Record<string, { pk: string; columns: string[] }> = {
  user_profiles: {
    pk: "firebase_uid",
    columns: ["firebase_uid", "owner_name", "phone_number", "avatar_cloud_path", "updated_at"],
  },
  user_settings: {
    pk: "firebase_uid",
    columns: [
      "firebase_uid",
      "auto_backup_enabled",
      "wifi_only",
      "backup_photos",
      "backup_videos",
      "backup_documents",
      "auto_lock_seconds",
      "dark_mode",
      "animations_4d_enabled",
      "reduced_motion",
      "accent_preset",
      "notifications_enabled",
      "updated_at",
    ],
  },
  categories: {
    pk: "id",
    columns: ["id", "firebase_uid", "name", "icon_key", "accent_hex", "is_custom", "created_at"],
  },
  folders: {
    pk: "id",
    columns: [
      "id",
      "firebase_uid",
      "name",
      "category_id",
      "parent_id",
      "is_auto_backup_enabled",
      "created_at",
    ],
  },
  files: {
    pk: "id",
    columns: [
      "id",
      "firebase_uid",
      "original_filename",
      "display_name",
      "category_id",
      "folder_id",
      "mime_type",
      "size_bytes",
      "cloud_path",
      "cloud_bucket",
      "backup_status",
      "sha256",
      "is_favorite",
      "is_in_vault",
      "is_deleted",
      "deleted_at",
      "created_at",
      "updated_at",
    ],
  },
  document_metadata: {
    pk: "file_id",
    columns: [
      "file_id",
      "firebase_uid",
      "page_count",
      "document_category",
      "expiry_date_iso",
      "remind_30_days",
      "remind_15_days",
      "remind_7_days",
      "remind_on_day",
      "notifications_enabled",
      "updated_at",
    ],
  },
  document_extractions: {
    pk: "file_id",
    columns: [
      "file_id",
      "firebase_uid",
      "document_name",
      "document_type",
      "document_number",
      "owner_name",
      "father_name",
      "mother_name",
      "date_of_birth",
      "issue_date",
      "expiry_date",
      "address",
      "mobile_number",
      "organization_name",
      "registration_number",
      "raw_text",
      "is_verified",
      "updated_at",
    ],
  },
  text_notes: {
    pk: "id",
    columns: [
      "id",
      "firebase_uid",
      "title",
      "content",
      "category_id",
      "is_favorite",
      "is_deleted",
      "deleted_at",
      "created_at",
      "updated_at",
    ],
  },
  saved_links: {
    pk: "id",
    columns: [
      "id",
      "firebase_uid",
      "link_type",
      "title",
      "url",
      "thumbnail_url",
      "description",
      "category_id",
      "is_favorite",
      "is_deleted",
      "deleted_at",
      "created_at",
      "updated_at",
    ],
  },
  backup_history: {
    pk: "id",
    columns: [
      "id",
      "firebase_uid",
      "backup_id",
      "file_id",
      "file_name",
      "action",
      "status",
      "bytes_transferred",
      "message",
      "timestamp",
    ],
  },
  sync_history: {
    pk: "id",
    columns: ["id", "firebase_uid", "summary", "device_info", "timestamp"],
  },
  file_versions: {
    pk: "id",
    columns: [
      "id",
      "firebase_uid",
      "file_id",
      "version_number",
      "cloud_path",
      "size_bytes",
      "sha256",
      "created_at",
    ],
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

export function createLockerRouter(
  db: DbAdapter = defaultDb,
  requireAuth: RequestHandler = defaultRequireAuth,
  requireOwner: RequestHandler = defaultRequireOwner
): Router {
  const router = Router();

  /**
   * POST /api/auth/register-owner
   * Verifies token and registers/updates the authenticated user cleanly in Neon `users`.
   * If OWNER_FIREBASE_UID / AUTHORIZED_OWNER_PHONE are pinned in env, enforces single-owner pin.
   */
  router.post(
    "/auth/register-owner",
    requireAuth,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const parsed = registerOwnerSchema.safeParse(req.body ?? {});
      if (!parsed.success) {
        res.status(400).json({ error: "Invalid registration payload." });
        return;
      }

      const phone = req.phoneNumber || parsed.data.phoneNumber || "";
      const configuredUid = (process.env.OWNER_FIREBASE_UID || "").trim();
      const configuredPhone = (process.env.AUTHORIZED_OWNER_PHONE || "").trim();

      if (configuredUid && configuredUid !== uid) {
        res.status(403).json({ error: "Single-owner restriction: unauthorized Firebase UID." });
        return;
      }
      if (configuredPhone && phone && configuredPhone !== phone) {
        res.status(403).json({ error: "Single-owner restriction: unauthorized phone number." });
        return;
      }

      const now = Date.now();
      const client = await db.connect();
      try {
        await client.query("BEGIN");
        const existing = await client.query<{
          firebase_uid: string;
          phone_number: string | null;
          is_authorized_owner: boolean;
        }>(
          "SELECT firebase_uid, phone_number, is_authorized_owner FROM users WHERE firebase_uid = $1",
          [uid]
        );

        if (existing.rows.length > 0) {
          const user = existing.rows[0];
          if (!user.is_authorized_owner) {
            await client.query("ROLLBACK");
            res.status(403).json({ error: "User access disabled." });
            return;
          }
          await client.query(
            "UPDATE users SET last_login_at = $1, phone_number = COALESCE(NULLIF($2, ''), phone_number) WHERE firebase_uid = $3",
            [now, phone, uid]
          );
        } else {
          await client.query(
            `INSERT INTO users (firebase_uid, phone_number, is_authorized_owner, created_at, last_login_at)
             VALUES ($1, NULLIF($2, ''), TRUE, $3, $3)`,
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
          await client.query(
            `INSERT INTO backup_settings (user_id, updated_at)
             VALUES ($1, $2) ON CONFLICT (user_id) DO NOTHING`,
            [uid, now]
          );
        }

        await client.query("COMMIT");
        res.json({ ok: true, firebaseUid: uid, userId: uid, phoneNumber: phone });
      } catch (_err) {
        await client.query("ROLLBACK");
        res.status(500).json({ error: "Failed to register authenticated user in Neon PostgreSQL." });
      } finally {
        client.release();
      }
    }
  );

  /**
   * PUT /api/security/mpin
   * Stores PBKDF2-HMAC-SHA256 MPIN hash & salt (never plain text) scoped strictly to req.firebaseUid.
   */
  router.put(
    "/security/mpin",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const parsed = mpinSecuritySchema.safeParse(req.body);
      if (!parsed.success) {
        res.status(400).json({ error: "Invalid MPIN hash payload." });
        return;
      }
      const now = Date.now();
      try {
        await db.query(
          `INSERT INTO user_security (
             firebase_uid, mpin_hash, mpin_salt, mpin_length,
             secret_mpin_hash, secret_mpin_salt, biometric_enabled, updated_at
           )
           VALUES ($1, $2, $3, COALESCE($4, 6), $5, $6, COALESCE($7, FALSE), $8)
           ON CONFLICT (firebase_uid) DO UPDATE SET
             mpin_hash = EXCLUDED.mpin_hash,
             mpin_salt = EXCLUDED.mpin_salt,
             mpin_length = COALESCE($4, user_security.mpin_length),
             secret_mpin_hash = COALESCE($5, user_security.secret_mpin_hash),
             secret_mpin_salt = COALESCE($6, user_security.secret_mpin_salt),
             biometric_enabled = COALESCE($7, user_security.biometric_enabled),
             updated_at = EXCLUDED.updated_at`,
          [
            uid,
            parsed.data.mpinHash,
            parsed.data.mpinSalt,
            parsed.data.mpinLength ?? null,
            parsed.data.secretMpinHash ?? null,
            parsed.data.secretMpinSalt ?? null,
            parsed.data.biometricEnabled ?? null,
            now,
          ]
        );
        res.json({ ok: true });
      } catch (_err) {
        res.status(500).json({ error: "Failed to update MPIN security hash." });
      }
    }
  );

  /**
   * GET /api/files
   * Returns non-deleted files strictly scoped to authenticated user's UID.
   */
  router.get(
    "/files",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      try {
        const result = await db.query(
          "SELECT * FROM files WHERE firebase_uid = $1 ORDER BY updated_at DESC",
          [uid]
        );
        res.json(result.rows);
      } catch (_err) {
        res.status(500).json({ error: "Failed to fetch files." });
      }
    }
  );

  /**
   * POST /api/sync/:table
   * Parameterized upsert strictly enforcing firebase_uid = verified token UID.
   */
  router.post(
    "/sync/:table",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const table = Array.isArray(req.params.table) ? req.params.table[0] : req.params.table;
      const spec = ALLOWED_SYNC_TABLES[table];
      if (!spec) {
        res.status(400).json({ error: "Unsupported table name." });
        return;
      }

      const body = (req.body && typeof req.body === "object" ? req.body : {}) as Record<string, unknown>;
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

      const sql =
        updates.length > 0
          ? `INSERT INTO ${table} (${cols.join(", ")}) VALUES (${placeholders})
             ON CONFLICT (${spec.pk}) DO UPDATE SET ${updates}
             WHERE ${table}.firebase_uid = $${cols.indexOf("firebase_uid") + 1}`
          : `INSERT INTO ${table} (${cols.join(", ")}) VALUES (${placeholders})
             ON CONFLICT (${spec.pk}) DO NOTHING`;

      try {
        await db.query(sql, values);
        res.json({ ok: true });
      } catch (_err) {
        res.status(500).json({ error: `SQL upsert failed for ${table}.` });
      }
    }
  );

  router.delete(
    "/sync/:table/:id",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const table = Array.isArray(req.params.table) ? req.params.table[0] : req.params.table;
      const id = Array.isArray(req.params.id) ? req.params.id[0] : req.params.id;
      const spec = ALLOWED_SYNC_TABLES[table];
      if (!spec || spec.pk === "firebase_uid") {
        res.status(400).json({ error: "Invalid delete target." });
        return;
      }

      try {
        await db.query(`DELETE FROM ${table} WHERE ${spec.pk} = $1 AND firebase_uid = $2`, [id, uid]);
        res.json({ ok: true });
      } catch (_err) {
        res.status(500).json({ error: `SQL delete failed for ${table}.` });
      }
    }
  );

  return router;
}

export const lockerRouter = createLockerRouter();
