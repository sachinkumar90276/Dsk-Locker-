package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FileItemDao {
    @Query("SELECT * FROM files WHERE isDeleted = 0 AND isInVault = 0 ORDER BY updatedAt DESC")
    fun observeActiveFiles(): Flow<List<FileItemEntity>>

    @Query("SELECT * FROM files WHERE isDeleted = 0 AND isInVault = 1 ORDER BY updatedAt DESC")
    fun observeVaultFiles(): Flow<List<FileItemEntity>>

    @Query("SELECT * FROM files WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashFiles(): Flow<List<FileItemEntity>>

    @Query("SELECT * FROM files WHERE isDeleted = 0")
    suspend fun getAllNonDeletedFilesOnce(): List<FileItemEntity>

    @Query("SELECT * FROM files WHERE id = :id LIMIT 1")
    suspend fun getFileById(id: String): FileItemEntity?

    @Query("SELECT * FROM files WHERE id = :id LIMIT 1")
    fun observeFileById(id: String): Flow<FileItemEntity?>

    @Query("SELECT * FROM files WHERE isDeleted = 0 AND backupStatus IN ('MOBILE_ONLY', 'BACKUP_PENDING', 'BACKUP_FAILED')")
    suspend fun getPendingBackupFiles(): List<FileItemEntity>

    @Query("SELECT * FROM files WHERE isDeleted = 0 AND sha256 = :sha256 AND sha256 != ''")
    suspend fun findBySha256(sha256: String): List<FileItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: FileItemEntity)

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun hardDeleteById(id: String)

    @Query("DELETE FROM files WHERE isDeleted = 1")
    suspend fun emptyTrashFiles()
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun getFoldersOnce(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CustomCategoryDao {
    @Query("SELECT * FROM custom_categories ORDER BY createdAt ASC")
    fun observeCustomCategories(): Flow<List<CustomCategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CustomCategoryEntity)

    @Query("DELETE FROM custom_categories WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface DocumentExtractionDao {
    @Query("SELECT * FROM document_extractions")
    fun observeAll(): Flow<List<DocumentExtractionEntity>>

    @Query("SELECT * FROM document_extractions WHERE fileId = :fileId LIMIT 1")
    fun observeForFile(fileId: String): Flow<DocumentExtractionEntity?>

    @Query("SELECT * FROM document_extractions WHERE fileId = :fileId LIMIT 1")
    suspend fun getForFile(fileId: String): DocumentExtractionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(extraction: DocumentExtractionEntity)
}

@Dao
interface DocumentReminderDao {
    @Query("SELECT * FROM document_reminders ORDER BY expiryDateIso ASC")
    fun observeAll(): Flow<List<DocumentReminderEntity>>

    @Query("SELECT * FROM document_reminders")
    suspend fun getAllOnce(): List<DocumentReminderEntity>

    @Query("SELECT * FROM document_reminders WHERE fileId = :fileId LIMIT 1")
    fun observeForFile(fileId: String): Flow<DocumentReminderEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: DocumentReminderEntity)

    @Query("DELETE FROM document_reminders WHERE fileId = :fileId")
    suspend fun deleteForFile(fileId: String)
}

@Dao
interface TextNoteDao {
    @Query("SELECT * FROM text_notes WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeActiveNotes(): Flow<List<TextNoteEntity>>

    @Query("SELECT * FROM text_notes WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashNotes(): Flow<List<TextNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: TextNoteEntity)

    @Query("DELETE FROM text_notes WHERE id = :id")
    suspend fun hardDeleteById(id: String)

    @Query("DELETE FROM text_notes WHERE isDeleted = 1")
    suspend fun emptyTrashNotes()
}

@Dao
interface SavedLinkDao {
    @Query("SELECT * FROM saved_links WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeActiveLinks(): Flow<List<SavedLinkEntity>>

    @Query("SELECT * FROM saved_links WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashLinks(): Flow<List<SavedLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: SavedLinkEntity)

    @Query("DELETE FROM saved_links WHERE id = :id")
    suspend fun hardDeleteById(id: String)

    @Query("DELETE FROM saved_links WHERE isDeleted = 1")
    suspend fun emptyTrashLinks()
}

@Dao
interface BackupHistoryDao {
    @Query("SELECT * FROM backup_history ORDER BY timestamp DESC LIMIT 100")
    fun observeRecent(): Flow<List<BackupHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BackupHistoryEntity)
}

@Dao
interface SecurityLogDao {
    @Query("SELECT * FROM security_logs ORDER BY timestamp DESC LIMIT 100")
    fun observeRecent(): Flow<List<SecurityLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SecurityLogEntity)
}

@Dao
interface ProfileSettingsDao {
    @Query("SELECT * FROM profiles WHERE id = 'owner' LIMIT 1")
    fun observeProfile(): Flow<OwnerProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = 'owner' LIMIT 1")
    suspend fun getProfileOnce(): OwnerProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: OwnerProfileEntity)

    @Query("SELECT * FROM user_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<UserSettingsEntity?>

    @Query("SELECT * FROM user_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsOnce(): UserSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: UserSettingsEntity)
}
