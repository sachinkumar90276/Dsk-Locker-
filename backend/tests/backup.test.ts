import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import type { AddressInfo } from "node:net";
import type { QueryResult, QueryResultRow } from "pg";
import { createApp } from "../src/server";
import type { DbAdapter, DbClient } from "../src/db/pool";
import type { TokenVerifier } from "../src/middleware/auth";

interface UserRow {
  firebase_uid: string;
  phone_number: string | null;
  is_authorized_owner: boolean;
  created_at: number;
  last_login_at: number;
}

interface SecurityRow {
  firebase_uid: string;
  mpin_hash: string;
  mpin_salt: string;
  mpin_length: number;
  wrapped_vault_key: string | null;
  vault_key_version: number;
  key_recovery_mode: string | null;
  biometric_enabled: boolean;
  failed_attempts: number;
  lockout_until_ms: number;
  updated_at: number;
}

interface BackupFileRow {
  id: string;
  user_id: string;
  backup_id: string | null;
  file_id: string;
  original_filename: string;
  display_name: string;
  category_id: string;
  folder_id: string | null;
  mime_type: string;
  size_bytes: number;
  sha256: string;
  is_in_vault: boolean;
  is_favorite: boolean;
  is_encrypted_payload: boolean;
  key_version: number;
  file_content: Buffer;
  metadata_json: Record<string, unknown>;
  created_at: number;
  updated_at: number;
}

class InMemoryNeonDb implements DbAdapter {
  users = new Map<string, UserRow>();
  userProfiles = new Map<string, Record<string, unknown>>();
  userSettings = new Map<string, Record<string, unknown>>();
  userSecurity = new Map<string, SecurityRow>();
  files = new Map<string, Record<string, unknown>>();
  backupFiles = new Map<string, BackupFileRow>(); // key = `${user_id}::${file_id}`
  userBackups: Array<Record<string, unknown>> = [];
  backupHistory: Array<Record<string, unknown>> = [];
  restoreHistory: Array<Record<string, unknown>> = [];
  backupSettings = new Map<string, Record<string, unknown>>();
  securityLogs: Array<Record<string, unknown>> = [];
  simulatedDbOverheadBytes = 0;

  private snapshotState() {
    return {
      users: new Map(this.users),
      userProfiles: new Map(this.userProfiles),
      userSettings: new Map(this.userSettings),
      userSecurity: new Map(this.userSecurity),
      files: new Map(this.files),
      backupFiles: new Map(this.backupFiles),
      userBackups: [...this.userBackups],
      backupHistory: [...this.backupHistory],
      restoreHistory: [...this.restoreHistory],
      backupSettings: new Map(this.backupSettings),
      securityLogs: [...this.securityLogs],
    };
  }

  private restoreState(snap: ReturnType<InMemoryNeonDb["snapshotState"]>) {
    this.users = snap.users;
    this.userProfiles = snap.userProfiles;
    this.userSettings = snap.userSettings;
    this.userSecurity = snap.userSecurity;
    this.files = snap.files;
    this.backupFiles = snap.backupFiles;
    this.userBackups = snap.userBackups;
    this.backupHistory = snap.backupHistory;
    this.restoreHistory = snap.restoreHistory;
    this.backupSettings = snap.backupSettings;
    this.securityLogs = snap.securityLogs;
  }

  async connect(): Promise<DbClient> {
    let txSnap: ReturnType<InMemoryNeonDb["snapshotState"]> | null = null;
    const self = this;
    return {
      async query<T extends QueryResultRow = QueryResultRow>(text: string, params: unknown[] = []) {
        const trimmed = text.trim().toUpperCase();
        if (trimmed === "BEGIN") {
          txSnap = self.snapshotState();
          return { rows: [], rowCount: 0 } as unknown as QueryResult<T>;
        }
        if (trimmed === "COMMIT") {
          txSnap = null;
          return { rows: [], rowCount: 0 } as unknown as QueryResult<T>;
        }
        if (trimmed === "ROLLBACK") {
          if (txSnap) self.restoreState(txSnap);
          txSnap = null;
          return { rows: [], rowCount: 0 } as unknown as QueryResult<T>;
        }
        return self.query<T>(text, params);
      },
      release() {},
    };
  }

