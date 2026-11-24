package com.example.data.remote

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class FetchedLinkMetadata(
    val title: String?,
    val description: String?,
    val thumbnailUrl: String?,
    val pageContent: String? = null
)

data class CloudBackupSettingsDto(
    val cloudBackupEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val backupPhotos: Boolean = true,
    val backupVideos: Boolean = true,
    val backupDocuments: Boolean = true
)

data class CloudRestoreRecordDto(
    val id: String,
    val status: String,
    val totalFiles: Int,
    val restoredFiles: Int,
    val verifiedFiles: Int,
    val failedFiles: Int,
    val bytesRestored: Long,
    val message: String,
    val startedAt: Long,
    val completedAt: Long?
)

data class CloudBackupStatusDto(
    val userId: String,
    val lastBackupId: String?,
    val lastBackupAt: Long?,
    val lastBackupStatus: String,
    val backedUpFilesCount: Int,
    val totalBackupSizeBytes: Long,
    val photosCount: Int,
    val pdfsCount: Int,
    val documentsCount: Int,
    val videosCount: Int,
    val vaultFilesCount: Int,
    val notesCount: Int,
    val foldersCount: Int,
    val categoriesCount: Int,
    val linksCount: Int,
    val quotaLimitBytes: Long,
    val maxFileBytes: Long,
    val neonDbUsedBytes: Long,
    val neonDbLimitBytes: Long,
    val hasWrappedRecoveryKey: Boolean,
    val vaultKeyVersion: Int,
    val keyRecoveryMode: String?,
    val settings: CloudBackupSettingsDto,
    val latestRestore: CloudRestoreRecordDto?
)

data class CloudBackupFileMetaDto(
    val fileId: String,
    val originalFilename: String,
    val displayName: String,
    val categoryId: String,
    val folderId: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val isInVault: Boolean,
    val isFavorite: Boolean,
    val isEncryptedPayload: Boolean,
    val keyVersion: Int,
    val metadataJson: JSONObject?,
    val createdAt: Long,
    val updatedAt: Long
)

data class CloudSnapshotDto(
    val folders: List<JSONObject>,
    val customCategories: List<JSONObject>,
    val textNotes: List<JSONObject>,
    val savedLinks: List<JSONObject>
)

data class CloudBackupFilesResponseDto(
    val files: List<CloudBackupFileMetaDto>,
    val snapshot: CloudSnapshotDto
)

data class CloudBackupHistoryEntryDto(
    val id: String,
    val fileId: String?,
    val fileName: String,
    val action: String,
    val status: String,
    val bytesTransferred: Long,
    val message: String,
    val timestamp: Long
)

data class CloudBackupHistoryResponseDto(
    val backups: List<CloudBackupHistoryEntryDto>,
    val restores: List<CloudRestoreRecordDto>
)

data class CloudRestoreManifestDto(
    val restoreId: String,
    val totalFiles: Int,
    val totalBytes: Long,
    val files: List<CloudBackupFileMetaDto>,
    val snapshot: CloudSnapshotDto,
    val hasWrappedRecoveryKey: Boolean,
    val keyRecoveryMode: String?
)

data class DownloadedBackupBinary(
    val file: File,
    val sha256Header: String,
    val isEncryptedPayload: Boolean,
    val keyVersion: Int
)

data class CloudBackupUploadFile(
    val fileId: String,
    val originalFilename: String,
    val displayName: String,
    val categoryId: String,
    val folderId: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val isInVault: Boolean,
    val isFavorite: Boolean,
    val isEncryptedPayload: Boolean,
    val keyVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val bytes: ByteArray,
    val extractionJson: JSONObject? = null,
    val extendedOcrJson: String? = null,
    val reminderJson: JSONObject? = null
)

data class CloudBackupCreateResultDto(
    val backupId: String,
    val storedCount: Int,
    val backedUpDocuments: Int,
    val totalBackupBytes: Long,
    val quotaLimitBytes: Long,
    val lastBackupAt: Long
)

data class BackendHealthStatusDto(
    val reachable: Boolean,
    val service: String,
    val status: String,
    val missingEnv: List<String>,
    val baseUrl: String,
    val httpCode: Int = 200
)

class TransientBackendHttpException(
    val httpCode: Int,
    message: String
) : IOException(message)

