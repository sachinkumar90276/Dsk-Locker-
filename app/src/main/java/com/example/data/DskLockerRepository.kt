package com.example.data

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.ai.DeterministicDocumentParser
import com.example.ai.DocumentOcrEngine
import com.example.ai.DocumentOcrResult
import com.example.ai.ExtendedOcrMetadata
import com.example.ai.OcrMetadataStore
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
import java.io.IOException
import java.util.UUID

data class BackupExecutionSummary(
    val uploadedCount: Int,
    val failedCount: Int,
    val skippedCount: Int = 0,
    val transientRetryNeeded: Boolean = false,
    val notice: String? = null
)

data class RestoreExecutionSummary(
    val restoredCount: Int,
    val verifiedCount: Int,
    val failedCount: Int,
    val restoredMetadataCount: Int,
    val missingKeyFailures: Int = 0,
    val notice: String
)

class DskLockerRepository(
    private val context: Context,
    private val customSqlClient: SqlBackendClient? = null,
    private val idTokenOverride: (suspend () -> String?)? = null
) {
    private val db by lazy { DskLockerDatabase.getInstance(context) }
    val prefs by lazy { SecurePrefs(context) }
    val phoneAuth by lazy { FirebasePhoneAuthManager() }
    val storageManager by lazy { FirebaseStorageManager() }
    val sqlClient by lazy { customSqlClient ?: SqlBackendClient() }
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

    private fun isBackendConfiguredForClient(): Boolean =
        customSqlClient != null || BackendConfig.isSqlBackendConfigured

    private suspend fun resolveIdToken(): String? {
        if (idTokenOverride != null) {
            return idTokenOverride.invoke()?.takeIf { it.isNotBlank() }
        }
        return phoneAuth.getFreshIdToken(false)?.takeIf { it.isNotBlank() }
    }

    suspend fun ensureDefaultsInitialized() {
        if (prefs.hasMpin() || !prefs.firebaseUid.isNullOrBlank()) {
            val currentFbUser = phoneAuth.currentUser()
            if (currentFbUser != null) {
                prefs.firebaseUid = currentFbUser.uid
                if (!currentFbUser.phoneNumber.isNullOrBlank()) {
                    prefs.ownerPhone = currentFbUser.phoneNumber
                }
                if (!currentFbUser.email.isNullOrBlank()) {
                    prefs.ownerEmail = currentFbUser.email
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

    fun hasFirebaseSession(): Boolean =
        phoneAuth.currentUser() != null || idTokenOverride != null

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

    // --- Firebase Phone Authentication (+91 Owner Verification & Reinstall Recovery) ---
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

    suspend fun signInWithGoogle(uiContext: Context): GoogleSignInResult {
        return when (val authRes = phoneAuth.signInWithGoogle(uiContext)) {
            is GoogleSignInResult.Success -> {
                val fin = finalizeFirebaseOwnerSession(authRes.session)
                if (fin.isSuccess) {
                    GoogleSignInResult.Success(fin.getOrThrow())
                } else {
                    logSecurity(
                        "GOOGLE_SIGNIN_REJECTED",
                        fin.exceptionOrNull()?.message ?: "Cloud verification failed",
                        false
                    )
                    GoogleSignInResult.Error(
                        fin.exceptionOrNull()?.localizedMessage ?: "Cloud verification rejected."
                    )
                }
            }
            is GoogleSignInResult.Cancelled -> GoogleSignInResult.Cancelled
            is GoogleSignInResult.Error -> {
                logSecurity("GOOGLE_SIGNIN_FAILED", authRes.message, false)
                authRes
            }
        }
    }

    suspend fun attemptSilentGoogleSignIn(): Result<FirebaseSessionUser?> {
        val session = phoneAuth.attemptSilentGoogleSignIn(context) ?: return Result.success(null)
        return finalizeFirebaseOwnerSession(session).map { it }
    }

    suspend fun registerWithEmail(
        email: String,
        password: String,
        displayName: String? = null
    ): EmailAuthResult {
        return when (val res = phoneAuth.registerWithEmailPassword(email, password, displayName)) {
            is EmailAuthResult.Success -> {
                val fin = finalizeFirebaseOwnerSession(res.session)
                if (fin.isSuccess) {
                    logSecurity("EMAIL_REGISTER_SUCCESS", "Registered ${res.session.email ?: res.session.uid}", true)
                    res.copy(session = fin.getOrThrow())
                } else {
                    logSecurity("EMAIL_REGISTER_REJECTED", fin.exceptionOrNull()?.message ?: "Cloud verification rejected", false)
                    EmailAuthResult.Error(fin.exceptionOrNull()?.localizedMessage ?: "Cloud verification rejected.")
                }
            }
            is EmailAuthResult.Error -> {
                logSecurity("EMAIL_REGISTER_FAILED", res.message, false)
                res
            }
        }
    }

    suspend fun signInWithEmail(email: String, password: String): EmailAuthResult {
        return when (val res = phoneAuth.signInWithEmailPassword(email, password)) {
            is EmailAuthResult.Success -> {
                val fin = finalizeFirebaseOwnerSession(res.session)
                if (fin.isSuccess) {
                    logSecurity("EMAIL_LOGIN_SUCCESS", "Signed in ${res.session.email ?: res.session.uid}", true)
                    res.copy(session = fin.getOrThrow())
                } else {
                    logSecurity("EMAIL_LOGIN_REJECTED", fin.exceptionOrNull()?.message ?: "Cloud verification rejected", false)
                    EmailAuthResult.Error(fin.exceptionOrNull()?.localizedMessage ?: "Cloud verification rejected.")
                }
            }
            is EmailAuthResult.Error -> {
                logSecurity("EMAIL_LOGIN_FAILED", res.message, false)
                res
            }
        }
    }

    suspend fun sendPasswordResetEmail(email: String): Result<Unit> =
        phoneAuth.sendPasswordResetEmail(email).also { res ->
            logSecurity(
                "EMAIL_PASSWORD_RESET",
                if (res.isSuccess) "Password reset sent to ${email.trim()}" else res.exceptionOrNull()?.message ?: "Reset failed",
                res.isSuccess
            )
        }

    suspend fun sendCurrentUserVerificationEmail(): Result<Unit> =
        phoneAuth.sendVerificationEmail()

    suspend fun refreshEmailVerificationStatus(): Result<Boolean> =
        phoneAuth.reloadEmailVerificationStatus()

    fun currentUserEmailVerified(): Boolean? =
        phoneAuth.currentUserEmailVerified()

    suspend fun finalizeFirebaseOwnerSession(session: FirebaseSessionUser): Result<FirebaseSessionUser> {
        val existingUid = prefs.firebaseUid
        if (!existingUid.isNullOrBlank() && existingUid != session.uid) {
            phoneAuth.signOut()
            logSecurity("UNAUTHORIZED_OWNER_BLOCKED", "Rejected Firebase UID ${session.uid}", false)
            return Result.failure(
                SecurityException("Single-owner lock: DSK LOCKER on this device is linked to another cloud account.")
            )
        }
        val restricted = BackendConfig.restrictedOwnerPhone
        if (session.phoneNumber.isNotBlank()) {
            val check = validateOwnerPhoneEligibility(session.phoneNumber)
            if (check.isFailure) {
                phoneAuth.signOut()
                logSecurity("UNAUTHORIZED_OWNER_BLOCKED", "Rejected ${session.phoneNumber}", false)
                return Result.failure(check.exceptionOrNull() ?: SecurityException("Unauthorized owner number."))
            }
        } else if (restricted.isNotBlank()) {
            phoneAuth.signOut()
            logSecurity("UNAUTHORIZED_OWNER_BLOCKED", "Rejected account without authorized owner phone $restricted", false)
            return Result.failure(
                SecurityException("Access restricted: Only authorized owner $restricted may register or sign in.")
            )
        }

        val existingProfile = db.profileSettingsDao().getProfileOnce() ?: OwnerProfileEntity()
        val effectivePhone = session.phoneNumber.ifBlank { prefs.ownerPhone.orEmpty() }
        val effectiveOwnerName = when {
            existingProfile.ownerName.isNotBlank() && existingProfile.ownerName != "DSK Owner" -> existingProfile.ownerName
            !session.displayName.isNullOrBlank() -> session.displayName
            !session.email.isNullOrBlank() -> session.email.substringBefore("@")
            else -> "DSK Owner"
        }

        val sqlReg = sqlClient.registerOrVerifyOwner(
            firebaseIdToken = session.idToken,
            phoneE164 = effectivePhone,
            ownerName = effectiveOwnerName
        )
        if (sqlReg.isFailure) {
            phoneAuth.signOut()
            logSecurity("SQL_OWNER_REJECTED", sqlReg.exceptionOrNull()?.message ?: "SQL rejected user", false)
            return Result.failure(sqlReg.exceptionOrNull() ?: SecurityException("Owner registration denied by Render backend."))
        }

        prefs.firebaseUid = session.uid
        if (session.phoneNumber.isNotBlank()) {
            prefs.ownerPhone = session.phoneNumber
        }
        if (!session.email.isNullOrBlank()) {
            prefs.ownerEmail = session.email
        }
        prefs.isCloudOwnerVerified = true

        val updatedProfile = existingProfile.copy(
            ownerName = effectiveOwnerName,
            phoneNumber = effectivePhone.ifBlank { existingProfile.phoneNumber },
            firebaseUid = session.uid,
            updatedAt = System.currentTimeMillis()
        )
        db.profileSettingsDao().upsertProfile(updatedProfile)
        syncProfile(updatedProfile)

        // Sync existing local MPIN hash if present so server-side MPIN verify & key recovery work immediately
        prefs.getMpinHashForSync()?.let { (hash, salt) ->
            sqlClient.syncMpinSecurityHash(
                firebaseIdToken = session.idToken,
                mpinHash = hash,
                mpinSalt = salt,
                mpinLength = prefs.getMpinLength(),
                biometricEnabled = prefs.biometricEnabled
            )
        }

        val label = session.phoneNumber.ifBlank { session.email.orEmpty() }
        logSecurity("FIREBASE_OWNER_VERIFIED", "Verified Firebase UID ${session.uid} ($label)", true)
        return Result.success(session)
    }

    suspend fun logoutSession() {
        logSecurity("OWNER_LOGOUT", "Owner logged out from cloud session", true)
        phoneAuth.signOutWithCredentialClear(context)
    }

    suspend fun saveNewMpin(mpin: String) {
        prefs.saveMpin(mpin)
        val (hash, salt) = prefs.getMpinHashForSync() ?: return
        val idToken = resolveIdToken()
        if (!idToken.isNullOrBlank() && isBackendConfiguredForClient()) {
            sqlClient.syncMpinSecurityHash(
                firebaseIdToken = idToken,
                mpinHash = hash,
                mpinSalt = salt,
                mpinLength = mpin.length,
                biometricEnabled = prefs.biometricEnabled
            )
        }
        logSecurity("MPIN_UPDATED", "Owner ${mpin.length}-digit PBKDF2 MPIN hash updated securely", true)
    }

    suspend fun verifyMpinWithBackendIfLinked(mpin: String, purpose: String = "RECOVERY"): Result<Unit> {
        if (prefs.hasMpin() && !prefs.verifyMpin(mpin)) {
            return Result.failure(SecurityException("Incorrect local MPIN."))
        }
        val idToken = resolveIdToken()
        if (!idToken.isNullOrBlank() && isBackendConfiguredForClient()) {
            val serverRes = sqlClient.verifyServerMpin(idToken, mpin, purpose)
            if (serverRes.isFailure) return serverRes
        }
        return Result.success(Unit)
    }

    suspend fun findDuplicateFiles(sha256: String): List<FileItemEntity> {
        if (sha256.isBlank()) return emptyList()
        return db.fileItemDao().findBySha256(sha256)
    }

    fun isOcrSupported(file: FileItemEntity): Boolean =
        ocrEngine.isOcrSupported(file.originalFilename, file.mimeType)

    suspend fun resolveReadableFile(file: FileItemEntity): File? =
        LocalFileManager.resolveReadableFile(
            context = context,
            item = file,
            portableVaultKeyBase64 = if (file.isInVault) prefs.getPortableVaultKeyBase64() else null
        )

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
        val portableKey = if (isInVault) prefs.getOrCreatePortableVaultKeyBase64() else null

        for (uri in uris) {
            val res = LocalFileManager.importUri(
                context = context,
                uri = uri,
                isInVault = isInVault,
                folderId = folderId,
                forcedCategoryId = forcedCategoryId,
                portableVaultKeyBase64 = portableKey
            )
            res.getOrNull()?.let { item ->
                val status = if (runAutoBackupIfEnabled && settings?.autoBackupEnabled == true) {
                    BackupStatus.BACKUP_PENDING
                } else {
                    BackupStatus.MOBILE_ONLY
                }
                val finalItem = item.copy(backupStatus = status)
                db.fileItemDao().upsert(finalItem)
                if (isInVault && portableKey != null) {
                    prefs.setFileKeyVersion(finalItem.id, 1)
                }
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
        val settings = db.profileSettingsDao().getSettingsOnce()
        val updated = file.copy(
            displayName = newDisplayName.trim(),
            backupStatus = if (settings?.autoBackupEnabled == true) BackupStatus.BACKUP_PENDING else file.backupStatus,
            updatedAt = System.currentTimeMillis()
        )
        db.fileItemDao().upsert(updated)
        syncFileMetadata(updated)
        if (settings?.autoBackupEnabled == true) {
            BackupScheduler.enqueueImmediateBackup(context, settings.wifiOnly)
        }
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
                if (file.isInVault) {
                    prefs.setFileKeyVersion(newId, prefs.getFileKeyVersion(file.id))
                }
            }
            val copyItem = file.copy(
                id = newId,
                displayName = "${file.displayName} (Copy)",
                categoryId = targetCategoryId,
                folderId = targetFolderId,
                localPath = copiedPath,
                cloudPath = null,
                cloudBucket = null,
                backupStatus = if (copiedPath != null) BackupStatus.BACKUP_PENDING else BackupStatus.MOBILE_ONLY,
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
        val token = resolveIdToken()
        when (mode) {
            DeleteMode.MOBILE_ONLY -> {
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
                logSecurity("DELETE_MOBILE_ONLY", "Removed local copy of ${file.displayName}; Cloud backup untouched", true)
            }

            DeleteMode.CLOUD_ONLY -> {
                if (!file.cloudPath.isNullOrBlank() && !token.isNullOrBlank() && isBackendConfiguredForClient()) {
                    sqlClient.deleteCloudBackupFile(token, file.id)
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
                logSecurity("DELETE_CLOUD_ONLY", "Removed Neon cloud copy of ${file.displayName}; mobile file kept", true)
            }

            DeleteMode.BOTH -> {
                if (!file.cloudPath.isNullOrBlank() && !token.isNullOrBlank() && isBackendConfiguredForClient()) {
                    sqlClient.deleteCloudBackupFile(token, file.id)
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
                logSecurity("DELETE_BOTH_TO_TRASH", "Moved ${file.displayName} to Recycle Bin & removed Neon cloud backup", true)
            }
        }
    }

    suspend fun restoreFromTrash(file: FileItemEntity) {
        val restored = file.copy(isDeleted = false, deletedAt = null, updatedAt = System.currentTimeMillis())
        db.fileItemDao().upsert(restored)
        syncFileMetadata(restored)
    }

    suspend fun permanentlyDeleteFile(file: FileItemEntity) {
        val token = resolveIdToken()
        file.localPath?.let { path -> runCatching { File(path).delete() } }
        if (!file.cloudPath.isNullOrBlank() && !token.isNullOrBlank() && isBackendConfiguredForClient()) {
            sqlClient.deleteCloudBackupFile(token, file.id)
        }
        deleteRemoteRowIfAuthenticated("files", file.id)
        db.documentReminderDao().deleteForFile(file.id)
        OcrMetadataStore.delete(context, file.id)
        db.fileItemDao().hardDeleteById(file.id)
        logSecurity("PERMANENT_DELETE", "Permanently purged ${file.displayName}", true)
    }

    suspend fun emptyAllTrash() {
        db.fileItemDao().emptyTrashFiles()
        db.textNoteDao().emptyTrashNotes()
        db.savedLinkDao().emptyTrashLinks()
        logSecurity("EMPTY_TRASH", "Emptied Recycle Bin", true)
    }

    // --- Real Neon PostgreSQL + Render Backup Engine ---
    suspend fun buildCloudSnapshot(): CloudSnapshotDto {
        val foldersList = db.folderDao().getFoldersOnce().map { f ->
            JSONObject()
                .put("id", f.id)
                .put("name", f.name)
                .put("category_id", f.categoryId)
                .put("parent_id", f.parentId)
                .put("is_auto_backup_enabled", f.isAutoBackupEnabled)
                .put("created_at", f.createdAt)
        }
        val categoriesList = db.customCategoryDao().getAllOnce().map { c ->
            JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("icon_key", c.iconKey)
                .put("accent_hex", c.accentHex)
                .put("created_at", c.createdAt)
        }
        val notesList = db.textNoteDao().getActiveOnce().map { n ->
            JSONObject()
                .put("id", n.id)
                .put("title", n.title)
                .put("content", n.content)
                .put("category_id", n.categoryId)
                .put("is_favorite", n.isFavorite)
                .put("created_at", n.createdAt)
                .put("updated_at", n.updatedAt)
        }
        val linksList = db.savedLinkDao().getActiveOnce().map { l ->
            JSONObject()
                .put("id", l.id)
                .put("link_type", l.linkType)
                .put("title", l.title)
                .put("url", l.url)
                .put("thumbnail_url", l.thumbnailUrl)
                .put("description", l.description)
                .put("category_id", l.categoryId)
                .put("is_favorite", l.isFavorite)
                .put("created_at", l.createdAt)
                .put("updated_at", l.updatedAt)
        }
        return CloudSnapshotDto(
            folders = foldersList,
            customCategories = categoriesList,
            textNotes = notesList,
            savedLinks = linksList
        )
    }

    suspend fun runNeonCloudBackup(
        filesToBackup: List<FileItemEntity>? = null,
        isManual: Boolean = true,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): BackupExecutionSummary = withContext(Dispatchers.IO) {
        val targets = filesToBackup ?: db.fileItemDao().getPendingBackupFiles()
        val settings = db.profileSettingsDao().getSettingsOnce()
        val folderMap = db.folderDao().getFoldersOnce().associateBy { it.id }

        if (!isManual && settings?.autoBackupEnabled == false) {
            return@withContext BackupExecutionSummary(0, 0, targets.size, notice = "Auto backup is paused.")
        }

        if (customSqlClient == null) {
            val issue = BackendConfig.configurationIssue
            if (issue != null) {
                targets.forEach { f ->
                    if (f.localPath != null && f.backupStatus != BackupStatus.MOBILE_AND_CLOUD) {
                        db.fileItemDao().upsert(f.copy(backupStatus = BackupStatus.BACKUP_PENDING))
                    }
                }
                return@withContext BackupExecutionSummary(
                    uploadedCount = 0,
                    failedCount = 0,
                    skippedCount = targets.size,
                    transientRetryNeeded = false,
                    notice = issue
                )
            }
        }

        val idToken = resolveIdToken()
        if (idToken.isNullOrBlank()) {
            targets.forEach { f ->
                if (f.localPath != null && f.backupStatus != BackupStatus.MOBILE_AND_CLOUD) {
                    db.fileItemDao().upsert(f.copy(backupStatus = BackupStatus.BACKUP_PENDING))
                }
            }
            return@withContext BackupExecutionSummary(
                uploadedCount = 0,
                failedCount = 0,
                skippedCount = targets.size,
                transientRetryNeeded = true,
                notice = "Cloud account not linked yet. Local files are queued safely as BACKUP_PENDING."
            )
        }

        val extractionMap = db.documentExtractionDao().getAllOnce().associateBy { it.fileId }
        val reminderMap = db.documentReminderDao().getAllOnce().associateBy { it.fileId }
        val allNonDeletedCount = db.fileItemDao().getAllNonDeletedFilesOnce().size
        val snapshot = buildCloudSnapshot()

        // Ensure portable Vault recovery key is wrapped on server if vault items exist
        var keyWrapNotice: String? = null
        if (targets.any { it.isInVault } && !prefs.isPortableKeyWrappedInCloud) {
            val portableKey = prefs.getOrCreatePortableVaultKeyBase64()
            val wrapRes = sqlClient.wrapRecoveryKey(idToken, portableKey, keyVersion = 1)
            if (wrapRes.isSuccess) {
                prefs.isPortableKeyWrappedInCloud = true
            } else {
                keyWrapNotice = wrapRes.exceptionOrNull()?.localizedMessage
            }
        }

        val filteredTargets = if (isManual) {
            targets
        } else {
            targets.filter { fileItem ->
                val folderDisabled = fileItem.folderId?.let { folderMap[it]?.isAutoBackupEnabled == false } == true
                if (folderDisabled) return@filter false
                if (settings == null) return@filter true
                when (fileItem.categoryId) {
                    DskCategories.CAT_PHOTOS -> settings.backupPhotos
                    DskCategories.CAT_VIDEOS -> settings.backupVideos
                    DskCategories.CAT_DOCUMENTS, DskCategories.CAT_PDF, DskCategories.CAT_WORD,
                    DskCategories.CAT_EXCEL, DskCategories.CAT_TEXT, DskCategories.CAT_PERSONAL_RECORDS ->
                        settings.backupDocuments
                    else -> true
                }
            }
        }

        if (filteredTargets.isEmpty()) {
            // Still persist folders/notes/links snapshot cleanly when requested manually
            if (isManual) {
                onProgress("Syncing notes, folders & categories...", 0.7f)
                val snapRes = sqlClient.createBackup(
                    firebaseIdToken = idToken,
                    action = "MANUAL_BACKUP",
                    deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}",
                    totalDocuments = allNonDeletedCount,
                    pendingDocuments = 0,
                    failedDocuments = 0,
                    files = emptyList(),
                    snapshot = snapshot
                )
                onProgress("Done", 1f)
                return@withContext if (snapRes.isSuccess) {
                    prefs.lastBackupMs = snapRes.getOrThrow().lastBackupAt
                    BackupExecutionSummary(0, 0, notice = "Cloud snapshot synced cleanly to Neon PostgreSQL.")
                } else {
                    val err = snapRes.exceptionOrNull()
                    BackupExecutionSummary(
                        0,
                        1,
                        transientRetryNeeded = err is IOException,
                        notice = err?.localizedMessage ?: "Snapshot backup failed."
                    )
                }
            }
            return@withContext BackupExecutionSummary(0, 0)
        }

        var uploaded = 0
        var failed = 0
        var transientNeedRetry = false
        var lastErrorMsg: String? = null
        val portableKey = prefs.getPortableVaultKeyBase64()

        filteredTargets.forEachIndexed { idx, item ->
            onProgress(item.displayName, ((idx.toFloat() + 0.2f) / filteredTargets.size.toFloat()).coerceIn(0.05f, 0.95f))

            val localFile = item.localPath?.let { File(it) }
            if (localFile == null || !localFile.exists()) {
                failed++
                db.fileItemDao().upsert(item.copy(backupStatus = BackupStatus.BACKUP_FAILED))
                db.backupHistoryDao().insert(
                    BackupHistoryEntity(
                        fileId = item.id,
                        fileName = item.displayName,
                        action = if (isManual) "MANUAL_BACKUP" else "AUTO_BACKUP",
                        status = "FAILED",
                        message = "Local file unavailable on device; skipped without deletion."
                    )
                )
                return@forEachIndexed
            }

            // Prepare bytes: if vault item is v0 hardware encrypted and portable key exists, upgrade encrypted copy for backup
            var payloadBytes = localFile.readBytes()
            var isEncryptedPayload = item.isInVault
            var keyVersion = if (item.isInVault) prefs.getFileKeyVersion(item.id) else 0

            if (item.isInVault && keyVersion == 0 && !portableKey.isNullOrBlank()) {
                val plainTemp = File(context.cacheDir, "vault_rekey_${item.id}")
                val encTemp = File(context.cacheDir, "vault_rekey_enc_${item.id}")
                val converted = runCatching {
                    localFile.inputStream().use { inp ->
                        plainTemp.outputStream().use { out -> CryptoManager.decryptStream(inp, out) }
                    }
                    plainTemp.inputStream().use { inp ->
                        encTemp.outputStream().use { out ->
                            CryptoManager.encryptStreamWithKey(CryptoManager.secretKeyFromBase64(portableKey), inp, out)
                        }
                    }
                    encTemp.readBytes()
                }.getOrNull()
                runCatching { plainTemp.delete() }
                runCatching { encTemp.delete() }
                if (converted != null) {
                    payloadBytes = converted
                    keyVersion = 1
                    isEncryptedPayload = true
                }
            }

            val checksum = CryptoManager.sha256Bytes(payloadBytes)
            val extEntity = extractionMap[item.id]
            val extJson = extEntity?.let { ex ->
                JSONObject()
                    .put("file_id", ex.fileId)
                    .putOpt("document_name", ex.documentName)
                    .putOpt("document_type", ex.documentType)
                    .putOpt("document_number", ex.documentNumber)
                    .putOpt("owner_name", ex.ownerName)
                    .putOpt("father_name", ex.fatherName)
                    .putOpt("mother_name", ex.motherName)
                    .putOpt("date_of_birth", ex.dateOfBirth)
                    .putOpt("issue_date", ex.issueDate)
                    .putOpt("expiry_date", ex.expiryDate)
                    .putOpt("address", ex.address)
                    .putOpt("mobile_number", ex.mobileNumber)
                    .putOpt("organization_name", ex.organizationName)
                    .putOpt("registration_number", ex.registrationNumber)
                    .put("raw_text", ex.rawText)
                    .put("is_verified", ex.isVerified)
                    .put("updated_at", ex.updatedAt)
            }
            val extendedOcrJson = OcrMetadataStore.load(context, item.id)?.toJsonString()
            val remEntity = reminderMap[item.id]
            val remJson = remEntity?.let { r ->
                JSONObject()
                    .put("id", r.id)
                    .put("file_id", r.fileId)
                    .put("document_name", r.documentName)
                    .put("expiry_date_iso", r.expiryDateIso)
                    .put("remind_30_days", r.remind30Days)
                    .put("remind_15_days", r.remind15Days)
                    .put("remind_7_days", r.remind7Days)
                    .put("remind_on_day", r.remindOnDay)
                    .put("notifications_enabled", r.notificationsEnabled)
            }

            val uploadPayload = CloudBackupUploadFile(
                fileId = item.id,
                originalFilename = item.originalFilename,
                displayName = item.displayName,
                categoryId = item.categoryId,
                folderId = item.folderId,
                mimeType = item.mimeType,
                sizeBytes = payloadBytes.size.toLong(),
                sha256 = checksum,
                isInVault = item.isInVault,
                isFavorite = item.isFavorite,
                isEncryptedPayload = isEncryptedPayload,
                keyVersion = keyVersion,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                bytes = payloadBytes,
                extractionJson = extJson,
                extendedOcrJson = extendedOcrJson,
                reminderJson = remJson
            )

            val uploadRes = sqlClient.createBackup(
                firebaseIdToken = idToken,
                action = if (isManual) "MANUAL_BACKUP" else "AUTO_BACKUP",
                deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}",
                totalDocuments = allNonDeletedCount,
                pendingDocuments = (filteredTargets.size - uploaded - 1).coerceAtLeast(0),
                failedDocuments = failed,
                files = listOf(uploadPayload),
                snapshot = if (idx == filteredTargets.lastIndex) snapshot else null
            )

            if (uploadRes.isSuccess) {
                val created = uploadRes.getOrThrow()
                val updated = item.copy(
                    cloudPath = "neon://backup_files/${item.id}",
                    cloudBucket = "neon-postgresql",
                    backupStatus = BackupStatus.MOBILE_AND_CLOUD,
                    sha256 = if (item.isInVault) item.sha256 else checksum,
                    updatedAt = System.currentTimeMillis()
                )
                db.fileItemDao().upsert(updated)
                db.backupHistoryDao().insert(
                    BackupHistoryEntity(
                        fileId = updated.id,
                        fileName = updated.displayName,
                        action = if (isManual) "MANUAL_BACKUP" else "AUTO_BACKUP",
                        status = "SUCCESS",
                        bytesTransferred = payloadBytes.size.toLong(),
                        message = "Backed up to Neon PostgreSQL & verified SHA-256."
                    )
                )
                prefs.lastBackupMs = created.lastBackupAt
                uploaded++
            } else {
                val err = uploadRes.exceptionOrNull()
                val msg = err?.localizedMessage ?: "Neon backup upload failed"
                lastErrorMsg = msg
                val isTransient = err is IOException || msg.contains("timeout", ignoreCase = true) || msg.contains("connect", ignoreCase = true)
                if (isTransient) {
                    transientNeedRetry = true
                    db.fileItemDao().upsert(item.copy(backupStatus = BackupStatus.BACKUP_PENDING))
                } else {
                    db.fileItemDao().upsert(item.copy(backupStatus = BackupStatus.BACKUP_FAILED))
                }
                // CRITICAL: NEVER delete local files on failed backup!
                db.backupHistoryDao().insert(
                    BackupHistoryEntity(
                        fileId = item.id,
                        fileName = item.displayName,
                        action = if (isManual) "MANUAL_BACKUP" else "AUTO_BACKUP",
                        status = "FAILED",
                        message = msg
                    )
                )
                failed++
            }
        }

        onProgress("Backup complete", 1f)
        val combinedNotice = listOfNotNull(
            lastErrorMsg,
            keyWrapNotice?.let { "Recovery key note: $it" }
        ).joinToString(" • ").takeIf { it.isNotBlank() }

        BackupExecutionSummary(
            uploadedCount = uploaded,
            failedCount = failed,
            transientRetryNeeded = transientNeedRetry,
            notice = combinedNotice
        )
    }

    suspend fun runManualBackup(filesToBackup: List<FileItemEntity>? = null): Pair<Int, Int> {
        val summary = runNeonCloudBackup(filesToBackup = filesToBackup, isManual = true)
        return summary.uploadedCount to summary.failedCount
    }

    suspend fun recoverInterruptedBackups() = withContext(Dispatchers.IO) {
        val pending = db.fileItemDao().getPendingBackupFiles()
        val settings = db.profileSettingsDao().getSettingsOnce()
        if (pending.isNotEmpty() && settings?.autoBackupEnabled == true && isBackendConfiguredForClient()) {
            BackupScheduler.enqueueImmediateBackup(context, settings.wifiOnly)
        }
    }

    suspend fun verifyLiveBackendHealth(): Result<BackendHealthStatusDto> {
        if (customSqlClient == null && BackendConfig.configurationIssue != null) {
            return Result.failure(IllegalStateException(BackendConfig.configurationIssue))
        }
        return sqlClient.checkBackendHealth()
    }

    suspend fun fetchCloudStatus(): Result<CloudBackupStatusDto> {
        if (customSqlClient == null && BackendConfig.configurationIssue != null) {
            return Result.failure(IllegalStateException(BackendConfig.configurationIssue))
        }
        val token = resolveIdToken()
            ?: return Result.failure(IllegalStateException("Sign in with your cloud account to view Neon backup status."))
        return sqlClient.fetchBackupStatus(token)
    }

    suspend fun fetchCloudFilesSnapshot(): Result<CloudBackupFilesResponseDto> {
        if (customSqlClient == null && BackendConfig.configurationIssue != null) {
            return Result.failure(IllegalStateException(BackendConfig.configurationIssue))
        }
        val token = resolveIdToken()
            ?: return Result.failure(IllegalStateException("Sign in with your cloud account to fetch cloud backups."))
        return sqlClient.fetchBackupFiles(token)
    }

    suspend fun fetchCloudHistory(): Result<CloudBackupHistoryResponseDto> {
        if (customSqlClient == null && BackendConfig.configurationIssue != null) {
            return Result.failure(IllegalStateException(BackendConfig.configurationIssue))
        }
        val token = resolveIdToken()
            ?: return Result.failure(IllegalStateException("Sign in with your cloud account to load cloud history."))
        return sqlClient.fetchBackupHistory(token)
    }

    suspend fun restoreFromCloud(
        files: List<FileItemEntity>,
        overwriteExisting: Boolean,
        verifiedMpinForKeyRecovery: String? = null,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): Result<Int> {
        val targetIds = files.map { it.id }.takeIf { it.isNotEmpty() }
        val res = restoreFullFromNeon(
            fileIds = targetIds,
            overwriteExisting = overwriteExisting,
            verifiedMpinForKeyRecovery = verifiedMpinForKeyRecovery,
            onProgress = onProgress
        )
        return res.map { it.verifiedCount }
    }

    suspend fun restoreFullFromNeon(
        fileIds: List<String>? = null,
        overwriteExisting: Boolean = false,
        verifiedMpinForKeyRecovery: String? = null,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): Result<RestoreExecutionSummary> = withContext(Dispatchers.IO) {
        if (customSqlClient == null && BackendConfig.configurationIssue != null) {
            return@withContext Result.failure(IllegalStateException(BackendConfig.configurationIssue))
        }
        val token = resolveIdToken()
            ?: return@withContext Result.failure(
                IllegalStateException("Cloud session unavailable. Sign in with Google to restore backups.")
            )

        val startRes = sqlClient.startRestoreJob(
            firebaseIdToken = token,
            fileIds = fileIds,
            deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        if (startRes.isFailure) {
            return@withContext Result.failure(startRes.exceptionOrNull() ?: IllegalStateException("Unable to start restore."))
        }

        val manifest = startRes.getOrThrow()

        // 1. Restore snapshot metadata (Custom categories, Folders, Notes, Saved Links)
        var restoredMetadataCount = 0
        for (catObj in manifest.snapshot.customCategories) {
            val id = catObj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val entity = CustomCategoryEntity(
                id = id,
                name = catObj.optString("name", "Custom"),
                iconKey = catObj.optString("icon_key", "custom"),
                accentHex = catObj.optString("accent_hex", "#00D4FF"),
                createdAt = catObj.optLong("created_at", System.currentTimeMillis())
            )
            db.customCategoryDao().upsert(entity)
            restoredMetadataCount++
        }
        for (folderObj in manifest.snapshot.folders) {
            val id = folderObj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val entity = FolderEntity(
                id = id,
                name = folderObj.optString("name", "Folder"),
                categoryId = folderObj.optString("category_id", DskCategories.CAT_DOCUMENTS),
                parentId = folderObj.optString("parent_id").takeIf { it.isNotBlank() && it != "null" },
                isAutoBackupEnabled = folderObj.optBoolean("is_auto_backup_enabled", true),
                createdAt = folderObj.optLong("created_at", System.currentTimeMillis())
            )
            db.folderDao().upsert(entity)
            restoredMetadataCount++
        }
        for (noteObj in manifest.snapshot.textNotes) {
            val id = noteObj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val entity = TextNoteEntity(
                id = id,
                title = noteObj.optString("title", "Note"),
                content = noteObj.optString("content", ""),
                categoryId = noteObj.optString("category_id", DskCategories.CAT_TEXT),
                isFavorite = noteObj.optBoolean("is_favorite", false),
                createdAt = noteObj.optLong("created_at", System.currentTimeMillis()),
                updatedAt = noteObj.optLong("updated_at", System.currentTimeMillis())
            )
            db.textNoteDao().upsert(entity)
            restoredMetadataCount++
        }
        for (linkObj in manifest.snapshot.savedLinks) {
            val id = linkObj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val entity = SavedLinkEntity(
                id = id,
                linkType = linkObj.optString("link_type", "WEBSITE_LINK"),
                title = linkObj.optString("title", "Link"),
                url = linkObj.optString("url", ""),
                thumbnailUrl = linkObj.optString("thumbnail_url").takeIf { it.isNotBlank() && it != "null" },
                description = linkObj.optString("description", ""),
                categoryId = linkObj.optString("category_id", DskCategories.CAT_WEBSITE_LINKS),
                isFavorite = linkObj.optBoolean("is_favorite", false),
                createdAt = linkObj.optLong("created_at", System.currentTimeMillis()),
                updatedAt = linkObj.optLong("updated_at", System.currentTimeMillis())
            )
            db.savedLinkDao().upsert(entity)
            restoredMetadataCount++
        }

        // 2. If encrypted vault items require key recovery, attempt authenticated key recovery honestly
        var recoveredKeyError: String? = null
        val needsPortableRecovery = manifest.files.any { it.isInVault && it.isEncryptedPayload && it.keyVersion >= 1 }
        if (needsPortableRecovery && prefs.getPortableVaultKeyBase64().isNullOrBlank()) {
            if (manifest.hasWrappedRecoveryKey) {
                val recRes = sqlClient.recoverVaultKey(token, verifiedMpinForKeyRecovery)
                if (recRes.isSuccess) {
                    prefs.saveRecoveredPortableVaultKeyBase64(recRes.getOrThrow())
                } else {
                    recoveredKeyError = recRes.exceptionOrNull()?.localizedMessage
                        ?: "Vault key recovery failed. MPIN and wrapped recovery key are required."
                }
            } else {
                recoveredKeyError =
                    "No wrapped vault encryption key is backed up on server; encrypted vault items cannot be unlocked by MPIN alone."
            }
        }

        // 3. Download every file binary & verify SHA-256 integrity
        var restoredCount = 0
        var verifiedCount = 0
        var failedCount = 0
        var missingKeyFailures = 0
        var bytesRestored = 0L

        val files = manifest.files
        files.forEachIndexed { idx, meta ->
            onProgress(meta.displayName, ((idx.toFloat() + 0.1f) / files.size.coerceAtLeast(1).toFloat()).coerceIn(0.05f, 0.95f))

            val existingLocal = db.fileItemDao().getFileById(meta.fileId)
            val existingPathFile = existingLocal?.localPath?.let { File(it) }
            if (existingPathFile != null && existingPathFile.exists() && !overwriteExisting) {
                // Ensure OCR & reminder metadata still get restored even if binary file already exists
                restoreFileSideMetadata(meta)
                restoredCount++
                verifiedCount++
                return@forEachIndexed
            }

            val destDir = if (meta.isInVault) LocalFileManager.getVaultDir(context) else LocalFileManager.getFilesDir(context)
            val safeName = meta.originalFilename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destFile = File(destDir, if (meta.isInVault) "${meta.fileId}_$safeName.enc" else "${meta.fileId}_$safeName")

            val dlRes = sqlClient.downloadBackupFileContent(
                firebaseIdToken = token,
                fileId = meta.fileId,
                destFile = destFile,
                onProgress = { pct ->
                    val overall = ((idx + pct) / files.size.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    onProgress(meta.displayName, overall)
                }
            )
            if (dlRes.isFailure) {
                destFile.delete()
                failedCount++
                db.backupHistoryDao().insert(
                    BackupHistoryEntity(
                        fileId = meta.fileId,
                        fileName = meta.displayName,
                        action = "RESTORE",
                        status = "FAILED",
                        message = dlRes.exceptionOrNull()?.localizedMessage ?: "Binary download failed."
                    )
                )
                return@forEachIndexed
            }

            val downloaded = dlRes.getOrThrow()
            val expectedSha = meta.sha256.ifBlank { downloaded.sha256Header }
            val actualSha = CryptoManager.sha256File(destFile)
            if (expectedSha.isNotBlank() && !actualSha.equals(expectedSha, ignoreCase = true)) {
                destFile.delete()
                failedCount++
                db.backupHistoryDao().insert(
                    BackupHistoryEntity(
                        fileId = meta.fileId,
                        fileName = meta.displayName,
                        action = "RESTORE",
                        status = "FAILED",
                        message = "SHA-256 integrity verification mismatch; corrupted download discarded."
                    )
                )
                return@forEachIndexed
            }

            // If vault item, confirm decryption key is actually valid (honest encryption key check)
            if (meta.isInVault && (meta.isEncryptedPayload || downloaded.isEncryptedPayload)) {
                val kv = maxOf(meta.keyVersion, downloaded.keyVersion)
                val portableKey = prefs.getPortableVaultKeyBase64()
                val canDecrypt = runCatching {
                    val tempOut = File(context.cacheDir, "verify_vault_${meta.fileId}")
                    val ok = try {
                        if (kv >= 1 && !portableKey.isNullOrBlank()) {
                            destFile.inputStream().use { inp ->
                                tempOut.outputStream().use { out ->
                                    CryptoManager.decryptStreamWithKey(CryptoManager.secretKeyFromBase64(portableKey), inp, out)
                                }
                            }
                            true
                        } else if (kv == 0) {
                            destFile.inputStream().use { inp ->
                                tempOut.outputStream().use { out ->
                                    CryptoManager.decryptStream(inp, out)
                                }
                            }
                            true
                        } else {
                            false
                        }
                    } finally {
                        runCatching { tempOut.delete() }
                    }
                    ok
                }.getOrDefault(false)

                if (!canDecrypt) {
                    destFile.delete()
                    failedCount++
                    missingKeyFailures++
                    val errReason = recoveredKeyError
                        ?: if (kv == 0) {
                            "Encrypted with a previous device's hardware AndroidKeyStore key that cannot be recovered after reinstall."
                        } else {
                            "Portable vault decryption key is missing or MPIN verification did not unlock it."
                        }
                    db.backupHistoryDao().insert(
                        BackupHistoryEntity(
                            fileId = meta.fileId,
                            fileName = meta.displayName,
                            action = "RESTORE",
                            status = "FAILED",
                            message = errReason
                        )
                    )
                    return@forEachIndexed
                }
                prefs.setFileKeyVersion(meta.fileId, kv)
            }

            val restoredEntity = FileItemEntity(
                id = meta.fileId,
                originalFilename = meta.originalFilename,
                displayName = meta.displayName,
                categoryId = meta.categoryId,
                folderId = meta.folderId,
                mimeType = meta.mimeType,
                sizeBytes = meta.sizeBytes,
                localPath = destFile.absolutePath,
                cloudPath = "neon://backup_files/${meta.fileId}",
                cloudBucket = "neon-postgresql",
                backupStatus = BackupStatus.MOBILE_AND_CLOUD,
                sha256 = if (meta.isInVault) (existingLocal?.sha256 ?: actualSha) else actualSha,
                isFavorite = meta.isFavorite,
                isInVault = meta.isInVault,
                isDeleted = false,
                createdAt = meta.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            db.fileItemDao().upsert(restoredEntity)
            restoreFileSideMetadata(meta)

            bytesRestored += destFile.length()
            restoredCount++
            verifiedCount++
            db.backupHistoryDao().insert(
                BackupHistoryEntity(
                    fileId = meta.fileId,
                    fileName = meta.displayName,
                    action = "RESTORE",
                    status = "SUCCESS",
                    bytesTransferred = destFile.length(),
                    message = "Restored from Neon PostgreSQL & verified SHA-256."
                )
            )
        }

        val finalStatus = when {
            files.isEmpty() && restoredMetadataCount > 0 -> "COMPLETED"
            verifiedCount == files.size -> "COMPLETED"
            verifiedCount > 0 || restoredMetadataCount > 0 -> "PARTIAL"
            else -> "FAILED"
        }
        val notice = buildString {
            append("Restored $verifiedCount/${files.size} file(s) & $restoredMetadataCount metadata item(s) with SHA-256 verification.")
            if (missingKeyFailures > 0) {
                append(" $missingKeyFailures encrypted vault item(s) could not be decrypted because the required encryption key is unavailable.")
            }
        }

        sqlClient.completeRestoreJob(
            firebaseIdToken = token,
            restoreId = manifest.restoreId,
            status = finalStatus,
            restoredFiles = restoredCount,
            verifiedFiles = verifiedCount,
            failedFiles = failedCount,
            bytesRestored = bytesRestored,
            message = notice
        )

        onProgress("Restore finished", 1f)
        Result.success(
            RestoreExecutionSummary(
                restoredCount = restoredCount,
                verifiedCount = verifiedCount,
                failedCount = failedCount,
                restoredMetadataCount = restoredMetadataCount,
                missingKeyFailures = missingKeyFailures,
                notice = notice
            )
        )
    }

    private suspend fun restoreFileSideMetadata(meta: CloudBackupFileMetaDto) {
        val mJson = meta.metadataJson ?: return
        mJson.optJSONObject("extraction")?.let { exObj ->
            val entity = DocumentExtractionEntity(
                fileId = meta.fileId,
                documentName = exObj.optString("document_name").takeIf { it.isNotBlank() && it != "null" },
                documentType = exObj.optString("document_type").takeIf { it.isNotBlank() && it != "null" },
                documentNumber = exObj.optString("document_number").takeIf { it.isNotBlank() && it != "null" },
                ownerName = exObj.optString("owner_name").takeIf { it.isNotBlank() && it != "null" },
                fatherName = exObj.optString("father_name").takeIf { it.isNotBlank() && it != "null" },
                motherName = exObj.optString("mother_name").takeIf { it.isNotBlank() && it != "null" },
                dateOfBirth = exObj.optString("date_of_birth").takeIf { it.isNotBlank() && it != "null" },
                issueDate = exObj.optString("issue_date").takeIf { it.isNotBlank() && it != "null" },
                expiryDate = exObj.optString("expiry_date").takeIf { it.isNotBlank() && it != "null" },
                address = exObj.optString("address").takeIf { it.isNotBlank() && it != "null" },
                mobileNumber = exObj.optString("mobile_number").takeIf { it.isNotBlank() && it != "null" },
                organizationName = exObj.optString("organization_name").takeIf { it.isNotBlank() && it != "null" },
                registrationNumber = exObj.optString("registration_number").takeIf { it.isNotBlank() && it != "null" },
                rawText = exObj.optString("raw_text", ""),
                isVerified = exObj.optBoolean("is_verified", false),
                updatedAt = exObj.optLong("updated_at", System.currentTimeMillis())
            )
            db.documentExtractionDao().upsert(entity)
        }
        val extendedJsonStr = mJson.optString("extendedOcrJson", "").takeIf { it.isNotBlank() && it != "null" }
        if (extendedJsonStr != null) {
            ExtendedOcrMetadata.fromJsonString(extendedJsonStr)?.let { extMeta ->
                OcrMetadataStore.save(context, extMeta)
            }
        }
        mJson.optJSONObject("reminder")?.let { remObj ->
            val expiry = remObj.optString("expiry_date_iso", "").trim()
            if (expiry.isNotBlank()) {
                db.documentReminderDao().upsert(
                    DocumentReminderEntity(
                        id = remObj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        fileId = meta.fileId,
                        documentName = remObj.optString("document_name", meta.displayName),
                        expiryDateIso = expiry,
                        remind30Days = remObj.optBoolean("remind_30_days", true),
                        remind15Days = remObj.optBoolean("remind_15_days", true),
                        remind7Days = remObj.optBoolean("remind_7_days", true),
                        remindOnDay = remObj.optBoolean("remind_on_day", true),
                        notificationsEnabled = remObj.optBoolean("notifications_enabled", true)
                    )
                )
            }
        }
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

            val idToken = resolveIdToken().orEmpty()
            if (isBackendConfiguredForClient() && idToken.isNotBlank()) {
                val remoteFiles = sqlClient.fetchBackupFiles(idToken).getOrNull()?.files
                if (remoteFiles != null) {
                    for (meta in remoteFiles) {
                        val localExisting = db.fileItemDao().getFileById(meta.fileId)
                        if (localExisting == null) {
                            db.fileItemDao().upsert(
                                FileItemEntity(
                                    id = meta.fileId,
                                    originalFilename = meta.originalFilename,
                                    displayName = meta.displayName,
                                    categoryId = meta.categoryId,
                                    folderId = meta.folderId,
                                    mimeType = meta.mimeType,
                                    sizeBytes = meta.sizeBytes,
                                    localPath = null,
                                    cloudPath = "neon://backup_files/${meta.fileId}",
                                    cloudBucket = "neon-postgresql",
                                    backupStatus = BackupStatus.CLOUD_ONLY,
                                    sha256 = meta.sha256,
                                    isFavorite = meta.isFavorite,
                                    isInVault = meta.isInVault,
                                    isDeleted = false,
                                    createdAt = meta.createdAt,
                                    updatedAt = meta.updatedAt
                                )
                            )
                        }
                    }
                }
                syncRowIfAuthenticated(
                    "sync_history",
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("summary", "Workspace synced with Neon PostgreSQL + Render Backend")
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
        val readable = resolveReadableFile(item)
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
                OcrMetadataStore.save(context, res.extendedMetadata)
                val nextCategory = if (autoDetectCategory && !res.detectedCategoryId.isNullOrBlank()) {
                    res.detectedCategoryId
                } else item.categoryId
                val nextDisplayName = if (renameWithSuggested && !res.suggestedDisplayName.isNullOrBlank()) {
                    res.suggestedDisplayName
                } else item.displayName
                val settings = db.profileSettingsDao().getSettingsOnce()
                val updatedItem = item.copy(
                    categoryId = nextCategory,
                    displayName = nextDisplayName,
                    backupStatus = if (settings?.autoBackupEnabled == true) BackupStatus.BACKUP_PENDING else item.backupStatus,
                    updatedAt = System.currentTimeMillis()
                )
                db.fileItemDao().upsert(updatedItem)
                syncFileMetadata(updatedItem)
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
                if (settings?.autoBackupEnabled == true) {
                    BackupScheduler.enqueueImmediateBackup(context, settings.wifiOnly)
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
                OcrMetadataStore.save(context, DeterministicDocumentParser.enrichFromEntity(partial))
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

    fun loadExtendedOcrMetadata(extraction: DocumentExtractionEntity): ExtendedOcrMetadata =
        OcrMetadataStore.loadOrReconstruct(context, extraction)

    suspend fun saveVerifiedExtraction(
        item: FileItemEntity,
        extraction: DocumentExtractionEntity,
        applySuggestedDisplayName: Boolean = false,
        extendedMetadata: ExtendedOcrMetadata? = null
    ) {
        val verified = if (extendedMetadata != null) {
            extendedMetadata.toDocumentExtractionEntity(
                rawText = extraction.rawText,
                isVerified = true
            )
        } else {
            extraction.copy(isVerified = true, updatedAt = System.currentTimeMillis())
        }
        db.documentExtractionDao().upsert(verified)

        if (extendedMetadata != null) {
            val finalizedMeta = DeterministicDocumentParser.finalizeScoresAndMissing(
                extendedMetadata.copy(
                    needsReviewFields = emptySet(),
                    stageSummary = "Verified by user"
                ),
                verified.rawText
            )
            OcrMetadataStore.save(context, finalizedMeta)
        } else {
            OcrMetadataStore.save(
                context,
                DeterministicDocumentParser.enrichFromEntity(verified).copy(
                    needsReviewFields = emptySet(),
                    stageSummary = "Verified by user"
                )
            )
        }

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
        val settings = db.profileSettingsDao().getSettingsOnce()
        val updated = item.copy(
            displayName = newDisplayName ?: item.displayName,
            categoryId = detectedCat,
            backupStatus = if (settings?.autoBackupEnabled == true) BackupStatus.BACKUP_PENDING else item.backupStatus,
            updatedAt = System.currentTimeMillis()
        )
        db.fileItemDao().upsert(updated)
        syncFileMetadata(updated)
        syncExtraction(verified)
        if (!verified.expiryDate.isNullOrBlank()) {
            val reminder = DocumentReminderEntity(
                fileId = item.id,
                documentName = newDisplayName ?: verified.documentName ?: item.displayName,
                expiryDateIso = verified.expiryDate
            )
            db.documentReminderDao().upsert(reminder)
            syncDocumentMetadata(reminder, verified.documentType)
            BackupScheduler.scheduleDailyExpiryCheck(context)
        }
        if (settings?.autoBackupEnabled == true) {
            BackupScheduler.enqueueImmediateBackup(context, settings.wifiOnly)
        }
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
        val token = resolveIdToken()
        if (!token.isNullOrBlank() && isBackendConfiguredForClient()) {
            sqlClient.updateBackupSettings(
                token,
                CloudBackupSettingsDto(
                    cloudBackupEnabled = updated.autoBackupEnabled,
                    wifiOnly = updated.wifiOnly,
                    backupPhotos = updated.backupPhotos,
                    backupVideos = updated.backupVideos,
                    backupDocuments = updated.backupDocuments
                )
            )
        }
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
        if (!isBackendConfiguredForClient()) return
        val token = resolveIdToken().orEmpty()
        if (token.isNotBlank()) {
            sqlClient.upsertRow(table, token, json)
        }
    }

    private suspend fun deleteRemoteRowIfAuthenticated(table: String, recordId: String) {
        if (!isBackendConfiguredForClient()) return
        val token = resolveIdToken().orEmpty()
        if (token.isNotBlank()) {
            sqlClient.deleteRow(table, token, recordId)
        }
    }
}