  async query<T extends QueryResultRow = QueryResultRow>(
    text: string,
    params: unknown[] = []
  ): Promise<QueryResult<T>> {
    const sql = text.replace(/\s+/g, " ").trim();
    const res = (rows: unknown[]): QueryResult<T> =>
      ({ rows: rows as T[], rowCount: rows.length } as unknown as QueryResult<T>);

    if (sql === "SELECT 1") return res([{ "?column?": 1 }]);

    if (sql.startsWith("SELECT is_authorized_owner, phone_number FROM users WHERE firebase_uid = $1")) {
      const row = this.users.get(String(params[0]));
      return res(row ? [{ is_authorized_owner: row.is_authorized_owner, phone_number: row.phone_number }] : []);
    }

    if (sql.includes("INSERT INTO users (firebase_uid, phone_number")) {
      const [uid, phone, now] = params as [string, string | null, number];
      const existing = this.users.get(uid);
      this.users.set(uid, {
        firebase_uid: uid,
        phone_number: phone ?? existing?.phone_number ?? null,
        is_authorized_owner: true,
        created_at: existing?.created_at ?? now,
        last_login_at: now,
      });
      return res([]);
    }

    if (sql.includes("INSERT INTO user_profiles (firebase_uid, owner_name")) {
      const [uid, ownerName, phone, now] = params as [string, string, string, number];
      this.userProfiles.set(uid, {
        firebase_uid: uid,
        owner_name: ownerName,
        phone_number: phone,
        updated_at: now,
      });
      return res([]);
    }

    if (sql.includes("INSERT INTO user_settings (firebase_uid, updated_at)")) {
      const [uid, now] = params as [string, number];
      if (!this.userSettings.has(uid)) {
        this.userSettings.set(uid, { firebase_uid: uid, updated_at: now });
      }
      return res([]);
    }

    if (sql.includes("INSERT INTO user_security") && sql.includes("wrapped_vault_key")) {
      const [uid, wrapped, kv, now] = params as [string, string, number, number];
      const existing = this.userSecurity.get(uid);
      this.userSecurity.set(uid, {
        firebase_uid: uid,
        mpin_hash: existing?.mpin_hash ?? "",
        mpin_salt: existing?.mpin_salt ?? "",
        mpin_length: existing?.mpin_length ?? 6,
        wrapped_vault_key: wrapped,
        vault_key_version: kv,
        key_recovery_mode: "SERVER_WRAPPED_DEK",
        biometric_enabled: existing?.biometric_enabled ?? false,
        failed_attempts: existing?.failed_attempts ?? 0,
        lockout_until_ms: existing?.lockout_until_ms ?? 0,
        updated_at: now,
      });
      return res([]);
    }

    if (sql.startsWith("INSERT INTO user_security")) {
      const [uid, hash, salt, mpinLen, _sHash, _sSalt, bio, now] = params as [
        string,
        string,
        string,
        number | null,
        string | null,
        string | null,
        boolean | null,
        number
      ];
      const existing = this.userSecurity.get(uid);
      this.userSecurity.set(uid, {
        firebase_uid: uid,
        mpin_hash: hash,
        mpin_salt: salt,
        mpin_length: mpinLen ?? existing?.mpin_length ?? 6,
        wrapped_vault_key: existing?.wrapped_vault_key ?? null,
        vault_key_version: existing?.vault_key_version ?? 0,
        key_recovery_mode: existing?.key_recovery_mode ?? null,
        biometric_enabled: bio ?? existing?.biometric_enabled ?? false,
        failed_attempts: existing?.failed_attempts ?? 0,
        lockout_until_ms: existing?.lockout_until_ms ?? 0,
        updated_at: now,
      });
      return res([]);
    }

    if (sql.startsWith("SELECT mpin_hash, mpin_salt, failed_attempts, lockout_until_ms FROM user_security")) {
      const sec = this.userSecurity.get(String(params[0]));
      return res(
        sec
          ? [
              {
                mpin_hash: sec.mpin_hash,
                mpin_salt: sec.mpin_salt,
                failed_attempts: sec.failed_attempts,
                lockout_until_ms: sec.lockout_until_ms,
              },
            ]
          : []
      );
    }

    if (sql.startsWith("UPDATE user_security SET failed_attempts = 0")) {
      const [now, uid] = params as [number, string];
      const sec = this.userSecurity.get(uid);
      if (sec) {
        sec.failed_attempts = 0;
        sec.lockout_until_ms = 0;
        sec.updated_at = now;
      }
      return res([]);
    }

    if (sql.startsWith("UPDATE user_security SET failed_attempts = $1")) {
      const [attempts, lockout, now, uid] = params as [number, number, number, string];
      const sec = this.userSecurity.get(uid);
      if (sec) {
        sec.failed_attempts = attempts;
        sec.lockout_until_ms = lockout;
        sec.updated_at = now;
      }
      return res([]);
    }

    if (sql.startsWith("SELECT mpin_hash FROM user_security WHERE firebase_uid = $1")) {
      const sec = this.userSecurity.get(String(params[0]));
      return res(sec ? [{ mpin_hash: sec.mpin_hash }] : []);
    }

    if (sql.startsWith("UPDATE user_security SET wrapped_vault_key = $1")) {
      const [wrapped, kv, now, uid] = params as [string, number, number, string];
      const sec = this.userSecurity.get(uid);
      if (sec) {
        sec.wrapped_vault_key = wrapped;
        sec.vault_key_version = kv;
        sec.key_recovery_mode = "SERVER_WRAPPED_DEK";
        sec.updated_at = now;
      }
      return res([]);
    }

    if (sql.startsWith("SELECT wrapped_vault_key, vault_key_version, key_recovery_mode FROM user_security")) {
      const sec = this.userSecurity.get(String(params[0]));
      return res(
        sec
          ? [
              {
                wrapped_vault_key: sec.wrapped_vault_key,
                vault_key_version: sec.vault_key_version,
                key_recovery_mode: sec.key_recovery_mode,
              },
            ]
          : []
      );
    }

    if (sql.startsWith("SELECT mpin_hash, wrapped_vault_key, vault_key_version FROM user_security")) {
      const sec = this.userSecurity.get(String(params[0]));
      return res(
        sec
          ? [
              {
                mpin_hash: sec.mpin_hash,
                wrapped_vault_key: sec.wrapped_vault_key,
                vault_key_version: sec.vault_key_version,
              },
            ]
          : []
      );
    }

    if (sql.startsWith("SELECT wrapped_vault_key, key_recovery_mode FROM user_security")) {
      const sec = this.userSecurity.get(String(params[0]));
      return res(
        sec
          ? [
              {
                wrapped_vault_key: sec.wrapped_vault_key,
                key_recovery_mode: sec.key_recovery_mode,
              },
            ]
          : []
      );
    }

    if (sql.includes("INSERT INTO security_logs")) {
      this.securityLogs.push({ id: params[0], firebase_uid: params[1], event_type: params[2] });
      return res([]);
    }

    if (sql.startsWith("SELECT storage_quota_bytes, max_file_bytes FROM backup_settings WHERE user_id = $1")) {
      const row = this.backupSettings.get(String(params[0]));
      return res(row ? [row] : []);
    }

    if (sql.startsWith("SELECT * FROM backup_settings WHERE user_id = $1")) {
      const row = this.backupSettings.get(String(params[0]));
      return res(row ? [row] : []);
    }

    if (sql.startsWith("INSERT INTO backup_settings") && params.length === 2) {
      const [uid, now] = params as [string, number];
      if (!this.backupSettings.has(uid)) {
        this.backupSettings.set(uid, {
          user_id: uid,
          cloud_backup_enabled: true,
          wifi_only: true,
          backup_photos: true,
          backup_videos: true,
          backup_documents: true,
          storage_quota_bytes: 471859200,
          max_file_bytes: 20971520,
          updated_at: now,
        });
      }
      return res([]);
    }

    if (sql.startsWith("INSERT INTO backup_settings")) {
      const [uid, enabled, wifiOnly, photos, videos, docs, quota, maxFile, now] = params as [
        string,
        boolean,
        boolean,
        boolean,
        boolean,
        boolean,
        number,
        number,
        number
      ];
      this.backupSettings.set(uid, {
        user_id: uid,
        cloud_backup_enabled: enabled,
        wifi_only: wifiOnly,
        backup_photos: photos,
        backup_videos: videos,
        backup_documents: docs,
        storage_quota_bytes: quota,
        max_file_bytes: maxFile,
        updated_at: now,
      });
      return res([]);
    }

    if (sql.startsWith("SELECT COALESCE(SUM(size_bytes), 0) AS total_bytes FROM backup_files WHERE user_id = $1")) {
      const uid = String(params[0]);
      let total = 0;
      for (const r of this.backupFiles.values()) {
        if (r.user_id === uid) total += r.size_bytes;
      }
      return res([{ total_bytes: total }]);
    }

    if (sql.startsWith("SELECT pg_database_size")) {
      let total = this.simulatedDbOverheadBytes;
      for (const r of this.backupFiles.values()) total += r.size_bytes;
      return res([{ db_size: total }]);
    }

    if (sql.startsWith("SELECT size_bytes FROM backup_files WHERE user_id = $1 AND file_id = $2")) {
      const row = this.backupFiles.get(`${params[0]}::${params[1]}`);
      return res(row ? [{ size_bytes: row.size_bytes }] : []);
    }

    if (sql.startsWith("SELECT file_id FROM backup_files WHERE user_id = $1 AND sha256 = $2")) {
      const [uid, sha, fileId] = params as [string, string, string];
      for (const r of this.backupFiles.values()) {
        if (r.user_id === uid && r.sha256 === sha && r.file_id !== fileId) {
          return res([{ file_id: r.file_id }]);
        }
      }
      return res([]);
    }

    if (sql.startsWith("INSERT INTO backup_files")) {
      const row: BackupFileRow = {
        id: String(params[0]),
        user_id: String(params[1]),
        backup_id: params[2] ? String(params[2]) : null,
        file_id: String(params[3]),
        original_filename: String(params[4]),
        display_name: String(params[5]),
        category_id: String(params[6]),
        folder_id: params[7] ? String(params[7]) : null,
        mime_type: String(params[8]),
        size_bytes: Number(params[9]),
        sha256: String(params[10]),
        is_in_vault: Boolean(params[11]),
        is_favorite: Boolean(params[12]),
        is_encrypted_payload: Boolean(params[13]),
        key_version: Number(params[14]),
        file_content: Buffer.from(params[15] as Buffer),
        metadata_json: JSON.parse(String(params[16])),
        created_at: Number(params[17]),
        updated_at: Number(params[18]),
      };
      this.backupFiles.set(`${row.user_id}::${row.file_id}`, row);
      return res([]);
    }

    if (sql.startsWith("INSERT INTO files")) {
      this.files.set(`${params[1]}::${params[0]}`, {
        id: params[0],
        firebase_uid: params[1],
        display_name: params[3],
        backup_status: "MOBILE_AND_CLOUD",
      });
      return res([]);
    }

    if (sql.startsWith("INSERT INTO backup_history")) {
      this.backupHistory.unshift({
        id: params[0],
        firebase_uid: params[1],
        user_id: params[1],
        backup_id: params[2],
        file_id: params[3],
        file_name: params[4],
        action: params[5],
        status: params[6] === "RESTORE" ? params[4] : sql.includes("'SUCCESS'") ? "SUCCESS" : params[6],
        bytes_transferred: params[7],
        message: params[8],
        timestamp: params[9] ?? params[8],
      });
      // Normalize action/status cleanly for tests
      const top = this.backupHistory[0];
      if (sql.includes("'RESTORE'")) {
        top.file_name = params[3];
        top.action = "RESTORE";
        top.status = params[4];
        top.bytes_transferred = params[5];
        top.message = params[6];
        top.timestamp = params[7];
      } else {
        top.status = "SUCCESS";
      }
      return res([]);
    }

    if (sql.startsWith("SELECT snapshot_json FROM user_backups WHERE user_id = $1")) {
      const uid = String(params[0]);
      const match = this.userBackups
        .filter((b) => b.user_id === uid)
        .sort((a, b) => Number(b.started_at) - Number(a.started_at))[0];
      return res(match ? [{ snapshot_json: match.snapshot_json }] : []);
    }

    if (sql.startsWith("SELECT id, snapshot_json FROM user_backups WHERE user_id = $1")) {
      const uid = String(params[0]);
      const match = this.userBackups
        .filter((b) => b.user_id === uid)
        .sort((a, b) => Number(b.started_at) - Number(a.started_at))[0];
      return res(match ? [{ id: match.id, snapshot_json: match.snapshot_json }] : []);
    }

    if (sql.startsWith("SELECT COUNT(*) AS cnt, COALESCE(SUM(size_bytes), 0) AS total_bytes FROM backup_files")) {
      const uid = String(params[0]);
      let cnt = 0;
      let total = 0;
      for (const r of this.backupFiles.values()) {
        if (r.user_id === uid) {
          cnt++;
          total += r.size_bytes;
        }
      }
      return res([{ cnt, total_bytes: total }]);
    }

    if (sql.startsWith("INSERT INTO user_backups")) {
      const row = {
        id: params[0],
        user_id: params[1],
        status: "COMPLETED",
        total_documents: params[2],
        backed_up_documents: params[3],
        pending_documents: params[4],
        failed_documents: params[5],
        total_bytes: params[6],
        snapshot_json: JSON.parse(String(params[7])),
        device_info: params[8],
        started_at: params[9],
        completed_at: params[9],
      };
      this.userBackups = this.userBackups.filter((b) => b.id !== row.id);
      this.userBackups.unshift(row);
      return res([]);
    }

    if (sql.startsWith("SELECT file_id, category_id, mime_type, original_filename, size_bytes, is_in_vault, updated_at FROM backup_files")) {
      const uid = String(params[0]);
      return res(
        Array.from(this.backupFiles.values())
          .filter((r) => r.user_id === uid)
          .map((r) => ({
            file_id: r.file_id,
            category_id: r.category_id,
            mime_type: r.mime_type,
            original_filename: r.original_filename,
            size_bytes: r.size_bytes,
            is_in_vault: r.is_in_vault,
            updated_at: r.updated_at,
          }))
      );
    }

    if (sql.startsWith("SELECT id, status, snapshot_json, completed_at, started_at FROM user_backups")) {
      const uid = String(params[0]);
      const match = this.userBackups
        .filter((b) => b.user_id === uid)
        .sort((a, b) => Number(b.started_at) - Number(a.started_at))[0];
      return res(match ? [match] : []);
    }

    if (sql.startsWith("SELECT * FROM restore_history WHERE user_id = $1")) {
      const uid = String(params[0]);
      return res(
        this.restoreHistory
          .filter((r) => r.user_id === uid)
          .sort((a, b) => Number(b.started_at) - Number(a.started_at))
      );
    }

    if (sql.startsWith("SELECT id, firebase_uid AS user_id, backup_id")) {
      const uid = String(params[0]);
      return res(
        this.backupHistory
          .filter((h) => h.firebase_uid === uid)
          .sort((a, b) => Number(b.timestamp) - Number(a.timestamp))
      );
    }

    if (sql.startsWith("SELECT id, user_id, status, total_documents")) {
      const uid = String(params[0]);
      return res(this.userBackups.filter((b) => b.user_id === uid));
    }

    if (sql.includes("FROM backup_files WHERE user_id = $1 AND file_id = ANY($2::text[])")) {
      const uid = String(params[0]);
      const ids = params[1] as string[];
      return res(
        Array.from(this.backupFiles.values())
          .filter((r) => r.user_id === uid && ids.includes(r.file_id))
          .map(({ file_content: _omit, ...rest }) => rest)
      );
    }

    if (sql.includes("FROM backup_files WHERE user_id = $1 ORDER BY updated_at DESC")) {
      const uid = String(params[0]);
      return res(
        Array.from(this.backupFiles.values())
          .filter((r) => r.user_id === uid)
          .sort((a, b) => b.updated_at - a.updated_at)
          .map(({ file_content: _omit, ...rest }) => rest)
      );
    }

    if (sql.startsWith("SELECT file_content, sha256, mime_type, original_filename, is_encrypted_payload, key_version FROM backup_files")) {
      const row = this.backupFiles.get(`${params[0]}::${params[1]}`);
      return res(row ? [row] : []);
    }

    if (sql.startsWith("DELETE FROM backup_files WHERE user_id = $1 AND file_id = $2")) {
      this.backupFiles.delete(`${params[0]}::${params[1]}`);
      return res([]);
    }

    if (sql.startsWith("UPDATE files SET cloud_path = NULL")) {
      const row = this.files.get(`${params[0]}::${params[1]}`);
      if (row) row.backup_status = "MOBILE_ONLY";
      return res([]);
    }

    if (sql.startsWith("INSERT INTO restore_history")) {
      this.restoreHistory.unshift({
        id: params[0],
        user_id: params[1],
        backup_id: params[2],
        status: "IN_PROGRESS",
        total_files: params[3],
        restored_files: 0,
        verified_files: 0,
        failed_files: 0,
        bytes_restored: 0,
        message: params[4],
        device_info: params[5],
        started_at: params[6],
        completed_at: null,
      });
      return res([]);
    }

    if (sql.startsWith("UPDATE restore_history SET status = $1")) {
      const [status, restored, verified, failed, bytes, msg, now, id, uid] = params;
      const target = this.restoreHistory.find((r) => r.id === id && r.user_id === uid);
      if (target) {
        target.status = status;
        target.restored_files = restored;
        target.verified_files = verified;
        target.failed_files = failed;
        target.bytes_restored = bytes;
        target.message = msg;
        target.completed_at = now;
      }
      return res([]);
    }

    return res([]);
  }
}

