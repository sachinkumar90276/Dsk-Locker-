package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class DskTypeConverters {
    @TypeConverter
    fun fromBackupStatus(value: BackupStatus): String = value.name

    @TypeConverter
    fun toBackupStatus(value: String): BackupStatus =
        runCatching { BackupStatus.valueOf(value) }.getOrDefault(BackupStatus.MOBILE_ONLY)
}

@Database(
    entities = [
        FileItemEntity::class,
        FolderEntity::class,
        CustomCategoryEntity::class,
        DocumentExtractionEntity::class,
        DocumentReminderEntity::class,
        TextNoteEntity::class,
        SavedLinkEntity::class,
        BackupHistoryEntity::class,
        SecurityLogEntity::class,
        OwnerProfileEntity::class,
        UserSettingsEntity::class,
        BackupSnapshotEntity::class,
        PendingSyncOpEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(DskTypeConverters::class)
abstract class DskLockerDatabase : RoomDatabase() {
    abstract fun fileItemDao(): FileItemDao
    abstract fun folderDao(): FolderDao
    abstract fun customCategoryDao(): CustomCategoryDao
    abstract fun documentExtractionDao(): DocumentExtractionDao
    abstract fun documentReminderDao(): DocumentReminderDao
    abstract fun textNoteDao(): TextNoteDao
    abstract fun savedLinkDao(): SavedLinkDao
    abstract fun backupHistoryDao(): BackupHistoryDao
    abstract fun securityLogDao(): SecurityLogDao
    abstract fun profileSettingsDao(): ProfileSettingsDao
    abstract fun backupSnapshotDao(): BackupSnapshotDao
    abstract fun pendingSyncOpDao(): PendingSyncOpDao

    companion object {
        @Volatile
        private var INSTANCE: DskLockerDatabase? = null

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `backup_snapshots` (
                        `id` TEXT NOT NULL,
                        `backupType` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `totalFiles` INTEGER NOT NULL,
                        `backedUpFiles` INTEGER NOT NULL,
                        `failedFiles` INTEGER NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `summaryMessage` TEXT NOT NULL,
                        `deviceInfo` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_sync_ops` (
                        `id` TEXT NOT NULL,
                        `opType` TEXT NOT NULL,
                        `tableName` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): DskLockerDatabase {
            val appCtx = context.applicationContext ?: context
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    appCtx,
                    DskLockerDatabase::class.java,
                    "dsk_locker_db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
