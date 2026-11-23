import { Pool, PoolClient, QueryResult, QueryResultRow } from "pg";
import dotenv from "dotenv";

dotenv.config();

export interface DbClient {
  query<T extends QueryResultRow = QueryResultRow>(
    text: string,
    params?: unknown[]
  ): Promise<QueryResult<T>>;
  release(): void;
}

export interface DbAdapter {
  query<T extends QueryResultRow = QueryResultRow>(
    text: string,
    params?: unknown[]
  ): Promise<QueryResult<T>>;
  connect(): Promise<DbClient>;
}

let pgPool: Pool | null = null;
let schemaInitialized = false;

export function getRequiredMissingServerEnv(): string[] {
  const missing: string[] = [];
  if (!(process.env.DATABASE_URL || "").trim()) {
    missing.push("DATABASE_URL");
  }
  if (!(process.env.VAULT_MASTER_SECRET || "").trim()) {
    missing.push("VAULT_MASTER_SECRET");
  }
  return missing;
}

export function resolvePgSslConfig(connectionString: string): boolean | { rejectUnauthorized: boolean } {
  const explicit = (process.env.PG_SSL || "").trim().toLowerCase();
  if (explicit === "false") return false;
  if (
    explicit === "true" ||
    connectionString.includes("neon.tech") ||
    connectionString.includes("sslmode=require") ||
    connectionString.includes("sslmode=verify")
  ) {
    return { rejectUnauthorized: false };
  }
  return false;
}

export function getPgPool(): Pool {
  if (pgPool) return pgPool;
  const connectionString = (process.env.DATABASE_URL || "").trim();
  if (!connectionString) {
    throw new Error("DATABASE_URL environment variable is required to connect to Neon PostgreSQL.");
  }
  pgPool = new Pool({
    connectionString,
    ssl: resolvePgSslConfig(connectionString),
    max: 15,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 10_000,
  });
  return pgPool;
}

export const defaultDb: DbAdapter = {
  query<T extends QueryResultRow = QueryResultRow>(text: string, params?: unknown[]) {
    return getPgPool().query<T>(text, params);
  },
  connect(): Promise<DbClient> {
    return getPgPool().connect() as Promise<PoolClient>;
  },
};

// Backwards-compatible named export
export const pool = defaultDb;

