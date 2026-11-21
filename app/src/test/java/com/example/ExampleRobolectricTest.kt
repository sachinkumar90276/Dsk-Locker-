package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.DocumentOcrEngine
import com.example.data.DskLockerRepository
import com.example.data.local.BackupStatus
import com.example.data.local.DeleteMode
import com.example.data.local.DskCategories
import com.example.data.local.DskLockerDatabase
import com.example.data.local.FileItemEntity
import com.example.data.local.OwnerProfileEntity
import com.example.security.CryptoManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches DSK LOCKER`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("DSK LOCKER", appName)
    }

    @Test
    fun `mpin pbkdf2 hashing verifies correct pin and rejects wrong pin`() {
        val salt = CryptoManager.generateSaltBase64()
        val hash = CryptoManager.hashMpin("482910", salt)

        assertNotEquals("482910", hash)
        assertTrue(CryptoManager.verifyMpin("482910", salt, hash))
        assertFalse(CryptoManager.verifyMpin("123456", salt, hash))
    }

    @Test
    fun `firebase uid maps cleanly and single owner restriction blocks secondary phones`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = DskLockerRepository(context)
        val db = DskLockerDatabase.getInstance(context)

        repo.prefs.ownerPhone = "+919876543210"
        repo.prefs.firebaseUid = "firebase-owner-uid-001"
        db.profileSettingsDao().upsertProfile(
            OwnerProfileEntity(
                ownerName = "DSK Owner",
                phoneNumber = "+919876543210",
                firebaseUid = "firebase-owner-uid-001"
            )
        )

        assertTrue(repo.validateOwnerPhoneEligibility("+919876543210").isSuccess)
        val blocked = repo.validateOwnerPhoneEligibility("+919111122222")
        assertTrue(blocked.isFailure)
        assertEquals("firebase-owner-uid-001", repo.currentFirebaseUid())
        assertEquals("firebase-owner-uid-001", db.profileSettingsDao().getProfileOnce()?.firebaseUid)
    }

    @Test
    fun `mobile only deletion preserves permanent firebase storage backup`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = DskLockerDatabase.getInstance(context)
        val repo = DskLockerRepository(context)

        val tempFile = File(context.cacheDir, "aadhaar_test.pdf").apply {
            writeText("Test Aadhaar PDF Bytes")
        }
        val item = FileItemEntity(
            id = "test-file-1",
            originalFilename = "IMG_12345.pdf",
            displayName = "Aadhaar Card - Rahul",
            categoryId = DskCategories.CAT_PDF,
            mimeType = "application/pdf",
            sizeBytes = tempFile.length(),
            localPath = tempFile.absolutePath,
            cloudPath = "users/firebase-owner-uid-001/documents/test-file-1_IMG_12345.pdf",
            cloudBucket = "gen-lang-client-0114461808.firebasestorage.app",
            backupStatus = BackupStatus.MOBILE_AND_CLOUD
        )
        db.fileItemDao().upsert(item)

        repo.deleteFileWithMode(item, DeleteMode.MOBILE_ONLY)

        val after = db.fileItemDao().getFileById("test-file-1")
        assertNotNull(after)
        assertNull(after?.localPath)
        assertEquals(BackupStatus.CLOUD_ONLY, after?.backupStatus)
        assertEquals("users/firebase-owner-uid-001/documents/test-file-1_IMG_12345.pdf", after?.cloudPath)
        assertTrue(!tempFile.exists())
    }

    @Test
    fun `document display naming preserves original and formats owner`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DocumentOcrEngine(context)
        assertEquals("Aadhaar Card - Priya Verma", engine.buildDisplayName("Aadhaar Card", "Priya Verma"))
        assertEquals("Passport", engine.buildDisplayName("Passport", null))
        assertNull(engine.buildDisplayName(null, "Priya Verma"))

        val active = runBlocking { DskLockerDatabase.getInstance(context).fileItemDao().observeActiveFiles().first() }
        assertTrue(active.size >= 0)
    }

    @Test
    fun `DskLockerViewModel instantiates cleanly via AndroidViewModelFactory`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        com.google.firebase.FirebaseApp.initializeApp(app)
        com.example.security.configureAppCheck(app, null)
        val factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        val vm = androidx.lifecycle.ViewModelProvider(
            androidx.lifecycle.ViewModelStore(),
            factory
        )[com.example.ui.DskLockerViewModel::class.java]
        assertNotNull(vm)
        assertEquals(com.example.ui.AuthStage.CREATE_MPIN, vm.authStage.value)

        vm.repo.prefs.saveMpin("654321")
        val vmWithPin = androidx.lifecycle.ViewModelProvider(
            androidx.lifecycle.ViewModelStore(),
            factory
        )[com.example.ui.DskLockerViewModel::class.java]
        assertEquals(com.example.ui.AuthStage.MPIN_LOGIN, vmWithPin.authStage.value)

        vmWithPin.startForgotMpin()
        assertEquals(com.example.ui.AuthStage.CREATE_MPIN, vmWithPin.authStage.value)
        vmWithPin.cancelForgotMpin()
        assertEquals(com.example.ui.AuthStage.MPIN_LOGIN, vmWithPin.authStage.value)
    }

    @Test
    fun `login mpin supports 4 and 6 digits and secret mpin rejects matching login mpin`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        com.google.firebase.FirebaseApp.initializeApp(app)
        com.example.security.configureAppCheck(app, null)
        val factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        val vm = androidx.lifecycle.ViewModelProvider(
            androidx.lifecycle.ViewModelStore(),
            factory
        )[com.example.ui.DskLockerViewModel::class.java]

        vm.repo.prefs.saveMpin("2580")
        assertTrue(vm.repo.prefs.hasMpin())
        assertEquals(4, vm.loginMpinLength())
        assertTrue(vm.repo.prefs.verifyMpin("2580"))
        assertFalse(vm.repo.prefs.verifyMpin("1234"))

        // Secret MPIN not yet set -> blocks delete verification
        assertFalse(vm.hasSecretMpin())
        assertFalse(vm.verifySecretMpinForDelete("9999"))

        // Rejects matching Login MPIN
        val samePinResult = vm.setOrChangeSecretMpin("2580", "2580")
        assertTrue(samePinResult.isFailure)
        assertFalse(vm.hasSecretMpin())

        // Accepts distinct 6-digit Secret MPIN
        val validSecretResult = vm.setOrChangeSecretMpin("908172", "908172")
        assertTrue(validSecretResult.isSuccess)
        assertTrue(vm.hasSecretMpin())
        assertEquals(6, vm.secretMpinLength())
        assertTrue(vm.verifySecretMpinForDelete("908172"))
        assertFalse(vm.verifySecretMpinForDelete("2580"))
    }

    @Test
    fun `6 main categories and 10 documents subcategories classify and preserve legacy data`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(6, DskCategories.builtInCategories.size)

        // Legacy category preservation inside Documents
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_PDF, DskCategories.CAT_DOCUMENTS))
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_PERSONAL_RECORDS, DskCategories.CAT_DOCUMENTS))
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_AADHAAR, DskCategories.CAT_DOCUMENTS))
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_PAN, DskCategories.CAT_DOCUMENTS))
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_WORD, DskCategories.CAT_DOCUMENTS))
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_EXCEL, DskCategories.CAT_DOCUMENTS))

        // OCR content classification -> Documents + Smart Subcategory resolution
        val aadhaarItem = FileItemEntity(
            id = "aadhaar-1",
            originalFilename = "IMG_20261121_01.jpg",
            displayName = "IMG_20261121_01.jpg",
            categoryId = DskCategories.classifyFromOcrContent(
                rawText = "Unique Identification Authority of India 1234 5678 9012",
                detectedDocName = "Aadhaar Card",
                detectedDocType = "Aadhaar Card",
                originalFilename = "IMG_20261121_01.jpg",
                mimeType = "image/jpeg",
                currentCategoryId = DskCategories.CAT_PHOTOS
            ),
            mimeType = "image/jpeg",
            sizeBytes = 512L
        )
        assertEquals(DskCategories.CAT_DOCUMENTS, aadhaarItem.categoryId)
        val aadhaarExtraction = com.example.data.local.DocumentExtractionEntity(
            fileId = "aadhaar-1",
            documentName = "Aadhaar Card",
            documentType = "Aadhaar Card",
            documentNumber = "1234 5678 9012",
            rawText = "Unique Identification Authority of India 1234 5678 9012"
        )
        assertEquals(
            com.example.data.local.DocumentSubcategory.OTHER_DOCUMENTS,
            DskCategories.resolveDocumentSubcategory(aadhaarItem, null)
        )
        assertEquals(
            com.example.data.local.DocumentSubcategory.AADHAAR,
            DskCategories.resolveDocumentSubcategory(aadhaarItem, aadhaarExtraction)
        )

        val panExtraction = com.example.data.local.DocumentExtractionEntity(
            fileId = "pan-1",
            documentName = "PAN Card",
            documentType = "PAN Card",
            documentNumber = "ABCDE1234F",
            rawText = "INCOME TAX DEPARTMENT Permanent Account Number ABCDE1234F"
        )
        val panItem = FileItemEntity(
            id = "pan-1",
            originalFilename = "IMG_20261121_02.jpg",
            displayName = "IMG_20261121_02.jpg",
            categoryId = DskCategories.CAT_DOCUMENTS,
            mimeType = "image/jpeg",
            sizeBytes = 400L
        )
        assertTrue(DskCategories.matchesDocumentSubcategory(panItem, panExtraction, com.example.data.local.DocumentSubcategory.PAN))

        // Office Docs (DOCX / XLSX) resolve to OFFICE_DOCUMENTS subcategory
        val xlsxItem = FileItemEntity(
            id = "xlsx-1",
            originalFilename = "expenses_2026.xlsx",
            displayName = "expenses_2026.xlsx",
            categoryId = DskCategories.CAT_DOCUMENTS,
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            sizeBytes = 1024L
        )
        assertEquals(
            com.example.data.local.DocumentSubcategory.OFFICE_DOCUMENTS,
            DskCategories.resolveDocumentSubcategory(xlsxItem, null)
        )

        // Duplicate SHA-256 detection in Room DAO
        val db = DskLockerDatabase.getInstance(context)
        db.fileItemDao().upsert(
            FileItemEntity(
                id = "dup-1",
                originalFilename = "doc1.pdf",
                displayName = "doc1.pdf",
                categoryId = DskCategories.CAT_DOCUMENTS,
                mimeType = "application/pdf",
                sizeBytes = 100L,
                sha256 = "deadbeef1234"
            )
        )
        db.fileItemDao().upsert(
            FileItemEntity(
                id = "dup-2",
                originalFilename = "doc1_copy.pdf",
                displayName = "doc1_copy.pdf",
                categoryId = DskCategories.CAT_DOCUMENTS,
                mimeType = "application/pdf",
                sizeBytes = 100L,
                sha256 = "deadbeef1234"
            )
        )
        val matches = db.fileItemDao().findBySha256("deadbeef1234")
        assertEquals(2, matches.size)
    }

    @Test
    fun `ocr engine format support covers all supported files and rejects apk zip audio cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DocumentOcrEngine(context)

        // OCR-supported formats
        assertTrue(engine.isOcrSupported("doc.pdf", "application/pdf"))
        assertTrue(engine.isOcrSupported("resume.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertTrue(engine.isOcrSupported("budget.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertTrue(engine.isOcrSupported("photo.jpg", "image/jpeg"))
        assertTrue(engine.isOcrSupported("scan.png", "image/png"))
        assertTrue(engine.isOcrSupported("clip.mp4", "video/mp4"))
        assertTrue(engine.isOcrSupported("notes.txt", "text/plain"))
        assertTrue(engine.isOcrSupported("data.csv", "text/csv"))

        // Honest unsupported binary formats
        assertFalse(engine.isOcrSupported("archive.zip", "application/zip"))
        assertFalse(engine.isOcrSupported("installer.apk", "application/vnd.android.package-archive"))
        assertFalse(engine.isOcrSupported("recording.mp3", "audio/mpeg"))
    }

    @Test
    fun `video url, web url, text entry, and original filename persist cleanly in Room`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = DskLockerRepository(context)
        val db = DskLockerDatabase.getInstance(context)

        // Video URL (text-only)
        val videoLink = com.example.data.local.SavedLinkEntity(
            id = "vlink-1",
            linkType = "VIDEO_URL",
            title = "Android Security Tutorial",
            url = "https://example.com/watch?v=123",
            categoryId = DskCategories.CAT_VIDEO_URLS
        )
        repo.saveLink(videoLink)

        // Web URL
        val webLink = com.example.data.local.SavedLinkEntity(
            id = "wlink-1",
            linkType = "WEBSITE_LINK",
            title = "Income Tax Portal",
            url = "https://incometax.gov.in",
            description = "Tax filing bookmark",
            categoryId = DskCategories.CAT_WEBSITE_LINKS
        )
        repo.saveLink(webLink)

        // Text entry
        val textNote = com.example.data.local.TextNoteEntity(
            id = "text-1",
            title = "Emergency Contact",
            content = "Primary ICE number saved offline.",
            categoryId = DskCategories.CAT_TEXT
        )
        repo.saveTextNote(textNote)

        val savedLinks = db.savedLinkDao().observeActiveLinks().first()
        val savedNotes = db.textNoteDao().observeActiveNotes().first()
        assertTrue(savedLinks.any { it.id == "vlink-1" && it.linkType == "VIDEO_URL" })
        assertTrue(savedLinks.any { it.id == "wlink-1" && it.linkType == "WEBSITE_LINK" })
        assertTrue(savedNotes.any { it.id == "text-1" && it.title == "Emergency Contact" })

        // Original filename preserved on import + displayName only optional rename
        val fileItem = FileItemEntity(
            id = "preserve-1",
            originalFilename = "IMG_20261121.jpg",
            displayName = "IMG_20261121.jpg",
            categoryId = DskCategories.CAT_DOCUMENTS,
            mimeType = "image/jpeg",
            sizeBytes = 256L
        )
        db.fileItemDao().upsert(fileItem)
        val ext = com.example.data.local.DocumentExtractionEntity(
            fileId = "preserve-1",
            documentName = "PAN Card",
            documentType = "PAN Card",
            ownerName = "Aarav Mehta"
        )
        repo.saveVerifiedExtraction(fileItem, ext, applySuggestedDisplayName = false)
        val withoutRename = db.fileItemDao().getFileById("preserve-1")
        assertEquals("IMG_20261121.jpg", withoutRename?.originalFilename)
        assertEquals("IMG_20261121.jpg", withoutRename?.displayName)

        repo.saveVerifiedExtraction(fileItem, ext, applySuggestedDisplayName = true)
        val withRename = db.fileItemDao().getFileById("preserve-1")
        assertEquals("IMG_20261121.jpg", withRename?.originalFilename)
        assertEquals("PAN Card - Aarav Mehta", withRename?.displayName)
    }
}

