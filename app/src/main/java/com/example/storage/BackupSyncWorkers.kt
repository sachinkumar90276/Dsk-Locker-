package com.example.storage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.local.BackupHistoryEntity
import com.example.data.local.BackupStatus
import com.example.data.local.DskCategories
import com.example.data.local.DskLockerDatabase
import com.example.data.remote.FirebasePhoneAuthManager
import com.example.data.remote.FirebaseStorageManager
import com.example.data.remote.FirebaseStoragePaths
import com.example.data.remote.SqlBackendClient
import com.example.security.CryptoManager
import com.example.security.SecurePrefs
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = DskLockerDatabase.getInstance(applicationContext)
        val prefs = SecurePrefs(applicationContext)
        val settings = db.profileSettingsDao().getSettingsOnce()
        val authManager = FirebasePhoneAuthManager()
        val firebaseUser = authManager.currentUser()
        val uid = firebaseUser?.uid ?: return Result.success()
        val idToken = authManager.getFreshIdToken(false).orEmpty()

        val storage = FirebaseStorageManager()
        val sqlClient = SqlBackendClient()
        val pendingFiles = db.fileItemDao().getPendingBackupFiles()
        val folderMap = db.folderDao().getFoldersOnce().associateBy { it.id }

        for (fileItem in pendingFiles) {
            val isManual = inputData.getBoolean("manual", false)
            if (!isManual) {
                val folderDisabled = fileItem.folderId?.let { folderMap[it]?.isAutoBackupEnabled == false } == true
                if (folderDisabled) continue
                if (settings != null) {
                    val allowCat = when (fileItem.categoryId) {
                        DskCategories.CAT_PHOTOS -> settings.backupPhotos
                        DskCategories.CAT_VIDEOS -> settings.backupVideos
                        DskCategories.CAT_PDF, DskCategories.CAT_WORD, DskCategories.CAT_EXCEL,
                        DskCategories.CAT_TEXT, DskCategories.CAT_PERSONAL_RECORDS -> settings.backupDocuments
                        else -> true
                    }
                    if (!allowCat) continue
                }
            }

            val localFile = fileItem.localPath?.let { File(it) }
            if (localFile == null || !localFile.exists()) continue

            val cloudPath = FirebaseStoragePaths.buildPrivateObjectPath(
                firebaseUid = uid,
                fileId = fileItem.id,
                originalFilename = fileItem.originalFilename,
                categoryId = fileItem.categoryId,
                isInVault = fileItem.isInVault
            )
            val bytes = localFile.readBytes()
            val checksum = if (fileItem.isInVault) CryptoManager.sha256Bytes(bytes) else fileItem.sha256
            val uploadRes = storage.uploadBytes(
                objectPath = cloudPath,
                bytes = bytes,
                mimeType = if (fileItem.isInVault) "application/octet-stream" else fileItem.mimeType,
                sha256 = checksum
            )

            if (uploadRes.isSuccess) {
                val bucket = storage.bucketName()
                val updated = fileItem.copy(
                    cloudPath = cloudPath,
                    cloudBucket = bucket,
                    backupStatus = BackupStatus.MOBILE_AND_CLOUD,
                    updatedAt = System.currentTimeMillis()
                )
                db.fileItemDao().upsert(updated)

                if (idToken.isNotBlank()) {
                    val metaJson = JSONObject()
                        .put("id", updated.id)
                        .put("original_filename", updated.originalFilename)
                        .put("display_name", updated.displayName)
                        .put("category_id", updated.categoryId)
                        .put("folder_id", updated.folderId)
                        .put("mime_type", updated.mimeType)
                        .put("size_bytes", updated.sizeBytes)
                        .put("cloud_path", cloudPath)
                        .put("cloud_bucket", bucket)
                        .put("backup_status", updated.backupStatus.name)
                        .put("sha256", checksum)
                        .put("is_favorite", updated.isFavorite)
                        .put("is_in_vault", updated.isInVault)
                        .put("is_deleted", updated.isDeleted)
                        .put("created_at", updated.createdAt)
                        .put("updated_at", updated.updatedAt)
                    sqlClient.upsertRow("files", idToken, metaJson)

                    sqlClient.upsertRow(
                        "file_versions",
                        idToken,
                        JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("file_id", updated.id)
                            .put("version_number", 1)
                            .put("cloud_path", cloudPath)
                            .put("size_bytes", updated.sizeBytes)
                            .put("sha256", checksum)
                            .put("created_at", updated.updatedAt)
                    )
                }

                val history = BackupHistoryEntity(
                    fileId = updated.id,
                    fileName = updated.displayName,
                    action = if (isManual) "MANUAL_BACKUP" else "AUTO_BACKUP",
                    status = "SUCCESS",
                    bytesTransferred = updated.sizeBytes,
                    message = "Uploaded to Firebase Storage ($cloudPath) & verified SHA-256."
                )
                db.backupHistoryDao().insert(history)
                if (idToken.isNotBlank()) {
                    sqlClient.upsertRow(
                        "backup_history",
                        idToken,
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
                }
                prefs.lastBackupMs = System.currentTimeMillis()
            } else {
                val err = uploadRes.exceptionOrNull()?.localizedMessage ?: "Firebase Storage upload failed"
                db.fileItemDao().upsert(fileItem.copy(backupStatus = BackupStatus.BACKUP_FAILED))
                db.backupHistoryDao().insert(
                    BackupHistoryEntity(
                        fileId = fileItem.id,
                        fileName = fileItem.displayName,
                        action = if (isManual) "MANUAL_BACKUP" else "AUTO_BACKUP",
                        status = "FAILED",
                        message = err
                    )
                )
            }
        }
        return Result.success()
    }
}

class ExpiryReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = DskLockerDatabase.getInstance(applicationContext)
        val settings = db.profileSettingsDao().getSettingsOnce()
        if (settings?.notificationsEnabled == false) return Result.success()

        val reminders = db.documentReminderDao().getAllOnce()
        val today = LocalDate.now()

        reminders.filter { it.notificationsEnabled }.forEach { rem ->
            val expiry = parseDate(rem.expiryDateIso) ?: return@forEach
            val daysUntil = ChronoUnit.DAYS.between(today, expiry).toInt()
            val shouldNotify = (daysUntil == 30 && rem.remind30Days) ||
                (daysUntil == 15 && rem.remind15Days) ||
                (daysUntil == 7 && rem.remind7Days) ||
                (daysUntil == 0 && rem.remindOnDay) ||
                (daysUntil in 1..6 && rem.remind7Days)

            if (shouldNotify) {
                val label = when {
                    daysUntil <= 0 -> "expires TODAY (${rem.expiryDateIso})"
                    else -> "expires in $daysUntil day(s) (${rem.expiryDateIso})"
                }
                DskNotificationHelper.postExpiryAlert(
                    applicationContext,
                    rem.id.hashCode(),
                    "Document Expiry Alert",
                    "${rem.documentName} $label."
                )
            }
        }
        return Result.success()
    }

    private fun parseDate(raw: String): LocalDate? {
        val formats = listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "MM/dd/yyyy")
        for (fmt in formats) {
            val parsed = runCatching { LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern(fmt)) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}

object DskNotificationHelper {
    private const val CHANNEL_ID = "dsk_locker_alerts"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DSK LOCKER Security & Expiry Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Document expiry reminders and cloud backup alerts"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun postExpiryAlert(context: Context, notificationId: Int, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}

object BackupScheduler {
    fun schedulePeriodicAutoBackup(context: Context, enabled: Boolean, wifiOnly: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork("dsk_periodic_backup")
            return
        }
        val netType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(netType)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(
            "dsk_periodic_backup",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueImmediateBackup(context: Context, wifiOnly: Boolean) {
        val netType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(netType).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "dsk_manual_backup",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    fun scheduleDailyExpiryCheck(context: Context) {
        val req = PeriodicWorkRequestBuilder<ExpiryReminderWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "dsk_expiry_reminders",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }
}
