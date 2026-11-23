package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Xml
import androidx.core.content.FileProvider
import com.example.data.local.BackupStatus
import com.example.data.local.DskCategories
import com.example.data.local.FileItemEntity
import com.example.security.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

data class DeviceStorageStats(
    val deviceTotalBytes: Long,
    val deviceAvailableBytes: Long,
    val lockerLocalBytes: Long
)

object LocalFileManager {
    private const val ROOT_DIR = "dsk_locker"
    private const val FILES_DIR = "files"
    private const val VAULT_DIR = "vault"
    private const val AVATAR_DIR = "avatar"
    private const val PREVIEW_CACHE_DIR = "vault_preview"

    fun getFilesDir(context: Context): File =
        File(context.filesDir, "$ROOT_DIR/$FILES_DIR").apply { mkdirs() }

    fun getVaultDir(context: Context): File =
        File(context.filesDir, "$ROOT_DIR/$VAULT_DIR").apply { mkdirs() }

    fun getAvatarDir(context: Context): File =
        File(context.filesDir, "$ROOT_DIR/$AVATAR_DIR").apply { mkdirs() }

    fun getPreviewCacheDir(context: Context): File =
        File(context.cacheDir, PREVIEW_CACHE_DIR).apply { mkdirs() }

    suspend fun importUri(
        context: Context,
        uri: Uri,
        isInVault: Boolean = false,
        folderId: String? = null,
        forcedCategoryId: String? = null,
        portableVaultKeyBase64: String? = null
    ): Result<FileItemEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var originalName = ""
            if (uri.scheme == "content") {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let {
                                originalName = it
                            }
                        }
                    }
                }
            }
            if (originalName.isBlank()) {
                originalName = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: "document_${System.currentTimeMillis()}"
            }

            val mimeType = resolver.getType(uri) ?: guessMimeFromName(originalName)
            val id = UUID.randomUUID().toString()
            val safeName = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destFile = if (isInVault) {
                File(getVaultDir(context), "${id}_$safeName.enc")
            } else {
                File(getFilesDir(context), "${id}_$safeName")
            }

            val tempPlainFile = File(context.cacheDir, "import_tmp_$id")
            resolver.openInputStream(uri)?.use { input ->
                tempPlainFile.outputStream().use { out ->
                    input.copyTo(out, bufferSize = 16 * 1024)
                }
            } ?: throw IllegalStateException("Unable to read selected file.")

            val plainSize = tempPlainFile.length()
            val sha256 = CryptoManager.sha256File(tempPlainFile)

            if (isInVault) {
                val portableKey = portableVaultKeyBase64?.takeIf { it.isNotBlank() }
                    ?.let { CryptoManager.secretKeyFromBase64(it) }
                tempPlainFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        if (portableKey != null) {
                            CryptoManager.encryptStreamWithKey(portableKey, input, output)
                        } else {
                            CryptoManager.encryptStream(input, output)
                        }
                    }
                }
                tempPlainFile.delete()
            } else {
                if (!tempPlainFile.renameTo(destFile)) {
                    tempPlainFile.copyTo(destFile, overwrite = true)
                    tempPlainFile.delete()
                }
            }

            val categoryId = forcedCategoryId ?: DskCategories.classifyFile(originalName, mimeType)

            FileItemEntity(
                id = id,
                originalFilename = originalName,
                displayName = originalName,
                categoryId = categoryId,
                folderId = folderId,
                mimeType = mimeType,
                sizeBytes = plainSize,
                localPath = destFile.absolutePath,
                backupStatus = BackupStatus.MOBILE_ONLY,
                sha256 = sha256,
                isInVault = isInVault
            )
        }
    }

    suspend fun copyAvatarUri(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(getAvatarDir(context), "owner_avatar_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("Could not open selected avatar.")
            dest.absolutePath
        }
    }

    suspend fun resolveReadableFile(
        context: Context,
        item: FileItemEntity,
        portableVaultKeyBase64: String? = null
    ): File? = withContext(Dispatchers.IO) {
        val local = item.localPath?.let { File(it) } ?: return@withContext null
        if (!local.exists()) return@withContext null
        if (!item.isInVault) return@withContext local

        val outFile = File(getPreviewCacheDir(context), "${item.id}_${item.originalFilename}")
        val portableKey = portableVaultKeyBase64?.takeIf { it.isNotBlank() }
            ?.let { runCatching { CryptoManager.secretKeyFromBase64(it) }.getOrNull() }

        val decryptedWithPortable = if (portableKey != null) {
            runCatching {
                local.inputStream().use { input ->
                    outFile.outputStream().use { output ->
                        CryptoManager.decryptStreamWithKey(portableKey, input, output)
                    }
                }
            }.isSuccess
        } else false

        if (!decryptedWithPortable) {
            val decryptedWithHardware = runCatching {
                local.inputStream().use { input ->
                    outFile.outputStream().use { output ->
                        CryptoManager.decryptStream(input, output)
                    }
                }
            }.isSuccess
            if (!decryptedWithHardware) {
                runCatching { outFile.delete() }
                return@withContext null
            }
        }
        outFile
    }

    fun wipeVaultPreviewCache(context: Context) {
        getPreviewCacheDir(context).listFiles()?.forEach { it.delete() }
    }

    suspend fun exportToPhoneDownloads(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        onProgress: (Float) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!sourceFile.exists()) throw IllegalStateException("Local file missing.")
            val total = sourceFile.length().coerceAtLeast(1L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DSK_LOCKER")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Unable to create Downloads entry.")
                resolver.openOutputStream(itemUri)?.use { output ->
                    copyWithProgress(sourceFile, output, total, onProgress)
                } ?: throw IllegalStateException("Unable to write to Downloads.")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                "Downloads/DSK_LOCKER/$fileName"
            } else {
                val targetDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DSK_LOCKER").apply {
                    mkdirs()
                }
                val dest = File(targetDir, fileName)
                dest.outputStream().use { output ->
                    copyWithProgress(sourceFile, output, total, onProgress)
                }
                dest.absolutePath
            }
        }
    }

    suspend fun exportToSafUri(
        context: Context,
        sourceFile: File,
        targetUri: Uri,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val total = sourceFile.length().coerceAtLeast(1L)
            context.contentResolver.openOutputStream(targetUri)?.use { output ->
                copyWithProgress(sourceFile, output, total, onProgress)
            } ?: throw IllegalStateException("Unable to open destination file.")
        }
    }

    private fun copyWithProgress(
        sourceFile: File,
        output: java.io.OutputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit
    ) {
        sourceFile.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            var copied = 0L
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                output.write(buf, 0, read)
                copied += read
                onProgress((copied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
            }
        }
        onProgress(1f)
    }

    fun buildShareOrOpenIntent(context: Context, file: File, mimeType: String, isShare: Boolean): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return if (isShare) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun readDocumentOrSpreadsheetPreview(file: File, originalName: String, mimeType: String): List<String> {
        val ext = originalName.substringAfterLast('.', "").lowercase()
        return runCatching {
            when {
                ext == "docx" || mimeType.contains("wordprocessingml") -> {
                    val lines = mutableListOf<String>()
                    ZipInputStream(file.inputStream().buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (entry.name == "word/document.xml") {
                                val parser = Xml.newPullParser()
                                parser.setInput(zip, "UTF-8")
                                var event = parser.eventType
                                val para = StringBuilder()
                                while (event != XmlPullParser.END_DOCUMENT) {
                                    if (event == XmlPullParser.TEXT) {
                                        para.append(parser.text)
                                    } else if (event == XmlPullParser.END_TAG && parser.name == "p") {
                                        if (para.isNotBlank()) lines.add(para.toString().trim())
                                        para.clear()
                                    }
                                    event = parser.next()
                                }
                                break
                            }
                            entry = zip.nextEntry
                        }
                    }
                    if (lines.isEmpty()) listOf("Empty DOCX document.") else lines
                }

                ext == "xlsx" || mimeType.contains("spreadsheetml") -> {
                    val sharedStrings = mutableListOf<String>()
                    val rows = mutableListOf<String>()
                    ZipInputStream(file.inputStream().buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (entry.name == "xl/sharedStrings.xml") {
                                val parser = Xml.newPullParser()
                                parser.setInput(zip, "UTF-8")
                                var event = parser.eventType
                                val current = StringBuilder()
                                while (event != XmlPullParser.END_DOCUMENT) {
                                    if (event == XmlPullParser.TEXT) current.append(parser.text)
                                    else if (event == XmlPullParser.END_TAG && parser.name == "si") {
                                        sharedStrings.add(current.toString())
                                        current.clear()
                                    }
                                    event = parser.next()
                                }
                            } else if (entry.name == "xl/worksheets/sheet1.xml") {
                                val parser = Xml.newPullParser()
                                parser.setInput(zip, "UTF-8")
                                var event = parser.eventType
                                var currentType: String? = null
                                var inValue = false
                                val rowCells = mutableListOf<String>()
                                while (event != XmlPullParser.END_DOCUMENT) {
                                    when (event) {
                                        XmlPullParser.START_TAG -> {
                                            if (parser.name == "c") {
                                                currentType = parser.getAttributeValue(null, "t")
                                            } else if (parser.name == "v" || parser.name == "t") {
                                                inValue = true
                                            }
                                        }
                                        XmlPullParser.TEXT -> if (inValue) {
                                            val raw = parser.text.orEmpty()
                                            val resolved = if (currentType == "s") {
                                                raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: raw
                                            } else raw
                                            rowCells.add(resolved)
                                        }
                                        XmlPullParser.END_TAG -> {
                                            if (parser.name == "v" || parser.name == "t") inValue = false
                                            else if (parser.name == "row" && rowCells.isNotEmpty()) {
                                                rows.add(rowCells.joinToString("  |  "))
                                                rowCells.clear()
                                            }
                                        }
                                    }
                                    event = parser.next()
                                }
                            }
                            entry = zip.nextEntry
                        }
                    }
                    if (rows.isEmpty() && sharedStrings.isNotEmpty()) sharedStrings else rows.ifEmpty { listOf("Spreadsheet contains no readable rows.") }
                }

                ext == "html" || ext == "htm" || mimeType == "text/html" -> {
                    val raw = file.readText(Charsets.UTF_8)
                    val clean = raw
                        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                        .replace(Regex("<[^>]+>"), "\n")
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .take(1500)
                    clean.ifEmpty { listOf("Empty HTML document.") }
                }

                else -> {
                    file.readLines(Charsets.UTF_8).take(1500)
                }
            }
        }.getOrElse { listOf("Unable to preview text: ${it.localizedMessage}") }
    }

    fun getDeviceStorageStats(context: Context): DeviceStorageStats {
        return runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            val total = stat.totalBytes
            val avail = stat.availableBytes
            val lockerBytes = folderSize(File(context.filesDir, ROOT_DIR))
            DeviceStorageStats(total, avail, lockerBytes)
        }.getOrDefault(DeviceStorageStats(0L, 0L, 0L))
    }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var sum = 0L
        dir.walkTopDown().forEach { if (it.isFile) sum += it.length() }
        return sum
    }

    data class VideoPreviewMeta(
        val thumbnailFile: File?,
        val durationMs: Long
    )

    suspend fun getVideoPreviewMeta(context: Context, item: FileItemEntity): VideoPreviewMeta = withContext(Dispatchers.IO) {
        val thumbDir = File(context.cacheDir, "video_thumbs").apply { mkdirs() }
        val thumbFile = File(thumbDir, "${item.id}.jpg")
        val readable = resolveReadableFile(context, item) ?: return@withContext VideoPreviewMeta(null, 0L)
        val retriever = android.media.MediaMetadataRetriever()
        var duration = 0L
        var resultThumb: File? = if (thumbFile.exists() && thumbFile.length() > 0L) thumbFile else null
        try {
            retriever.setDataSource(readable.absolutePath)
            duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (resultThumb == null) {
                val frame = retriever.getFrameAtTime(
                    (duration.coerceAtLeast(1000L) * 150L), // 15% in microseconds
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: retriever.getFrameAtTime(0L)
                if (frame != null) {
                    thumbFile.outputStream().use { out ->
                        frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                    }
                    frame.recycle()
                    resultThumb = thumbFile
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { retriever.release() }
        }
        VideoPreviewMeta(resultThumb, duration)
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "--:--"
        val totalSec = durationMs / 1000L
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, mins, secs)
        } else {
            "%02d:%02d".format(mins, secs)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.2f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    private fun guessMimeFromName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "txt", "log", "md" -> "text/plain"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
