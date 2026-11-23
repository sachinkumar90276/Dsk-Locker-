package com.example.data.remote

object SqlSchemaViewerData {
    val sqlAndStorageRulesSummary = """
-- DSK LOCKER: NEON POSTGRESQL + RENDER BACKUP SCHEMA (KEYED BY firebase_uid)
-- Full DDL saved at: database/schema.sql
-- Render Express API saved at: backend/src/
-- Supabase is NOT used anywhere in DSK LOCKER.

1.  users (firebase_uid PK, phone_number UNIQUE)
2.  user_profiles (firebase_uid PK -> users)
3.  user_settings (firebase_uid PK -> users)
4.  user_security (firebase_uid PK -> users; PBKDF2 mpin_hash, wrapped_vault_key)
5.  categories (id PK, firebase_uid FK -> users)
6.  folders (id PK, firebase_uid FK -> users)
7.  files (id PK, firebase_uid FK -> users, cloud_path, sha256)
8.  document_metadata (file_id PK -> files, firebase_uid FK -> users)
9.  document_extractions (file_id PK -> files, firebase_uid FK -> users)
10. text_notes (id PK, firebase_uid FK -> users)
11. saved_links (id PK, firebase_uid FK -> users)
12. backup_history (id PK, backup_id, firebase_uid FK -> users)
13. sync_history (id PK, firebase_uid FK -> users)
14. file_versions (id PK, firebase_uid FK -> users, file_id FK -> files)
15. favorites (id PK, firebase_uid FK -> users)
16. vault_metadata (file_id PK -> files, firebase_uid FK -> users, AES-256-GCM)
17. download_history (id PK, firebase_uid FK -> users)
18. security_logs (id PK, firebase_uid FK -> users)
19. user_backups (id PK, user_id FK -> users, backed_up_documents, total_backup_bytes)
20. backup_files (file_id PK, user_id FK -> users, BYTEA content_bytes, sha256)
21. backup_settings (user_id PK -> users, cloud_backup_enabled, wifi_only)
22. restore_history (id PK, user_id FK -> users, verified_files, bytes_restored)

-- RENDER CLOUD API ENDPOINTS (Zero-Trust Firebase ID Token + CORS Allowlist):
POST /api/backup/create | GET /api/backup/status | GET /api/backup/history
GET  /api/backup/files  | GET /api/backup/files/:fileId/content | DELETE /api/backup/files/:fileId
POST /api/backup/restore | POST /api/backup/restore/complete | PUT /api/backup/settings
POST /api/security/mpin/verify | POST /api/security/recovery-key
""".trimIndent()
}
