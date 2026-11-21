-- ==========================================================
-- DSK LOCKER - POSTGRESQL 18-TABLE SCHEMA (FIREBASE UID KEYED)
-- Single Authorized Owner Private Universe Architecture
-- ==========================================================

-- 1. users (Firebase UID canonical identity + Single-Owner lock)
CREATE TABLE IF NOT EXISTS users (
  firebase_uid VARCHAR(128) PRIMARY KEY,
  phone_number VARCHAR(20) NOT NULL UNIQUE,
  is_authorized_owner BOOLEAN NOT NULL DEFAULT TRUE,
  singleton_guard SMALLINT NOT NULL DEFAULT 1 UNIQUE CHECK (singleton_guard = 1),
  created_at BIGINT NOT NULL,
  last_login_at BIGINT NOT NULL
);

-- 2. user_profiles
CREATE TABLE IF NOT EXISTS user_profiles (
  firebase_uid VARCHAR(128) PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
  owner_name TEXT NOT NULL DEFAULT 'DSK Owner',
  phone_number VARCHAR(20) NOT NULL,
  avatar_cloud_path TEXT,
  updated_at BIGINT NOT NULL
);

-- 3. user_settings
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

-- 4. user_security (PBKDF2-HMAC-SHA256 MPIN hash/salt, NEVER plain text)
CREATE TABLE IF NOT EXISTS user_security (
  firebase_uid VARCHAR(128) PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
  mpin_hash TEXT NOT NULL,
  mpin_salt TEXT NOT NULL,
  biometric_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  failed_attempts INTEGER NOT NULL DEFAULT 0,
  lockout_until_ms BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL
);

-- 5. categories (Built-in + custom categories owned by Firebase UID)
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

-- 6. folders
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

-- 7. files
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
CREATE INDEX IF NOT EXISTS idx_files_category ON files(firebase_uid, category_id);

-- 8. document_metadata
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
CREATE INDEX IF NOT EXISTS idx_doc_meta_uid ON document_metadata(firebase_uid);

-- 9. document_extractions
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
CREATE INDEX IF NOT EXISTS idx_doc_ext_uid ON document_extractions(firebase_uid);

-- 10. text_notes
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
CREATE INDEX IF NOT EXISTS idx_notes_uid ON text_notes(firebase_uid);

-- 11. saved_links
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
CREATE INDEX IF NOT EXISTS idx_links_uid ON saved_links(firebase_uid);

-- 12. backup_history
CREATE TABLE IF NOT EXISTS backup_history (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  file_id VARCHAR(128),
  file_name TEXT NOT NULL,
  action VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  bytes_transferred BIGINT NOT NULL DEFAULT 0,
  message TEXT NOT NULL DEFAULT '',
  timestamp BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_backup_hist_uid ON backup_history(firebase_uid);

-- 13. sync_history
CREATE TABLE IF NOT EXISTS sync_history (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  summary TEXT NOT NULL,
  device_info TEXT,
  timestamp BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sync_hist_uid ON sync_history(firebase_uid);

-- 14. file_versions
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
CREATE INDEX IF NOT EXISTS idx_versions_uid ON file_versions(firebase_uid, file_id);

-- 15. favorites
CREATE TABLE IF NOT EXISTS favorites (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  item_type VARCHAR(32) NOT NULL, -- FILE, NOTE, LINK
  item_id VARCHAR(128) NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE(firebase_uid, item_type, item_id)
);
CREATE INDEX IF NOT EXISTS idx_favorites_uid ON favorites(firebase_uid);

-- 16. vault_metadata
CREATE TABLE IF NOT EXISTS vault_metadata (
  file_id VARCHAR(128) PRIMARY KEY REFERENCES files(id) ON DELETE CASCADE,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  cipher_alg VARCHAR(64) NOT NULL DEFAULT 'AES-256-GCM',
  storage_path TEXT,
  encrypted_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_vault_meta_uid ON vault_metadata(firebase_uid);

-- 17. download_history
CREATE TABLE IF NOT EXISTS download_history (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  file_id VARCHAR(128),
  file_name TEXT NOT NULL,
  downloaded_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_download_hist_uid ON download_history(firebase_uid);

-- 18. security_logs
CREATE TABLE IF NOT EXISTS security_logs (
  id VARCHAR(128) PRIMARY KEY,
  firebase_uid VARCHAR(128) NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
  event_type VARCHAR(64) NOT NULL,
  detail TEXT NOT NULL,
  success BOOLEAN NOT NULL DEFAULT TRUE,
  timestamp BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_security_logs_uid ON security_logs(firebase_uid);
