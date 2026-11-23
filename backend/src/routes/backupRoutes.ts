import { Router, RequestHandler, Response } from "express";
import crypto from "crypto";
import { z } from "zod";
import { DbAdapter, defaultDb } from "../db/pool";
import {
  AuthenticatedRequest,
  requireAuthorizedOwner as defaultRequireOwner,
  requireFirebaseAuth as defaultRequireAuth,
} from "../middleware/auth";
import {
  computeMpinLockoutPenaltySeconds,
  sha256Hex,
  unwrapVaultKeyForUser,
  verifyPbkdf2Mpin,
  wrapVaultKeyForUser,
} from "../security/vaultCrypto";

const backupFilePayloadSchema = z.object({
  fileId: z.string().min(1).max(128),
  originalFilename: z.string().min(1).max(512),
  displayName: z.string().min(1).max(512),
  categoryId: z.string().min(1).max(128),
  folderId: z.string().max(128).nullable().optional(),
  mimeType: z.string().min(1).max(255).default("application/octet-stream"),
  sizeBytes: z.number().int().nonnegative(),
  sha256: z.string().max(128).default(""),
  isInVault: z.boolean().default(false),
  isFavorite: z.boolean().default(false),
  isEncryptedPayload: z.boolean().default(false),
  keyVersion: z.number().int().nonnegative().default(0),
  createdAt: z.number().int().nonnegative(),
  updatedAt: z.number().int().nonnegative(),
  fileContentBase64: z.string().min(1),
  extraction: z.record(z.unknown()).nullable().optional(),
  extendedOcrJson: z.string().nullable().optional(),
  reminder: z.record(z.unknown()).nullable().optional(),
});

const backupCreateSchema = z.object({
  backupId: z.string().min(1).max(128).optional(),
  action: z.enum(["MANUAL_BACKUP", "AUTO_BACKUP", "RETRY"]).default("MANUAL_BACKUP"),
  deviceInfo: z.string().max(256).optional(),
  totalDocuments: z.number().int().nonnegative().optional(),
  pendingDocuments: z.number().int().nonnegative().optional(),
  failedDocuments: z.number().int().nonnegative().optional(),
  files: z.array(backupFilePayloadSchema).max(50).default([]),
  snapshot: z
    .object({
      folders: z.array(z.record(z.unknown())).default([]),
      customCategories: z.array(z.record(z.unknown())).default([]),
      textNotes: z.array(z.record(z.unknown())).default([]),
      savedLinks: z.array(z.record(z.unknown())).default([]),
    })
    .optional(),
});

const backupSettingsSchema = z.object({
  cloudBackupEnabled: z.boolean(),
  wifiOnly: z.boolean().default(true),
  backupPhotos: z.boolean().default(true),
  backupVideos: z.boolean().default(true),
  backupDocuments: z.boolean().default(true),
});

const restoreStartSchema = z.object({
  backupId: z.string().max(128).optional(),
  fileIds: z.array(z.string().min(1).max(128)).optional(),
  deviceInfo: z.string().max(256).optional(),
});

const restoreCompleteSchema = z.object({
  restoreId: z.string().min(1).max(128),
  status: z.enum(["COMPLETED", "PARTIAL", "FAILED"]),
  restoredFiles: z.number().int().nonnegative(),
  verifiedFiles: z.number().int().nonnegative(),
  failedFiles: z.number().int().nonnegative(),
  bytesRestored: z.number().int().nonnegative(),
  message: z.string().max(1000).default(""),
});

const verifyMpinSchema = z.object({
  mpin: z.string().regex(/^\d{4,6}$/),
  purpose: z.string().max(64).default("VERIFY"),
});

const recoveryKeySchema = z.discriminatedUnion("action", [
  z.object({
    action: z.literal("wrap"),
    vaultKeyBase64: z.string().min(16).max(512),
    keyVersion: z.number().int().positive().default(1),
    mpin: z.string().regex(/^\d{4,6}$/).optional(),
  }),
  z.object({
    action: z.literal("recover"),
    mpin: z.string().regex(/^\d{4,6}$/).optional(),
  }),
]);

