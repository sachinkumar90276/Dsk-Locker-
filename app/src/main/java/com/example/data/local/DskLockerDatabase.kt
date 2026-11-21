package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

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
        UserSettingsEntity::class
    ],
    version = 2,
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

    companion object {
        @Volatile
        private var INSTANCE: DskLockerDatabase? = null

        fun getInstance(context: Context): DskLockerDatabase {
            val appCtx = context.applicationContext ?: context
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    appCtx,
                    DskLockerDatabase::class.java,
                    "dsk_locker_db"
                )
                    .fallbackToDestructiveMigration(true)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