function hashMpinPbkdf2(pin: string, saltBase64: string): string {
  const salt = Buffer.from(saltBase64, "base64");
  const hash = crypto.pbkdf2Sync(pin, salt, 100_000, 32, "sha256");
  return hash.toString("base64");
}

function sha256(buf: Buffer): string {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

async function withServer(
  db: DbAdapter,
  fn: (baseUrl: string) => Promise<void>
) {
  process.env.VAULT_MASTER_SECRET = "0123456789abcdef0123456789abcdef";
  process.env.DATABASE_URL = "postgresql://user:pass@ep-demo.us-east-2.aws.neon.tech/dsk?sslmode=require";
  const verifyToken: TokenVerifier = async (token) => {
    if (token === "token-user-a") return { uid: "uid-user-a", phone_number: "+919000000001" };
    if (token === "token-user-b") return { uid: "uid-user-b", phone_number: "+919000000002" };
    throw new Error("Invalid token");
  };
  const app = createApp({
    db,
    tokenVerifier: verifyToken,
    allowedOrigins: ["https://dsklocker.example.com"],
  });
  const server = app.listen(0);
  await new Promise<void>((resolve) => server.once("listening", resolve));
  const { port } = server.address() as AddressInfo;
  try {
    await fn(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}

test("Render + Neon Backup end-to-end backup, restore, security, quota, CORS, and user isolation", async () => {
  const db = new InMemoryNeonDb();
  await withServer(db, async (baseUrl) => {
    // 1. Root, Health & strict CORS checks
    const rootResp = await fetch(`${baseUrl}/`);
    assert.equal(rootResp.status, 200);

    const health = await fetch(`${baseUrl}/health`);
    assert.equal(health.status, 200);
    const healthJson = (await health.json()) as { status: string; missingEnv: string[] };
    assert.equal(healthJson.status, "ok");
    assert.deepEqual(healthJson.missingEnv, []);

    const rogueOrigin = await fetch(`${baseUrl}/health`, {
      headers: { Origin: "https://evil-attacker.example" },
    });
    assert.equal(rogueOrigin.status, 403);

    const allowedOrigin = await fetch(`${baseUrl}/health`, {
      headers: { Origin: "https://dsklocker.example.com" },
    });
    assert.equal(allowedOrigin.status, 200);
    assert.equal(allowedOrigin.headers.get("access-control-allow-origin"), "https://dsklocker.example.com");

    const capacitorOrigin = await fetch(`${baseUrl}/health`, {
      headers: { Origin: "capacitor://localhost" },
    });
    assert.equal(capacitorOrigin.status, 200);
    assert.equal(capacitorOrigin.headers.get("access-control-allow-origin"), "capacitor://localhost");

    // 2. Unauthenticated rejection
    const unauth = await fetch(`${baseUrl}/api/backup/status`);
    assert.equal(unauth.status, 401);

    // 3. Register User A & User B + Sync MPIN hashes
    for (const [token, phone, pin] of [
      ["token-user-a", "+919000000001", "482910"],
      ["token-user-b", "+919000000002", "135790"],
    ]) {
      const reg = await fetch(`${baseUrl}/api/auth/register-owner`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({ phoneNumber: phone, ownerName: `Owner ${phone}` }),
      });
      assert.equal(reg.status, 200);

      const salt = crypto.randomBytes(16).toString("base64");
      const hash = hashMpinPbkdf2(pin, salt);
      const sec = await fetch(`${baseUrl}/api/security/mpin`, {
        method: "PUT",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({ mpinHash: hash, mpinSalt: salt, mpinLength: 6 }),
      });
      assert.equal(sec.status, 200);
    }

    // 4. Backup PDF, Image, OCR metadata, and full snapshot for User A
    const pdfBytes = Buffer.from("%PDF-1.4\n%DSK-LOCKER-TEST-PDF\nHello Aadhaar", "utf8");
    const imgBytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);
    const now = Date.now();

    const backupResp = await fetch(`${baseUrl}/api/backup/create`, {
      method: "POST",
      headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
      body: JSON.stringify({
        action: "MANUAL_BACKUP",
        totalDocuments: 2,
        files: [
          {
            fileId: "file-pdf-1",
            originalFilename: "Aadhaar_Scan.pdf",
            displayName: "Aadhaar Card - Aarav",
            categoryId: "documents",
            folderId: "folder-gov",
            mimeType: "application/pdf",
            sizeBytes: pdfBytes.length,
            sha256: sha256(pdfBytes),
            isInVault: false,
            isFavorite: true,
            isEncryptedPayload: false,
            keyVersion: 0,
            createdAt: now,
            updatedAt: now,
            fileContentBase64: pdfBytes.toString("base64"),
            extraction: { document_name: "Aadhaar Card", owner_name: "Aarav", raw_text: "Aadhaar" },
            extendedOcrJson: "{\"extra\":true}",
            reminder: { id: "rem-1", expiry_date_iso: "2030-01-01", document_name: "Aadhaar Card" },
          },
          {
            fileId: "file-img-1",
            originalFilename: "Photo.png",
            displayName: "Passport Photo",
            categoryId: "photos",
            mimeType: "image/png",
            sizeBytes: imgBytes.length,
            sha256: sha256(imgBytes),
            createdAt: now,
            updatedAt: now,
            fileContentBase64: imgBytes.toString("base64"),
          },
        ],
        snapshot: {
          folders: [{ id: "folder-gov", name: "Gov IDs", category_id: "documents", created_at: now }],
          customCategories: [{ id: "cat-tax", name: "Tax", created_at: now }],
          textNotes: [{ id: "note-1", title: "Bank Note", content: "IFSC SBIN0001", created_at: now, updated_at: now }],
          savedLinks: [{ id: "link-1", link_type: "WEBSITE_LINK", title: "UIDAI", url: "https://uidai.gov.in", created_at: now, updated_at: now }],
        },
      }),
    });
    assert.equal(backupResp.status, 200);
    const backupJson = (await backupResp.json()) as { storedCount: number; backedUpDocuments: number };
    assert.equal(backupJson.storedCount, 2);
    assert.equal(backupJson.backedUpDocuments, 2);

    // 5. SHA-256 mismatch is rejected with 400 and rolls back cleanly
    const badSha = await fetch(`${baseUrl}/api/backup/create`, {
      method: "POST",
      headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
      body: JSON.stringify({
        files: [
          {
            fileId: "file-corrupt",
            originalFilename: "bad.pdf",
            displayName: "Bad",
            categoryId: "documents",
            mimeType: "application/pdf",
            sizeBytes: pdfBytes.length,
            sha256: "00000000000000000000000000000000",
            createdAt: now,
            updatedAt: now,
            fileContentBase64: pdfBytes.toString("base64"),
          },
        ],
      }),
    });
    assert.equal(badSha.status, 400);
    assert.equal(db.backupFiles.has("uid-user-a::file-corrupt"), false);

    // 6. Quota overflow triggers 507
    db.simulatedDbOverheadBytes = 512 * 1024 * 1024 - 10;
    const quotaFail = await fetch(`${baseUrl}/api/backup/create`, {
      method: "POST",
      headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
      body: JSON.stringify({
        files: [
          {
            fileId: "file-overquota",
            originalFilename: "big.pdf",
            displayName: "Big",
            categoryId: "documents",
            mimeType: "application/pdf",
            sizeBytes: pdfBytes.length,
            sha256: sha256(pdfBytes),
            createdAt: now,
            updatedAt: now,
            fileContentBase64: pdfBytes.toString("base64"),
          },
        ],
      }),
    });
    assert.equal(quotaFail.status, 507);
    db.simulatedDbOverheadBytes = 0;

    // 7. Strict User Isolation: User B sees 0 files and cannot download or delete User A's files
    const statusB = await fetch(`${baseUrl}/api/backup/status`, {
      headers: { Authorization: "Bearer token-user-b" },
    });
    assert.equal(statusB.status, 200);
    const statusBJson = (await statusB.json()) as { backedUpFilesCount: number };
    assert.equal(statusBJson.backedUpFilesCount, 0);

    const stealContent = await fetch(`${baseUrl}/api/backup/files/file-pdf-1/content`, {
      headers: { Authorization: "Bearer token-user-b" },
    });
    assert.equal(stealContent.status, 404);

    await fetch(`${baseUrl}/api/backup/files/file-pdf-1`, {
      method: "DELETE",
      headers: { Authorization: "Bearer token-user-b" },
    });
    assert.equal(db.backupFiles.has("uid-user-a::file-pdf-1"), true);

    // 8. Honest error when no wrapped recovery key exists, followed by Wrap + MPIN Verify + Recover
    const missingKeyResp = await fetch(`${baseUrl}/api/security/recovery-key`, {
      method: "POST",
      headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
      body: JSON.stringify({ action: "recover", mpin: "482910" }),
    });
    assert.equal(missingKeyResp.status, 404);

    const portableKey = crypto.randomBytes(32).toString("base64");
    const wrapResp = await fetch(`${baseUrl}/api/security/recovery-key`, {
      method: "POST",
      headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
      body: JSON.stringify({ action: "wrap", vaultKeyBase64: portableKey, keyVersion: 1 }),
    });
    assert.equal(wrapResp.status, 200);

    const recoverResp = await fetch(`${baseUrl}/api/security/recovery-key`, {
      method: "POST",
      headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
      body: JSON.stringify({ action: "recover", mpin: "482910" }),
    });
    assert.equal(recoverResp.status, 200);
    const recoveredJson = (await recoverResp.json()) as { vaultKeyBase64: string };
    assert.equal(recoveredJson.vaultKeyBase64, portableKey);

    // 9. Server MPIN verify lockout rate limiting after 5 wrong attempts (401 x4 then 429)
    for (let i = 1; i <= 4; i++) {
      const badPin = await fetch(`${baseUrl}/api/security/mpin/verify`, {
        method: "POST",
        headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
        body: JSON.stringify({ mpin: "000000" }),
      });
      assert.equal(badPin.status, 401);
    }
    const lockoutResp = await fetch(`${baseUrl}/api/security/mpin/verify`, {
      method: "POST",
      headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
      body: JSON.stringify({ mpin: "000000" }),
    });
    assert.equal(lockoutResp.status, 429);

    // 10. Restore workflow: start restore -> download content -> complete -> check status & history
    const startRestore = await fetch(`${baseUrl}/api/backup/restore`, {
      method: "POST",
      headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
      body: JSON.stringify({ deviceInfo: "Pixel 9" }),
    });
    assert.equal(startRestore.status, 200);
    const restoreManifest = (await startRestore.json()) as {
      restoreId: string;
      totalFiles: number;
      hasWrappedRecoveryKey: boolean;
      snapshot: { textNotes: Array<{ title: string }> };
    };
    assert.equal(restoreManifest.totalFiles, 2);
    assert.equal(restoreManifest.hasWrappedRecoveryKey, true);
    assert.equal(restoreManifest.snapshot.textNotes[0].title, "Bank Note");

    const dlPdf = await fetch(`${baseUrl}/api/backup/files/file-pdf-1/content`, {
      headers: { Authorization: "Bearer token-user-a" },
    });
    assert.equal(dlPdf.status, 200);
    assert.equal(dlPdf.headers.get("x-file-sha256"), sha256(pdfBytes));
    const dlBuf = Buffer.from(await dlPdf.arrayBuffer());
    assert.deepEqual(dlBuf, pdfBytes);

    const completeRestore = await fetch(`${baseUrl}/api/backup/restore/complete`, {
      method: "POST",
      headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
      body: JSON.stringify({
        restoreId: restoreManifest.restoreId,
        status: "COMPLETED",
        restoredFiles: 2,
        verifiedFiles: 2,
        failedFiles: 0,
        bytesRestored: pdfBytes.length + imgBytes.length,
        message: "Verified clean restore",
      }),
    });
    assert.equal(completeRestore.status, 200);

    const restoreStatus = await fetch(`${baseUrl}/api/backup/restore-status`, {
      headers: { Authorization: "Bearer token-user-a" },
    });
    assert.equal(restoreStatus.status, 200);
    const restoreStatusJson = (await restoreStatus.json()) as {
      hasRestoreHistory: boolean;
      latestRestore: { status: string; verified_files: number };
    };
    assert.equal(restoreStatusJson.hasRestoreHistory, true);
    assert.equal(restoreStatusJson.latestRestore.status, "COMPLETED");
    assert.equal(restoreStatusJson.latestRestore.verified_files, 2);

    // 11. Settings update
    const putSettings = await fetch(`${baseUrl}/api/backup/settings`, {
      method: "PUT",
      headers: { Authorization: "Bearer token-user-a", "Content-Type": "application/json" },
      body: JSON.stringify({
        cloudBackupEnabled: true,
        wifiOnly: false,
        backupPhotos: true,
        backupVideos: false,
        backupDocuments: true,
      }),
    });
    assert.equal(putSettings.status, 200);
  });
});