class SqlBackendClient(
    private val baseUrlProvider: () -> String = { BackendConfig.apiBaseUrl }
) {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val jsonMedia by lazy { "application/json; charset=utf-8".toMediaType() }

    private fun resolvedBaseUrl(): String = baseUrlProvider().trimEnd('/')

    private fun requireConfiguredUrl(): String {
        val url = resolvedBaseUrl()
        if (url.isBlank()) {
            val issue = BackendConfig.configurationIssue
            throw IllegalStateException(
                issue ?: "Render backend URL missing: configure BACKEND_API_URL to ${BackendConfig.LIVE_RENDER_API_URL}."
            )
        }
        if (BackendConfig.isSupabaseUrl(url)) {
            throw IllegalStateException("Supabase URLs are disabled. Configure BACKEND_API_URL to ${BackendConfig.LIVE_RENDER_API_URL}.")
        }
        return url
    }

    private fun isTransientHttpCode(code: Int): Boolean =
        code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504

    private fun throwHttpFailure(code: Int, body: String, fallback: String): Nothing {
        val parsed = parseError(body, fallback)
        if (isTransientHttpCode(code)) {
            throw TransientBackendHttpException(code, parsed)
        }
        throw IllegalStateException(parsed)
    }

    private fun executeWithColdStartRetry(requestBuilder: () -> Request): okhttp3.Response {
        var lastException: Exception? = null
        val retryDelaysMs = longArrayOf(1000L, 2000L)
        for (attempt in 0..2) {
            try {
                val resp = http.newCall(requestBuilder()).execute()
                if (attempt < 2 && (resp.code == 429 || resp.code == 502 || resp.code == 503 || resp.code == 504)) {
                    resp.close()
                    Thread.sleep(retryDelaysMs[attempt])
                    continue
                }
                return resp
            } catch (e: IOException) {
                lastException = e
                if (attempt < 2) {
                    Thread.sleep(retryDelaysMs[attempt])
                }
            }
        }
        throw IOException(formatNetworkError(lastException), lastException)
    }

    private fun formatNetworkError(err: Throwable?): String {
        val msg = err?.message.orEmpty()
        return when {
            err is java.net.UnknownHostException ->
                "Unable to resolve backend host. Check your internet connection."
            err is java.net.SocketTimeoutException ->
                "Render backend took too long to respond (waking up from sleep). Please tap Retry in a few seconds."
            err is java.net.ConnectException ->
                "Unable to reach DSK LOCKER backend (${resolvedBaseUrl()}). Verify internet connectivity."
            msg.isNotBlank() ->
                "Network connection failed: $msg"
            else ->
                "Network connection failed while reaching DSK LOCKER backend."
        }
    }

    suspend fun checkBackendHealth(): Result<BackendHealthStatusDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            executeWithColdStartRetry {
                Request.Builder().url("$url/").get().build()
            }.use { rootResp ->
                if (!rootResp.isSuccessful) {
                    throw IllegalStateException("Root endpoint check failed (${rootResp.code}).")
                }
            }
            executeWithColdStartRetry {
                Request.Builder().url("$url/health").get().build()
            }.use { resp ->
                val str = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(str) }.getOrNull()
                val missing = mutableListOf<String>()
                json?.optJSONArray("missingEnv")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let { missing.add(it) }
                    }
                }
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseError(str, "Health check returned HTTP ${resp.code}."))
                }
                BackendHealthStatusDto(
                    reachable = true,
                    service = json?.optString("service").orEmpty().ifBlank { "DSK LOCKER Backend" },
                    status = json?.optString("status").orEmpty().ifBlank { "ok" },
                    missingEnv = missing,
                    baseUrl = url,
                    httpCode = resp.code
                )
            }
        }
    }

    suspend fun registerOrVerifyOwner(
        firebaseIdToken: String,
        phoneE164: String,
        ownerName: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = resolvedBaseUrl()
        if (url.isBlank() || firebaseIdToken.isBlank()) {
            return@withContext Result.success(Unit)
        }
        runCatching {
            val payload = JSONObject()
            if (phoneE164.isNotBlank()) payload.put("phoneNumber", phoneE164)
            if (!ownerName.isNullOrBlank()) payload.put("ownerName", ownerName)

            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/auth/register-owner")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, resp.body?.string().orEmpty(), "Owner registration denied (${resp.code}).")
                }
            }
        }
    }

    suspend fun syncMpinSecurityHash(
        firebaseIdToken: String,
        mpinHash: String,
        mpinSalt: String,
        mpinLength: Int = 6,
        biometricEnabled: Boolean? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = resolvedBaseUrl()
        if (url.isBlank() || firebaseIdToken.isBlank()) {
            return@withContext Result.success(Unit)
        }
        runCatching {
            val payload = JSONObject()
                .put("mpinHash", mpinHash)
                .put("mpinSalt", mpinSalt)
                .put("mpinLength", mpinLength)
            if (biometricEnabled != null) payload.put("biometricEnabled", biometricEnabled)

            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/security/mpin")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .addHeader("Content-Type", "application/json")
                    .put(payload.toString().toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, resp.body?.string().orEmpty(), "Security hash sync failed (${resp.code}).")
                }
            }
        }
    }

    suspend fun verifyServerMpin(
        firebaseIdToken: String,
        mpin: String,
        purpose: String = "RESTORE"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            val payload = JSONObject()
                .put("mpin", mpin)
                .put("purpose", purpose)
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/security/mpin/verify")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, str, "MPIN verification denied (${resp.code}).")
                }
            }
        }
    }

    suspend fun wrapRecoveryKey(
        firebaseIdToken: String,
        vaultKeyBase64: String,
        keyVersion: Int = 1,
        mpin: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            val payload = JSONObject()
                .put("action", "wrap")
                .put("vaultKeyBase64", vaultKeyBase64)
                .put("keyVersion", keyVersion)
            if (!mpin.isNullOrBlank()) payload.put("mpin", mpin)
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/security/recovery-key")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, str, "Failed to wrap recovery key (${resp.code}).")
                }
            }
        }
    }

    suspend fun recoverVaultKey(
        firebaseIdToken: String,
        mpin: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            val payload = JSONObject().put("action", "recover")
            if (!mpin.isNullOrBlank()) payload.put("mpin", mpin)
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/security/recovery-key")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, str, "Encryption key recovery failed (${resp.code}).")
                }
                val json = JSONObject(str)
                json.getString("vaultKeyBase64")
            }
        }
    }

    // --- Neon PostgreSQL + Render Backup Endpoints ---
    suspend fun createBackup(
        firebaseIdToken: String,
        action: String,
        deviceInfo: String,
        totalDocuments: Int,
        pendingDocuments: Int,
        failedDocuments: Int,
        files: List<CloudBackupUploadFile>,
        snapshot: CloudSnapshotDto?
    ): Result<CloudBackupCreateResultDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            val filesArr = JSONArray()
            for (f in files) {
                val obj = JSONObject()
                    .put("fileId", f.fileId)
                    .put("originalFilename", f.originalFilename)
                    .put("displayName", f.displayName)
                    .put("categoryId", f.categoryId)
                    .put("folderId", f.folderId)
                    .put("mimeType", f.mimeType)
                    .put("sizeBytes", f.sizeBytes)
                    .put("sha256", f.sha256)
                    .put("isInVault", f.isInVault)
                    .put("isFavorite", f.isFavorite)
                    .put("isEncryptedPayload", f.isEncryptedPayload)
                    .put("keyVersion", f.keyVersion)
                    .put("createdAt", f.createdAt)
                    .put("updatedAt", f.updatedAt)
                    .put("fileContentBase64", Base64.encodeToString(f.bytes, Base64.NO_WRAP))
                if (f.extractionJson != null) obj.put("extraction", f.extractionJson)
                if (!f.extendedOcrJson.isNullOrBlank()) obj.put("extendedOcrJson", f.extendedOcrJson)
                if (f.reminderJson != null) obj.put("reminder", f.reminderJson)
                filesArr.put(obj)
            }

            val root = JSONObject()
                .put("action", action)
                .put("deviceInfo", deviceInfo)
                .put("totalDocuments", totalDocuments)
                .put("pendingDocuments", pendingDocuments)
                .put("failedDocuments", failedDocuments)
                .put("files", filesArr)

            if (snapshot != null) {
                root.put(
                    "snapshot",
                    JSONObject()
                        .put("folders", JSONArray().apply { snapshot.folders.forEach { put(it) } })
                        .put("customCategories", JSONArray().apply { snapshot.customCategories.forEach { put(it) } })
                        .put("textNotes", JSONArray().apply { snapshot.textNotes.forEach { put(it) } })
                        .put("savedLinks", JSONArray().apply { snapshot.savedLinks.forEach { put(it) } })
                )
            }

            val jsonPayload = root.toString()
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/backup/create")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonPayload.toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, bodyStr, "Cloud backup upload failed (${resp.code}).")
                }
                val json = JSONObject(bodyStr)
                CloudBackupCreateResultDto(
                    backupId = json.optString("backupId", ""),
                    storedCount = json.optInt("storedCount", files.size),
                    backedUpDocuments = json.optInt("backedUpDocuments", 0),
                    totalBackupBytes = json.optLong("totalBackupBytes", 0L),
                    quotaLimitBytes = json.optLong("quotaLimitBytes", 0L),
                    lastBackupAt = json.optLong("lastBackupAt", System.currentTimeMillis())
                )
            }
        }
    }

    suspend fun fetchBackupStatus(firebaseIdToken: String): Result<CloudBackupStatusDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            val req = Request.Builder()
                .url("$url/api/backup/status")
                .addHeader("Authorization", "Bearer $firebaseIdToken")
                .get()
                .build()
            executeWithColdStartRetry { req }.use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseError(str, "Failed to load backup status (${resp.code})."))
                }
                val json = JSONObject(str)
                val settingsObj = json.optJSONObject("settings") ?: JSONObject()
                val latestRestore = json.optJSONObject("latestRestore")?.let { parseRestoreRecord(it) }
                CloudBackupStatusDto(
                    userId = json.optString("userId", ""),
                    lastBackupId = json.optNull("lastBackupId"),
                    lastBackupAt = json.optLong("lastBackupAt", 0L).takeIf { it > 0L },
                    lastBackupStatus = json.optString("lastBackupStatus", "NONE"),
                    backedUpFilesCount = json.optInt("backedUpFilesCount", 0),
                    totalBackupSizeBytes = json.optLong("totalBackupSizeBytes", 0L),
                    photosCount = json.optInt("photosCount", 0),
                    pdfsCount = json.optInt("pdfsCount", 0),
                    documentsCount = json.optInt("documentsCount", 0),
                    videosCount = json.optInt("videosCount", 0),
                    vaultFilesCount = json.optInt("vaultFilesCount", 0),
                    notesCount = json.optInt("notesCount", 0),
                    foldersCount = json.optInt("foldersCount", 0),
                    categoriesCount = json.optInt("categoriesCount", 0),
                    linksCount = json.optInt("linksCount", 0),
                    quotaLimitBytes = json.optLong("quotaLimitBytes", 471_859_200L),
                    maxFileBytes = json.optLong("maxFileBytes", 20_971_520L),
                    neonDbUsedBytes = json.optLong("neonDbUsedBytes", 0L),
                    neonDbLimitBytes = json.optLong("neonDbLimitBytes", 536_870_912L),
                    hasWrappedRecoveryKey = json.optBoolean("hasWrappedRecoveryKey", false),
                    vaultKeyVersion = json.optInt("vaultKeyVersion", 0),
                    keyRecoveryMode = json.optNull("keyRecoveryMode"),
                    settings = CloudBackupSettingsDto(
                        cloudBackupEnabled = settingsObj.optBoolean("cloudBackupEnabled", true),
                        wifiOnly = settingsObj.optBoolean("wifiOnly", true),
                        backupPhotos = settingsObj.optBoolean("backupPhotos", true),
                        backupVideos = settingsObj.optBoolean("backupVideos", true),
                        backupDocuments = settingsObj.optBoolean("backupDocuments", true)
                    ),
                    latestRestore = latestRestore
                )
            }
        }
    }

    suspend fun fetchBackupHistory(firebaseIdToken: String): Result<CloudBackupHistoryResponseDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            val req = Request.Builder()
                .url("$url/api/backup/history")
                .addHeader("Authorization", "Bearer $firebaseIdToken")
                .get()
                .build()
            executeWithColdStartRetry { req }.use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseError(str, "Failed to load backup history (${resp.code})."))
                }
                val json = JSONObject(str)
                val backups = mutableListOf<CloudBackupHistoryEntryDto>()
                json.optJSONArray("backups")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        backups.add(
                            CloudBackupHistoryEntryDto(
                                id = o.optString("id"),
                                fileId = o.optNull("file_id"),
                                fileName = o.optString("file_name", "Backup Event"),
                                action = o.optString("action", "BACKUP"),
                                status = o.optString("status", "SUCCESS"),
                                bytesTransferred = o.optLong("bytes_transferred", 0L),
                                message = o.optString("message", ""),
                                timestamp = o.optLong("timestamp", 0L)
                            )
                        )
                    }
                }
                val restores = mutableListOf<CloudRestoreRecordDto>()
                json.optJSONArray("restores")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { restores.add(parseRestoreRecord(it)) }
                    }
                }
                CloudBackupHistoryResponseDto(backups = backups, restores = restores)
            }
        }
    }

    suspend fun fetchBackupFiles(firebaseIdToken: String): Result<CloudBackupFilesResponseDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            val req = Request.Builder()
                .url("$url/api/backup/files")
                .addHeader("Authorization", "Bearer $firebaseIdToken")
                .get()
                .build()
            executeWithColdStartRetry { req }.use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseError(str, "Failed to load cloud backup files (${resp.code})."))
                }
                val json = JSONObject(str)
                CloudBackupFilesResponseDto(
                    files = parseFileMetaArray(json.optJSONArray("files")),
                    snapshot = parseSnapshot(json.optJSONObject("snapshot"))
                )
            }
        }
    }

    suspend fun downloadBackupFileContent(
        firebaseIdToken: String,
        fileId: String,
        destFile: File,
        onProgress: (Float) -> Unit = {}
    ): Result<DownloadedBackupBinary> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            destFile.parentFile?.mkdirs()
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/backup/files/${URLEncoder.encode(fileId, "UTF-8")}/content")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .get()
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    val str = resp.body?.string().orEmpty()
                    throwHttpFailure(resp.code, str, "Failed to download backed-up file (${resp.code}).")
                }
                val body = resp.body ?: throw IllegalStateException("Empty download response.")
                val total = body.contentLength().coerceAtLeast(1L)
                body.byteStream().use { input ->
                    destFile.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var copied = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            copied += read
                            onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
                onProgress(1f)
                DownloadedBackupBinary(
                    file = destFile,
                    sha256Header = resp.header("X-File-Sha256").orEmpty(),
                    isEncryptedPayload = resp.header("X-Is-Encrypted-Payload")?.toBooleanStrictOrNull() ?: false,
                    keyVersion = resp.header("X-Key-Version")?.toIntOrNull() ?: 0
                )
            }
        }
    }

    suspend fun deleteCloudBackupFile(
        firebaseIdToken: String,
        fileId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = resolvedBaseUrl()
        if (url.isBlank() || firebaseIdToken.isBlank()) return@withContext Result.success(Unit)
        runCatching {
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/backup/files/${URLEncoder.encode(fileId, "UTF-8")}")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .delete()
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, resp.body?.string().orEmpty(), "Cloud delete failed (${resp.code}).")
                }
            }
        }
    }

    suspend fun startRestoreJob(
        firebaseIdToken: String,
        fileIds: List<String>? = null,
        deviceInfo: String = "Android DSK LOCKER"
    ): Result<CloudRestoreManifestDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            val payload = JSONObject().put("deviceInfo", deviceInfo)
            if (!fileIds.isNullOrEmpty()) {
                payload.put("fileIds", JSONArray().apply { fileIds.forEach { put(it) } })
            }
            val bodyStr = payload.toString()
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/backup/restore")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .addHeader("Content-Type", "application/json")
                    .post(bodyStr.toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, str, "Restore start failed (${resp.code}).")
                }
                val json = JSONObject(str)
                CloudRestoreManifestDto(
                    restoreId = json.getString("restoreId"),
                    totalFiles = json.optInt("totalFiles", 0),
                    totalBytes = json.optLong("totalBytes", 0L),
                    files = parseFileMetaArray(json.optJSONArray("files")),
                    snapshot = parseSnapshot(json.optJSONObject("snapshot")),
                    hasWrappedRecoveryKey = json.optBoolean("hasWrappedRecoveryKey", false),
                    keyRecoveryMode = json.optNull("keyRecoveryMode")
                )
            }
        }
    }

    suspend fun completeRestoreJob(
        firebaseIdToken: String,
        restoreId: String,
        status: String,
        restoredFiles: Int,
        verifiedFiles: Int,
        failedFiles: Int,
        bytesRestored: Long,
        message: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireConfiguredUrl()
            val payload = JSONObject()
                .put("restoreId", restoreId)
                .put("status", status)
                .put("restoredFiles", restoredFiles)
                .put("verifiedFiles", verifiedFiles)
                .put("failedFiles", failedFiles)
                .put("bytesRestored", bytesRestored)
                .put("message", message)
            val bodyStr = payload.toString()
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/backup/restore/complete")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .addHeader("Content-Type", "application/json")
                    .post(bodyStr.toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, resp.body?.string().orEmpty(), "Restore completion sync failed (${resp.code}).")
                }
            }
        }
    }

    suspend fun updateBackupSettings(
        firebaseIdToken: String,
        settings: CloudBackupSettingsDto
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = resolvedBaseUrl()
        if (url.isBlank() || firebaseIdToken.isBlank()) return@withContext Result.success(Unit)
        runCatching {
            val payload = JSONObject()
                .put("cloudBackupEnabled", settings.cloudBackupEnabled)
                .put("wifiOnly", settings.wifiOnly)
                .put("backupPhotos", settings.backupPhotos)
                .put("backupVideos", settings.backupVideos)
                .put("backupDocuments", settings.backupDocuments)
            val bodyStr = payload.toString()
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/backup/settings")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .addHeader("Content-Type", "application/json")
                    .put(bodyStr.toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, resp.body?.string().orEmpty(), "Backup settings update failed (${resp.code}).")
                }
            }
        }
    }

    suspend fun upsertRow(
        table: String,
        firebaseIdToken: String,
        rowJson: JSONObject
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = resolvedBaseUrl()
        if (url.isBlank() || firebaseIdToken.isBlank()) {
            return@withContext Result.success(Unit)
        }
        runCatching {
            val bodyStr = rowJson.toString()
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/sync/$table")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .addHeader("Content-Type", "application/json")
                    .post(bodyStr.toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, resp.body?.string().orEmpty(), "SQL sync $table failed (${resp.code}).")
                }
            }
        }
    }

    suspend fun deleteRow(
        table: String,
        firebaseIdToken: String,
        recordId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = resolvedBaseUrl()
        if (url.isBlank() || firebaseIdToken.isBlank()) {
            return@withContext Result.success(Unit)
        }
        runCatching {
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/sync/$table/${URLEncoder.encode(recordId, "UTF-8")}")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .delete()
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, resp.body?.string().orEmpty(), "SQL delete $table failed (${resp.code}).")
                }
            }
        }
    }

    suspend fun fetchRemoteFiles(firebaseIdToken: String): Result<JSONArray> = withContext(Dispatchers.IO) {
        val url = resolvedBaseUrl()
        if (url.isBlank() || firebaseIdToken.isBlank()) {
            return@withContext Result.success(JSONArray())
        }
        runCatching {
            executeWithColdStartRetry {
                Request.Builder()
                    .url("$url/api/files")
                    .addHeader("Authorization", "Bearer $firebaseIdToken")
                    .get()
                    .build()
            }.use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throwHttpFailure(resp.code, str, "Fetch SQL files failed (${resp.code}).")
                }
                JSONArray(str.ifBlank { "[]" })
            }
        }
    }

    suspend fun fetchLinkMetadata(rawUrl: String): FetchedLinkMetadata = withContext(Dispatchers.IO) {
        val normalized = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl"
        }

        if (normalized.contains("youtube.com", ignoreCase = true) ||
            normalized.contains("youtu.be", ignoreCase = true)
        ) {
            val ytResult = runCatching {
                val encoded = URLEncoder.encode(normalized, "UTF-8")
                val req = Request.Builder()
                    .url("https://www.youtube.com/oembed?url=$encoded&format=json")
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        FetchedLinkMetadata(
                            title = json.optString("title").takeIf { it.isNotBlank() },
                            description = json.optString("author_name").takeIf { it.isNotBlank() }?.let { "Channel: $it" },
                            thumbnailUrl = json.optString("thumbnail_url").takeIf { it.isNotBlank() }
                        )
                    } else null
                }
            }.getOrNull()
            if (ytResult != null) return@withContext ytResult
        }

        runCatching {
            val req = Request.Builder()
                .url(normalized)
                .addHeader("User-Agent", "Mozilla/5.0 (Android) DSKLocker/1.0")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching FetchedLinkMetadata(null, null, null)
                val html = resp.body?.string()?.take(65_536).orEmpty()
                val ogTitle = extractMetaContent(html, "og:title")
                    ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(html)?.groupValues?.getOrNull(1)?.trim()
                val ogDesc = extractMetaContent(html, "og:description")
                    ?: extractMetaContent(html, "description")
                val ogImage = extractMetaContent(html, "og:image")
                val visibleText = extractVisibleHtmlText(html)
                FetchedLinkMetadata(
                    title = ogTitle?.replace(Regex("\\s+"), " ")?.take(140),
                    description = ogDesc?.replace(Regex("\\s+"), " ")?.take(300),
                    thumbnailUrl = ogImage,
                    pageContent = visibleText
                )
            }
        }.getOrDefault(FetchedLinkMetadata(null, null, null, null))
    }

    private fun parseFileMetaArray(arr: JSONArray?): List<CloudBackupFileMetaDto> {
        if (arr == null) return emptyList()
        val list = mutableListOf<CloudBackupFileMetaDto>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                CloudBackupFileMetaDto(
                    fileId = o.optString("file_id", o.optString("id")),
                    originalFilename = o.optString("original_filename", "document"),
                    displayName = o.optString("display_name", "document"),
                    categoryId = o.optString("category_id", "documents"),
                    folderId = o.optNull("folder_id"),
                    mimeType = o.optString("mime_type", "application/octet-stream"),
                    sizeBytes = o.optLong("size_bytes", 0L),
                    sha256 = o.optString("sha256", ""),
                    isInVault = o.optBoolean("is_in_vault", false),
                    isFavorite = o.optBoolean("is_favorite", false),
                    isEncryptedPayload = o.optBoolean("is_encrypted_payload", false),
                    keyVersion = o.optInt("key_version", 0),
                    metadataJson = o.optJSONObject("metadata_json") ?: o.optNull("metadata_json")?.let {
                        runCatching { JSONObject(it) }.getOrNull()
                    },
                    createdAt = o.optLong("created_at", 0L),
                    updatedAt = o.optLong("updated_at", 0L)
                )
            )
        }
        return list
    }

    private fun parseSnapshot(obj: JSONObject?): CloudSnapshotDto {
        if (obj == null) return CloudSnapshotDto(emptyList(), emptyList(), emptyList(), emptyList())
        return CloudSnapshotDto(
            folders = obj.optJSONArray("folders").toJsonObjectList(),
            customCategories = obj.optJSONArray("customCategories").toJsonObjectList(),
            textNotes = obj.optJSONArray("textNotes").toJsonObjectList(),
            savedLinks = obj.optJSONArray("savedLinks").toJsonObjectList()
        )
    }

    private fun parseRestoreRecord(o: JSONObject): CloudRestoreRecordDto {
        return CloudRestoreRecordDto(
            id = o.optString("id"),
            status = o.optString("status", "UNKNOWN"),
            totalFiles = o.optInt("total_files", 0),
            restoredFiles = o.optInt("restored_files", 0),
            verifiedFiles = o.optInt("verified_files", 0),
            failedFiles = o.optInt("failed_files", 0),
            bytesRestored = o.optLong("bytes_restored", 0L),
            message = o.optString("message", ""),
            startedAt = o.optLong("started_at", 0L),
            completedAt = o.optLong("completed_at", 0L).takeIf { it > 0L }
        )
    }

    private fun JSONArray?.toJsonObjectList(): List<JSONObject> {
        if (this == null) return emptyList()
        val res = mutableListOf<JSONObject>()
        for (i in 0 until length()) {
            optJSONObject(i)?.let { res.add(it) }
        }
        return res
    }

    private fun JSONObject.optNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
    }

    private fun extractVisibleHtmlText(html: String): String? {
        if (html.isBlank()) return null
        val cleaned = html
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.isNotBlank() }?.take(12_000)
    }

    private fun extractMetaContent(html: String, key: String): String? {
        val pattern = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(key)}["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val reversePattern = Regex(
            """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["']${Regex.escape(key)}["']""",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(html)?.groupValues?.getOrNull(1)
            ?: reversePattern.find(html)?.groupValues?.getOrNull(1)
    }

    private fun parseError(body: String, fallback: String): String {
        return runCatching {
            val json = JSONObject(body)
            json.optString("error").takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: fallback
        }.getOrDefault(fallback)
    }
}