const STARTUP_SCHEMA_SQL = `
CREATE TABLE IF NOT EXISTS users (
  firebase_uid VARCHAR(128) PRIMARY KEY,
  phone_number VARCHAR(20),
  is_authorized_owner BOOLEAN NOT NULL DEFAULT TRUE,
  created_at BIGINT NOT NULL,
  last_login_at BIGINT NOT NULL
);

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_singleton_guard_key;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_singleton_guard_check;
ALTER TABLE users ALTER COLUMN phone_number DROP NOT NULL;

CREATE TABLE IF NOT EXISTS user_profiles (
  firebase_uid VARCHAR(128) PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
  owner_name TEXT NOT NULL DEFAULT 'DSK Owner',
  phone_number VARCHAR(20) NOT NULL DEFAULT '',
  avatar_cloud_path TEXT,
  updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_settings (
  firebase_uid VARCHAR(128) PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
  auto_backup_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  wifi_only BOOLEAN NOT NULL DEFAULT TRUE,
  backup_photos BOOLEAN NOT NULL DEFAULT TRUE,
  backup_videos BOOLEAN NOT NULL DEFAULT TRUE,
  backup_documents BOOLEAN NOT NULL DEFAULT TRUE,
  auto_lock_seconds INTEGER NOT NULL DEFAULT 120,
  dark_mode BOOLEAN NOT NULL DEFAULT TRUE,
  animations_4d_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  reduced_motion BOOLEAN NOT NULL DEFAULT FALSE,
  accent_preset VARCHAR(64) NOT NULL DEFAULT 'ELECTRIC_COSMOS',
  notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_security (
  firebase_uid VARCHAR(128) PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
  mpin_hash TEXT NOT NULL,
  mpin_salt TEXT NOT NULL,
  mpin_length INTEGER NOT NULL DEFAULT 6,
  secret_mpin_hash TEXT,
  secret_mpin_salt TEXT,
  wrapped_vault_key TEXT,
  vault_key_version INTEGER NOT NULL DEFAULT 0,
  key_recovery_mode VARCHAR(64),
  biometric_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  failed_attempts INTEGER NOT NULL DEFAULT 0,
  lockout_until_ms BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL
);

ALTER TABLE user_security ADD COLUMN IF NOT EXISTS mpin_length INTEGER NOT NULL DEFAULT 6;
ALTER TABLE user_security ADD COLUMN IF NOT EXISTS secret_mpin_hash TEXT;
ALTER TABLE user_security ADD COLUMN IF NOT EXISTS secret_mpin_salt TEXT;
ALTER TABLE user_security ADD COLUMN IF NOT EXISTS wrapped_vault_key TEXT;
ALTER TABLE user_security ADD COLUMN IF NOT EXISTS vault_key_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE user_security ADD COLUMN IF NOT EXISTS key_recovery_mode VARCHAR(64);

CREATE TABLE IF NOT EXISTS categories (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  name TEXT NOT NULL,
  icon_key VARCHAR(64) NOT NULL DEFAULT 'custom',
  accent_hex VARCHAR(16) NOT NULL DEFAULT '#00D4FF',
  is_custom BOOLEAN NOT NULL DEFAULT TRUE,
  created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_categories_uid ON categories(firebase_uid);

CREATE TABLE IF NOT EXISTS folders (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  name TEXT NOT NULL,
  category_id VARCHAR(128) NOT NULL,
  parent_id VARCHAR(128),
  is_auto_backup_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_folders_uid ON folders(firebase_uid);

CREATE TABLE IF NOT EXISTS files (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  original_filename TEXT NOT NULL,
  display_name TEXT NOT NULL,
  category_id VARCHAR(128) NOT NULL,
  folder_id VARCHAR(128),
  mime_type TEXT NOT NULL,
  size_bytes BIGINT NOT NULL,
  cloud_path TEXT,
  cloud_bucket TEXT,
  backup_status VARCHAR(32) NOT NULL DEFAULT 'MOBILE_ONLY',
  sha256 VARCHAR(128) NOT NULL DEFAULT '',
  is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
  is_in_vault BOOLEAN NOT NULL DEFAULT FALSE,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at BIGINT,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_files_uid ON files(firebase_uid);

CREATE TABLE IF NOT EXISTS document_metadata (
  file_id VARCHAR(128) PRIMARY KEY REFERENCES files(id) ON DELETE CASCADE,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  page_count INTEGER NOT NULL DEFAULT 1,
  document_category TEXT,
  expiry_date_iso VARCHAR(32),
  remind_30_days BOOLEAN NOT NULL DEFAULT TRUE,
  remind_15_days BOOLEAN NOT NULL DEFAULT TRUE,
  remind_7_days BOOLEAN NOT NULL DEFAULT TRUE,
  remind_on_day BOOLEAN NOT NULL DEFAULT TRUE,
  notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS document_extractions (
  file_id VARCHAR(128) PRIMARY KEY REFERENCES files(id) ON DELETE CASCADE,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  document_name TEXT,
  document_type TEXT,
  document_number TEXT,
  owner_name TEXT,
  father_name TEXT,
  mother_name TEXT,
  date_of_birth TEXT,
  issue_date TEXT,
  expiry_date TEXT,
  address TEXT,
  mobile_number TEXT,
  organization_name TEXT,
  registration_number TEXT,
  raw_text TEXT NOT NULL DEFAULT '',
  is_verified BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS text_notes (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  category_id VARCHAR(128) NOT NULL DEFAULT 'text_files',
  is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at BIGINT,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS saved_links (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  link_type VARCHAR(32) NOT NULL,
  title TEXT NOT NULL,
  url TEXT NOT NULL,
  thumbnail_url TEXT,
  description TEXT NOT NULL DEFAULT '',
  category_id VARCHAR(128) NOT NULL,
  is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at BIGINT,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS backup_history (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  backup_id VARCHAR(128),
  file_id VARCHAR(128),
  file_name TEXT NOT NULL,
  action VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  bytes_transferred BIGINT NOT NULL DEFAULT 0,
  message TEXT NOT NULL DEFAULT '',
  timestamp BIGINT NOT NULL
);
ALTER TABLE backup_history ADD COLUMN IF NOT EXISTS backup_id VARCHAR(128);
CREATE INDEX IF NOT EXISTS idx_backup_hist_uid ON backup_history(firebase_uid, timestamp DESC);

CREATE TABLE IF NOT EXISTS sync_history (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  summary TEXT NOT NULL,
  device_info TEXT,
  timestamp BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS file_versions (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  file_id VARCHAR(128) NOT NULL REFERENCES files(id) ON DELETE CASCADE,
  version_number INTEGER NOT NULL DEFAULT 1,
  cloud_path TEXT NOT NULL,
  size_bytes BIGINT NOT NULL DEFAULT 0,
  sha256 VARCHAR(128) NOT NULL DEFAULT '',
  created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS favorites (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  item_type VARCHAR(32) NOT NULL,
  item_id VARCHAR(128) NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE(firebase_uid, item_type, item_id)
);

CREATE TABLE IF NOT EXISTS vault_metadata (
  file_id VARCHAR(128) PRIMARY KEY REFERENCES files(id) ON DELETE CASCADE,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  cipher_alg VARCHAR(64) NOT NULL DEFAULT 'AES-256-GCM',
  storage_path TEXT,
  encrypted_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS download_history (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  file_id VARCHAR(128),
  file_name TEXT NOT NULL,
  downloaded_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS security_logs (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  event_type VARCHAR(64) NOT NULL,
  detail TEXT NOT NULL,
  success BOOLEAN NOT NULL DEFAULT TRUE,
  timestamp BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_backups (
  id VARCHAR(128) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
  total_documents INTEGER NOT NULL DEFAULT 0,
  backed_up_documents INTEGER NOT NULL DEFAULT 0,
  pending_documents INTEGER NOT NULL DEFAULT 0,
  failed_documents INTEGER NOT NULL DEFAULT 0,
  total_bytes BIGINT NOT NULL DEFAULT 0,
  snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  device_info TEXT,
  started_at BIGINT NOT NULL,
  completed_at BIGINT
);
CREATE INDEX IF NOT EXISTS idx_user_backups_uid ON user_backups(user_id, started_at DESC);

CREATE TABLE IF NOT EXISTS backup_files (
  id VARCHAR(128) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  backup_id VARCHAR(128) REFERENCES user_backups(id) ON DELETE SET NULL,
  file_id VARCHAR(128) NOT NULL,
  original_filename TEXT NOT NULL,
  display_name TEXT NOT NULL,
  category_id VARCHAR(128) NOT NULL,
  folder_id VARCHAR(128),
  mime_type TEXT NOT NULL DEFAULT 'application/octet-stream',
  size_bytes BIGINT NOT NULL,
  sha256 VARCHAR(128) NOT NULL,
  is_in_vault BOOLEAN NOT NULL DEFAULT FALSE,
  is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
  is_encrypted_payload BOOLEAN NOT NULL DEFAULT FALSE,
  key_version INTEGER NOT NULL DEFAULT 0,
  file_content BYTEA NOT NULL,
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  UNIQUE(user_id, file_id)
);
CREATE INDEX IF NOT EXISTS idx_backup_files_uid ON backup_files(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_backup_files_sha ON backup_files(user_id, sha256);

CREATE TABLE IF NOT EXISTS backup_settings (
  user_id VARCHAR(128) PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
  cloud_backup_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  wifi_only BOOLEAN NOT NULL DEFAULT TRUE,
  backup_photos BOOLEAN NOT NULL DEFAULT TRUE,
  backup_videos BOOLEAN NOT NULL DEFAULT TRUE,
  backup_documents BOOLEAN NOT NULL DEFAULT TRUE,
  storage_quota_bytes BIGINT NOT NULL DEFAULT 471859200,
  max_file_bytes BIGINT NOT NULL DEFAULT 20971520,
  updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS restore_history (
  id VARCHAR(128) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  backup_id VARCHAR(128),
  status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
  total_files INTEGER NOT NULL DEFAULT 0,
  restored_files INTEGER NOT NULL DEFAULT 0,
  verified_files INTEGER NOT NULL DEFAULT 0,
  failed_files INTEGER NOT NULL DEFAULT 0,
  bytes_restored BIGINT NOT NULL DEFAULT 0,
  message TEXT NOT NULL DEFAULT '',
  device_info TEXT,
  started_at BIGINT NOT NULL,
  completed_at BIGINT
);
CREATE INDEX IF NOT EXISTS idx_restore_history_uid ON restore_history(user_id, started_at DESC);
`;

export async function ensureBackupSchema(db: DbAdapter = defaultDb): Promise<void> {
  if (schemaInitialized && db === defaultDb) return;
  await db.query(STARTUP_SCHEMA_SQL);
  if (db === defaultDb) {
    schemaInitialized = true;
  }
}
