package com.example.data.local

enum class BackupStatus(val label: String) {
    MOBILE_AND_CLOUD("MOBILE + CLOUD"),
    CLOUD_ONLY("CLOUD ONLY"),
    MOBILE_ONLY("MOBILE ONLY"),
    BACKUP_PENDING("BACKUP PENDING"),
    BACKUP_FAILED("BACKUP FAILED")
}

enum class DeleteMode(val label: String, val subtitle: String) {
    MOBILE_ONLY(
        "DELETE FROM MOBILE ONLY",
        "Removes file from phone storage. Cloud backup remains safe."
    ),
    CLOUD_ONLY(
        "DELETE FROM CLOUD ONLY",
        "Removes file from Firebase Storage Cloud. Local phone copy stays intact."
    ),
    BOTH(
        "DELETE FROM BOTH",
        "Moves local copy to Recycle Bin and removes cloud backup."
    )
}

enum class SortOption(val label: String) {
    NEWEST_FIRST("Newest First"),
    OLDEST_FIRST("Oldest First"),
    NAME_ASC("Name A-Z"),
    NAME_DESC("Name Z-A"),
    LARGEST_FIRST("Largest First"),
    SMALLEST_FIRST("Smallest First")
}

enum class DocumentSubcategory(val id: String, val label: String) {
    ALL("all", "All"),
    AADHAAR("aadhaar", "Aadhaar"),
    PAN("pan", "PAN"),
    PASSPORT("passport", "Passport"),
    DRIVING_LICENCE("driving_licence", "Driving Licence"),
    VOTER_ID("voter_id", "Voter ID"),
    RESUME("resume", "Resume"),
    CERTIFICATES("certificates", "Certificates"),
    BILLS("bills", "Bills"),
    OFFICE_DOCUMENTS("office_documents", "Office Documents"),
    OTHER_DOCUMENTS("other_documents", "Other Documents")
}

data class BuiltInCategory(
    val id: String,
    val title: String,
    val iconKey: String,
    val isLinkCategory: Boolean = false
)

object DskCategories {
    // 6 Main Dashboard Categories
    const val CAT_DOCUMENTS = "documents"
    const val CAT_PHOTOS = "photos"
    const val CAT_VIDEOS = "videos"
    const val CAT_VIDEO_URLS = "video_urls"
    const val CAT_WEBSITE_LINKS = "website_links"
    const val CAT_TEXT = "text_files"

    // Legacy IDs preserved strictly for existing stored Room records (zero data loss)
    const val CAT_AADHAAR = "aadhaar_card"
    const val CAT_PAN = "pan_card"
    const val CAT_DRIVING_LICENCE = "driving_licence"
    const val CAT_VOTER_ID = "voter_id"
    const val CAT_PASSPORT = "passport"
    const val CAT_WEB_PAGES = "web_pages"
    const val CAT_OFFICE_DOCS = "office_documents"
    const val CAT_OTHER = "other_files"
    const val CAT_PDF = "pdf_documents"
    const val CAT_WORD = "word_documents"
    const val CAT_EXCEL = "excel_documents"
    const val CAT_AUDIO = "audio"
    const val CAT_APK = "apk_files"
    const val CAT_ARCHIVE = "zip_rar"
    const val CAT_PERSONAL_RECORDS = "personal_records"

    private val legacyDocumentCategoryIds = setOf(
        CAT_DOCUMENTS,
        CAT_AADHAAR,
        CAT_PAN,
        CAT_DRIVING_LICENCE,
        CAT_VOTER_ID,
        CAT_PASSPORT,
        CAT_OFFICE_DOCS,
        CAT_OTHER,
        CAT_WEB_PAGES,
        CAT_PDF,
        CAT_WORD,
        CAT_EXCEL,
        CAT_AUDIO,
        CAT_APK,
        CAT_ARCHIVE,
        CAT_PERSONAL_RECORDS
    )

    val builtInCategories = listOf(
        BuiltInCategory(CAT_DOCUMENTS, "Documents", "documents"),
        BuiltInCategory(CAT_PHOTOS, "Personal Photos", "photo"),
        BuiltInCategory(CAT_VIDEOS, "Videos", "video"),
        BuiltInCategory(CAT_VIDEO_URLS, "Video URL", "video_link", isLinkCategory = true),
        BuiltInCategory(CAT_WEBSITE_LINKS, "Web URL", "web_link", isLinkCategory = true),
        BuiltInCategory(CAT_TEXT, "Text", "text")
    )

