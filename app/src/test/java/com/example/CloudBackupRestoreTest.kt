package com.example

import android.content.Context
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ExtendedOcrMetadata
import com.example.ai.OcrMetadataStore
import com.example.data.DskLockerRepository
import com.example.data.local.*
import com.example.data.remote.AuthValidation
import com.example.data.remote.BackendConfig
import com.example.data.remote.CloudBackupSettingsDto
import com.example.data.remote.FirebasePhoneAuthManager
import com.example.data.remote.FirebaseSessionUser
import com.example.data.remote.SqlBackendClient
import com.example.security.CryptoManager
import com.example.storage.BackupFrequency
import com.example.storage.BackupScheduler
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudBackupRestoreTest {

    private lateinit var context: Context
    private lateinit var server: FakeRenderNeonServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = FakeRenderNeonServer()
        baseUrl = server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `production BackendConfig points to live Render URL and rejects supabase and localhost URLs`() {
        assertEquals("https://dsk-locker-k5jn.onrender.com", BackendConfig.apiBaseUrl)
        assertTrue(BackendConfig.isSqlBackendConfigured)
        assertTrue(BackendConfig.isLocalhostUrl("http://localhost:8080"))
        assertTrue(BackendConfig.isLocalhostUrl("http://127.0.0.1:8080"))
        assertTrue(BackendConfig.isLocalhostUrl("http://10.0.2.2:8080"))
        assertFalse(BackendConfig.isLocalhostUrl("https://dsk-locker-k5jn.onrender.com"))
    }

    @Test
    fun `health check verifies root and health endpoints and recovers from single cold-start 503`() = runBlocking {
        server.failNextHealthWith503 = true
        val client = SqlBackendClient { baseUrl }
        val healthRes = client.checkBackendHealth()
        assertTrue(healthRes.isSuccess)
        val health = healthRes.getOrThrow()
        assertTrue(health.reachable)
        assertEquals("ok", health.status)
        assertEquals(200, health.httpCode)
    }

    @Test
    fun `Google Sign-In session finalizes cleanly without phone number and enforces single-owner UID lock`() = runBlocking {
        val repo = DskLockerRepository(
            context = context,
            customSqlClient = SqlBackendClient { baseUrl },
            idTokenOverride = { "token-user-a" }
        )
        repo.saveNewMpin("482910")

        val googleSession = FirebaseSessionUser(
            uid = "uid-user-a",
            phoneNumber = "",
            idToken = "token-user-a",
            email = "owner@gmail.com",
            displayName = "Sachin Owner"
        )
        val firstRes = repo.finalizeFirebaseOwnerSession(googleSession)
        assertTrue(firstRes.isSuccess)
        assertEquals("uid-user-a", repo.prefs.firebaseUid)
        assertEquals("owner@gmail.com", repo.prefs.ownerEmail)
        assertTrue(repo.prefs.isCloudOwnerVerified)

        val profile = DskLockerDatabase.getInstance(context).profileSettingsDao().getProfileOnce()
        assertEquals("Sachin Owner", profile?.ownerName)
        assertEquals("uid-user-a", profile?.firebaseUid)

        // Different Firebase UID must be blocked by single-owner device lock
        val otherAccount = FirebaseSessionUser(
            uid = "uid-user-b",
            phoneNumber = "",
            idToken = "token-user-b",
            email = "intruder@gmail.com",
            displayName = "Other User"
        )
        val blockedRes = repo.finalizeFirebaseOwnerSession(otherAccount)
        assertTrue(blockedRes.isFailure)
        assertTrue(blockedRes.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `supabase URL is explicitly blocked by SqlBackendClient and never contacted`() = runBlocking {
        val supabaseClient = SqlBackendClient { "https://iwjojoukkqapdogxezdj.supabase.co/rest/v1" }
        val res = supabaseClient.fetchBackupStatus("token-user-a")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("Supabase", ignoreCase = true) == true)
    }

    @Test
    fun `offline and server-error backup never deletes local files and retry succeeds cleanly`() = runBlocking {
        val repo = DskLockerRepository(
            context = context,
            customSqlClient = SqlBackendClient { baseUrl },
            idTokenOverride = { "token-user-a" }
        )
        val db = DskLockerDatabase.getInstance(context)

        val localFile = File(context.filesDir, "offline_contract.pdf").apply {
            writeText("%PDF-1.4\nOffline safe test PDF")
        }
        val sha = CryptoManager.sha256File(localFile)
        val item = FileItemEntity(
            id = "offline-doc-1",
            originalFilename = "offline_contract.pdf",
            displayName = "Rent Agreement",
            categoryId = DskCategories.CAT_DOCUMENTS,
            mimeType = "application/pdf",
            sizeBytes = localFile.length(),
            localPath = localFile.absolutePath,
            backupStatus = BackupStatus.BACKUP_PENDING,
            sha256 = sha
        )
        db.fileItemDao().upsert(item)

        // Simulate 413 failure on server -> marks BACKUP_FAILED, keeps local file intact
        server.forceNextCreateErrorCode = 413
        server.forceNextCreateErrorMessage = "File too large for tier"
        val failedSummary = repo.runNeonCloudBackup(isManual = true)
        assertEquals(0, failedSummary.uploadedCount)
        assertEquals(1, failedSummary.failedCount)
        assertTrue(localFile.exists())
        assertEquals(BackupStatus.BACKUP_FAILED, db.fileItemDao().getFileById("offline-doc-1")?.backupStatus)

        // Simulate offline IOException (unreachable port) -> queues BACKUP_PENDING, keeps local file intact
        val deadRepo = DskLockerRepository(
            context = context,
            customSqlClient = SqlBackendClient { "http://127.0.0.1:1" },
            idTokenOverride = { "token-user-a" }
        )
        val offlineSummary = deadRepo.runNeonCloudBackup(filesToBackup = listOf(item), isManual = true)
        assertEquals(0, offlineSummary.uploadedCount)
        assertTrue(offlineSummary.transientRetryNeeded)
        assertTrue(localFile.exists())
        assertEquals(BackupStatus.BACKUP_PENDING, db.fileItemDao().getFileById("offline-doc-1")?.backupStatus)

        // Retry when online -> succeeds & marks MOBILE_AND_CLOUD
        server.forceNextCreateErrorCode = null
        val okSummary = repo.runNeonCloudBackup(isManual = true)
        assertEquals(1, okSummary.uploadedCount)
        assertEquals(0, okSummary.failedCount)
        assertEquals(BackupStatus.MOBILE_AND_CLOUD, db.fileItemDao().getFileById("offline-doc-1")?.backupStatus)
        assertTrue(localFile.exists())
    }

    @Test
    fun `full cloud backup and reinstall restore preserves files, vault portable key decryption, folders, notes, OCR, and reminders`() = runBlocking {
        val repo = DskLockerRepository(
            context = context,
            customSqlClient = SqlBackendClient { baseUrl },
            idTokenOverride = { "token-user-a" }
        )
        val db = DskLockerDatabase.getInstance(context)

        // Configure owner MPIN & sync hash to backend
        repo.saveNewMpin("482910")
        assertTrue(repo.verifyMpinWithBackendIfLinked("482910", "VERIFY").isSuccess)

        // Create folder, custom category, text note, saved link
        db.customCategoryDao().upsert(CustomCategoryEntity(id = "cat-tax", name = "Tax Filings"))
        db.folderDao().upsert(FolderEntity(id = "folder-ids", name = "Gov IDs", categoryId = DskCategories.CAT_DOCUMENTS))
        db.textNoteDao().upsert(TextNoteEntity(id = "note-1", title = "Locker Note", content = "Account Backup OK"))
        db.savedLinkDao().upsert(
            SavedLinkEntity(
                id = "link-1",
                linkType = "WEBSITE_LINK",
                title = "DigiLocker",
                url = "https://digilocker.gov.in",
                categoryId = DskCategories.CAT_WEBSITE_LINKS
            )
        )

        // 1. Plain PDF document + OCR + Extended OCR + Reminder
        val pdfFile = File(context.filesDir, "pan_doc.pdf").apply {
            writeText("%PDF-1.4\nPAN ABCDE1234F Aarav")
        }
        val pdfItem = FileItemEntity(
            id = "doc-pdf-1",
            originalFilename = "pan_doc.pdf",
            displayName = "PAN Card - Aarav",
            categoryId = DskCategories.CAT_DOCUMENTS,
            folderId = "folder-ids",
            mimeType = "application/pdf",
            sizeBytes = pdfFile.length(),
            localPath = pdfFile.absolutePath,
            backupStatus = BackupStatus.BACKUP_PENDING,
            sha256 = CryptoManager.sha256File(pdfFile)
        )
        db.fileItemDao().upsert(pdfItem)
        db.documentExtractionDao().upsert(
            DocumentExtractionEntity(
                fileId = "doc-pdf-1",
                documentName = "PAN Card",
                documentType = "PAN Card",
                documentNumber = "ABCDE1234F",
                ownerName = "Aarav Sharma",
                rawText = "INCOME TAX DEPARTMENT ABCDE1234F",
                isVerified = true
            )
        )
        OcrMetadataStore.save(
            context,
            ExtendedOcrMetadata(
                fileId = "doc-pdf-1",
                stageSummary = "Verified PAN",
                confidenceScore = 95,
                confidenceLabel = "HIGH"
            )
        )
        db.documentReminderDao().upsert(
            DocumentReminderEntity(
                id = "rem-1",
                fileId = "doc-pdf-1",
                documentName = "PAN Card",
                expiryDateIso = "2032-05-01"
            )
        )

        // 2. Image file
        val imgBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 10, 20, 30, 40)
        val imgFile = File(context.filesDir, "photo_id.png").apply { writeBytes(imgBytes) }
        val imgItem = FileItemEntity(
            id = "img-1",
            originalFilename = "photo_id.png",
            displayName = "Passport Size Photo",
            categoryId = DskCategories.CAT_PHOTOS,
            mimeType = "image/png",
            sizeBytes = imgBytes.size.toLong(),
            localPath = imgFile.absolutePath,
            backupStatus = BackupStatus.BACKUP_PENDING,
            sha256 = CryptoManager.sha256File(imgFile)
        )
        db.fileItemDao().upsert(imgItem)

        // 3. Vault item encrypted with portable AES-256-GCM DEK (keyVersion = 1)
        val portableKey = repo.prefs.getOrCreatePortableVaultKeyBase64()
        val vaultPlainBytes = "Top Secret Vault Seed Recovery".toByteArray()
        val vaultEncFile = File(context.filesDir, "vault_secret.enc")
        vaultPlainBytes.inputStream().use { inp ->
            vaultEncFile.outputStream().use { out ->
                CryptoManager.encryptStreamWithKey(CryptoManager.secretKeyFromBase64(portableKey), inp, out)
            }
        }
        repo.prefs.setFileKeyVersion("vault-1", 1)
        val vaultItem = FileItemEntity(
            id = "vault-1",
            originalFilename = "secret_seed.txt",
            displayName = "Secret Seed",
            categoryId = DskCategories.CAT_DOCUMENTS,
            mimeType = "text/plain",
            sizeBytes = vaultPlainBytes.size.toLong(),
            localPath = vaultEncFile.absolutePath,
            backupStatus = BackupStatus.BACKUP_PENDING,
            sha256 = CryptoManager.sha256Bytes(vaultPlainBytes),
            isInVault = true
        )
        db.fileItemDao().upsert(vaultItem)

        // Back up everything to Render + Neon
        val summary = repo.runNeonCloudBackup(isManual = true)
        assertEquals(3, summary.uploadedCount)
        assertEquals(0, summary.failedCount)
        assertEquals(3, server.filesForUser("uid-user-a").size)

        // Verify cloud status & strict multi-user isolation (User B sees 0 files)
        val statusA = repo.fetchCloudStatus().getOrThrow()
        assertEquals(3, statusA.backedUpFilesCount)
        assertEquals(1, statusA.vaultFilesCount)
        assertEquals(1, statusA.notesCount)
        assertEquals(1, statusA.foldersCount)
        assertTrue(statusA.hasWrappedRecoveryKey)

        val repoUserB = DskLockerRepository(
            context = context,
            customSqlClient = SqlBackendClient { baseUrl },
            idTokenOverride = { "token-user-b" }
        )
        assertEquals(0, repoUserB.fetchCloudStatus().getOrThrow().backedUpFilesCount)

        // Simulate app reinstall on a new phone: wipe local Room tables, delete local files, clear portable vault key
        withContext(Dispatchers.IO) { db.clearAllTables() }
        pdfFile.delete()
        imgFile.delete()
        vaultEncFile.delete()
        repo.prefs.saveRecoveredPortableVaultKeyBase64("") // clear local portable key so server must unwrap with MPIN
        assertNull(db.fileItemDao().getFileById("doc-pdf-1"))

        // Restore using owner session + verified MPIN
        val restoreRes = repo.restoreFullFromNeon(
            overwriteExisting = false,
            verifiedMpinForKeyRecovery = "482910"
        )
        assertTrue(restoreRes.isSuccess)
        val restored = restoreRes.getOrThrow()
        assertEquals(3, restored.verifiedCount)
        assertEquals(0, restored.failedCount)
        assertEquals(0, restored.missingKeyFailures)

        // Verify restored PDF content, OCR metadata, extended JSON, reminder, folder, note, link
        val restoredPdf = db.fileItemDao().getFileById("doc-pdf-1")!!
        assertEquals(BackupStatus.MOBILE_AND_CLOUD, restoredPdf.backupStatus)
        assertTrue(File(restoredPdf.localPath!!).readText().contains("ABCDE1234F"))
        val restoredExt = db.documentExtractionDao().getForFile("doc-pdf-1")!!
        assertEquals("ABCDE1234F", restoredExt.documentNumber)
        assertEquals("Verified PAN", OcrMetadataStore.load(context, "doc-pdf-1")?.stageSummary)
        assertEquals("2032-05-01", db.documentReminderDao().getAllOnce().first { it.fileId == "doc-pdf-1" }.expiryDateIso)
        assertTrue(db.folderDao().getFoldersOnce().any { it.name == "Gov IDs" })
        assertTrue(db.textNoteDao().getActiveOnce().any { it.title == "Locker Note" })
        assertTrue(db.savedLinkDao().getActiveOnce().any { it.title == "DigiLocker" })

        // Verify restored Vault file decrypts using the recovered portable key!
        val restoredVault = db.fileItemDao().getFileById("vault-1")!!
        val decryptedVault = repo.resolveReadableFile(restoredVault)
        assertNotNull(decryptedVault)
        assertEquals("Top Secret Vault Seed Recovery", decryptedVault!!.readText())
    }

    @Test
    fun `corrupted download triggers SHA-256 mismatch failure and missing wrapped key fails honestly`() = runBlocking {
        val repo = DskLockerRepository(
            context = context,
            customSqlClient = SqlBackendClient { baseUrl },
            idTokenOverride = { "token-user-a" }
        )
        val db = DskLockerDatabase.getInstance(context)

        // Back up one normal PDF and one legacy v0 hardware-key vault entry without wrapped recovery key
        val pdfFile = File(context.filesDir, "integrity_test.pdf").apply { writeText("Original Clean Bytes") }
        val item = FileItemEntity(
            id = "tamper-1",
            originalFilename = "integrity_test.pdf",
            displayName = "Clean Doc",
            categoryId = DskCategories.CAT_DOCUMENTS,
            mimeType = "application/pdf",
            sizeBytes = pdfFile.length(),
            localPath = pdfFile.absolutePath,
            backupStatus = BackupStatus.BACKUP_PENDING,
            sha256 = CryptoManager.sha256File(pdfFile)
        )
        db.fileItemDao().upsert(item)
        assertEquals(1, repo.runNeonCloudBackup(isManual = true).uploadedCount)

        // Inject bit-flip corruption on server response during download -> restore MUST reject!
        withContext(Dispatchers.IO) { db.clearAllTables() }
        pdfFile.delete()
        server.corruptDownloadForFileId = "tamper-1"
        val res = repo.restoreFullFromNeon(fileIds = listOf("tamper-1"))
        assertTrue(res.isSuccess)
        val summary = res.getOrThrow()
        assertEquals(0, summary.verifiedCount)
        assertEquals(1, summary.failedCount)
        assertNull(db.fileItemDao().getFileById("tamper-1"))
        val logs = repo.backupHistory.first()
        assertTrue(logs.any { it.action == "RESTORE" && it.status == "FAILED" && it.message.contains("SHA-256") })

        // Test honest failure when an encrypted vault item has no wrapped recovery key on server
        server.corruptDownloadForFileId = null
        server.seedUnrecoverableVaultItem("uid-user-a", "orphan-vault-1")
        val orphanRes = repo.restoreFullFromNeon(fileIds = listOf("orphan-vault-1")).getOrThrow()
        assertEquals(0, orphanRes.verifiedCount)
        assertEquals(1, orphanRes.missingKeyFailures)
        assertTrue(orphanRes.notice.contains("encryption key is unavailable", ignoreCase = true))
    }

    @Test
    fun `recoverInterruptedBackups and backup settings update execute cleanly`() = runBlocking {
        val repo = DskLockerRepository(
            context = context,
            customSqlClient = SqlBackendClient { baseUrl },
            idTokenOverride = { "token-user-a" }
        )
        repo.ensureDefaultsInitialized()
        repo.updateSettings { it.copy(autoBackupEnabled = true, wifiOnly = false) }
        repo.recoverInterruptedBackups()

        assertFalse(server.latestSettingsForUser("uid-user-a")?.wifiOnly ?: true)
    }

    @Test
    fun `AuthValidation validates emails and normalizes country-code phone numbers cleanly`() {
        assertTrue(AuthValidation.isValidEmail("sachin.vc.rajput@gmail.com"))
        assertTrue(AuthValidation.isValidEmail("  user+tag@domain.co.in  "))
        assertFalse(AuthValidation.isValidEmail("invalid-email"))
        assertFalse(AuthValidation.isValidEmail("user@"))

        val inPhone = AuthValidation.normalizeAndValidatePhone("09876543210", "+91")
        assertTrue(inPhone.isSuccess)
        assertEquals("+919876543210", inPhone.getOrThrow())

        val usPhone = AuthValidation.normalizeAndValidatePhone("(415) 555-2671", "+1")
        assertTrue(usPhone.isSuccess)
        assertEquals("+14155552671", usPhone.getOrThrow())

        val explicitOverride = AuthValidation.normalizeAndValidatePhone("+971501234567", "+91")
        assertTrue(explicitOverride.isSuccess)
        assertEquals("+971501234567", explicitOverride.getOrThrow())

        val tooShort = AuthValidation.normalizeAndValidatePhone("12345", "+91")
        assertTrue(tooShort.isFailure)
        val blank = AuthValidation.normalizeAndValidatePhone("   ", "+91")
        assertTrue(blank.isFailure)
    }

    @Test
    fun `FirebasePhoneAuthManager formats email and phone auth errors cleanly`() {
        val mgr = FirebasePhoneAuthManager()
        assertTrue(mgr.formatAuthError(IllegalStateException("unknown status code: 17006"), isPhoneFlow = true).contains("17006"))
        assertTrue(mgr.formatAuthError(IllegalStateException("OPERATION_NOT_ALLOWED"), isPhoneFlow = false).contains("Email/Password"))
    }

    @Test
    fun `BackupScheduler computes deterministic schedule delays, frequencies, and formatted times`() {
        assertEquals(BackupFrequency.DAILY, BackupFrequency.fromId(null))
        assertEquals(BackupFrequency.WEEKLY, BackupFrequency.fromId("weekly"))
        assertEquals(BackupFrequency.MONTHLY, BackupFrequency.fromId("MONTHLY"))
        assertEquals(7L, BackupFrequency.WEEKLY.intervalDays)
        assertEquals(30L, BackupFrequency.MONTHLY.intervalDays)

        assertEquals("02:00 AM", BackupScheduler.formatBackupTime(2, 0))
        assertEquals("12:30 PM", BackupScheduler.formatBackupTime(12, 30))
        assertEquals("12:05 AM", BackupScheduler.formatBackupTime(0, 5))
        assertEquals("11:45 PM", BackupScheduler.formatBackupTime(23, 45))

        val baseCal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 23, 10, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val nowMs = baseCal.timeInMillis
        // Target 14:30 same day -> 4.5 hours later
        val sameDayDelay = BackupScheduler.computeInitialDelayMillis(14, 30, nowMs)
        assertEquals(4L * 60L * 60L * 1000L + 30L * 60L * 1000L, sameDayDelay)
        // Target 02:00 already passed -> schedules for 02:00 next day (16 hours later)
        val nextDayDelay = BackupScheduler.computeInitialDelayMillis(2, 0, nowMs)
        assertEquals(16L * 60L * 60L * 1000L, nextDayDelay)
    }

    @Test
    fun `Room MIGRATION_3_4 preserves user_settings row and adds schedule columns non-destructively`() {
        val dbName = "migration_3_4_test.db"
        context.deleteDatabase(dbName)
        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE `user_settings` (
                            `id` INTEGER NOT NULL PRIMARY KEY,
                            `autoBackupEnabled` INTEGER NOT NULL,
                            `wifiOnly` INTEGER NOT NULL,
                            `backupPhotos` INTEGER NOT NULL,
                            `backupVideos` INTEGER NOT NULL,
                            `backupDocuments` INTEGER NOT NULL,
                            `autoLockSeconds` INTEGER NOT NULL,
                            `darkMode` INTEGER NOT NULL,
                            `animations4DEnabled` INTEGER NOT NULL,
                            `reducedMotion` INTEGER NOT NULL,
                            `accentPreset` TEXT NOT NULL,
                            `notificationsEnabled` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO `user_settings` VALUES (1, 1, 0, 1, 1, 1, 300, 1, 1, 0, 'CYAN_NEON', 1)
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val sqlite = helper.writableDatabase
        DskLockerDatabase.MIGRATION_3_4.migrate(sqlite)

        sqlite.query("SELECT autoBackupEnabled, wifiOnly, autoLockSeconds, chargingOnly, backupFrequency, backupHour, backupMinute FROM user_settings WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(300, cursor.getInt(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals("DAILY", cursor.getString(4))
            assertEquals(2, cursor.getInt(5))
            assertEquals(0, cursor.getInt(6))
        }
        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `updateSettings persists chargingOnly, backupFrequency, and custom time in Room and syncs scheduler`() = runBlocking {
        val repo = DskLockerRepository(
            context = context,
            customSqlClient = SqlBackendClient { baseUrl },
            idTokenOverride = { "token-user-a" }
        )
        repo.ensureDefaultsInitialized()
        repo.updateSettings {
            it.copy(
                autoBackupEnabled = true,
                wifiOnly = true,
                chargingOnly = true,
                backupFrequency = BackupFrequency.WEEKLY.id,
                backupHour = 21,
                backupMinute = 45
            )
        }
        val saved = DskLockerDatabase.getInstance(context).profileSettingsDao().getSettingsOnce()
        assertNotNull(saved)
        assertTrue(saved!!.chargingOnly)
        assertEquals("WEEKLY", saved.backupFrequency)
        assertEquals(21, saved.backupHour)
        assertEquals(45, saved.backupMinute)
        BackupScheduler.syncScheduleFromDatabase(context)
    }

    /**
     * Lightweight real HTTP server emulating the Render + Neon Cloud Backup API endpoints for OkHttp integration tests.
     */
    private class FakeRenderNeonServer {
        private var httpServer: HttpServer? = null
        private val filesByUser = ConcurrentHashMap<String, ConcurrentHashMap<String, StoredFile>>()
        private val snapshotsByUser = ConcurrentHashMap<String, JSONObject>()
        private val securityByUser = ConcurrentHashMap<String, SecurityState>()
        private val settingsByUser = ConcurrentHashMap<String, CloudBackupSettingsDto>()
        private val restoresByUser = ConcurrentHashMap<String, MutableList<JSONObject>>()
        private val historyByUser = ConcurrentHashMap<String, MutableList<JSONObject>>()

        @Volatile var forceNextCreateErrorCode: Int? = null
        @Volatile var forceNextCreateErrorMessage: String? = null
        @Volatile var corruptDownloadForFileId: String? = null
        @Volatile var failNextHealthWith503: Boolean = false

        data class StoredFile(
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
            val metadataJson: JSONObject,
            val createdAt: Long,
            val updatedAt: Long,
            val bytes: ByteArray
        )

        data class SecurityState(
            var mpinHash: String = "",
            var mpinSalt: String = "",
            var wrappedVaultKey: String? = null,
            var keyVersion: Int = 0
        )

        fun start(): String {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.createContext("/") { ex ->
                try {
                    handle(ex)
                } catch (t: Throwable) {
                    sendJson(ex, 500, JSONObject().put("error", t.message ?: "Server error"))
                }
            }
            s.start()
            httpServer = s
            return "http://127.0.0.1:${s.address.port}"
        }

        fun stop() {
            httpServer?.stop(0)
            httpServer = null
        }

        fun filesForUser(uid: String): Map<String, StoredFile> = filesByUser[uid].orEmpty()
        fun latestSettingsForUser(uid: String): CloudBackupSettingsDto? = settingsByUser[uid]

        fun seedUnrecoverableVaultItem(uid: String, fileId: String) {
            val map = filesByUser.getOrPut(uid) { ConcurrentHashMap() }
            val cipher = ByteArray(32) { it.toByte() }
            val sha = sha256Hex(cipher)
            map[fileId] = StoredFile(
                fileId = fileId,
                originalFilename = "legacy_vault.enc",
                displayName = "Legacy Vault",
                categoryId = DskCategories.CAT_DOCUMENTS,
                folderId = null,
                mimeType = "text/plain",
                sizeBytes = cipher.size.toLong(),
                sha256 = sha,
                isInVault = true,
                isFavorite = false,
                isEncryptedPayload = true,
                keyVersion = 0,
                metadataJson = JSONObject(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                bytes = cipher
            )
            securityByUser[uid]?.wrappedVaultKey = null
        }

        private fun handle(ex: HttpExchange) {
            val path = ex.requestURI.path
            val method = ex.requestMethod

            if (method == "GET" && path == "/") {
                sendJson(ex, 200, JSONObject().put("service", "DSK LOCKER Test Backend").put("status", "ok"))
                return
            }
            if (method == "GET" && path == "/health") {
                if (failNextHealthWith503) {
                    failNextHealthWith503 = false
                    sendJson(ex, 503, JSONObject().put("status", "waking_up"))
                    return
                }
                sendJson(ex, 200, JSONObject().put("service", "DSK LOCKER Test Backend").put("status", "ok").put("missingEnv", JSONArray()))
                return
            }

            val authHeader = ex.requestHeaders.getFirst("Authorization").orEmpty()
            val token = authHeader.removePrefix("Bearer ").trim()
            val uid = when (token) {
                "token-user-a" -> "uid-user-a"
                "token-user-b" -> "uid-user-b"
                else -> {
                    sendJson(ex, 401, JSONObject().put("error", "Unauthorized"))
                    return
                }
            }

            val bodyText = ex.requestBody.bufferedReader().readText()

            when {
                method == "POST" && path == "/api/auth/register-owner" -> {
                    sendJson(ex, 200, JSONObject().put("ok", true).put("firebaseUid", uid))
                }

                method == "POST" && path.startsWith("/api/sync/") -> {
                    sendJson(ex, 200, JSONObject().put("ok", true))
                }

                method == "PUT" && path == "/api/security/mpin" -> {
                    val j = JSONObject(bodyText)
                    val st = securityByUser.getOrPut(uid) { SecurityState() }
                    st.mpinHash = j.getString("mpinHash")
                    st.mpinSalt = j.getString("mpinSalt")
                    sendJson(ex, 200, JSONObject().put("ok", true))
                }

                method == "POST" && path == "/api/security/mpin/verify" -> {
                    val j = JSONObject(bodyText)
                    val st = securityByUser[uid]
                    if (st == null || !CryptoManager.verifyMpin(j.getString("mpin"), st.mpinSalt, st.mpinHash)) {
                        sendJson(ex, 401, JSONObject().put("error", "Incorrect MPIN."))
                    } else {
                        sendJson(ex, 200, JSONObject().put("ok", true))
                    }
                }

                method == "POST" && path == "/api/security/recovery-key" -> {
                    val j = JSONObject(bodyText)
                    val st = securityByUser.getOrPut(uid) { SecurityState() }
                    if (j.getString("action") == "wrap") {
                        st.wrappedVaultKey = j.getString("vaultKeyBase64")
                        st.keyVersion = j.optInt("keyVersion", 1)
                        sendJson(ex, 200, JSONObject().put("ok", true))
                    } else {
                        val pin = j.optString("mpin", "")
                        if (st.mpinHash.isNotBlank() && !CryptoManager.verifyMpin(pin, st.mpinSalt, st.mpinHash)) {
                            sendJson(ex, 401, JSONObject().put("error", "Incorrect MPIN."))
                        } else if (st.wrappedVaultKey.isNullOrBlank()) {
                            sendJson(ex, 404, JSONObject().put("error", "No wrapped key stored."))
                        } else {
                            sendJson(ex, 200, JSONObject().put("ok", true).put("vaultKeyBase64", st.wrappedVaultKey).put("keyVersion", st.keyVersion))
                        }
                    }
                }

                method == "PUT" && path == "/api/backup/settings" -> {
                    val j = JSONObject(bodyText)
                    settingsByUser[uid] = CloudBackupSettingsDto(
                        cloudBackupEnabled = j.optBoolean("cloudBackupEnabled", true),
                        wifiOnly = j.optBoolean("wifiOnly", true),
                        backupPhotos = j.optBoolean("backupPhotos", true),
                        backupVideos = j.optBoolean("backupVideos", true),
                        backupDocuments = j.optBoolean("backupDocuments", true)
                    )
                    sendJson(ex, 200, JSONObject().put("ok", true))
                }

                method == "POST" && path == "/api/backup/create" -> {
                    val errCode = forceNextCreateErrorCode
                    if (errCode != null) {
                        sendJson(ex, errCode, JSONObject().put("error", forceNextCreateErrorMessage ?: "Upload failed"))
                        return
                    }
                    val j = JSONObject(bodyText)
                    val filesArr = j.optJSONArray("files") ?: JSONArray()
                    val userMap = filesByUser.getOrPut(uid) { ConcurrentHashMap() }
                    val histList = historyByUser.getOrPut(uid) { mutableListOf() }

                    for (i in 0 until filesArr.length()) {
                        val f = filesArr.getJSONObject(i)
                        val bytes = Base64.decode(f.getString("fileContentBase64"), Base64.DEFAULT)
                        val metaJson = JSONObject()
                            .putOpt("extraction", f.optJSONObject("extraction"))
                            .putOpt("extendedOcrJson", f.opt("extendedOcrJson"))
                            .putOpt("reminder", f.optJSONObject("reminder"))
                        val fileId = f.getString("fileId")
                        userMap[fileId] = StoredFile(
                            fileId = fileId,
                            originalFilename = f.getString("originalFilename"),
                            displayName = f.getString("displayName"),
                            categoryId = f.getString("categoryId"),
                            folderId = f.optString("folderId").takeIf { it.isNotBlank() && it != "null" },
                            mimeType = f.optString("mimeType", "application/octet-stream"),
                            sizeBytes = bytes.size.toLong(),
                            sha256 = sha256Hex(bytes),
                            isInVault = f.optBoolean("isInVault", false),
                            isFavorite = f.optBoolean("isFavorite", false),
                            isEncryptedPayload = f.optBoolean("isEncryptedPayload", false),
                            keyVersion = f.optInt("keyVersion", 0),
                            metadataJson = metaJson,
                            createdAt = f.getLong("createdAt"),
                            updatedAt = f.getLong("updatedAt"),
                            bytes = bytes
                        )
                        histList.add(
                            JSONObject()
                                .put("id", "h_${fileId}_${System.currentTimeMillis()}")
                                .put("file_id", fileId)
                                .put("file_name", f.getString("displayName"))
                                .put("action", j.optString("action", "MANUAL_BACKUP"))
                                .put("status", "SUCCESS")
                                .put("bytes_transferred", bytes.size)
                                .put("message", "Backed up")
                                .put("timestamp", System.currentTimeMillis())
                        )
                    }
                    j.optJSONObject("snapshot")?.let { snapshotsByUser[uid] = it }

                    val totalBytes = userMap.values.sumOf { it.sizeBytes }
                    sendJson(
                        ex,
                        200,
                        JSONObject()
                            .put("ok", true)
                            .put("backupId", "bk_1")
                            .put("storedCount", filesArr.length())
                            .put("backedUpDocuments", userMap.size)
                            .put("totalBackupBytes", totalBytes)
                            .put("quotaLimitBytes", 450L * 1024L * 1024L)
                            .put("lastBackupAt", System.currentTimeMillis())
                    )
                }

                method == "GET" && path == "/api/backup/status" -> {
                    val userMap = filesByUser[uid].orEmpty()
                    val snap = snapshotsByUser[uid] ?: JSONObject()
                    val st = securityByUser[uid]
                    sendJson(
                        ex,
                        200,
                        JSONObject()
                            .put("userId", uid)
                            .put("lastBackupId", "bk_1")
                            .put("lastBackupAt", System.currentTimeMillis())
                            .put("lastBackupStatus", "COMPLETED")
                            .put("backedUpFilesCount", userMap.size)
                            .put("totalBackupSizeBytes", userMap.values.sumOf { it.sizeBytes })
                            .put("photosCount", userMap.values.count { it.categoryId == DskCategories.CAT_PHOTOS })
                            .put("pdfsCount", userMap.values.count { it.mimeType == "application/pdf" })
                            .put("documentsCount", userMap.values.count { it.categoryId == DskCategories.CAT_DOCUMENTS })
                            .put("videosCount", 0)
                            .put("vaultFilesCount", userMap.values.count { it.isInVault })
                            .put("notesCount", snap.optJSONArray("textNotes")?.length() ?: 0)
                            .put("foldersCount", snap.optJSONArray("folders")?.length() ?: 0)
                            .put("categoriesCount", snap.optJSONArray("customCategories")?.length() ?: 0)
                            .put("linksCount", snap.optJSONArray("savedLinks")?.length() ?: 0)
                            .put("quotaLimitBytes", 450L * 1024L * 1024L)
                            .put("maxFileBytes", 20L * 1024L * 1024L)
                            .put("neonDbUsedBytes", userMap.values.sumOf { it.sizeBytes })
                            .put("neonDbLimitBytes", 512L * 1024L * 1024L)
                            .put("hasWrappedRecoveryKey", !st?.wrappedVaultKey.isNullOrBlank())
                            .put("vaultKeyVersion", st?.keyVersion ?: 0)
                            .put("settings", JSONObject().put("cloudBackupEnabled", true).put("wifiOnly", true))
                    )
                }

                method == "GET" && path == "/api/backup/files" -> {
                    val userMap = filesByUser[uid].orEmpty()
                    val arr = JSONArray()
                    userMap.values.forEach { arr.put(it.toJson()) }
                    sendJson(ex, 200, JSONObject().put("files", arr).put("snapshot", snapshotsByUser[uid] ?: JSONObject()))
                }

                method == "GET" && path == "/api/backup/history" -> {
                    val bArr = JSONArray()
                    historyByUser[uid].orEmpty().forEach { bArr.put(it) }
                    val rArr = JSONArray()
                    restoresByUser[uid].orEmpty().forEach { rArr.put(it) }
                    sendJson(ex, 200, JSONObject().put("backups", bArr).put("restores", rArr))
                }

                method == "POST" && path == "/api/backup/restore" -> {
                    val req = JSONObject(bodyText)
                    val filterIds = req.optJSONArray("fileIds")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    val userMap = filesByUser[uid].orEmpty()
                    val matched = userMap.values.filter { filterIds == null || it.fileId in filterIds }
                    val arr = JSONArray()
                    matched.forEach { arr.put(it.toJson()) }
                    val st = securityByUser[uid]
                    sendJson(
                        ex,
                        200,
                        JSONObject()
                            .put("restoreId", "rst_${System.currentTimeMillis()}")
                            .put("totalFiles", matched.size)
                            .put("totalBytes", matched.sumOf { it.sizeBytes })
                            .put("files", arr)
                            .put("snapshot", snapshotsByUser[uid] ?: JSONObject())
                            .put("hasWrappedRecoveryKey", !st?.wrappedVaultKey.isNullOrBlank())
                    )
                }

                method == "POST" && path == "/api/backup/restore/complete" -> {
                    val j = JSONObject(bodyText)
                    restoresByUser.getOrPut(uid) { mutableListOf() }.add(j)
                    sendJson(ex, 200, JSONObject().put("ok", true))
                }

                method == "GET" && path.startsWith("/api/backup/files/") && path.endsWith("/content") -> {
                    val fileId = path.removePrefix("/api/backup/files/").removeSuffix("/content")
                    val row = filesByUser[uid]?.get(fileId)
                    if (row == null) {
                        sendJson(ex, 404, JSONObject().put("error", "Not found"))
                        return
                    }
                    val bytesToSend = if (corruptDownloadForFileId == fileId) {
                        row.bytes.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0].toInt() xor 0xFF).toByte() }
                    } else row.bytes

                    ex.responseHeaders.add("Content-Type", row.mimeType)
                    ex.responseHeaders.add("X-File-Sha256", row.sha256)
                    ex.responseHeaders.add("X-Is-Encrypted-Payload", row.isEncryptedPayload.toString())
                    ex.responseHeaders.add("X-Key-Version", row.keyVersion.toString())
                    ex.sendResponseHeaders(200, bytesToSend.size.toLong())
                    ex.responseBody.use { it.write(bytesToSend) }
                }

                else -> sendJson(ex, 404, JSONObject().put("error", "Route not handled: $method $path"))
            }
        }

        private fun StoredFile.toJson(): JSONObject =
            JSONObject()
                .put("file_id", fileId)
                .put("original_filename", originalFilename)
                .put("display_name", displayName)
                .put("category_id", categoryId)
                .putOpt("folder_id", folderId)
                .put("mime_type", mimeType)
                .put("size_bytes", sizeBytes)
                .put("sha256", sha256)
                .put("is_in_vault", isInVault)
                .put("is_favorite", isFavorite)
                .put("is_encrypted_payload", isEncryptedPayload)
                .put("key_version", keyVersion)
                .put("metadata_json", metadataJson)
                .put("created_at", createdAt)
                .put("updated_at", updatedAt)

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun sendJson(ex: HttpExchange, status: Int, json: JSONObject) {
            val bytes = json.toString().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }
}