function getEnvNumber(name: string, fallback: number): number {
  const n = Number(process.env[name]);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

export function createBackupRouter(
  db: DbAdapter = defaultDb,
  requireAuth: RequestHandler = defaultRequireAuth,
  requireOwner: RequestHandler = defaultRequireOwner
): Router {
  const router = Router();

  const neonFreeTierBytes = () => getEnvNumber("NEON_FREE_TIER_LIMIT_MB", 512) * 1024 * 1024;
  const defaultUserQuotaBytes = () => getEnvNumber("DEFAULT_USER_QUOTA_MB", 450) * 1024 * 1024;
  const defaultMaxFileBytes = () => getEnvNumber("MAX_BACKUP_FILE_MB", 20) * 1024 * 1024;

  async function verifyUserMpinWithRateLimit(
    uid: string,
    mpin: string
  ): Promise<
    | { ok: true }
    | { ok: false; status: number; error: string; retryAfterSeconds?: number }
  > {
    const now = Date.now();
    const secRes = await db.query<{
      mpin_hash: string;
      mpin_salt: string;
      failed_attempts: number;
      lockout_until_ms: number | string;
    }>(
      `SELECT mpin_hash, mpin_salt, failed_attempts, lockout_until_ms
       FROM user_security
       WHERE firebase_uid = $1`,
      [uid]
    );

    if (secRes.rows.length === 0) {
      return {
        ok: false,
        status: 404,
        error: "Server MPIN hash not registered for this account yet.",
      };
    }

    const sec = secRes.rows[0];
    const lockoutUntil = Number(sec.lockout_until_ms || 0);
    if (lockoutUntil > now) {
      const retryAfterSeconds = Math.ceil((lockoutUntil - now) / 1000);
      return {
        ok: false,
        status: 429,
        retryAfterSeconds,
        error: `Too many failed MPIN attempts. Retry in ${retryAfterSeconds}s.`,
      };
    }

    const valid = verifyPbkdf2Mpin(mpin, sec.mpin_salt, sec.mpin_hash);
    if (valid) {
      await db.query(
        `UPDATE user_security SET failed_attempts = 0, lockout_until_ms = 0, updated_at = $1 WHERE firebase_uid = $2`,
        [now, uid]
      );
      await db.query(
        `INSERT INTO security_logs (id, firebase_uid, event_type, detail, success, timestamp)
         VALUES ($1, $2, 'SERVER_MPIN_VERIFY', 'Server-side MPIN verified cleanly', TRUE, $3)`,
        [crypto.randomUUID(), uid, now]
      );
      return { ok: true };
    }

    const attempts = Number(sec.failed_attempts || 0) + 1;
    const penaltySeconds = computeMpinLockoutPenaltySeconds(attempts);
    const nextLockout = penaltySeconds > 0 ? now + penaltySeconds * 1000 : 0;

    await db.query(
      `UPDATE user_security SET failed_attempts = $1, lockout_until_ms = $2, updated_at = $3 WHERE firebase_uid = $4`,
      [attempts, nextLockout, now, uid]
    );
    await db.query(
      `INSERT INTO security_logs (id, firebase_uid, event_type, detail, success, timestamp)
       VALUES ($1, $2, 'SERVER_MPIN_FAILED', $3, FALSE, $4)`,
      [
        crypto.randomUUID(),
        uid,
        `Failed MPIN verification attempt #${attempts}${penaltySeconds ? ` (locked ${penaltySeconds}s)` : ""}`,
        now,
      ]
    );

    if (penaltySeconds > 0) {
      return {
        ok: false,
        status: 429,
        retryAfterSeconds: penaltySeconds,
        error: `MPIN verification locked for ${penaltySeconds}s after repeated failed attempts.`,
      };
    }

    const remaining = Math.max(1, 5 - attempts);
    return {
      ok: false,
      status: 401,
      error: `Incorrect MPIN. ${remaining} attempt(s) remaining before lockout.`,
    };
  }

  /**
   * POST /api/backup/create
   * Transactional cloud backup storing file binary content (BYTEA) + snapshot metadata in Neon.
   */
  router.post(
    "/backup/create",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const parsed = backupCreateSchema.safeParse(req.body ?? {});
      if (!parsed.success) {
        res.status(400).json({
          error: "Invalid backup upload payload.",
          details: parsed.error.issues.slice(0, 3).map((i) => i.message),
        });
        return;
      }

      const payload = parsed.data;
      const backupId = payload.backupId || crypto.randomUUID();
      const now = Date.now();
      const client = await db.connect();

      try {
        await client.query("BEGIN");

        // Load user quota settings
        const settingsRes = await client.query<{
          storage_quota_bytes: number | string;
          max_file_bytes: number | string;
        }>(
          `SELECT storage_quota_bytes, max_file_bytes FROM backup_settings WHERE user_id = $1`,
          [uid]
        );
        const quotaLimit = Number(
          settingsRes.rows[0]?.storage_quota_bytes || defaultUserQuotaBytes()
        );
        const maxFileBytes = Number(
          settingsRes.rows[0]?.max_file_bytes || defaultMaxFileBytes()
        );

        // Current Neon usage checks
        const usedRes = await client.query<{ total_bytes: number | string }>(
          `SELECT COALESCE(SUM(size_bytes), 0) AS total_bytes FROM backup_files WHERE user_id = $1`,
          [uid]
        );
        let runningUserBytes = Number(usedRes.rows[0]?.total_bytes || 0);

        let dbUsedBytes = 0;
        try {
          const dbSizeRes = await client.query<{ db_size: number | string }>(
            `SELECT pg_database_size(current_database()) AS db_size`
          );
          dbUsedBytes = Number(dbSizeRes.rows[0]?.db_size || 0);
        } catch {
          dbUsedBytes = runningUserBytes;
        }
        const neonLimit = neonFreeTierBytes();

        const storedFiles: Array<{
          fileId: string;
          sha256: string;
          sizeBytes: number;
          duplicateOfFileId: string | null;
        }> = [];
        let batchBytesAdded = 0;

        for (const f of payload.files) {
          const buf = Buffer.from(f.fileContentBase64, "base64");
          if (buf.length === 0) {
            await client.query("ROLLBACK");
            res.status(400).json({ error: `Empty binary content for '${f.displayName}'.` });
            return;
          }

          if (buf.length > maxFileBytes) {
            await client.query("ROLLBACK");
            res.status(413).json({
              error: `File '${f.displayName}' (${Math.ceil(buf.length / (1024 * 1024))} MB) exceeds single-file Neon/Render limit (${Math.floor(maxFileBytes / (1024 * 1024))} MB).`,
            });
            return;
          }

          const computedSha = sha256Hex(buf);
          if (f.sha256 && f.sha256.toLowerCase() !== computedSha.toLowerCase()) {
            await client.query("ROLLBACK");
            res.status(400).json({
              error: `SHA-256 verification failed on upload for '${f.displayName}'. Upload rejected to protect backup integrity.`,
            });
            return;
          }

          // Check existing size delta for this fileId so overwrites don't double-count quota
          const existingFileRes = await client.query<{ size_bytes: number | string }>(
            `SELECT size_bytes FROM backup_files WHERE user_id = $1 AND file_id = $2`,
            [uid, f.fileId]
          );
          const previousSize = Number(existingFileRes.rows[0]?.size_bytes || 0);
          const sizeDelta = buf.length - previousSize;

          if (runningUserBytes + sizeDelta > quotaLimit) {
            await client.query("ROLLBACK");
            res.status(507).json({
              error: `Neon user backup quota exceeded (${Math.ceil((runningUserBytes + sizeDelta) / (1024 * 1024))} MB / ${Math.floor(quotaLimit / (1024 * 1024))} MB). Delete unneeded cloud backups or keep local only.`,
            });
            return;
          }
          if (dbUsedBytes + sizeDelta > neonLimit) {
            await client.query("ROLLBACK");
            res.status(507).json({
              error: `Neon PostgreSQL free-tier storage limit (${Math.floor(neonLimit / (1024 * 1024))} MB) reached.`,
            });
            return;
          }

          runningUserBytes += sizeDelta;
          dbUsedBytes += sizeDelta;
          batchBytesAdded += buf.length;

          // Duplicate SHA-256 detection within user's backups
          const dupRes = await client.query<{ file_id: string }>(
            `SELECT file_id FROM backup_files WHERE user_id = $1 AND sha256 = $2 AND file_id <> $3 LIMIT 1`,
            [uid, computedSha, f.fileId]
          );
          const duplicateOfFileId = dupRes.rows[0]?.file_id ?? null;

          const metaJson = JSON.stringify({
            extraction: f.extraction ?? null,
            extendedOcrJson: f.extendedOcrJson ?? null,
            reminder: f.reminder ?? null,
            duplicateOfFileId,
          });

          await client.query(
            `INSERT INTO backup_files (
               id, user_id, backup_id, file_id, original_filename, display_name,
               category_id, folder_id, mime_type, size_bytes, sha256,
               is_in_vault, is_favorite, is_encrypted_payload, key_version,
               file_content, metadata_json, created_at, updated_at
             ) VALUES (
               $1, $2, $3, $4, $5, $6,
               $7, $8, $9, $10, $11,
               $12, $13, $14, $15,
               $16, $17::jsonb, $18, $19
             )
             ON CONFLICT (user_id, file_id) DO UPDATE SET
               backup_id = EXCLUDED.backup_id,
               original_filename = EXCLUDED.original_filename,
               display_name = EXCLUDED.display_name,
               category_id = EXCLUDED.category_id,
               folder_id = EXCLUDED.folder_id,
               mime_type = EXCLUDED.mime_type,
               size_bytes = EXCLUDED.size_bytes,
               sha256 = EXCLUDED.sha256,
               is_in_vault = EXCLUDED.is_in_vault,
               is_favorite = EXCLUDED.is_favorite,
               is_encrypted_payload = EXCLUDED.is_encrypted_payload,
               key_version = EXCLUDED.key_version,
               file_content = EXCLUDED.file_content,
               metadata_json = EXCLUDED.metadata_json,
               updated_at = EXCLUDED.updated_at`,
            [
              `${uid}_${f.fileId}`,
              uid,
              backupId,
              f.fileId,
              f.originalFilename,
              f.displayName,
              f.categoryId,
              f.folderId ?? null,
              f.mimeType,
              buf.length,
              computedSha,
              f.isInVault,
              f.isFavorite,
              f.isEncryptedPayload,
              f.keyVersion,
              buf,
              metaJson,
              f.createdAt,
              f.updatedAt,
            ]
          );

          // Also keep `files` metadata table in sync cleanly
          await client.query(
            `INSERT INTO files (
               id, firebase_uid, original_filename, display_name, category_id, folder_id,
               mime_type, size_bytes, cloud_path, cloud_bucket, backup_status, sha256,
               is_favorite, is_in_vault, is_deleted, deleted_at, created_at, updated_at
             ) VALUES (
               $1, $2, $3, $4, $5, $6,
               $7, $8, $9, 'neon-bytea', 'MOBILE_AND_CLOUD', $10,
               $11, $12, FALSE, NULL, $13, $14
             )
             ON CONFLICT (id) DO UPDATE SET
               original_filename = EXCLUDED.original_filename,
               display_name = EXCLUDED.display_name,
               category_id = EXCLUDED.category_id,
               folder_id = EXCLUDED.folder_id,
               mime_type = EXCLUDED.mime_type,
               size_bytes = EXCLUDED.size_bytes,
               cloud_path = EXCLUDED.cloud_path,
               cloud_bucket = EXCLUDED.cloud_bucket,
               backup_status = EXCLUDED.backup_status,
               sha256 = EXCLUDED.sha256,
               is_favorite = EXCLUDED.is_favorite,
               is_in_vault = EXCLUDED.is_in_vault,
               updated_at = EXCLUDED.updated_at
             WHERE files.firebase_uid = $2`,
            [
              f.fileId,
              uid,
              f.originalFilename,
              f.displayName,
              f.categoryId,
              f.folderId ?? null,
              f.mimeType,
              buf.length,
              `neon://backup_files/${f.fileId}`,
              computedSha,
              f.isFavorite,
              f.isInVault,
              f.createdAt,
              f.updatedAt,
            ]
          );

          await client.query(
            `INSERT INTO backup_history (
               id, firebase_uid, backup_id, file_id, file_name, action, status,
               bytes_transferred, message, timestamp
             ) VALUES ($1, $2, $3, $4, $5, $6, 'SUCCESS', $7, $8, $9)`,
            [
              crypto.randomUUID(),
              uid,
              backupId,
              f.fileId,
              f.displayName,
              payload.action,
              buf.length,
              duplicateOfFileId
                ? `Backed up binary (${buf.length} B) to Neon PostgreSQL (SHA-256 matches existing item).`
                : `Backed up binary (${buf.length} B) to Neon PostgreSQL & verified SHA-256.`,
              now,
            ]
          );

          storedFiles.push({
            fileId: f.fileId,
            sha256: computedSha,
            sizeBytes: buf.length,
            duplicateOfFileId,
          });
        }

        // Preserve existing snapshot sections if caller sent partial snapshot
        const prevSnapRes = await client.query<{ snapshot_json: unknown }>(
          `SELECT snapshot_json FROM user_backups WHERE user_id = $1 ORDER BY started_at DESC LIMIT 1`,
          [uid]
        );
        const prevSnap =
          (prevSnapRes.rows[0]?.snapshot_json as Record<string, unknown> | undefined) || {};
        const mergedSnapshot = payload.snapshot
          ? {
              folders: payload.snapshot.folders,
              customCategories: payload.snapshot.customCategories,
              textNotes: payload.snapshot.textNotes,
              savedLinks: payload.snapshot.savedLinks,
            }
          : prevSnap;

        const backedUpCountRes = await client.query<{ cnt: number | string; total_bytes: number | string }>(
          `SELECT COUNT(*) AS cnt, COALESCE(SUM(size_bytes), 0) AS total_bytes FROM backup_files WHERE user_id = $1`,
          [uid]
        );
        const backedUpCount = Number(backedUpCountRes.rows[0]?.cnt || 0);
        const totalBackedUpBytes = Number(backedUpCountRes.rows[0]?.total_bytes || 0);

        await client.query(
          `INSERT INTO user_backups (
             id, user_id, status, total_documents, backed_up_documents,
             pending_documents, failed_documents, total_bytes, snapshot_json,
             device_info, started_at, completed_at
           ) VALUES (
             $1, $2, 'COMPLETED', $3, $4,
             $5, $6, $7, $8::jsonb,
             $9, $10, $10
           )
           ON CONFLICT (id) DO UPDATE SET
             status = 'COMPLETED',
             total_documents = EXCLUDED.total_documents,
             backed_up_documents = EXCLUDED.backed_up_documents,
             pending_documents = EXCLUDED.pending_documents,
             failed_documents = EXCLUDED.failed_documents,
             total_bytes = EXCLUDED.total_bytes,
             snapshot_json = EXCLUDED.snapshot_json,
             completed_at = EXCLUDED.completed_at`,
          [
            backupId,
            uid,
            payload.totalDocuments ?? backedUpCount,
            backedUpCount,
            payload.pendingDocuments ?? 0,
            payload.failedDocuments ?? 0,
            totalBackedUpBytes,
            JSON.stringify(mergedSnapshot),
            payload.deviceInfo ?? "Android DSK LOCKER",
            now,
          ]
        );

        await client.query("COMMIT");

        res.json({
          ok: true,
          backupId,
          storedFiles,
          storedCount: storedFiles.length,
          batchBytesAdded,
          backedUpDocuments: backedUpCount,
          totalBackupBytes: totalBackedUpBytes,
          quotaLimitBytes: quotaLimit,
          lastBackupAt: now,
        });
      } catch (_err) {
        await client.query("ROLLBACK");
        res.status(500).json({ error: "Failed to persist cloud backup to Neon PostgreSQL." });
      } finally {
        client.release();
      }
    }
  );

  /**
   * GET /api/backup/status
   * Returns complete Neon cloud backup metrics, quota usage, breakdown, settings, and recovery status.
   */
  router.get(
    "/backup/status",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      try {
        const filesSummaryRes = await db.query<{
          file_id: string;
          category_id: string;
          mime_type: string;
          original_filename: string;
          size_bytes: number | string;
          is_in_vault: boolean;
          updated_at: number | string;
        }>(
          `SELECT file_id, category_id, mime_type, original_filename, size_bytes, is_in_vault, updated_at
           FROM backup_files WHERE user_id = $1`,
          [uid]
        );

        let totalBytes = 0;
        let photosCount = 0;
        let pdfsCount = 0;
        let videosCount = 0;
        let documentsCount = 0;
        let vaultFilesCount = 0;
        let latestFileTs = 0;

        for (const r of filesSummaryRes.rows) {
          totalBytes += Number(r.size_bytes || 0);
          latestFileTs = Math.max(latestFileTs, Number(r.updated_at || 0));
          if (r.is_in_vault) vaultFilesCount++;
          const mime = (r.mime_type || "").toLowerCase();
          const name = (r.original_filename || "").toLowerCase();
          if (r.category_id === "photos" || mime.startsWith("image/")) {
            photosCount++;
          } else if (r.category_id === "videos" || mime.startsWith("video/")) {
            videosCount++;
          } else {
            documentsCount++;
            if (mime === "application/pdf" || name.endsWith(".pdf")) {
              pdfsCount++;
            }
          }
        }

        const latestBackupRes = await db.query<{
          id: string;
          status: string;
          snapshot_json: Record<string, unknown> | null;
          completed_at: number | string | null;
          started_at: number | string;
        }>(
          `SELECT id, status, snapshot_json, completed_at, started_at
           FROM user_backups WHERE user_id = $1
           ORDER BY started_at DESC LIMIT 1`,
          [uid]
        );

        const latestBackup = latestBackupRes.rows[0];
        const snap = (latestBackup?.snapshot_json || {}) as Record<string, unknown[]>;
        const notesCount = Array.isArray(snap.textNotes) ? snap.textNotes.length : 0;
        const foldersCount = Array.isArray(snap.folders) ? snap.folders.length : 0;
        const categoriesCount = Array.isArray(snap.customCategories) ? snap.customCategories.length : 0;
        const linksCount = Array.isArray(snap.savedLinks) ? snap.savedLinks.length : 0;

        const settingsRes = await db.query<{
          cloud_backup_enabled: boolean;
          wifi_only: boolean;
          backup_photos: boolean;
          backup_videos: boolean;
          backup_documents: boolean;
          storage_quota_bytes: number | string;
          max_file_bytes: number | string;
          updated_at: number | string;
        }>(`SELECT * FROM backup_settings WHERE user_id = $1`, [uid]);

        const secRes = await db.query<{
          wrapped_vault_key: string | null;
          vault_key_version: number;
          key_recovery_mode: string | null;
        }>(
          `SELECT wrapped_vault_key, vault_key_version, key_recovery_mode FROM user_security WHERE firebase_uid = $1`,
          [uid]
        );

        const restoreRes = await db.query(
          `SELECT * FROM restore_history WHERE user_id = $1 ORDER BY started_at DESC LIMIT 1`,
          [uid]
        );

        let neonDbUsedBytes = totalBytes;
        try {
          const sizeRes = await db.query<{ db_size: number | string }>(
            `SELECT pg_database_size(current_database()) AS db_size`
          );
          neonDbUsedBytes = Number(sizeRes.rows[0]?.db_size || totalBytes);
        } catch {
          // Ignore when pg_database_size unavailable in test engine
        }

        const st = settingsRes.rows[0];
        const lastBackupAt = Number(latestBackup?.completed_at || latestBackup?.started_at || latestFileTs || 0);

        res.json({
          userId: uid,
          lastBackupId: latestBackup?.id || null,
          lastBackupAt: lastBackupAt > 0 ? lastBackupAt : null,
          lastBackupStatus: latestBackup?.status || (filesSummaryRes.rows.length > 0 ? "COMPLETED" : "NONE"),
          backedUpFilesCount: filesSummaryRes.rows.length,
          totalBackupSizeBytes: totalBytes,
          photosCount,
          pdfsCount,
          documentsCount,
          videosCount,
          vaultFilesCount,
          notesCount,
          foldersCount,
          categoriesCount,
          linksCount,
          quotaLimitBytes: Number(st?.storage_quota_bytes || defaultUserQuotaBytes()),
          maxFileBytes: Number(st?.max_file_bytes || defaultMaxFileBytes()),
          neonDbUsedBytes,
          neonDbLimitBytes: neonFreeTierBytes(),
          hasWrappedRecoveryKey: Boolean(secRes.rows[0]?.wrapped_vault_key),
          vaultKeyVersion: Number(secRes.rows[0]?.vault_key_version || 0),
          keyRecoveryMode: secRes.rows[0]?.key_recovery_mode || null,
          settings: {
            cloudBackupEnabled: st?.cloud_backup_enabled ?? true,
            wifiOnly: st?.wifi_only ?? true,
            backupPhotos: st?.backup_photos ?? true,
            backupVideos: st?.backup_videos ?? true,
            backupDocuments: st?.backup_documents ?? true,
          },
          latestRestore: restoreRes.rows[0] || null,
        });
      } catch (_err) {
        res.status(500).json({ error: "Failed to read backup status from Neon PostgreSQL." });
      }
    }
  );

  /**
   * GET /api/backup/history
   * Returns user's backup_history, user_backups runs, and restore_history strictly scoped to uid.
   */
  router.get(
    "/backup/history",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      try {
        const [histRes, restoresRes, runsRes] = await Promise.all([
          db.query(
            `SELECT id, firebase_uid AS user_id, backup_id, file_id, file_name, action, status,
                    bytes_transferred, message, timestamp
             FROM backup_history WHERE firebase_uid = $1 ORDER BY timestamp DESC LIMIT 100`,
            [uid]
          ),
          db.query(
            `SELECT * FROM restore_history WHERE user_id = $1 ORDER BY started_at DESC LIMIT 50`,
            [uid]
          ),
          db.query(
            `SELECT id, user_id, status, total_documents, backed_up_documents, pending_documents,
                    failed_documents, total_bytes, device_info, started_at, completed_at
             FROM user_backups WHERE user_id = $1 ORDER BY started_at DESC LIMIT 30`,
            [uid]
          ),
        ]);
        res.json({
          backups: histRes.rows,
          restores: restoresRes.rows,
          runs: runsRes.rows,
        });
      } catch (_err) {
        res.status(500).json({ error: "Failed to load backup & restore history." });
      }
    }
  );

  /**
   * GET /api/backup/files
   * Lists user's backed-up file metadata (omitting binary BYTEA) plus snapshot metadata.
   */
  router.get(
    "/backup/files",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      try {
        const [filesRes, snapRes] = await Promise.all([
          db.query(
            `SELECT id, user_id, backup_id, file_id, original_filename, display_name,
                    category_id, folder_id, mime_type, size_bytes, sha256,
                    is_in_vault, is_favorite, is_encrypted_payload, key_version,
                    metadata_json, created_at, updated_at
             FROM backup_files WHERE user_id = $1 ORDER BY updated_at DESC`,
            [uid]
          ),
          db.query<{ snapshot_json: unknown }>(
            `SELECT snapshot_json FROM user_backups WHERE user_id = $1 ORDER BY started_at DESC LIMIT 1`,
            [uid]
          ),
        ]);

        res.json({
          files: filesRes.rows,
          snapshot: snapRes.rows[0]?.snapshot_json || {
            folders: [],
            customCategories: [],
            textNotes: [],
            savedLinks: [],
          },
        });
      } catch (_err) {
        res.status(500).json({ error: "Failed to list cloud backup files." });
      }
    }
  );

  /**
   * GET /api/backup/files/:fileId/content
   * Downloads raw binary BYTEA content strictly owned by authenticated user.
   */
  router.get(
    "/backup/files/:fileId/content",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const fileId = Array.isArray(req.params.fileId)
        ? req.params.fileId[0]
        : req.params.fileId;
      try {
        const result = await db.query<{
          file_content: Buffer | Uint8Array;
          sha256: string;
          mime_type: string;
          original_filename: string;
          is_encrypted_payload: boolean;
          key_version: number;
        }>(
          `SELECT file_content, sha256, mime_type, original_filename, is_encrypted_payload, key_version
           FROM backup_files
           WHERE user_id = $1 AND file_id = $2
           LIMIT 1`,
          [uid, fileId]
        );

        if (result.rows.length === 0) {
          res.status(404).json({ error: "Backup file not found for this account." });
          return;
        }

        const row = result.rows[0];
        const rawBuf = Buffer.isBuffer(row.file_content)
          ? row.file_content
          : Buffer.from(row.file_content);

        res.setHeader("Content-Type", row.mime_type || "application/octet-stream");
        res.setHeader("Content-Length", String(rawBuf.length));
        res.setHeader("X-File-Sha256", row.sha256);
        res.setHeader("X-Is-Encrypted-Payload", String(Boolean(row.is_encrypted_payload)));
        res.setHeader("X-Key-Version", String(Number(row.key_version || 0)));
        res.status(200).send(rawBuf);
      } catch (_err) {
        res.status(500).json({ error: "Failed to download backed-up file content." });
      }
    }
  );

  /**
   * DELETE /api/backup/files/:fileId
   * Deletes only the user's cloud backup copy (never deletes another user's rows).
   */
  router.delete(
    "/backup/files/:fileId",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const fileId = Array.isArray(req.params.fileId)
        ? req.params.fileId[0]
        : req.params.fileId;
      try {
        await db.query(
          `DELETE FROM backup_files WHERE user_id = $1 AND file_id = $2`,
          [uid, fileId]
        );
        await db.query(
          `UPDATE files SET cloud_path = NULL, cloud_bucket = NULL, backup_status = 'MOBILE_ONLY'
           WHERE firebase_uid = $1 AND id = $2`,
          [uid, fileId]
        );
        res.json({ ok: true });
      } catch (_err) {
        res.status(500).json({ error: "Failed to delete cloud backup file." });
      }
    }
  );

  /**
   * POST /api/backup/restore
   * Initiates a restore run in `restore_history` and returns manifest + metadata snapshot.
   */
  router.post(
    "/backup/restore",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const parsed = restoreStartSchema.safeParse(req.body ?? {});
      if (!parsed.success) {
        res.status(400).json({ error: "Invalid restore request." });
        return;
      }

      const now = Date.now();
      const restoreId = crypto.randomUUID();

      try {
        const filterIds = parsed.data.fileIds;
        const filesQuery =
          filterIds && filterIds.length > 0
            ? await db.query(
                `SELECT id, user_id, backup_id, file_id, original_filename, display_name,
                        category_id, folder_id, mime_type, size_bytes, sha256,
                        is_in_vault, is_favorite, is_encrypted_payload, key_version,
                        metadata_json, created_at, updated_at
                 FROM backup_files WHERE user_id = $1 AND file_id = ANY($2::text[])
                 ORDER BY updated_at DESC`,
                [uid, filterIds]
              )
            : await db.query(
                `SELECT id, user_id, backup_id, file_id, original_filename, display_name,
                        category_id, folder_id, mime_type, size_bytes, sha256,
                        is_in_vault, is_favorite, is_encrypted_payload, key_version,
                        metadata_json, created_at, updated_at
                 FROM backup_files WHERE user_id = $1 ORDER BY updated_at DESC`,
                [uid]
              );

        const snapRes = await db.query<{ snapshot_json: unknown; id: string }>(
          `SELECT id, snapshot_json FROM user_backups WHERE user_id = $1 ORDER BY started_at DESC LIMIT 1`,
          [uid]
        );
        const secRes = await db.query<{ wrapped_vault_key: string | null; key_recovery_mode: string | null }>(
          `SELECT wrapped_vault_key, key_recovery_mode FROM user_security WHERE firebase_uid = $1`,
          [uid]
        );

        const totalBytes = filesResBytes(filesQuery.rows);

        await db.query(
          `INSERT INTO restore_history (
             id, user_id, backup_id, status, total_files,
             restored_files, verified_files, failed_files, bytes_restored,
             message, device_info, started_at, completed_at
           ) VALUES (
             $1, $2, $3, 'IN_PROGRESS', $4,
             0, 0, 0, 0,
             $5, $6, $7, NULL
           )`,
          [
            restoreId,
            uid,
            parsed.data.backupId || snapRes.rows[0]?.id || null,
            filesQuery.rows.length,
            `Restore initiated for ${filesQuery.rows.length} backed-up file(s).`,
            parsed.data.deviceInfo || "Android DSK LOCKER",
            now,
          ]
        );

        res.json({
          restoreId,
          totalFiles: filesQuery.rows.length,
          totalBytes,
          files: filesQuery.rows,
          snapshot: snapRes.rows[0]?.snapshot_json || {
            folders: [],
            customCategories: [],
            textNotes: [],
            savedLinks: [],
          },
          hasWrappedRecoveryKey: Boolean(secRes.rows[0]?.wrapped_vault_key),
          keyRecoveryMode: secRes.rows[0]?.key_recovery_mode || null,
        });
      } catch (_err) {
        res.status(500).json({ error: "Failed to start Neon cloud backup restore." });
      }
    }
  );

  router.post(
    "/backup/restore/complete",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const parsed = restoreCompleteSchema.safeParse(req.body ?? {});
      if (!parsed.success) {
        res.status(400).json({ error: "Invalid restore completion payload." });
        return;
      }

      const d = parsed.data;
      const now = Date.now();
      try {
        await db.query(
          `UPDATE restore_history
           SET status = $1, restored_files = $2, verified_files = $3,
               failed_files = $4, bytes_restored = $5, message = $6, completed_at = $7
           WHERE id = $8 AND user_id = $9`,
          [
            d.status,
            d.restoredFiles,
            d.verifiedFiles,
            d.failedFiles,
            d.bytesRestored,
            d.message,
            now,
            d.restoreId,
            uid,
          ]
        );

        await db.query(
          `INSERT INTO backup_history (
             id, firebase_uid, backup_id, file_id, file_name, action, status,
             bytes_transferred, message, timestamp
           ) VALUES ($1, $2, $3, NULL, $4, 'RESTORE', $5, $6, $7, $8)`,
          [
            crypto.randomUUID(),
            uid,
            d.restoreId,
            `Restore (${d.verifiedFiles} verified / ${d.failedFiles} failed)`,
            d.status === "FAILED" ? "FAILED" : "SUCCESS",
            d.bytesRestored,
            d.message,
            now,
          ]
        );

        res.json({ ok: true });
      } catch (_err) {
        res.status(500).json({ error: "Failed to update restore status." });
      }
    }
  );

  /**
   * GET /api/backup/restore-status
   * Returns latest restore job status for authenticated user.
   */
  router.get(
    "/backup/restore-status",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      try {
        const result = await db.query(
          `SELECT * FROM restore_history WHERE user_id = $1 ORDER BY started_at DESC LIMIT 1`,
          [uid]
        );
        res.json({
          hasRestoreHistory: result.rows.length > 0,
          latestRestore: result.rows[0] || null,
        });
      } catch (_err) {
        res.status(500).json({ error: "Failed to load restore status." });
      }
    }
  );

  /**
   * PUT /api/backup/settings
   * Saves cloud backup preferences in Neon `backup_settings`.
   */
  router.put(
    "/backup/settings",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const parsed = backupSettingsSchema.safeParse(req.body ?? {});
      if (!parsed.success) {
        res.status(400).json({ error: "Invalid backup settings payload." });
        return;
      }
      const d = parsed.data;
      const now = Date.now();
      try {
        await db.query(
          `INSERT INTO backup_settings (
             user_id, cloud_backup_enabled, wifi_only,
             backup_photos, backup_videos, backup_documents,
             storage_quota_bytes, max_file_bytes, updated_at
           ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
           ON CONFLICT (user_id) DO UPDATE SET
             cloud_backup_enabled = EXCLUDED.cloud_backup_enabled,
             wifi_only = EXCLUDED.wifi_only,
             backup_photos = EXCLUDED.backup_photos,
             backup_videos = EXCLUDED.backup_videos,
             backup_documents = EXCLUDED.backup_documents,
             updated_at = EXCLUDED.updated_at`,
          [
            uid,
            d.cloudBackupEnabled,
            d.wifiOnly,
            d.backupPhotos,
            d.backupVideos,
            d.backupDocuments,
            defaultUserQuotaBytes(),
            defaultMaxFileBytes(),
            now,
          ]
        );
        res.json({ ok: true, settings: d, updatedAt: now });
      } catch (_err) {
        res.status(500).json({ error: "Failed to update backup settings." });
      }
    }
  );

  /**
   * POST /api/security/mpin/verify
   * Constant-time server-side MPIN verification with persistent rate limiting & lockout.
   */
  router.post(
    "/security/mpin/verify",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const parsed = verifyMpinSchema.safeParse(req.body ?? {});
      if (!parsed.success) {
        res.status(400).json({ error: "MPIN must be 4 to 6 digits." });
        return;
      }

      try {
        const verdict = await verifyUserMpinWithRateLimit(uid, parsed.data.mpin);
        if (!verdict.ok) {
          res.status(verdict.status).json({
            ok: false,
            error: verdict.error,
            retryAfterSeconds: verdict.retryAfterSeconds ?? 0,
          });
          return;
        }
        res.json({ ok: true });
      } catch (_err) {
        res.status(500).json({ error: "MPIN verification failed on server." });
      }
    }
  );

  /**
   * POST /api/security/recovery-key
   * Wraps or recovers the user's portable Vault DEK with VAULT_MASTER_SECRET (AES-256-GCM).
   * Explicitly returns an honest error if no wrapped key exists or VAULT_MASTER_SECRET is missing.
   */
  router.post(
    "/security/recovery-key",
    requireAuth,
    requireOwner,
    async (req: AuthenticatedRequest, res: Response) => {
      const uid = req.firebaseUid!;
      const masterSecret = (process.env.VAULT_MASTER_SECRET || "").trim();
      if (!masterSecret || masterSecret.length < 16) {
        res.status(503).json({
          error: "Server encryption-key recovery unavailable: VAULT_MASTER_SECRET is not configured on Render backend.",
        });
        return;
      }

      const parsed = recoveryKeySchema.safeParse(req.body ?? {});
      if (!parsed.success) {
        res.status(400).json({ error: "Invalid encryption-key recovery payload." });
        return;
      }

      const now = Date.now();
      try {
        if (parsed.data.action === "wrap") {
          const secCheck = await db.query<{ mpin_hash: string | null }>(
            `SELECT mpin_hash FROM user_security WHERE firebase_uid = $1`,
            [uid]
          );
          if (secCheck.rows[0]?.mpin_hash && parsed.data.mpin) {
            const mpinRes = await verifyUserMpinWithRateLimit(uid, parsed.data.mpin);
            if (!mpinRes.ok) {
              res.status(mpinRes.status).json({
                ok: false,
                error: mpinRes.error,
                retryAfterSeconds: mpinRes.retryAfterSeconds ?? 0,
              });
              return;
            }
          }

          const wrapped = wrapVaultKeyForUser(uid, parsed.data.vaultKeyBase64, masterSecret);
          await db.query(
            `INSERT INTO user_security (
               firebase_uid, mpin_hash, mpin_salt, wrapped_vault_key, vault_key_version,
               key_recovery_mode, updated_at
             ) VALUES ($1, '', '', $2, $3, 'SERVER_WRAPPED_DEK', $4)
             ON CONFLICT (firebase_uid) DO UPDATE SET
               wrapped_vault_key = EXCLUDED.wrapped_vault_key,
               vault_key_version = EXCLUDED.vault_key_version,
               key_recovery_mode = 'SERVER_WRAPPED_DEK',
               updated_at = EXCLUDED.updated_at`,
            [uid, wrapped, parsed.data.keyVersion, now]
          );
          res.json({ ok: true, keyVersion: parsed.data.keyVersion });
          return;
        }

        // action === "recover"
        const secRes = await db.query<{
          mpin_hash: string | null;
          wrapped_vault_key: string | null;
          vault_key_version: number | null;
        }>(
          `SELECT mpin_hash, wrapped_vault_key, vault_key_version
           FROM user_security WHERE firebase_uid = $1`,
          [uid]
        );
        const row = secRes.rows[0];
        if (!row) {
          res.status(404).json({
            error: "No security profile found. Configure MPIN & portable recovery key before backup.",
          });
          return;
        }
        if (row.mpin_hash) {
          if (!parsed.data.mpin) {
            res.status(400).json({ error: "MPIN is required to recover the vault encryption key." });
            return;
          }
          const mpinRes = await verifyUserMpinWithRateLimit(uid, parsed.data.mpin);
          if (!mpinRes.ok) {
            res.status(mpinRes.status).json({
              ok: false,
              error: mpinRes.error,
              retryAfterSeconds: mpinRes.retryAfterSeconds ?? 0,
            });
            return;
          }
        }
        if (!row.wrapped_vault_key) {
          res.status(404).json({
            error:
              "No wrapped portable encryption key was backed up for this account. Hardware AndroidKeyStore keys from a wiped/old device cannot be recovered by MPIN alone.",
          });
          return;
        }

        const vaultKeyBase64 = unwrapVaultKeyForUser(uid, row.wrapped_vault_key, masterSecret);
        res.json({
          ok: true,
          vaultKeyBase64,
          keyVersion: Number(row.vault_key_version || 1),
        });
      } catch (_err) {
        res.status(500).json({ error: "Failed to process encryption-key recovery." });
      }
    }
  );

  return router;
}

function filesResBytes(rows: Array<Record<string, unknown>>): number {
  let sum = 0;
  for (const r of rows) {
    sum += Number(r.size_bytes || 0);
  }
  return sum;
}

export const backupRouter = createBackupRouter();