    fun matchesCategory(itemCategoryId: String, filterCategoryId: String?): Boolean {
        if (filterCategoryId == null) return true
        if (itemCategoryId == filterCategoryId) return true
        return when (filterCategoryId) {
            CAT_DOCUMENTS -> itemCategoryId in legacyDocumentCategoryIds
            else -> false
        }
    }

    fun classifyFile(fileName: String, mimeType: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()

        return when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic") -> CAT_PHOTOS
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v") -> CAT_VIDEOS
            else -> CAT_DOCUMENTS
        }
    }

    fun detectDocumentTypeFromContent(
        rawText: String,
        detectedDocName: String? = null,
        detectedDocType: String? = null,
        originalFilename: String = "",
        mimeType: String = ""
    ): String? {
        val ocrCombined = listOfNotNull(detectedDocName, detectedDocType, rawText.take(6000))
            .joinToString(" ")
            .lowercase()

        if (ocrCombined.isNotBlank()) {
            when {
                ocrCombined.contains("aadhaar") || ocrCombined.contains("aadhar") || ocrCombined.contains("uidai") ||
                    ocrCombined.contains("unique identification authority") -> return "Aadhaar Card"

                ocrCombined.contains("permanent account number") || ocrCombined.contains("income tax department") ||
                    ocrCombined.contains("pan card") || Regex("""\b[a-z]{5}[0-9]{4}[a-z]\b""").containsMatchIn(ocrCombined) ->
                    return "PAN Card"

                ocrCombined.contains("driving licence") || ocrCombined.contains("driving license") ||
                    ocrCombined.contains("motor vehicles") || ocrCombined.contains("dl no") ->
                    return "Driving Licence"

                ocrCombined.contains("election commission") || ocrCombined.contains("elector photo identity") ||
                    ocrCombined.contains("voter id") || ocrCombined.contains("voter card") || ocrCombined.contains("epic no") ->
                    return "Voter ID"

                ocrCombined.contains("passport") &&
                    (ocrCombined.contains("republic") || ocrCombined.contains("nationality") || ocrCombined.contains("country code")) ->
                    return "Passport"

                ocrCombined.contains("curriculum vitae") || ocrCombined.contains("resume") ||
                    (ocrCombined.contains("experience") && ocrCombined.contains("education") && ocrCombined.contains("skills")) ->
                    return "Resume"

                ocrCombined.contains("certificate") || ocrCombined.contains("marksheet") ||
                    ocrCombined.contains("university") || ocrCombined.contains("diploma") ||
                    ocrCombined.contains("board of secondary") || ocrCombined.contains("grade sheet") ->
                    return "Certificate"

                ocrCombined.contains("tax invoice") || ocrCombined.contains("invoice") ||
                    ocrCombined.contains("gstin") || ocrCombined.contains("bill no") ||
                    ocrCombined.contains("total amount") || ocrCombined.contains("amount due") ||
                    ocrCombined.contains("electricity bill") || ocrCombined.contains("payment receipt") ->
                    return "Bill / Invoice"
            }
        }

        val ext = originalFilename.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        if (ext in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "odt", "ods", "rtf") ||
            mime.contains("wordprocessingml") || mime == "application/msword" ||
            mime.contains("spreadsheetml") || mime.contains("presentationml")
        ) {
            return "Office Document"
        }

        val cleanType = detectedDocType?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("Unclassified Document", ignoreCase = true)
        }
        val cleanName = detectedDocName?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("null", ignoreCase = true)
        }
        return cleanType ?: cleanName
    }

    fun classifyFromOcrContent(
        rawText: String,
        detectedDocName: String?,
        detectedDocType: String?,
        originalFilename: String,
        mimeType: String,
        currentCategoryId: String
    ): String {
        val inferredType = detectDocumentTypeFromContent(
            rawText = rawText,
            detectedDocName = detectedDocName,
            detectedDocType = detectedDocType,
            originalFilename = originalFilename,
            mimeType = mimeType
        )

        val isRecognizedDoc = inferredType != null &&
            !inferredType.equals("Unclassified Document", ignoreCase = true)

        return when {
            isRecognizedDoc -> CAT_DOCUMENTS
            currentCategoryId in legacyDocumentCategoryIds -> CAT_DOCUMENTS
            else -> currentCategoryId
        }
    }

    fun resolveDocumentSubcategory(
        file: FileItemEntity,
        extraction: DocumentExtractionEntity?
    ): DocumentSubcategory {
        val docText = listOfNotNull(
            extraction?.documentName,
            extraction?.documentType,
            extraction?.rawText?.take(4000)
        ).joinToString(" ").lowercase()

        when {
            file.categoryId == CAT_AADHAAR ||
                docText.contains("aadhaar") || docText.contains("aadhar") || docText.contains("uidai") ->
                return DocumentSubcategory.AADHAAR

            file.categoryId == CAT_PAN ||
                docText.contains("pan card") || docText.contains("permanent account number") ||
                docText.contains("income tax department") ||
                Regex("""\b[a-z]{5}[0-9]{4}[a-z]\b""").containsMatchIn(docText) ->
                return DocumentSubcategory.PAN

            file.categoryId == CAT_PASSPORT ||
                (docText.contains("passport") && !docText.contains("photo")) ->
                return DocumentSubcategory.PASSPORT

            file.categoryId == CAT_DRIVING_LICENCE ||
                docText.contains("driving licence") || docText.contains("driving license") || docText.contains("dl no") ->
                return DocumentSubcategory.DRIVING_LICENCE

            file.categoryId == CAT_VOTER_ID ||
                docText.contains("voter id") || docText.contains("election commission") || docText.contains("elector photo") ->
                return DocumentSubcategory.VOTER_ID

            docText.contains("resume") || docText.contains("curriculum vitae") ->
                return DocumentSubcategory.RESUME

            docText.contains("certificate") || docText.contains("marksheet") || docText.contains("diploma") ||
                docText.contains("academic") ->
                return DocumentSubcategory.CERTIFICATES

            docText.contains("bill") || docText.contains("invoice") || docText.contains("receipt") || docText.contains("gstin") ->
                return DocumentSubcategory.BILLS
        }

        val ext = file.originalFilename.substringAfterLast('.', "").lowercase()
        val mime = file.mimeType.lowercase()
        if (file.categoryId in setOf(CAT_OFFICE_DOCS, CAT_WORD, CAT_EXCEL) ||
            ext in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "odt", "ods", "rtf") ||
            mime.contains("wordprocessingml") || mime == "application/msword" ||
            mime.contains("spreadsheetml") || mime.contains("presentationml") ||
            docText.contains("office document")
        ) {
            return DocumentSubcategory.OFFICE_DOCUMENTS
        }

        return DocumentSubcategory.OTHER_DOCUMENTS
    }

    fun matchesDocumentSubcategory(
        file: FileItemEntity,
        extraction: DocumentExtractionEntity?,
        subcategory: DocumentSubcategory
    ): Boolean {
        if (!matchesCategory(file.categoryId, CAT_DOCUMENTS)) return false
        if (subcategory == DocumentSubcategory.ALL) return true
        return resolveDocumentSubcategory(file, extraction) == subcategory
    }

    fun labelFor(categoryId: String, customCategories: List<CustomCategoryEntity> = emptyList()): String {
        val mappedLegacy = when (categoryId) {
            CAT_DOCUMENTS, CAT_AADHAAR, CAT_PAN, CAT_DRIVING_LICENCE, CAT_VOTER_ID, CAT_PASSPORT,
            CAT_OFFICE_DOCS, CAT_OTHER, CAT_WEB_PAGES, CAT_PDF, CAT_WORD, CAT_EXCEL,
            CAT_AUDIO, CAT_APK, CAT_ARCHIVE, CAT_PERSONAL_RECORDS -> "Documents"
            else -> null
        }
        return builtInCategories.firstOrNull { it.id == categoryId }?.title
            ?: mappedLegacy
            ?: customCategories.firstOrNull { it.id == categoryId }?.name
            ?: "Documents"
    }
}
