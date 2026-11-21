package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Xml
import com.example.data.local.DocumentExtractionEntity
import com.example.data.local.DskCategories
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "DocumentOcrEngine"

sealed interface DocumentOcrResult {
    data class Success(
        val extraction: DocumentExtractionEntity,
        val suggestedDisplayName: String?,
        val detectedCategoryId: String? = null
    ) : DocumentOcrResult

    data class PartialRawTextOnly(
        val rawText: String,
        val errorMessage: String,
        val detectedDocumentName: String? = null,
        val detectedDocumentType: String? = null,
        val detectedDocumentNumber: String? = null,
        val detectedCategoryId: String? = null
    ) : DocumentOcrResult

    data class Error(val message: String) : DocumentOcrResult
}

class DocumentOcrEngine(private val context: Context) {

    private val extractionSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "documentName" to Schema.string("Official name of the document (e.g., Aadhaar Card, PAN Card, Passport, Driving Licence, Voter ID, Resume, Certificate, Bill / Invoice, Office Document)", nullable = true),
                "documentType" to Schema.string("Document category/type (Aadhaar Card, PAN Card, Passport, Driving Licence, Voter ID, Resume, Certificate, Bills, Office Document, or Unclassified Document if unclear)", nullable = true),
                "documentNumber" to Schema.string("Primary ID or document number explicitly printed on the document", nullable = true),
                "ownerName" to Schema.string("Full name of the document holder/owner", nullable = true),
                "fatherName" to Schema.string("Father's name if explicitly printed", nullable = true),
                "motherName" to Schema.string("Mother's name if explicitly printed", nullable = true),
                "dateOfBirth" to Schema.string("Date of birth in YYYY-MM-DD or original format if present", nullable = true),
                "issueDate" to Schema.string("Issue date in YYYY-MM-DD if explicitly present", nullable = true),
                "expiryDate" to Schema.string("Expiry/valid-until date in YYYY-MM-DD if explicitly present", nullable = true),
                "address" to Schema.string("Full postal address if printed on document", nullable = true),
                "mobileNumber" to Schema.string("Phone or mobile number if printed on document", nullable = true),
                "organizationName" to Schema.string("Issuing authority, institution, bank, or organization name", nullable = true),
                "registrationNumber" to Schema.string("Registration, enrollment, or reference number if separate from document number", nullable = true)
            ),
            optionalProperties = listOf(
                "documentName", "documentType", "documentNumber", "ownerName",
                "fatherName", "motherName", "dateOfBirth", "issueDate", "expiryDate",
                "address", "mobileNumber", "organizationName", "registrationNumber"
            )
        )
    }

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = extractionSchema
                temperature = 0.0f
            }
        )
    }

    fun isOcrSupported(originalFilename: String, mimeType: String): Boolean {
        val ext = originalFilename.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        return mime.startsWith("image/") ||
            mime.startsWith("video/") ||
            mime == "application/pdf" ||
            mime.startsWith("text/") ||
            mime.contains("wordprocessingml") ||
            mime == "application/msword" ||
            mime.contains("spreadsheetml") ||
            mime.contains("presentationml") ||
            ext in setOf(
                "jpg", "jpeg", "png", "webp", "bmp", "heic",
                "mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v",
                "pdf", "doc", "docx", "xls", "xlsx", "pptx",
                "txt", "md", "csv", "json", "xml", "log", "html", "htm", "rtf", "odt", "ods"
            )
    }

    suspend fun analyzeDocument(
        fileId: String,
        file: File,
        originalFilename: String,
        mimeType: String,
        onProgress: (stageLabel: String, fraction: Float) -> Unit = { _, _ -> }
    ): DocumentOcrResult = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext DocumentOcrResult.Error("Local file is missing. Restore from Cloud before running OCR.")
        }

        val ext = originalFilename.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        if (!isOcrSupported(originalFilename, mime)) {
            val label = ext.ifBlank { mime }.uppercase()
            return@withContext DocumentOcrResult.Error("Unsupported OCR format ($label). File saved safely without OCR.")
        }

        var previewBitmap: Bitmap? = null
        val extractedTextBuilder = StringBuilder()

        try {
            onProgress("SCANNING DOCUMENT", 0.30f)
            when {
                mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic") -> {
                    val bmp = decodeScaledBitmap(file, 1600)
                        ?: return@withContext DocumentOcrResult.Error("Could not decode image file ($ext).")
                    previewBitmap = bmp
                    onProgress("SCANNING DOCUMENT", 0.45f)
                    val ocr = runCatching { recognizeBitmapText(bmp) }.getOrDefault("")
                    extractedTextBuilder.append(ocr)
                }

                mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v") -> {
                    onProgress("SCANNING DOCUMENT", 0.45f)
                    val vidResult = extractVideoFrameText(file)
                    previewBitmap = vidResult.first
                    extractedTextBuilder.append(vidResult.second)
                }

                mime == "application/pdf" || ext == "pdf" -> {
                    onProgress("SCANNING DOCUMENT", 0.45f)
                    val pdfResult = extractPdfPagesAndText(file)
                    previewBitmap = pdfResult.first
                    extractedTextBuilder.append(pdfResult.second)
                }

                ext == "docx" || mime.contains("wordprocessingml") -> {
                    extractedTextBuilder.append(extractDocxText(file))
                }

                ext == "xlsx" || mime.contains("spreadsheetml") -> {
                    extractedTextBuilder.append(extractXlsxText(file))
                }

                ext == "pptx" || mime.contains("presentationml") -> {
                    extractedTextBuilder.append(extractPptxText(file))
                }

                ext == "doc" || ext == "xls" || mime == "application/msword" -> {
                    extractedTextBuilder.append(extractLegacyDocPrintableText(file))
                }

                ext in setOf("html", "htm") || mime == "text/html" -> {
                    extractedTextBuilder.append(extractHtmlVisibleText(file.readText(Charsets.UTF_8).take(80_000)))
                }

                else -> {
                    extractedTextBuilder.append(file.readText(Charsets.UTF_8).take(40_000))
                }
            }

            val rawText = extractedTextBuilder.toString().trim()
            val baseCategory = DskCategories.classifyFile(originalFilename, mime)
            val isVideoOrPhoto = baseCategory == DskCategories.CAT_PHOTOS || baseCategory == DskCategories.CAT_VIDEOS

            if (rawText.isBlank() && previewBitmap == null) {
                return@withContext DocumentOcrResult.Error("No readable text found in $originalFilename. File saved safely.")
            }

            onProgress("EXTRACTING INFORMATION", 0.65f)
            val heuristic = detectBasicDocInfo(rawText, originalFilename, mime, baseCategory)
            val localNumber = extractExplicitDocumentNumber(rawText, heuristic.second)

            val promptText = buildString {
                appendLine("Analyze this personal file carefully and extract structured metadata.")
                appendLine("STRICT RULES:")
                appendLine("1. Extract ONLY fields that are explicitly and clearly present in the document text or image.")
                appendLine("2. If ANY field is missing, blurry, ambiguous, or absent, set it to null. NEVER guess or invent data.")
                appendLine("3. Classify documentType as one of: Aadhaar Card, PAN Card, Passport, Driving Licence, Voter ID, Resume, Certificate, Bills, Office Document — OR 'Unclassified Document' if unclear.")
                appendLine("4. Normalize issueDate, expiryDate, and dateOfBirth to YYYY-MM-DD only when full day/month/year is clearly readable.")
                appendLine("Original filename: $originalFilename")
                if (rawText.isNotBlank()) {
                    appendLine("\n--- EXTRACTED CONTENT ---")
                    appendLine(rawText.take(24_000))
                }
            }

            onProgress("EXTRACTING INFORMATION", 0.78f)
            val response = withTimeout(45_000L) {
                if (previewBitmap != null) {
                    model.generateContent(
                        content {
                            image(previewBitmap)
                            text(promptText)
                        }
                    )
                } else {
                    model.generateContent(promptText)
                }
            }

            val jsonText = response.text?.takeIf { it.isNotBlank() }
            if (jsonText == null) {
                if (rawText.isBlank() && isVideoOrPhoto) {
                    return@withContext DocumentOcrResult.Error("No readable document text detected in media.")
                }
                val detectedCat = DskCategories.classifyFromOcrContent(
                    rawText = rawText,
                    detectedDocName = heuristic.first,
                    detectedDocType = heuristic.second,
                    originalFilename = originalFilename,
                    mimeType = mime,
                    currentCategoryId = baseCategory
                )
                return@withContext DocumentOcrResult.PartialRawTextOnly(
                    rawText = rawText,
                    errorMessage = "Saved extracted text. Review and verify details manually.",
                    detectedDocumentName = heuristic.first,
                    detectedDocumentType = heuristic.second,
                    detectedDocumentNumber = localNumber,
                    detectedCategoryId = detectedCat
                )
            }

            val json = JSONObject(jsonText)
            val aiDocName = json.optNullableString("documentName")
            val aiDocType = json.optNullableString("documentType")
            val contentDetectedType = DskCategories.detectDocumentTypeFromContent(
                rawText = rawText,
                detectedDocName = aiDocName,
                detectedDocType = aiDocType,
                originalFilename = originalFilename,
                mimeType = mime
            )
            val finalDocType = when {
                !contentDetectedType.isNullOrBlank() -> contentDetectedType
                !aiDocType.isNullOrBlank() -> aiDocType
                baseCategory == DskCategories.CAT_DOCUMENTS -> "Unclassified Document"
                else -> null
            }
            val finalDocName = aiDocName ?: heuristic.first ?: contentDetectedType
            val ownerName = json.optNullableString("ownerName")

            val extraction = DocumentExtractionEntity(
                fileId = fileId,
                documentName = finalDocName,
                documentType = finalDocType,
                documentNumber = json.optNullableString("documentNumber") ?: localNumber,
                ownerName = ownerName,
                fatherName = json.optNullableString("fatherName"),
                motherName = json.optNullableString("motherName"),
                dateOfBirth = json.optNullableString("dateOfBirth"),
                issueDate = json.optNullableString("issueDate"),
                expiryDate = json.optNullableString("expiryDate"),
                address = json.optNullableString("address"),
                mobileNumber = json.optNullableString("mobileNumber"),
                organizationName = json.optNullableString("organizationName"),
                registrationNumber = json.optNullableString("registrationNumber"),
                rawText = rawText,
                isVerified = false,
                updatedAt = System.currentTimeMillis()
            )

            val suggestedDisplayName = buildDisplayName(finalDocName, ownerName)
            val detectedCategory = DskCategories.classifyFromOcrContent(
                rawText = rawText,
                detectedDocName = finalDocName,
                detectedDocType = finalDocType,
                originalFilename = originalFilename,
                mimeType = mime,
                currentCategoryId = baseCategory
            )
            DocumentOcrResult.Success(extraction, suggestedDisplayName, detectedCategory)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI Document analysis timed out", e)
            buildFallbackResult(extractedTextBuilder.toString().trim(), originalFilename, mime, "OCR parsing timed out. Extracted text preserved for manual verification.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI Document analysis fallback", e)
            buildFallbackResult(
                extractedTextBuilder.toString().trim(),
                originalFilename,
                mime,
                e.localizedMessage ?: "AI extraction unavailable offline; local OCR text preserved."
            )
        }
    }

    private fun buildFallbackResult(
        rawText: String,
        originalFilename: String,
        mime: String,
        fallbackNotice: String
    ): DocumentOcrResult {
        val baseCat = DskCategories.classifyFile(originalFilename, mime)
        if (rawText.isBlank()) {
            return DocumentOcrResult.Error("Could not extract readable text from $originalFilename. File saved safely — edit details manually if needed.")
        }
        val heuristic = detectBasicDocInfo(rawText, originalFilename, mime, baseCat)
        val localNumber = extractExplicitDocumentNumber(rawText, heuristic.second)
        val cat = DskCategories.classifyFromOcrContent(
            rawText = rawText,
            detectedDocName = heuristic.first,
            detectedDocType = heuristic.second,
            originalFilename = originalFilename,
            mimeType = mime,
            currentCategoryId = baseCat
        )
        return DocumentOcrResult.PartialRawTextOnly(
            rawText = rawText,
            errorMessage = fallbackNotice,
            detectedDocumentName = heuristic.first,
            detectedDocumentType = heuristic.second,
            detectedDocumentNumber = localNumber,
            detectedCategoryId = cat
        )
    }

    fun detectBasicDocInfo(
        rawText: String,
        fileName: String,
        mimeType: String,
        baseCategory: String = DskCategories.classifyFile(fileName, mimeType)
    ): Pair<String?, String?> {
        val detectedType = DskCategories.detectDocumentTypeFromContent(
            rawText = rawText,
            originalFilename = fileName,
            mimeType = mimeType
        )
        return when {
            detectedType != null -> detectedType to detectedType
            baseCategory == DskCategories.CAT_DOCUMENTS -> null to "Unclassified Document"
            else -> null to null
        }
    }

    fun extractExplicitDocumentNumber(rawText: String, detectedType: String?): String? {
        if (rawText.isBlank()) return null
        return when (detectedType) {
            "PAN Card" -> Regex("""\b[A-Z]{5}[0-9]{4}[A-Z]\b""").find(rawText)?.value
            "Aadhaar Card" -> Regex("""\b\d{4}[\s-]?\d{4}[\s-]?\d{4}\b""").find(rawText)?.value
            else -> null
        }
    }

    fun buildDisplayName(documentName: String?, ownerName: String?): String? {
        val cleanDoc = documentName?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("Unclassified Document", ignoreCase = true)
        }
        val cleanOwner = ownerName?.trim()?.takeIf { it.isNotBlank() }
        return when {
            cleanDoc != null && cleanOwner != null -> "$cleanDoc - $cleanOwner"
            cleanDoc != null -> cleanDoc
            else -> null
        }
    }

    private suspend fun recognizeBitmapText(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    cont.resume(visionText.text)
                }
                .addOnFailureListener { err ->
                    cont.resumeWithException(err)
                }
        }
    }

    private suspend fun extractVideoFrameText(file: File): Pair<Bitmap?, String> {
        val retriever = MediaMetadataRetriever()
        val sb = StringBuilder()
        var firstFrame: Bitmap? = null
        val seenLines = linkedSetOf<String>()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1000L) ?: 4000L
            val timestampsUs = listOf(0.10, 0.35, 0.60, 0.85)
                .map { ((durationMs * it).toLong()) * 1000L }
                .distinct()

            for ((idx, timeUs) in timestampsUs.withIndex()) {
                val frame = runCatching {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: continue
                val text = runCatching { recognizeBitmapText(frame) }.getOrDefault("").trim()
                if (text.isNotBlank()) {
                    val freshLines = text.lines().map { it.trim() }.filter { it.isNotBlank() && seenLines.add(it) }
                    if (freshLines.isNotEmpty()) {
                        sb.appendLine("--- Video Frame ${idx + 1} ---")
                        freshLines.forEach { sb.appendLine(it) }
                    }
                }
                if (firstFrame == null) {
                    firstFrame = frame
                } else {
                    frame.recycle()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Video frame extraction fallback", t)
        } finally {
            runCatching { retriever.release() }
        }
        return firstFrame to sb.toString()
    }

    private suspend fun extractPdfPagesAndText(file: File): Pair<Bitmap?, String> {
        val sb = StringBuilder()
        var firstPageBitmap: Bitmap? = null
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pageCount = renderer.pageCount.coerceAtMost(5)
                for (i in 0 until pageCount) {
                    renderer.openPage(i).use { page ->
                        val scale = 2
                        val width = (page.width * scale).coerceIn(600, 1600)
                        val height = (page.height * scale).coerceIn(600, 2200)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pageText = recognizeBitmapText(bitmap)
                        if (pageText.isNotBlank()) {
                            sb.appendLine("--- Page ${i + 1} ---")
                            sb.appendLine(pageText)
                        }
                        if (i == 0) {
                            firstPageBitmap = bitmap
                        } else {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        return firstPageBitmap to sb.toString()
    }

    private fun extractDocxText(file: File): String {
        val sb = StringBuilder()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val parser = Xml.newPullParser()
                    parser.setInput(zip, "UTF-8")
                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.TEXT) {
                            val t = parser.text
                            if (!t.isNullOrBlank()) sb.append(t).append(' ')
                        } else if (eventType == XmlPullParser.END_TAG && parser.name == "p") {
                            sb.appendLine()
                        }
                        eventType = parser.next()
                    }
                    break
                }
                entry = zip.nextEntry
            }
        }
        return sb.toString().trim()
    }

    private fun extractXlsxText(file: File): String {
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
                } else if (entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml")) {
                    val parser = Xml.newPullParser()
                    parser.setInput(zip, "UTF-8")
                    var event = parser.eventType
                    var currentType: String? = null
                    var inValue = false
                    val rowCells = mutableListOf<String>()
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                if (parser.name == "c") currentType = parser.getAttributeValue(null, "t")
                                else if (parser.name == "v" || parser.name == "t") inValue = true
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
                                    rows.add(rowCells.joinToString(" | "))
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
        return if (rows.isNotEmpty()) rows.joinToString("\n") else sharedStrings.joinToString("\n")
    }

    private fun extractPptxText(file: File): String {
        val sb = StringBuilder()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                    val parser = Xml.newPullParser()
                    parser.setInput(zip, "UTF-8")
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.TEXT) {
                            val t = parser.text
                            if (!t.isNullOrBlank()) sb.append(t).append(' ')
                        } else if (event == XmlPullParser.END_TAG && parser.name == "p") {
                            sb.appendLine()
                        }
                        event = parser.next()
                    }
                    sb.appendLine()
                }
                entry = zip.nextEntry
            }
        }
        return sb.toString().trim()
    }

    private fun extractHtmlVisibleText(html: String): String {
        return html
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(24_000)
    }

    private fun extractLegacyDocPrintableText(file: File): String {
        val bytes = file.readBytes()
        val sb = StringBuilder()
        val current = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126 || c == 10 || c == 13 || c == 9) {
                current.append(c.toChar())
            } else {
                if (current.length >= 6) {
                    sb.append(current).append(' ')
                }
                current.clear()
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().take(24_000)
    }

    private fun decodeScaledBitmap(file: File, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
            sample *= 2
        }
        val loadOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, loadOpts) ?: return null
        val rotation = runCatching {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return decoded
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "").trim()
        return if (v.isBlank() || v.equals("null", ignoreCase = true)) null else v
    }
}
