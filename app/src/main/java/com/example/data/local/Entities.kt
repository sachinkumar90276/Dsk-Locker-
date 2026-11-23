package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "files")
data class FileItemEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val originalFilename: String,
    val displayName: String,
    val categoryId: String,
    val folderId: String? = null,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String? = null,
    val cloudPath: String? = null,
    val cloudBucket: String? = null,
    val backupStatus: BackupStatus = BackupStatus.MOBILE_ONLY,
    val sha256: String = "",
    val isFavorite: Boolean = false,
    val isInVault: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val categoryId: String,
    val parentId: String? = null,
    val isAutoBackupEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "custom_categories")
data class CustomCategoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val iconKey: String = "custom",
    val accentHex: String = "#00D4FF",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "document_extractions")
data class DocumentExtractionEntity(
    @PrimaryKey val fileId: String,
    val documentName: String? = null,
    val documentType: String? = null,
    val documentNumber: String? = null,
    val ownerName: String? = null,
    val fatherName: String? = null,
    val motherName: String? = null,
    val dateOfBirth: String? = null,
    val issueDate: String? = null,
    val expiryDate: String? = null,
    val address: String? = null,
    val mobileNumber: String? = null,
    val organizationName: String? = null,
    val registrationNumber: String? = null,
    val rawText: String = "",
    val isVerified: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "document_reminders")
data class DocumentReminderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val fileId: String,
    val documentName: String,
    val expiryDateIso: String,
    val remind30Days: Boolean = true,
    val remind15Days: Boolean = true,
    val remind7Days: Boolean = true,
    val remindOnDay: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "text_notes")
data class TextNoteEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val categoryId: String = DskCategories.CAT_TEXT,
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_links")
data class SavedLinkEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val linkType: String, // "VIDEO_URL" or "WEBSITE_LINK"
    val title: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val description: String = "",
    val categoryId: String,
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "backup_history")
data class BackupHistoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val fileId: String? = null,
    val fileName: String,
    val action: String, // AUTO_BACKUP, MANUAL_BACKUP, RESTORE, RETRY
    val status: String, // SUCCESS, FAILED
    val bytesTransferred: Long = 0L,
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "security_logs")
data class SecurityLogEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val eventType: String,
    val detail: String,
    val success: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "profiles")
data class OwnerProfileEntity(
    @PrimaryKey val id: String = "owner",
    val ownerName: String = "DSK Owner",
    val phoneNumber: String = "",
    val avatarLocalPath: String? = null,
    val avatarCloudPath: String? = null,
    val firebaseUid: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val autoBackupEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val backupPhotos: Boolean = true,
    val backupVideos: Boolean = true,
    val backupDocuments: Boolean = true,
    val autoLockSeconds: Int = 120,
    val darkMode: Boolean = true,
    val animations4DEnabled: Boolean = true,
    val reducedMotion: Boolean = false,
    val accentPreset: String = "ELECTRIC_COSMOS",
    val notificationsEnabled: Boolean = true
)

@Entity(tableName = "backup_snapshots")
data class BackupSnapshotEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val backupType: String = "MANUAL", // AUTO, MANUAL, RECONNECT, RESTORE
    val status: String = "SYNCING", // SYNCING, SUCCESS, PARTIAL, FAILED
    val totalFiles: Int = 0,
    val backedUpFiles: Int = 0,
    val failedFiles: Int = 0,
    val totalBytes: Long = 0L,
    val summaryMessage: String = "",
    val deviceInfo: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(tableName = "pending_sync_ops")
data class PendingSyncOpEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val opType: String, // UPLOAD_FILE, UPSERT_ROW, DELETE_CLOUD_FILE, DELETE_ROW
    val tableName: String,
    val targetId: String,
    val payloadJson: String = "{}",
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

