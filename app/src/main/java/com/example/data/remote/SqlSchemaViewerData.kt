package com.example.data.remote

object SqlSchemaViewerData {
    val sqlAndStorageRulesSummary = """
-- DSK LOCKER: POSTGRESQL 18-TABLE SCHEMA (KEYED BY firebase_uid)
-- Full DDL also saved in repo at: database/schema.sql
-- Secure Express Backend API saved in repo at: backend/src/
-- Firebase Storage Rules saved in repo at: storage.rules

1.  users (firebase_uid PK, phone_number UNIQUE, singleton_guard=1)
2.  user_profiles (firebase_uid PK -> users)
3.  user_settings (firebase_uid PK -> users)
4.  user_security (firebase_uid PK -> users; PBKDF2 mpin_hash & mpin_salt)
5.  categories (id PK, firebase_uid FK -> users)
6.  folders (id PK, firebase_uid FK -> users)
7.  files (id PK, firebase_uid FK -> users, cloud_path, sha256)
8.  document_metadata (file_id PK -> files, firebase_uid FK -> users)
9.  document_extractions (file_id PK -> files, firebase_uid FK -> users)
10. text_notes (id PK, firebase_uid FK -> users)
11. saved_links (id PK, firebase_uid FK -> users)
12. backup_history (id PK, firebase_uid FK -> users)
13. sync_history (id PK, firebase_uid FK -> users)
14. file_versions (id PK, firebase_uid FK -> users, file_id FK -> files)
15. favorites (id PK, firebase_uid FK -> users)
16. vault_metadata (file_id PK -> files, firebase_uid FK -> users, AES-256-GCM)
17. download_history (id PK, firebase_uid FK -> users)
18. security_logs (id PK, firebase_uid FK -> users)

-- FIREBASE STORAGE PRIVATE PATHS (storage.rules):
match /users/{uid}/documents/{allPaths=**} { allow read, write, delete: if request.auth.uid == uid; }
match /users/{uid}/photos/{allPaths=**}    { allow read, write, delete: if request.auth.uid == uid; }
match /users/{uid}/videos/{allPaths=**}    { allow read, write, delete: if request.auth.uid == uid; }
match /users/{uid}/audio/{allPaths=**}     { allow read, write, delete: if request.auth.uid == uid; }
match /users/{uid}/vault/{allPaths=**}     { allow read, write, delete: if request.auth.uid == uid; }
""".trimIndent()
}
