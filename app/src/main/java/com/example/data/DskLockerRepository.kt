package com.example.data

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.ai.DocumentOcrEngine
import com.example.ai.DocumentOcrResult
import com.example.data.local.*
import com.example.data.remote.*
import com.example.security.CryptoManager
import com.example.security.SecurePrefs
import com.example.storage.BackupScheduler
import com.example.storage.DeviceStorageStats
import com.example.storage.LocalFileManager
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DskLockerRepository(private val context: Context) {
    private val db by lazy { DskLockerDatabase.getInstance(context) }
    val prefs by lazy { SecurePrefs(context) }
    val phoneAuth by lazy { FirebasePhoneAuthManager() }
    val storageManager by lazy { FirebaseStorageManager() }
    private val sqlClient by lazy { SqlBackendClient() }
    private val ocrEngine by lazy { DocumentOcrEngine(context) }

    val activeFiles: Flow<List<FileItemEntity>> = db.fileItemDao().observeActiveFiles()
    val vaultFiles: Flow<List<FileItemEntity>> = db.fileItemDao().observeVaultFiles()
    val trashFiles: Flow<List<FileItemEntity>> = db.fileItemDao().observeTrashFiles()
    val folders: Flow<List<FolderEntity>> = db.folderDao().observeFolders()
    val customCategories: Flow<List<CustomCategoryEntity>> = db.customCategoryDao().observeCustomCategories()
    val documentExtractions: Flow<List<DocumentExtractionEntity>> = db.documentExtractionDao().observeAll()
    val documentReminders: Flow<List<DocumentReminderEntity>> = db.documentReminderDao().observeAll()
    val activeNotes: Flow<List<TextNoteEntity>> = db.textNoteDao().observeActiveNotes()
    val trashNotes: Flow<List<TextNoteEntity>> = db.textNoteDao().observeTrashNotes()
    val activeLinks: Flow<List<SavedLinkEntity>> = db.savedLinkDao().observeActiveLinks()
    val trashLinks: Flow<List<SavedLinkEntity>> = db.savedLinkDao().observeTrashLinks()
    val backupHistory: Flow<List<BackupHistoryEntity>> = db.backupHistoryDao().observeRecent()
    val securityLogs: Flow<List<SecurityLogEntity>> = db.securityLogDao().observeRecent()
    val profile: Flow<OwnerProfileEntity?> = db.profileSettingsDao().observeProfile()
    val settings: Flow<UserSettingsEntity?> = db.profileSettingsDao().observeSettings()

    suspend fun ensureDefaultsInitialized() {
        if (prefs.hasMpin() || !prefs.firebaseUid.isNullOrBlank()) {
            val currentFbUser = phoneAuth.currentUser()
            if (currentFbUser != null) {
                prefs.firebaseUid = currentFbUser.uid
                if (!currentFbUser.phoneNumber.isNullOrBlank()) {
                    prefs.ownerPhone = currentFbUser.phoneNumber
                }
            }
        }
        if (db.profileSettingsDao().getProfileOnce() == null) {
            db.profileSettingsDao().upsertProfile(
                OwnerProfileEntity(
                    ownerName = "DSK Owner",
                    phoneNumber = prefs.ownerPhone.orEmpty(),
                    firebaseUid = prefs.firebaseUid
                )
            )
        }
        if (db.profileSettingsDao().getSettingsOnce() == null) {
            db.profileSettingsDao().upsertSettings(UserSettingsEntity())
        }
    }

    fun hasFirebaseSession(): Boolean = phoneAuth.currentUser() != null

    fun currentFirebaseUid(): String? = phoneAuth.currentUser()?.uid ?: prefs.firebaseUid

    suspend fun logSecurity(eventType: String, detail: String, success: Boolean = true) {
        val entry = SecurityLogEntity(eventType = eventType, detail = detail, success = success)
        db.securityLogDao().insert(entry)
        syncRowIfAuthenticated(
            "security_logs",
            JSONObject()
                .put("id", entry.id)
                .put("event_type", entry.eventType)
                .put("detail", entry.detail)
                .put("success", entry.success)
                .put("timestamp", entry.timestamp)
        )
    }

    // --- Firebase Phone Authentication (+91 Single Authorized Owner) ---
    fun validateOwnerPhoneEligibility(phoneE164: String): Result<Unit> {
        val restricted = BackendConfig.restrictedOwnerPhone
        if (restricted.isNotBlank() && restricted != phoneE164) {
            return Result.failure(
                SecurityException("Access restricted: Only authorized owner $restricted may register or sign in.")
            )
        }
        val existingOwner = prefs.ownerPhone
        if (!existingOwner.isNullOrBlank() && existingOwner != phoneE164) {
            return Result.failure(
                SecurityException("Single-owner lock: DSK LOCKER on this device belongs to $existingOwner.")
            )
        }
        return Result.success(Unit)
    }

    fun startFirebasePhoneOtp(
        activity: Activity,
        phoneE164: String,
        resendToken: PhoneAuthProvider.ForceResendingToken? = null,
        onResult: (PhoneOtpStartResult) -> Unit
    ) {
        val check = validateOwnerPhoneEligibility(phoneE164)
        if (check.isFailure) {
            onResult(PhoneOtpStartResult.Error(check.exceptionOrNull()?.message ?: "Unauthorized phone number."))
            return
        }
        phoneAuth.sendOrResendOtp(activity, phoneE164, resendToken, onResult)
    }

    suspend fun verifyFirebaseOtp(
        verificationId: String,
        otpCode: String,
        expectedPhoneE164: String
    ): Result<FirebaseSessionUser> {
        val check = validateOwnerPhoneEligibility(expectedPhoneE164)
        if (check.isFailure) {
            return Result.failure(check.exceptionOrNull() ?: SecurityException("Unauthorized owner number."))
        }

        val authRes = phoneAuth.verifyOtpCode(verificationId, otpCode, expectedPhoneE164)
        if (authRes.isFailure) {
            logSecurity("FIREBASE_OTP_VERIFY", "OTP verification failed for $expectedPhoneE164", false)
            return Result.failure(authRes.exceptionOrNull() ?: IllegalStateException("OTP verification failed."))
        }

        val session = authRes.getOrThrow()
        return finalizeFirebaseOwnerSession(session)
    }

    suspend fun finalizeFirebaseOwnerSession(session: FirebaseSessionUser): Result<FirebaseSessionUser> {
        val check = validateOwnerPhoneEligibility(session.phoneNumber)
        if (check.isFailure) {
            phoneAuth.signOut()
            logSecurity("UNAUTHORIZED_OWNER_BLOCKED", "Rejected ${session.phoneNumber}", false)
            return Result.failure(check.exceptionOrNull() ?: SecurityException("Unauthorized owner number."))
        }

        // Enforce single-owner registration on SQL Backend using verified Firebase ID Token
        val existingProfile = db.profileSettingsDao().getProfileOnce() ?: OwnerProfileEntity()
        val sqlReg = sqlClient.registerOrVerifyOwner(
            firebaseIdToken = session.idToken,
            phoneE164 = session.phoneNumber,
            ownerName = existingProfile.ownerName
        )
        if (sqlReg.isFailure) {
            phoneAuth.signOut()
            logSecurity("SQL_OWNER_REJECTED", sqlReg.exceptionOrNull()?.message ?: "SQL rejected user", false)
            return Result.failure(sqlReg.exceptionOrNull() ?: SecurityException("Owner registration denied by SQL server."))
        }

        prefs.firebaseUid = session.uid
        prefs.ownerPhone = session.phoneNumber
        prefs.isCloudOwnerVerified = true

        val updatedProfile = existingProfile.copy(
            phoneNumber = session.phoneNumber,
            firebaseUid = session.uid,
            updatedAt = System.currentTimeMillis()
        )
        db.profileSettingsDao().upsertProfile(updatedProfile)
        syncProfile(updatedProfile)
        logSecurity("FIREBASE_OWNER_VERIFIED", "Verified Firebase UID ${session.uid} (${session.phoneNumber})", true)
        return Result.success(session)
    }

    suspend fun logoutSession() {
        logSecurity("OWNER_LOGOUT", "Owner logged out from Firebase session", true)
        phoneAuth.signOut()
    }

    suspend fun saveNewMpin(mpin: String) {
        prefs.saveMpin(mpin)
        val (hash, salt) = prefs.getMpinHashForSync() ?: return
        val idToken = phoneAuth.getFreshIdToken(false)
        if (!idToken.isNullOrBlank()) {
            sqlClient.syncMpinSecurityHash(
                firebaseIdToken = idToken,
                mpinHash = hash,
                mpinSalt = salt,
                biometricEnabled = prefs.biometricEnabled
            )
        }
        logSecurity("MPIN_UPDATED", "Owner 6-digit PBKDF2 MPIN hash updated securely", true)
    }

    suspend fun findDuplicateFiles(sha256: String): List<FileItemEntity> {
        if (sha256.isBlank()) return emptyList()
        return db.fileItemDao().findBySha256(sha256)
    }

    fun isOcrSupported(file: FileItemEntity): Boolean =
        ocrEngine.isOcrSupported(file.originalFilename, file.mimeType)

    // --- Files & Vault ---
    suspend fun importFiles(
        uris: List<Uri>,
        isInVault: Boolean,
        folderId: String?,
        forcedCategoryId: String?,
        runAutoBackupIfEnabled: Boolean = true
    ): List<FileItemEntity> {
        val imported = mutableListOf<FileItemEntity>()
        val settings = db.profileSettingsDao().getSettingsOnce()
        for (uri in uris) {
            val res = LocalFileManager.importUri(
                context = context,
                uri = uri,
                isInVault = isInVault,
                folderId = folderId,
                forcedCategoryId = forcedCategoryId
            )
            res.getOrNull()?.let { item ->
                val status = if (runAutoBackupIfEnabled && settings?.autoBackupEnabled == true && hasFirebaseSession()) {
                    BackupStatus.BACKUP_PENDING
                } else {
                    BackupStatus.MOBILE_ONLY
                }
                val finalItem = item.copy(backupStatus = status)
                db.fileItemDao().upsert(finalItem)
                syncFileMetadata(finalItem)
                if (isInVault) {
                    syncRowIfAuthenticated(
                        "vault_metadata",
                        JSONObject()
                            .put("file_id", finalItem.id)
                            .put("cipher_alg", "AES-256-GCM")
                            .put("storage_path", finalItem.cloudPath ?: "")
                            .put("encrypted_at", finalItem.createdAt)
                    )
                }
                imported.add(finalItem)
            }
        }
        if (imported.isNotEmpty() && runAutoBackupIfEnabled && settings?.autoBackupEnabled == true) {
            BackupScheduler.enqueueImmediateBackup(context, settings.wifiOnly)
        }
        return imported
    }

    suspend fun renameFile(file: FileItemEntity, newDisplayName: String) {
        val updated = file.copy(displayName = newDisplayName.trim(), updatedAt = System.currentTimeMillis())
        db.fileItemDao().upsert(updated)
        syncFileMetadata(updated)
    }

    suspend fun moveOrCopyFile(file: FileItemEntity, targetCategoryId: String, targetFolderId: String?, isCopy: Boolean) {
        val now = System.currentTimeMillis()
        if (isCopy) {
            val newId = UUID.randomUUID().toString()
            val src = file.localPath?.let { File(it) }
            var copiedPath: String? = null
            if (src != null && src.exists()) {
                val destDir = if (file.isInVault) LocalFileManager.getVaultDir(context) else LocalFileManager.getFilesDir(context)
                val dest = File(destDir, "${newId}_${src.name.substringAfter('_')}")
                src.copyTo(dest, overwrite = true)
                copiedPath = dest.absolutePath
            }
            val copyItem = file.copy(
                id = newId,
                displayName = "${file.displayName} (Copy)",
                categoryId = targetCategoryId,
                folderId = targetFolderId,
                localPath = copiedPath,
                cloudPath = null,
                cloudBucket = null,
                backupStatus = if (copiedPath != null) BackupStatus.MOBILE_ONLY else BackupStatus.BACKUP_PENDING,
                createdAt = now,
                updatedAt = now
            )
            db.fileItemDao().upsert(copyItem)
            syncFileMetadata(copyItem)
        } else {
            val moved = file.copy(
                categoryId = targetCategoryId,
                folderId = targetFolderId,
                updatedAt = now
            )
            db.fileItemDao().upsert(moved)
            syncFileMetadata(moved)
        }
    }

    suspend fun toggleFileFavorite(file: FileItemEntity) {
        val updated = file.copy(isFavorite = !file.isFavorite, updatedAt = System.currentTimeMillis())
        db.fileItemDao().upsert(updated)
        syncFileMetadata(updated)
        if (updated.isFavorite) {
            syncRowIfAuthenticated(
                "favorites",
                JSONObject()
                    .put("id", "fav_${updated.id}")
                    .put("item_type", "FILE")
                    .put("item_id", updated.id)
                    .put("created_at", updated.updatedAt)
            )
        } else {
            deleteRemoteRowIfAuthenticated("favorites", "fav_${updated.id}")
        }
    }

    suspend fun deleteFileWithMode(file: FileItemEntity, mode: DeleteMode) {
        val now = System.currentTimeMillis()
        when (mode) {
            DeleteMode.MOBILE_ONLY -> {
                // Default action: delete only local mobile file; NEVER touch Firebase Storage backup
                file.localPath?.let { path -> runCatching { File(path).delete() } }
                if (!file.cloudPath.isNullOrBlank()) {
                    val cloudOnly = file.copy(
                        localPath = null,
                        backupStatus = BackupStatus.CLOUD_ONLY,
                        updatedAt = now
                    )
                    db.fileItemDao().upsert(cloudOnly)
                    syncFileMetadata(cloudOnly)
                } else {
                    val trashed = file.copy(
                        localPath = null,
                        isDeleted = true,
                        deletedAt = now,
                        updatedAt = now
                    )
                    db.fileItemDao().upsert(trashed)
                    syncFileMetadata(trashed)
                }
                logSecurity("DELETE_MOBILE_ONLY", "Removed local copy of ${file.displayName}; Firebase Storage untouched", true)
            }

            DeleteMode.CLOUD_ONLY -> {
                if (!file.cloudPath.isNullOrBlank() && hasFirebaseSession()) {
                    storageManager.deleteObject(file.cloudPath)
                }
                val updated = file.copy(
                    cloudPath = null,
                    cloudBucket = null,
                    backupStatus = if (file.localPath != null && File(file.localPath).exists()) {
                        BackupStatus.MOBILE_ONLY
                    } else {
                        BackupStatus.BACKUP_PENDING
                    },
                    updatedAt = now
                )
                db.fileItemDao().upsert(updated)
                syncFileMetadata(updated)
                logSecurity("DELETE_CLOUD_ONLY", "Removed Firebase Storage copy of ${file.displayName}; mobile file kept", true)
            }

            DeleteMode.BOTH -> {
                if (!file.cloudPath.isNullOrBlank() && hasFirebaseSession()) {
                    storageManager.deleteObject(file.cloudPath)
                }
                val trashed = file.copy(
                    cloudPath = null,
                    cloudBucket = null,
                    isDeleted = true,
                    deletedAt = now,
                    updatedAt = now
                )
                db.fileItemDao().upsert(trashed)
                syncFileMetadata(trashed)
                logSecurity("DELETE_BOTH_TO_TRASH", "Moved ${file.displayName} to Recycle Bin & removed Firebase backup", true)
            }
        }
    }

    suspend fun restoreFromTrash(file: FileItemEntity) {
        val restored = file.copy(isDeleted = false, deletedAt = null, updatedAt = System.currentTimeMillis())
        db.fileItemDao().upsert(restored)
        syncFileMetadata(restored)
    }

    suspend fun permanentlyDeleteFile(file: FileItemEntity) {
        file.localPath?.let { path -> runCatching { File(path).delete() } }
        if (!file.cloudPath.isNullOrBlank() && hasFirebaseSession()) {
            storageManager.deleteObject(file.cloudPath)
        }
        deleteRemoteRowIfAuthenticated("files", file.id)
        db.documentReminderDao().deleteForFile(file.id)
        db.fileItemDao().hardDeleteById(file.id)
        logSecurity("PERMANENT_DELETE", "Permanently purged ${file.displayName}", true)
    }

    suspend fun emptyAllTrash() {
        db.fileItemDao().emptyTrashFiles()
        db.textNoteDao().emptyTrashNotes()
        db.savedLinkDao().emptyTrashLinks()
        logSecurity("EMPTY_TRASH", "Emptied Recycle Bin", true)
    }

    // --- Backup & Restore (Firebase Storage + SQL Metadata) ---
    suspend fun runManualBackup(filesToBackup: List<FileItemEntity>? = null): Pair<Int, Int> {
        val targets = filesToBackup ?: db.fileItemDao().getPendingBackupFiles()
        val uid = currentFirebaseUid().orEmpty()
        if (!hasFirebaseSession() || uid.isBlank()) {
            targets.forEach { f ->
                db.fileItemDao().upsert(f.copy(backupStatus = BackupStatus.BACKUP_PENDING))
            }
            return 0 to targets.size
        }

        var successCount = 0
        var failCount = 0
        for (item in targets) {
            val localFile = item.localPath?.let { File(it) }
            if (localFile == null || !localFile.exists()) {
                failCount++
                continue
            }
            val cloudPath = FirebaseStoragePaths.buildPrivateObjectPath(
                firebaseUid = uid,
                fileId = item.id,
                originalFilename = item.originalFilename,
                categoryId = item.categoryId,
                isInVault = item.isInVault
            )
            val bytes = withContext(Dispatchers.IO) { localFile.readBytes() }
            val checksum = if (item.isInVault) CryptoManager.sha256Bytes(bytes) else item.sha256
            val res = storageManager.uploadBytes(
                objectPath = cloudPath,
                bytes = bytes,
                mimeType = if (item.isInVault) "application/octet-stream" else item.mimeType,
                sha256 = checksum
            )
            if (res.isSuccess) {
                val updated = item.copy(
                    cloudPath = cloudPath,
                    cloudBucket = storageManager.bucketName(),
                    backupStatus = BackupStatus.MOBILE_AND_CLOUD,
                    updatedAt = System.currentTimeMillis()
                )
                db.fileItemDao().upsert(updated)
                syncFileMetadata(updated)
                syncRowIfAuthenticated(
                    "file_versions",
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("file_id", updated.id)
                        .put("version_number", 1)
                        .put("cloud_path", cloudPath)
                        .put("size_bytes", updated.sizeBytes)
                        .put("sha256", checksum)
                        .put("created_at", updated.updatedAt)
                )
                val history = BackupHistoryEntity(
                    fileId = updated.id,
                    fileName = updated.displayName,
                    action = "MANUAL_BACKUP",
                    status = "SUCCESS",
                    bytesTransferred = updated.sizeBytes,
                    message = "Uploaded to Firebase Storage ($cloudPath) & verified SHA-256."
                )
                db.backupHistoryDao().insert(history)
                syncRowIfAuthenticated(
                    "backup_history",
                    JSONObject()
                        .put("id", history.id)
                        .put("file_id", history.fileId)
                        .put("file_name", history.fileName)
                        .put("action", history.action)
                        .put("status", history.status)
                        .put("bytes_transferred", history.bytesTransferred)
                        .put("message", history.message)
                        .put("timestamp", history.timestamp)
                )
                prefs.lastBackupMs = System.currentTimeMillis()
                successCount++
            } else {
                db.fileItemDao().upsert(item.copy(backupStatus = BackupStatus.BACKUP_FAILED))
                db.backupHistoryDao().insert(
                    BackupHistoryEntity(
                        fileId = item.id,
                        fileName = item.displayName,
                        action = "MANUAL_BACKUP",
                        status = "FAILED",
                        message = res.exceptionOrNull()?.localizedMessage ?: "Firebase Storage upload failed"
                    )
                )
                failCount++
            }
        }
        return successCount to failCount
    }

    suspend fun restoreFromCloud(
        files: List<FileItemEntity>,
        overwriteExisting: Boolean,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): Result<Int> {
        if (!hasFirebaseSession()) {
            return Result.failure(IllegalStateException("Cloud backup session not linked. Local files remain safe on device."))
        }
        var restored = 0
        for (item in files) {
            val cloudPath = item.cloudPath ?: continue
            val existingLocal = item.localPath?.let { File(it) }
            if (existingLocal != null && existingLocal.exists() && !overwriteExisting) {
                continue
            }

            val destDir = if (item.isInVault) LocalFileManager.getVaultDir(context) else LocalFileManager.getFilesDir(context)
            val safeName = item.originalFilename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destFile = File(destDir, if (item.isInVault) "${item.id}_$safeName.enc" else "${item.id}_$safeName")

            val dlRes = storageManager.downloadToFile(
                objectPath = cloudPath,
                destFile = destFile,
                onProgress = { pct -> onProgress(item.displayName, pct) }
            )
            if (dlRes.isSuccess) {
                if (!item.isInVault && item.sha256.isNotBlank()) {
                    val actualSha = withContext(Dispatchers.IO) { CryptoManager.sha256File(destFile) }
                    if (!actualSha.equals(item.sha256, ignoreCase = true)) {
                        destFile.delete()
                        db.backupHistoryDao().insert(
                            BackupHistoryEntity(
                                fileId = item.id,
                                fileName = item.displayName,
                                action = "RESTORE",
                                status = "FAILED",
                                message = "SHA-256 integrity mismatch on restored file."
                            )
                        )
                        continue
                    }
                }
                val updated = item.copy(
                    localPath = destFile.absolutePath,
                    backupStatus = BackupStatus.MOBILE_AND_CLOUD,
                    updatedAt = System.currentTimeMillis()
                )
                db.fileItemDao().upsert(updated)
                db.backupHistoryDao().insert(
                    BackupHistoryEntity(
                        fileId = updated.id,
                        fileName = updated.displayName,
                        action = "RESTORE",
                        status = "SUCCESS",
                        bytesTransferred = updated.sizeBytes,
                        message = "Restored from Firebase Storage & verified integrity."
                    )
                )
                restored++
            }
        }
        return Result.success(restored)
    }

    // --- Refresh System (Never deletes files or logs out owner) ---
    suspend fun refreshWorkspace(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val allLocal = db.fileItemDao().getAllNonDeletedFilesOnce()
            for (item in allLocal) {
                val path = item.localPath
                if (path != null && !File(path).exists()) {
                    val newStatus = if (!item.cloudPath.isNullOrBlank()) {
                        BackupStatus.CLOUD_ONLY
                    } else {
                        item.backupStatus
                    }
                    db.fileItemDao().upsert(item.copy(localPath = null, backupStatus = newStatus))
                }
            }

            val idToken = phoneAuth.getFreshIdToken(false).orEmpty()
            if (BackendConfig.isSqlBackendConfigured && idToken.isNotBlank()) {
                val remoteFiles = sqlClient.fetchRemoteFiles(idToken).getOrNull()
                if (remoteFiles != null) {
                    for (i in 0 until remoteFiles.length()) {
                        val obj = remoteFiles.getJSONObject(i)
                        val id = obj.getString("id")
                        val localExisting = db.fileItemDao().getFileById(id)
                        if (localExisting == null && !obj.optBoolean("is_deleted", false)) {
                            db.fileItemDao().upsert(
                                FileItemEntity(
                                    id = id,
                                    originalFilename = obj.getString("original_filename"),
                                    displayName = obj.getString("display_name"),
                                    categoryId = obj.getString("category_id"),
                                    folderId = obj.optString("folder_id").takeIf { it.isNotBlank() && it != "null" },
                                    mimeType = obj.optString("mime_type", "application/octet-stream"),
                                    sizeBytes = obj.optLong("size_bytes", 0L),
                                    localPath = null,
                                    cloudPath = obj.optString("cloud_path").takeIf { it.isNotBlank() && it != "null" },
                                    cloudBucket = obj.optString("cloud_bucket").takeIf { it.isNotBlank() && it != "null" },
                                    backupStatus = BackupStatus.CLOUD_ONLY,
                                    sha256 = obj.optString("sha256", ""),
                                    isFavorite = obj.optBoolean("is_favorite", false),
                                    isInVault = obj.optBoolean("is_in_vault", false),
                                    isDeleted = false,
                                    createdAt = obj.optLong("created_at", System.currentTimeMillis()),
                                    updatedAt = obj.optLong("updated_at", System.currentTimeMillis())
                                )
                            )
                        }
                    }
                }
                syncRowIfAuthenticated(
                    "sync_history",
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("summary", "Workspace synced with Firebase Storage & SQL Backend")
                        .put("device_info", "${Build.MANUFACTURER} ${Build.MODEL}")
                        .put("timestamp", System.currentTimeMillis())
                )
            }
        }
    }

    // --- AI Document OCR & Reminders ---
    suspend fun runDocumentOcr(
        item: FileItemEntity,
        autoDetectCategory: Boolean = true,
        renameWithSuggested: Boolean = false,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): DocumentOcrResult {
        val readable = LocalFileManager.resolveReadableFile(context, item)
            ?: return DocumentOcrResult.Error("File is stored in Cloud Only. Restore to phone first to scan.")
        val res = ocrEngine.analyzeDocument(
            fileId = item.id,
            file = readable,
            originalFilename = item.originalFilename,
            mimeType = item.mimeType,
            onProgress = onProgress
        )
        when (res) {
            is DocumentOcrResult.Success -> {
                db.documentExtractionDao().upsert(res.extraction)
                val nextCategory = if (autoDetectCategory && !res.detectedCategoryId.isNullOrBlank()) {
                    res.detectedCategoryId
                } else item.categoryId
                val nextDisplayName = if (renameWithSuggested && !res.suggestedDisplayName.isNullOrBlank()) {
                    res.suggestedDisplayName
                } else item.displayName
                if (nextCategory != item.categoryId || nextDisplayName != item.displayName) {
                    val updatedItem = item.copy(
                        categoryId = nextCategory,
                        displayName = nextDisplayName,
                        updatedAt = System.currentTimeMillis()
                    )
                    db.fileItemDao().upsert(updatedItem)
                    syncFileMetadata(updatedItem)
                }
                syncExtraction(res.extraction)
                if (!res.extraction.expiryDate.isNullOrBlank()) {
                    val reminder = DocumentReminderEntity(
                        fileId = item.id,
                        documentName = res.suggestedDisplayName ?: item.displayName,
                        expiryDateIso = res.extraction.expiryDate
                    )
                    db.documentReminderDao().upsert(reminder)
                    syncDocumentMetadata(reminder, res.extraction.documentType)
                    BackupScheduler.scheduleDailyExpiryCheck(context)
                }
            }

            is DocumentOcrResult.PartialRawTextOnly -> {
                val existing = db.documentExtractionDao().getForFile(item.id)
                val partial = (existing ?: DocumentExtractionEntity(fileId = item.id)).copy(
                    documentName = res.detectedDocumentName ?: existing?.documentName,
                    documentType = res.detectedDocumentType ?: existing?.documentType,
                    documentNumber = res.detectedDocumentNumber ?: existing?.documentNumber,
                    rawText = res.rawText,
                    updatedAt = System.currentTimeMillis()
                )
                db.documentExtractionDao().upsert(partial)
                syncExtraction(partial)
                if (autoDetectCategory && !res.detectedCategoryId.isNullOrBlank() && res.detectedCategoryId != item.categoryId) {
                    val updatedItem = item.copy(
                        categoryId = res.detectedCategoryId,
                        updatedAt = System.currentTimeMillis()
                    )
                    db.fileItemDao().upsert(updatedItem)
                    syncFileMetadata(updatedItem)
                }
            }

            is DocumentOcrResult.Error -> Unit
        }
        return res
    }

    suspend fun saveVerifiedExtraction(
        item: FileItemEntity,
        extraction: DocumentExtractionEntity,
        applySuggestedDisplayName: Boolean = false
    ) {
        val verified = extraction.copy(isVerified = true, updatedAt = System.currentTimeMillis())
        db.documentExtractionDao().upsert(verified)
        val newDisplayName = if (applySuggestedDisplayName) {
            ocrEngine.buildDisplayName(verified.documentName, verified.ownerName)
        } else null
        val detectedCat = DskCategories.classifyFromOcrContent(
            rawText = verified.rawText,
            detectedDocName = verified.documentName,
            detectedDocType = verified.documentType,
            originalFilename = item.originalFilename,
            mimeType = item.mimeType,
            currentCategoryId = item.categoryId
        )
        if (!newDisplayName.isNullOrBlank() || detectedCat != item.categoryId) {
            val updated = item.copy(
                displayName = newDisplayName ?: item.displayName,
                categoryId = detectedCat,
                updatedAt = System.currentTimeMillis()
            )
            db.fileItemDao().upsert(updated)
            syncFileMetadata(updated)
        }
        syncExtraction(verified)
    }

    suspend fun saveExpiryReminder(reminder: DocumentReminderEntity) {
        db.documentReminderDao().upsert(reminder)
        BackupScheduler.scheduleDailyExpiryCheck(context)
        syncDocumentMetadata(reminder, null)
    }

    // --- Folders & Custom Categories ---
    suspend fun createFolder(name: String, categoryId: String, autoBackup: Boolean = true) {
        val folder = FolderEntity(name = name.trim(), categoryId = categoryId, isAutoBackupEnabled = autoBackup)
        db.folderDao().upsert(folder)
        syncRowIfAuthenticated(
            "folders",
            JSONObject()
                .put("id", folder.id)
                .put("name", folder.name)
                .put("category_id", folder.categoryId)
                .put("parent_id", folder.parentId)
                .put("is_auto_backup_enabled", folder.isAutoBackupEnabled)
                .put("created_at", folder.createdAt)
        )
    }

    suspend fun toggleFolderAutoBackup(folder: FolderEntity) {
        val updated = folder.copy(isAutoBackupEnabled = !folder.isAutoBackupEnabled)
        db.folderDao().upsert(updated)
        syncRowIfAuthenticated(
            "folders",
            JSONObject()
                .put("id", updated.id)
                .put("name", updated.name)
                .put("category_id", updated.categoryId)
                .put("parent_id", updated.parentId)
                .put("is_auto_backup_enabled", updated.isAutoBackupEnabled)
                .put("created_at", updated.createdAt)
        )
    }

    suspend fun deleteFolder(folder: FolderEntity) {
        db.folderDao().deleteById(folder.id)
        deleteRemoteRowIfAuthenticated("folders", folder.id)
        logSecurity("DELETE_FOLDER", "Deleted folder ${folder.name}", true)
    }

    suspend fun createCustomCategory(name: String) {
        val cat = CustomCategoryEntity(name = name.trim())
        db.customCategoryDao().upsert(cat)
        syncRowIfAuthenticated(
            "categories",
            JSONObject()
                .put("id", cat.id)
                .put("name", cat.name)
                .put("icon_key", cat.iconKey)
                .put("accent_hex", cat.accentHex)
                .put("is_custom", true)
                .put("created_at", cat.createdAt)
        )
    }

    // --- Text Notes ---
    suspend fun saveTextNote(note: TextNoteEntity) {
        val updated = note.copy(updatedAt = System.currentTimeMillis())
        db.textNoteDao().upsert(updated)
        syncRowIfAuthenticated(
            "text_notes",
            JSONObject()
                .put("id", updated.id)
                .put("title", updated.title)
                .put("content", updated.content)
                .put("category_id", updated.categoryId)
                .put("is_favorite", updated.isFavorite)
                .put("is_deleted", updated.isDeleted)
                .put("deleted_at", updated.deletedAt)
                .put("created_at", updated.createdAt)
                .put("updated_at", updated.updatedAt)
        )
    }

    suspend fun deleteTextNote(note: TextNoteEntity, permanent: Boolean = false) {
        if (permanent) {
            db.textNoteDao().hardDeleteById(note.id)
            deleteRemoteRowIfAuthenticated("text_notes", note.id)
        } else {
            saveTextNote(note.copy(isDeleted = true, deletedAt = System.currentTimeMillis()))
        }
        logSecurity("DELETE_NOTE", "Deleted note ${note.title}", true)
    }

    // --- Video URLs & Website Links ---
    suspend fun fetchLinkMetadata(url: String): FetchedLinkMetadata = sqlClient.fetchLinkMetadata(url)

    suspend fun saveLink(link: SavedLinkEntity) {
        val updated = link.copy(updatedAt = System.currentTimeMillis())
        db.savedLinkDao().upsert(updated)
        syncRowIfAuthenticated(
            "saved_links",
            JSONObject()
                .put("id", updated.id)
                .put("link_type", updated.linkType)
                .put("title", updated.title)
                .put("url", updated.url)
                .put("thumbnail_url", updated.thumbnailUrl)
                .put("description", updated.description)
                .put("category_id", updated.categoryId)
                .put("is_favorite", updated.isFavorite)
                .put("is_deleted", updated.isDeleted)
                .put("deleted_at", updated.deletedAt)
                .put("created_at", updated.createdAt)
                .put("updated_at", updated.updatedAt)
        )
    }

    suspend fun deleteLink(link: SavedLinkEntity, permanent: Boolean = false) {
        if (permanent) {
            db.savedLinkDao().hardDeleteById(link.id)
            deleteRemoteRowIfAuthenticated("saved_links", link.id)
        } else {
            saveLink(link.copy(isDeleted = true, deletedAt = System.currentTimeMillis()))
        }
        logSecurity("DELETE_LINK", "Deleted ${link.linkType} ${link.title}", true)
    }

    // --- Profile & Settings ---
    suspend fun updateProfile(name: String, avatarUri: Uri? = null) {
        val existing = db.profileSettingsDao().getProfileOnce() ?: OwnerProfileEntity()
        val avatarPath = if (avatarUri != null) {
            LocalFileManager.copyAvatarUri(context, avatarUri).getOrNull() ?: existing.avatarLocalPath
        } else existing.avatarLocalPath
        val updated = existing.copy(
            ownerName = name.trim().ifBlank { existing.ownerName },
            avatarLocalPath = avatarPath,
            firebaseUid = currentFirebaseUid() ?: existing.firebaseUid,
            updatedAt = System.currentTimeMillis()
        )
        db.profileSettingsDao().upsertProfile(updated)
        syncProfile(updated)
    }

    suspend fun updateSettings(transform: (UserSettingsEntity) -> UserSettingsEntity) {
        val current = db.profileSettingsDao().getSettingsOnce() ?: UserSettingsEntity()
        val updated = transform(current)
        db.profileSettingsDao().upsertSettings(updated)
        BackupScheduler.schedulePeriodicAutoBackup(context, updated.autoBackupEnabled, updated.wifiOnly)
        syncRowIfAuthenticated(
            "user_settings",
            JSONObject()
                .put("auto_backup_enabled", updated.autoBackupEnabled)
                .put("wifi_only", updated.wifiOnly)
                .put("backup_photos", updated.backupPhotos)
                .put("backup_videos", updated.backupVideos)
                .put("backup_documents", updated.backupDocuments)
                .put("auto_lock_seconds", updated.autoLockSeconds)
                .put("dark_mode", updated.darkMode)
                .put("animations_4d_enabled", updated.animations4DEnabled)
                .put("reduced_motion", updated.reducedMotion)
                .put("accent_preset", updated.accentPreset)
                .put("notifications_enabled", updated.notificationsEnabled)
                .put("updated_at", System.currentTimeMillis())
        )
    }

    fun getStorageStats(): DeviceStorageStats = LocalFileManager.getDeviceStorageStats(context)

    suspend fun recordDownload(file: FileItemEntity) {
        syncRowIfAuthenticated(
            "download_history",
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("file_id", file.id)
                .put("file_name", file.displayName)
                .put("downloaded_at", System.currentTimeMillis())
        )
    }

    private suspend fun syncProfile(profile: OwnerProfileEntity) {
        syncRowIfAuthenticated(
            "user_profiles",
            JSONObject()
                .put("owner_name", profile.ownerName)
                .put("phone_number", profile.phoneNumber)
                .put("avatar_cloud_path", profile.avatarCloudPath)
                .put("updated_at", profile.updatedAt)
        )
    }

    private suspend fun syncDocumentMetadata(reminder: DocumentReminderEntity, docCategory: String?) {
        syncRowIfAuthenticated(
            "document_metadata",
            JSONObject()
                .put("file_id", reminder.fileId)
                .put("page_count", 1)
                .put("document_category", docCategory)
                .put("expiry_date_iso", reminder.expiryDateIso)
                .put("remind_30_days", reminder.remind30Days)
                .put("remind_15_days", reminder.remind15Days)
                .put("remind_7_days", reminder.remind7Days)
                .put("remind_on_day", reminder.remindOnDay)
                .put("notifications_enabled", reminder.notificationsEnabled)
                .put("updated_at", System.currentTimeMillis())
        )
    }

    private suspend fun syncFileMetadata(item: FileItemEntity) {
        syncRowIfAuthenticated(
            "files",
            JSONObject()
                .put("id", item.id)
                .put("original_filename", item.originalFilename)
                .put("display_name", item.displayName)
                .put("category_id", item.categoryId)
                .put("folder_id", item.folderId)
                .put("mime_type", item.mimeType)
                .put("size_bytes", item.sizeBytes)
                .put("cloud_path", item.cloudPath)
                .put("cloud_bucket", item.cloudBucket)
                .put("backup_status", item.backupStatus.name)
                .put("sha256", item.sha256)
                .put("is_favorite", item.isFavorite)
                .put("is_in_vault", item.isInVault)
                .put("is_deleted", item.isDeleted)
                .put("deleted_at", item.deletedAt)
                .put("created_at", item.createdAt)
                .put("updated_at", item.updatedAt)
        )
    }

    private suspend fun syncExtraction(ex: DocumentExtractionEntity) {
        syncRowIfAuthenticated(
            "document_extractions",
            JSONObject()
                .put("file_id", ex.fileId)
                .put("document_name", ex.documentName)
                .put("document_type", ex.documentType)
                .put("document_number", ex.documentNumber)
                .put("owner_name", ex.ownerName)
                .put("father_name", ex.fatherName)
                .put("mother_name", ex.motherName)
                .put("date_of_birth", ex.dateOfBirth)
                .put("issue_date", ex.issueDate)
                .put("expiry_date", ex.expiryDate)
                .put("address", ex.address)
                .put("mobile_number", ex.mobileNumber)
                .put("organization_name", ex.organizationName)
                .put("registration_number", ex.registrationNumber)
                .put("raw_text", ex.rawText)
                .put("is_verified", ex.isVerified)
                .put("updated_at", ex.updatedAt)
        )
    }

    private suspend fun syncRowIfAuthenticated(table: String, json: JSONObject) {
        if (!BackendConfig.isSqlBackendConfigured) return
        val token = phoneAuth.getFreshIdToken(false).orEmpty()
        if (token.isNotBlank()) {
            sqlClient.upsertRow(table, token, json)
        }
    }

    private suspend fun deleteRemoteRowIfAuthenticated(table: String, recordId: String) {
        if (!BackendConfig.isSqlBackendConfigured) return
        val token = phoneAuth.getFreshIdToken(false).orEmpty()
        if (token.isNotBlank()) {
            sqlClient.deleteRow(table, token, recordId)
        }
    }
}
