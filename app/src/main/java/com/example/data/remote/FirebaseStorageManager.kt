package com.example.data.remote

import com.example.data.local.DskCategories
import com.google.firebase.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

object FirebaseStoragePaths {
    fun folderForCategory(categoryId: String, isInVault: Boolean): String {
        if (isInVault) return "vault"
        return when (categoryId) {
            DskCategories.CAT_PHOTOS -> "photos"
            DskCategories.CAT_VIDEOS -> "videos"
            DskCategories.CAT_AUDIO -> "audio"
            else -> "documents"
        }
    }

    fun buildPrivateObjectPath(
        firebaseUid: String,
        fileId: String,
        originalFilename: String,
        categoryId: String,
        isInVault: Boolean
    ): String {
        val safeUid = firebaseUid.trim().ifBlank { throw IllegalArgumentException("Firebase UID required") }
        val safeName = originalFilename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val folder = folderForCategory(categoryId, isInVault)
        val suffix = if (isInVault && !safeName.endsWith(".enc")) ".enc" else ""
        return "users/$safeUid/$folder/${fileId}_$safeName$suffix"
    }
}

class FirebaseStorageManager {
    private val storageRef
        get() = Firebase.storage.reference

    fun bucketName(): String = runCatching { Firebase.storage.reference.bucket }.getOrDefault("firebase-storage")

    suspend fun uploadBytes(
        objectPath: String,
        bytes: ByteArray,
        mimeType: String,
        sha256: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val ref = storageRef.child(objectPath)
            val metadata = StorageMetadata.Builder()
                .setContentType(mimeType.ifBlank { "application/octet-stream" })
                .setCustomMetadata("sha256", sha256)
                .build()
            ref.putBytes(bytes, metadata).await()
            objectPath
        }
    }

    suspend fun downloadToFile(
        objectPath: String,
        destFile: File,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destFile.parentFile?.mkdirs()
            val ref = storageRef.child(objectPath)
            val task = ref.getFile(destFile)
            task.addOnProgressListener { snap ->
                val total = snap.totalByteCount
                if (total > 0) {
                    onProgress((snap.bytesTransferred.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                }
            }
            task.await()
            onProgress(1f)
            destFile
        }
    }

    suspend fun deleteObject(objectPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            storageRef.child(objectPath).delete().await()
            Unit
        }
    }
}
