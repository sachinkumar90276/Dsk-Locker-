package com.example

import com.example.data.local.DskCategories
import com.example.data.remote.FirebaseStoragePaths
import com.example.security.CryptoManager
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun `sha256 bytes returns deterministic 64-char hex digest`() {
        val digest = CryptoManager.sha256Bytes("DSK_LOCKER_INTEGRITY".toByteArray())
        assertEquals(64, digest.length)
        assertEquals(digest, CryptoManager.sha256Bytes("DSK_LOCKER_INTEGRITY".toByteArray()))
    }

    @Test
    fun `category classifier maps all major file types into the 6 main categories`() {
        // Exactly 6 main built-in categories
        assertEquals(6, DskCategories.builtInCategories.size)
        assertEquals(
            listOf(
                DskCategories.CAT_DOCUMENTS,
                DskCategories.CAT_PHOTOS,
                DskCategories.CAT_VIDEOS,
                DskCategories.CAT_VIDEO_URLS,
                DskCategories.CAT_WEBSITE_LINKS,
                DskCategories.CAT_TEXT
            ),
            DskCategories.builtInCategories.map { it.id }
        )

        // Images -> Personal Photos; Videos -> Videos; All other files/documents -> Documents
        assertEquals(DskCategories.CAT_PHOTOS, DskCategories.classifyFile("pic.jpg", "image/jpeg"))
        assertEquals(DskCategories.CAT_PHOTOS, DskCategories.classifyFile("photo.png", "image/png"))
        assertEquals(DskCategories.CAT_PHOTOS, DskCategories.classifyFile("IMG_20261121.jpg", "image/jpeg"))
        assertEquals(DskCategories.CAT_VIDEOS, DskCategories.classifyFile("clip.mp4", "video/mp4"))
        assertEquals(DskCategories.CAT_VIDEOS, DskCategories.classifyFile("movie.mkv", "video/x-matroska"))
        assertEquals(DskCategories.CAT_DOCUMENTS, DskCategories.classifyFile("doc.pdf", "application/pdf"))
        assertEquals(DskCategories.CAT_DOCUMENTS, DskCategories.classifyFile("resume.docx", "application/octet-stream"))
        assertEquals(DskCategories.CAT_DOCUMENTS, DskCategories.classifyFile("sheet.xlsx", "application/octet-stream"))
        assertEquals(DskCategories.CAT_DOCUMENTS, DskCategories.classifyFile("saved.html", "text/html"))
        assertEquals(DskCategories.CAT_DOCUMENTS, DskCategories.classifyFile("voice.mp3", "audio/mpeg"))
        assertEquals(DskCategories.CAT_DOCUMENTS, DskCategories.classifyFile("app.apk", "application/vnd.android.package-archive"))
        assertEquals(DskCategories.CAT_DOCUMENTS, DskCategories.classifyFile("bundle.zip", "application/zip"))
        assertEquals(DskCategories.CAT_DOCUMENTS, DskCategories.classifyFile("notes.txt", "text/plain"))

        // Legacy ID compatibility inside Documents
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_PDF, DskCategories.CAT_DOCUMENTS))
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_AADHAAR, DskCategories.CAT_DOCUMENTS))
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_PAN, DskCategories.CAT_DOCUMENTS))
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_WORD, DskCategories.CAT_DOCUMENTS))
        assertTrue(DskCategories.matchesCategory(DskCategories.CAT_AUDIO, DskCategories.CAT_DOCUMENTS))
    }

    @Test
    fun `content-based document detection ignores random filenames and classifies by OCR content`() {
        // Random photo filename containing PAN text promotes to Documents as PAN Card
        val panCategory = DskCategories.classifyFromOcrContent(
            rawText = "INCOME TAX DEPARTMENT GOVT OF INDIA Permanent Account Number ABCDE1234F",
            detectedDocName = null,
            detectedDocType = null,
            originalFilename = "IMG_20261121.jpg",
            mimeType = "image/jpeg",
            currentCategoryId = DskCategories.CAT_PHOTOS
        )
        assertEquals(DskCategories.CAT_DOCUMENTS, panCategory)
        assertEquals(
            "PAN Card",
            DskCategories.detectDocumentTypeFromContent(
                rawText = "INCOME TAX DEPARTMENT GOVT OF INDIA Permanent Account Number ABCDE1234F",
                originalFilename = "IMG_20261121.jpg",
                mimeType = "image/jpeg"
            )
        )

        // Personal photo with no document keywords remains Personal Photos
        val photoCategory = DskCategories.classifyFromOcrContent(
            rawText = "",
            detectedDocName = null,
            detectedDocType = "Unclassified Document",
            originalFilename = "IMG_holiday.jpg",
            mimeType = "image/jpeg",
            currentCategoryId = DskCategories.CAT_PHOTOS
        )
        assertEquals(DskCategories.CAT_PHOTOS, photoCategory)
    }

    @Test
    fun `firebase storage private path routes categories to owner uid folders`() {
        val uid = "fb_uid_owner_99"
        assertEquals(
            "users/fb_uid_owner_99/photos/f1_selfie.jpg",
            FirebaseStoragePaths.buildPrivateObjectPath(uid, "f1", "selfie.jpg", DskCategories.CAT_PHOTOS, false)
        )
        assertEquals(
            "users/fb_uid_owner_99/videos/f2_clip.mp4",
            FirebaseStoragePaths.buildPrivateObjectPath(uid, "f2", "clip.mp4", DskCategories.CAT_VIDEOS, false)
        )
        assertEquals(
            "users/fb_uid_owner_99/audio/f3_note.mp3",
            FirebaseStoragePaths.buildPrivateObjectPath(uid, "f3", "note.mp3", DskCategories.CAT_AUDIO, false)
        )
        assertEquals(
            "users/fb_uid_owner_99/documents/f4_pan.pdf",
            FirebaseStoragePaths.buildPrivateObjectPath(uid, "f4", "pan.pdf", DskCategories.CAT_DOCUMENTS, false)
        )
        assertEquals(
            "users/fb_uid_owner_99/vault/f5_secret.pdf.enc",
            FirebaseStoragePaths.buildPrivateObjectPath(uid, "f5", "secret.pdf", DskCategories.CAT_DOCUMENTS, true)
        )
    }
}
