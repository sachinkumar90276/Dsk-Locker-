# DSK LOCKER

**DSK LOCKER** is a mobile-first, dark-themed personal Android vault and smart document manager built with **Kotlin**, **Jetpack Compose (Material 3)**, **Room**, **AndroidKeyStore AES-256-GCM encryption**, **CameraX + ML Kit Document Scanner**, **Automatic OCR**, **Media3 ExoPlayer**, and optional **Firebase + PostgreSQL** sync.

---

## Core Features

1. **MPIN & Biometric Unlock Flow**
   - Supports 4-digit or 6-digit PBKDF2-HMAC-SHA256 MPIN authentication plus optional device BiometricPrompt (Fingerprint / Face).
   - Post-MPIN payment-app-style **Unlock Flash Screen** preloads saved files, vault metrics, and categories before opening the Dashboard.

2. **Streamlined 3-Option Upload Popup**
   - **Photos & Videos** — Multi-select Android Photo Picker with automatic media type detection.
   - **Documents & Files** — Single unified picker supporting PDF, DOC, DOCX, XLS, XLSX, PPTX, TXT, CSV, RTF, ODT, ODS, APK, ZIP, audio, and more.
   - **Scan Physical Document** — Live CameraX + ML Kit Document Scanner with auto-crop, clarity/contrast enhancement, OCR extraction, and original scan preservation.

3. **6 Main Categories & 10 Document Subcategories**
   - **Main Categories:** `Documents`, `Personal Photos`, `Videos`, `Video URL`, `Web URL`, `Text`
   - **Smart Document Subcategories/Filters:** `All`, `Aadhaar`, `PAN`, `Passport`, `Driving Licence`, `Voter ID`, `Resume`, `Certificates`, `Bills`, `Office Documents`, `Other Documents`

4. **Dedicated Category Managers**
   - **Videos:** Actual video file upload, cached thumbnail, Media3 video preview, duration badge, original filename, and file size.
   - **Video URL:** Text-only link entry (`Video Name`, `Video URL`, `SAVE VIDEO LINK`) — no file upload clutter.
   - **Web URL:** `Website Name`, `Website URL`, and optional `Description`.
   - **Text:** `Text Name` and `Text Entry` with save, edit, copy, favorite, and search.

5. **Automatic OCR & Original Filename Preservation**
   - Extracts text from images, PDFs, Office documents, TXT/CSV, scanned documents, and video frames.
   - Classifies documents by extracted content (rather than random filenames), indexes extracted text for search, suggests optional display names, and preserves `originalFilename` by default.
   - Non-OCR binary formats (`APK`, `ZIP`, audio) are saved cleanly with clear status feedback.

6. **Premium Animated Flash Screens**
   - Real progress overlays during MPIN Unlock, File Import, OCR Processing, Metadata Save, and Link/Text Submit.

7. **Mandatory Delete Security (Secret MPIN)**
   - Every delete action enforces: **Secret MPIN Verification → Delete Confirmation → Actual Deletion**.
   - Deletion remains blocked until a dedicated Secret MPIN (distinct from Login MPIN) is configured in **Profile & Settings → Security & Privacy**.

---

## Project Structure

```text
.
├── app/
│   ├── build.gradle.kts              # App module configuration & signing hooks
│   ├── google-services.json          # Firebase client configuration (replace for your project)
│   ├── proguard-rules.pro            # R8 / ProGuard release keep rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/
│       │   │   ├── MainActivity.kt
│       │   │   ├── ai/               # DocumentOcrEngine (ML Kit + Firebase AI Logic)
│       │   │   ├── data/             # Room DB, Entities, DAOs, Repository, Remote clients
│       │   │   ├── security/         # PBKDF2 MPIN, Secret MPIN, AES-256-GCM, BiometricPrompt
│       │   │   ├── storage/          # LocalFileManager, video thumbnails, WorkManager sync
│       │   │   └── ui/               # ViewModel, Theme, 4D Components & Compose Screens
│       │   └── res/
│       └── test/                     # Unit & Robolectric test suites
├── backend/                          # Optional Node.js + Express + PostgreSQL API
├── database/schema.sql               # PostgreSQL schema (18 tables)
├── gradle/libs.versions.toml         # Gradle Version Catalog
├── storage.rules                     # Owner-only Firebase Storage security rules
├── .env.example                      # Client environment variable template
└── .gitignore
```

---

## Security & Secret Hygiene

Never commit secrets, API keys, passwords, or release keystores to GitHub. The `.gitignore` excludes:
- `.env`, `*.env.local`, `backend/.env`
- `local.properties`
- `*.jks`, `*.keystore`, `*.p12`, `*.pem`, `debug.keystore`
- `firebase-service-account.json`, `*service-account*.json`
- `.build-outputs/`, `build/`, `app/build/`, `.gradle/`

### Environment Configuration
1. Copy `.env.example` to `.env` locally (or configure keys in AI Studio Secrets for cloud builds):
   ```bash
   cp .env.example .env
   ```
2. For the optional SQL backend in `backend/`:
   ```bash
   cp backend/.env.example backend/.env
   ```

---

## Build & Test Commands

> Always use system `gradle` or your Android Studio Gradle sync.

```bash
# Run all unit & Robolectric tests
gradle :app:testDebugUnitTest

# Build debug APK
gradle :app:assembleDebug

# Build release APK / AAB (with KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD env vars)
gradle :app:assembleRelease
gradle :app:bundleRelease
```

---

## GitHub Deployment Guide

1. **Create a new GitHub repository** (e.g., `dsk-locker`) on [github.com/new](https://github.com/new) — keep it **empty** (do not initialize a second README or `.gitignore` on GitHub).
2. **Initialize Git locally** (in your exported project directory or Android Studio terminal):
   ```bash
   git init -b main
   git status
   ```
   Verify `.env`, `local.properties`, `*.jks`, and `firebase-service-account.json` do **not** appear in `git status`.
3. **Commit and push:**
   ```bash
   git add .
   git commit -m "Initial DSK LOCKER Android project"
   git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPO>.git
   git push -u origin main
   ```
4. **Release Signing Secrets in GitHub Actions (Optional):**
   Add `KEYSTORE_BASE64`, `STORE_PASSWORD`, and `KEY_PASSWORD` as **GitHub Repository Secrets** before running automated release APK/AAB builds.
