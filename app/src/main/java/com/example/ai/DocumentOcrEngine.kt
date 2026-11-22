package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
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
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
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

private const val TAG = "DocumentOcrEngine"
private const val MAX_PDF_PAGES_FULL_SCAN = 40

sealed interface DocumentOcrResult {
    data class Success(
        val extraction: DocumentExtractionEntity,
        val extendedMetadata: ExtendedOcrMetadata,
        val suggestedDisplayName: String?,
        val detectedCategoryId: String? = null,
        val notice: String? = null
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

private data class OrientationOcrOutput(
    val bestBitmap: Bitmap,
    val text: String,
    val appliedRotationDeg: Int,
    val contrastEnhanced: Boolean
)

private data class PdfExtractionOutput(
    val representativeBitmaps: List<Bitmap>,
    val combinedText: String,
    val pages: List<PageOcrSegment>
)

class DocumentOcrEngine(private val context: Context) {

    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val devanagariRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private val specificItemSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "key" to Schema.string("Field label e.g. IFSC Code, Branch, VID, Blood Group, Vehicle Class, Nationality, Policy Number, Nominee, Sum Assured, GSTIN, Total Amount, Roll Number, CGPA"),
                "value" to Schema.string("Exact extracted value from the document")
            )
        )
    }

    private val extractionSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "documentName" to Schema.string("Official document title/name printed on the document (e.g. Aadhaar Card, PAN Card, Residence Certificate, Caste Certificate, Income Certificate, Birth Certificate, Passport, Driving Licence, Voter ID, Ration Card, Mark Sheet, Bank Document, Insurance Document, Generic Document)", nullable = true),
                "documentType" to Schema.string("Exact document category/type: Aadhaar Card, PAN Card, Passport, Driving Licence, Voter ID, Residence Certificate, Caste Certificate, Income Certificate, Birth Certificate, Death Certificate, Marriage Certificate, Ration Card, Land Document, Government Order, Mark Sheet, Educational Certificate, Bank Document, Insurance Document, Medical Document, Employment Document, Legal Document, Application Form, Identity Card, Bill / Invoice, Resume, Office Document, Generic Document, Personal Photograph, or Other Document", nullable = true),
                "documentNumber" to Schema.string("Primary ID / certificate / card / account / policy / invoice number explicitly printed", nullable = true),
                "registrationNumber" to Schema.string("Separate application, registration, enrolment, CIF, PRAN, roll, or file number if printed", nullable = true),
                "ownerName" to Schema.string("ONLY the explicit Certificate Holder / Applicant / Beneficiary / Cardholder / Student / Customer name. NEVER copy Father, Mother, Spouse, Tehsildar/Officer, Authority, Organization, or filename here. Leave null if unclear.", nullable = true),
                "fatherName" to Schema.string("Father's name (S/O, D/O, पिता का नाम) if explicitly printed", nullable = true),
                "motherName" to Schema.string("Mother's name (माता का नाम) if explicitly printed", nullable = true),
                "spouseName" to Schema.string("Husband / Wife / Spouse name (W/O, H/O, पति/पत्नी का नाम) if explicitly printed", nullable = true),
                "dateOfBirth" to Schema.string("Date of birth in YYYY-MM-DD (or YYYY if only Year of Birth printed)", nullable = true),
                "gender" to Schema.string("Gender (Male, Female, or Transgender) if printed", nullable = true),
                "mobileNumber" to Schema.string("Phone / mobile number if explicitly printed", nullable = true),
                "email" to Schema.string("Email address if explicitly printed", nullable = true),
                "address" to Schema.string("Complete residential/postal address printed on document", nullable = true),
                "village" to Schema.string("Village / VTC / Gram / Ward name from address if present", nullable = true),
                "tehsil" to Schema.string("Tehsil / Taluka / Block / Sub-Division (तहसील / प्रखण्ड) name if present", nullable = true),
                "city" to Schema.string("City or town name if present", nullable = true),
                "district" to Schema.string("District / Janpad (जिला) name if present", nullable = true),
                "state" to Schema.string("State or UT name if present", nullable = true),
                "pinCode" to Schema.string("6-digit postal PIN code if present", nullable = true),
                "organizationName" to Schema.string("Issuing department, e-District portal, bank, insurer, university, board, school, company, or vendor name", nullable = true),
                "issueDate" to Schema.string("Issue / statement / invoice / bill date in YYYY-MM-DD", nullable = true),
                "expiryDate" to Schema.string("Expiry / valid till / due / maturity date in YYYY-MM-DD", nullable = true),
                "issuingAuthority" to Schema.string("Issuing authority / officer / signer designation (e.g. Tehsildar, SDM, District Magistrate, RTO, तहसीलदार) if printed", nullable = true),
                "specificDetails" to Schema.array(
                    specificItemSchema,
                    description = "Any important document-specific fields not in standard keys (Verification URL, Digitally Signed By, Caste Category, Annual Income, Khasra No, IFSC Code, Branch, VID, Blood Group, Vehicle Class, Nationality, Place of Birth, Policy Plan, Nominee, Sum Assured, GSTIN, Total Amount, Roll No, CGPA)",
                    nullable = true
                )
            ),
            optionalProperties = listOf(
                "documentName", "documentType", "documentNumber", "registrationNumber",
                "ownerName", "fatherName", "motherName", "spouseName", "dateOfBirth", "gender",
                "mobileNumber", "email", "address", "village", "tehsil", "city", "district",
                "state", "pinCode", "organizationName", "issueDate", "expiryDate",
                "issuingAuthority", "specificDetails"
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

        val aiBitmaps = mutableListOf<Bitmap>()
        val pageSegments = mutableListOf<PageOcrSegment>()
        val extractedTextBuilder = StringBuilder()
        var appliedRotationDeg = 0
        var contrastEnhanced = false

        try {
            // Stage 1 & 2: Full Document Preprocessing, Orientation Check & Bilingual OCR
            onProgress("ENHANCING & SCANNING", 0.15f)
            when {
                mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic") -> {
                    val decoded = ImagePreprocessor.decodeWithExifAndResolution(file)
                        ?: return@withContext DocumentOcrResult.Error("Could not decode image file ($ext).")
                    onProgress("DETECTING ORIENTATION & TEXT", 0.30f)
                    val oriented = detectBestOrientationAndRecognize(decoded)
                    appliedRotationDeg = oriented.appliedRotationDeg
                    contrastEnhanced = oriented.contrastEnhanced
                    aiBitmaps.add(scaleForAi(oriented.bestBitmap))
                    if (oriented.bestBitmap !== aiBitmaps.first()) {
                        oriented.bestBitmap.recycle()
                    }
                    extractedTextBuilder.append(oriented.text)
                    if (oriented.text.isNotBlank()) {
                        pageSegments.add(
                            PageOcrSegment(
                                pageNumber = 1,
                                text = oriented.text.trim(),
                                detectedHeading = oriented.text.lines().firstOrNull { it.isNotBlank() }?.take(60),
                                confidenceHint = if (oriented.text.length > 80) "HIGH" else "MEDIUM"
                            )
                        )
                    }
                }

                mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v") -> {
                    onProgress("SCANNING VIDEO FRAMES", 0.35f)
                    val vidResult = extractVideoFrameText(file)
                    vidResult.first?.let { aiBitmaps.add(scaleForAi(it)); if (it !== aiBitmaps.firstOrNull()) it.recycle() }
                    extractedTextBuilder.append(vidResult.second)
                    pageSegments.addAll(DeterministicDocumentParser.splitRawTextToPages(vidResult.second))
                }

                mime == "application/pdf" || ext == "pdf" -> {
                    onProgress("SCANNING ALL PDF PAGES", 0.22f)
                    val pdfOut = extractPdfPagesAndText(file) { label, frac ->
                        onProgress(label, (0.22f + frac * 0.35f).coerceIn(0.22f, 0.58f))
                    }
                    pdfOut.representativeBitmaps.forEach { bmp ->
                        val scaled = scaleForAi(bmp)
                        aiBitmaps.add(scaled)
                        if (scaled !== bmp) bmp.recycle()
                    }
                    extractedTextBuilder.append(pdfOut.combinedText)
                    pageSegments.addAll(pdfOut.pages)
                    contrastEnhanced = true
                }

                ext == "docx" || mime.contains("wordprocessingml") -> {
                    extractedTextBuilder.append(extractDocxText(file))
                    pageSegments.addAll(DeterministicDocumentParser.splitRawTextToPages(extractedTextBuilder.toString()))
                }

                ext == "xlsx" || mime.contains("spreadsheetml") -> {
                    extractedTextBuilder.append(extractXlsxText(file))
                    pageSegments.addAll(DeterministicDocumentParser.splitRawTextToPages(extractedTextBuilder.toString()))
                }

                ext == "pptx" || mime.contains("presentationml") -> {
                    extractedTextBuilder.append(extractPptxText(file))
                    pageSegments.addAll(DeterministicDocumentParser.splitRawTextToPages(extractedTextBuilder.toString()))
                }

                ext == "doc" || ext == "xls" || mime == "application/msword" -> {
                    extractedTextBuilder.append(extractLegacyDocPrintableText(file))
                    pageSegments.addAll(DeterministicDocumentParser.splitRawTextToPages(extractedTextBuilder.toString()))
                }

                ext in setOf("html", "htm") || mime == "text/html" -> {
                    extractedTextBuilder.append(extractHtmlVisibleText(file.readText(Charsets.UTF_8).take(80_000)))
                    pageSegments.addAll(DeterministicDocumentParser.splitRawTextToPages(extractedTextBuilder.toString()))
                }

                else -> {
                    extractedTextBuilder.append(file.readText(Charsets.UTF_8).take(48_000))
                    pageSegments.addAll(DeterministicDocumentParser.splitRawTextToPages(extractedTextBuilder.toString()))
                }
            }

            val rawText = extractedTextBuilder.toString().trim()
            val baseCategory = DskCategories.classifyFile(originalFilename, mime)
            val isVideoOrPhoto = baseCategory == DskCategories.CAT_PHOTOS || baseCategory == DskCategories.CAT_VIDEOS

            if (rawText.isBlank() && aiBitmaps.isEmpty()) {
                return@withContext DocumentOcrResult.Error("No readable text found in $originalFilename. File saved safely.")
            }

            // Stage 3: Local Bilingual Analysis & Baseline Classification
            onProgress("ANALYZING DOCUMENT STRUCTURE", 0.62f)
            val localFallback = DeterministicDocumentParser.parseLocal(
                fileId = fileId,
                rawText = rawText,
                originalFilename = originalFilename,
                mimeType = mime,
                pages = pageSegments,
                orientationAppliedDeg = appliedRotationDeg,
                enhancedContrastApplied = contrastEnhanced
            )

            // Stage 4: Structured AI Extraction with Representative Page Visuals + Full Text
            val promptText = buildString {
                appendLine("You are an expert universal bilingual (English + Hindi/Devanagari) document OCR extraction engine.")
                appendLine("Analyze ALL attached page images and the complete multi-page OCR text carefully across headers, tables, paragraphs, stamps, and footers.")
                appendLine("STRICT ANTI-HALLUCINATION & ACCURACY RULES:")
                appendLine("1. Extract ONLY values explicitly and visibly printed in the document. NEVER guess, assume, infer from filename, or fabricate missing fields. Leave absent/unreadable fields null.")
                appendLine("2. OWNER / HOLDER NAME GUARD (CRITICAL): Put ONLY the Certificate Holder / Applicant / Beneficiary / Cardholder / Student / Customer name in ownerName. NEVER put Father's Name, Mother's Name, Husband/Wife Name, Tehsildar/Officer/Signatory name, Issuing Authority, Organization Name, Address, or Filename into ownerName.")
                appendLine("3. Support ANY document type universally — including Residence/Domicile Certificate (निवास प्रमाण पत्र / मूल निवास प्रमाण पत्र), Caste Certificate (जाति प्रमाण पत्र), Income Certificate (आय प्रमाण पत्र), Birth/Death/Marriage Certificates, Ration Card, Land Records, Government Orders, Mark Sheets, Aadhaar, PAN, Passport, Driving Licence, Voter ID, Bank/Insurance/Medical/Employment/Legal documents, Application Forms, Bills/Invoices, or Generic Document for custom/uncommon documents.")
                appendLine("4. Preserve Hindi/Devanagari spelling verbatim when printed in Hindi (e.g. ग्राम, तहसील, जिला, नाम, पिता, माता). Do NOT translate printed names.")
                appendLine("5. Normalize dateOfBirth, issueDate, and expiryDate to YYYY-MM-DD whenever day, month, and year are explicitly readable (including Hindi months).")
                appendLine("6. Split address components into village, tehsil, city, district, state, and pinCode wherever present in addition to full address.")
                appendLine("7. Put document-specific extras (Verification Details/URL, Digitally Signed By, Caste Category, Annual Income, Khasra/Khatauni No, IFSC Code, Branch, VID, Blood Group, Vehicle Class, Nationality, Policy Plan, Nominee, Sum Assured, GSTIN, Total Amount, Roll No, CGPA) into specificDetails.")
                appendLine("Original filename (for reference ONLY — never treat as ownerName): $originalFilename")
                if (rawText.isNotBlank()) {
                    appendLine("\n--- COMPLETE DOCUMENT OCR TEXT (${pageSegments.size.coerceAtLeast(1)} PAGE(S)) ---")
                    appendLine(rawText.take(32_000))
                }
            }

            onProgress("AI STRUCTURED EXTRACTION", 0.78f)
            val aiResponseText = runCatching {
                withTimeout(55_000L) {
                    if (aiBitmaps.isNotEmpty()) {
                        model.generateContent(
                            content {
                                aiBitmaps.forEach { bmp -> image(bmp) }
                                text(promptText)
                            }
                        ).text
                    } else {
                        model.generateContent(promptText).text
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException && err !is TimeoutCancellationException) throw err
                Log.w(TAG, "AI extraction unavailable/timed out; falling back to local bilingual parser", err)
            }.getOrNull()?.takeIf { it.isNotBlank() }

            if (aiResponseText == null) {
                if (rawText.isBlank() && isVideoOrPhoto) {
                    return@withContext DocumentOcrResult.Error("No readable document text detected in media.")
                }
                return@withContext buildLocalRecoverySuccess(
                    fileId = fileId,
                    localFallback = localFallback,
                    rawText = rawText,
                    originalFilename = originalFilename,
                    mime = mime,
                    baseCategory = baseCategory,
                    notice = "Extracted offline using Hindi+English OCR engine. Verify and save details."
                )
            }

            onProgress("VALIDATING & VERIFYING FIELDS", 0.92f)
            val json = JSONObject(aiResponseText)
            val specificsMap = linkedMapOf<String, String>()
            json.optJSONArray("specificDetails")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val k = item.optNullableString("key") ?: continue
                    val v = item.optNullableString("value") ?: continue
                    specificsMap[k] = v
                }
            }

            val aiMeta = ExtendedOcrMetadata(
                fileId = fileId,
                documentName = json.optNullableString("documentName"),
                documentType = json.optNullableString("documentType"),
                ownerName = json.optNullableString("ownerName"),
                fatherName = json.optNullableString("fatherName"),
                motherName = json.optNullableString("motherName"),
                spouseName = json.optNullableString("spouseName"),
                dateOfBirth = json.optNullableString("dateOfBirth"),
                gender = json.optNullableString("gender"),
                documentNumber = json.optNullableString("documentNumber"),
                registrationNumber = json.optNullableString("registrationNumber"),
                mobileNumber = json.optNullableString("mobileNumber"),
                email = json.optNullableString("email"),
                address = json.optNullableString("address"),
                village = json.optNullableString("village"),
                tehsil = json.optNullableString("tehsil"),
                city = json.optNullableString("city"),
                district = json.optNullableString("district"),
                state = json.optNullableString("state"),
                pinCode = json.optNullableString("pinCode"),
                organizationName = json.optNullableString("organizationName"),
                issueDate = json.optNullableString("issueDate"),
                expiryDate = json.optNullableString("expiryDate"),
                issuingAuthority = json.optNullableString("issuingAuthority"),
                documentSpecificFields = specificsMap
            )

            // Stage 5: Reconcile AI + Local Deterministic Parser (grounded anti-hallucination + recovery)
            val reconciled = DeterministicDocumentParser.reconcileWithAi(
                ai = aiMeta,
                localFallback = localFallback,
                rawText = rawText,
                stageSummary = buildString {
                    append("9-Stage Universal OCR (${pageSegments.size.coerceAtLeast(1)} page")
                    if (pageSegments.size > 1) append("s")
                    append(")")
                    if (appliedRotationDeg != 0) append(" • Rotated $appliedRotationDeg°")
                    if (contrastEnhanced) append(" • Enhanced")
                },
                originalFilename = originalFilename
            )

            val finalEntity = reconciled.toDocumentExtractionEntity(rawText = rawText, isVerified = false)
            val suggestedDisplayName = buildDisplayName(reconciled.documentName, reconciled.ownerName)
            val detectedCategory = DskCategories.classifyFromOcrContent(
                rawText = rawText,
                detectedDocName = reconciled.documentName,
                detectedDocType = reconciled.documentType,
                originalFilename = originalFilename,
                mimeType = mime,
                currentCategoryId = baseCategory
            )

            DocumentOcrResult.Success(
                extraction = finalEntity,
                extendedMetadata = reconciled,
                suggestedDisplayName = suggestedDisplayName,
                detectedCategoryId = detectedCategory
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Document OCR failed, attempting deterministic recovery", e)
            val rawText = extractedTextBuilder.toString().trim()
            if (rawText.isBlank()) {
                DocumentOcrResult.Error("Could not extract readable text from $originalFilename. File saved safely.")
            } else {
                val baseCategory = DskCategories.classifyFile(originalFilename, mime)
                val localFallback = DeterministicDocumentParser.parseLocal(
                    fileId = fileId,
                    rawText = rawText,
                    originalFilename = originalFilename,
                    mimeType = mime,
                    pages = pageSegments,
                    orientationAppliedDeg = appliedRotationDeg,
                    enhancedContrastApplied = contrastEnhanced
                )
                buildLocalRecoverySuccess(
                    fileId = fileId,
                    localFallback = localFallback,
                    rawText = rawText,
                    originalFilename = originalFilename,
                    mime = mime,
                    baseCategory = baseCategory,
                    notice = e.localizedMessage ?: "AI extraction fallback — local Hindi+English OCR results recovered."
                )
            }
        } finally {
            aiBitmaps.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        }
    }

    private fun buildLocalRecoverySuccess(
        fileId: String,
        localFallback: ExtendedOcrMetadata,
        rawText: String,
        originalFilename: String,
        mime: String,
        baseCategory: String,
        notice: String
    ): DocumentOcrResult.Success {
        val enriched = localFallback.copy(
            fileId = fileId,
            stageSummary = "Local Bilingual OCR (${localFallback.pageExtractions.size.coerceAtLeast(1)} page(s)) • Offline Mode"
        )
        val finalized = DeterministicDocumentParser.finalizeScoresAndMissing(enriched, rawText)
        val entity = finalized.toDocumentExtractionEntity(rawText = rawText, isVerified = false)
        val suggested = buildDisplayName(finalized.documentName, finalized.ownerName)
        val cat = DskCategories.classifyFromOcrContent(
            rawText = rawText,
            detectedDocName = finalized.documentName,
            detectedDocType = finalized.documentType,
            originalFilename = originalFilename,
            mimeType = mime,
            currentCategoryId = baseCategory
        )
        return DocumentOcrResult.Success(
            extraction = entity,
            extendedMetadata = finalized,
            suggestedDisplayName = suggested,
            detectedCategoryId = cat,
            notice = notice
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

    /**
     * Runs both Latin and Devanagari (Hindi) ML Kit recognizers with spatial row reconstruction.
     * Automatically runs a second adaptive-threshold OCR pass if first-pass readability is sparse.
     */
    private suspend fun recognizeBilingualText(bitmap: Bitmap): String {
        val firstPass = recognizeBilingualSpatialPass(bitmap)
        if (scoreOcrReadability(firstPass) >= 95) {
            return firstPass
        }

        // Second pass on adaptive threshold variant to recover faint stamps, low-contrast tables, or watermarks
        val thresholdBmp = ImagePreprocessor.createAdaptiveThresholdVariant(bitmap)
        return try {
            val secondPass = recognizeBilingualSpatialPass(thresholdBmp)
            mergeUniqueOcrLines(firstPass, secondPass)
        } finally {
            if (thresholdBmp !== bitmap && !thresholdBmp.isRecycled) {
                thresholdBmp.recycle()
            }
        }
    }

    private suspend fun recognizeBilingualSpatialPass(bitmap: Bitmap): String {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val latinText = runRecognizerSpatial(latinRecognizer, inputImage)
        val hindiText = runRecognizerSpatial(devanagariRecognizer, inputImage)
        return mergeUniqueOcrLines(latinText, hindiText)
    }

    private fun mergeUniqueOcrLines(primary: String, secondary: String): String {
        val merged = linkedSetOf<String>()
        primary.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { merged.add(it) }
        secondary.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            val hasDevanagari = line.any { it in '\u0900'..'\u097F' }
            if (hasDevanagari || merged.none { it.equals(line, ignoreCase = true) }) {
                merged.add(line)
            }
        }
        return merged.joinToString("\n")
    }

    private suspend fun runRecognizerSpatial(
        client: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage
    ): String = suspendCancellableCoroutine { cont ->
        client.process(image)
            .addOnSuccessListener { visionText -> cont.resume(reconstructSpatialText(visionText)) }
            .addOnFailureListener { cont.resume("") }
    }

    /**
     * Reconstructs true left-to-right, top-to-bottom reading rows from ML Kit bounding boxes
     * so two-column government certificates and tables preserve "Label: Value" structure.
     */
    private fun reconstructSpatialText(visionText: Text): String {
        val allLines = visionText.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) return visionText.text

        data class BoundedLine(val text: String, val left: Int, val top: Int, val centerY: Int, val height: Int)

        val bounded = allLines.mapNotNull { line ->
            val txt = line.text.trim()
            val box = line.boundingBox
            if (txt.isBlank()) null
            else if (box == null) BoundedLine(txt, 0, Int.MAX_VALUE, Int.MAX_VALUE, 20)
            else BoundedLine(txt, box.left, box.top, box.centerY(), box.height().coerceAtLeast(10))
        }.sortedWith(compareBy<BoundedLine> { it.top }.thenBy { it.left })

        if (bounded.isEmpty()) return visionText.text

        val rows = mutableListOf<MutableList<BoundedLine>>()
        for (item in bounded) {
            val matchingRow = rows.lastOrNull()?.takeIf { row ->
                val anchor = row.first()
                val tolerance = (maxOf(anchor.height, item.height) * 0.65f).coerceAtLeast(12f)
                kotlin.math.abs(item.centerY - anchor.centerY) <= tolerance
            }
            if (matchingRow != null) {
                matchingRow.add(item)
            } else {
                rows.add(mutableListOf(item))
            }
        }

        return rows.joinToString("\n") { row ->
            row.sortedBy { it.left }.joinToString(" ") { it.text }
        }
    }

    /**
     * Enhances image & auto-detects document orientation across 0 / 90 / 270 / 180 degrees.
     */
    private suspend fun detectBestOrientationAndRecognize(rawBitmap: Bitmap): OrientationOcrOutput {
        val enhanced0 = ImagePreprocessor.enhanceForOcr(rawBitmap)
        if (rawBitmap !== enhanced0) rawBitmap.recycle()

        val text0 = recognizeBilingualText(enhanced0)
        val score0 = scoreOcrReadability(text0)
        if (score0 >= 120) {
            return OrientationOcrOutput(enhanced0, text0, 0, true)
        }

        var bestBmp = enhanced0
        var bestText = text0
        var bestScore = score0
        var bestDeg = 0

        for (deg in listOf(90, 270, 180)) {
            val matrix = Matrix().apply { postRotate(deg.toFloat()) }
            val rotated = Bitmap.createBitmap(enhanced0, 0, 0, enhanced0.width, enhanced0.height, matrix, true)
            val txt = recognizeBilingualText(rotated)
            val sc = scoreOcrReadability(txt)
            if (sc > bestScore + 12) {
                if (bestBmp !== enhanced0) bestBmp.recycle()
                bestBmp = rotated
                bestText = txt
                bestScore = sc
                bestDeg = deg
            } else {
                rotated.recycle()
            }
        }

        if (bestBmp !== enhanced0) {
            enhanced0.recycle()
        }
        return OrientationOcrOutput(bestBmp, bestText, bestDeg, true)
    }

    private fun scoreOcrReadability(text: String): Int {
        if (text.isBlank()) return 0
        val cleanLetters = text.count { it.isLetterOrDigit() || it in '\u0900'..'\u097F' }
        val lines = text.lines().count { it.trim().length >= 3 }
        val cues = listOf(
            "government", "india", "aadhaar", "income", "tax", "passport", "driving", "licence",
            "election", "bank", "invoice", "certificate", "residence", "domicile", "caste",
            "name", "dob", "address", "district", "tehsil", "tahsildar", "tehsildar",
            "भारत", "आधार", "नाम", "जन्म", "पता", "निवास", "प्रमाण", "तहसील", "जिला", "जाति", "आय"
        ).count { text.contains(it, ignoreCase = true) }
        return cleanLetters + (lines * 4) + (cues * 25)
    }

    private fun scaleForAi(src: Bitmap, maxDim: Int = 1400): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxDim) return src
        val ratio = maxDim.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            src,
            (w * ratio).toInt().coerceAtLeast(1),
            (h * ratio).toInt().coerceAtLeast(1),
            true
        )
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
                val enhanced = ImagePreprocessor.enhanceForOcr(frame)
                val text = runCatching { recognizeBilingualText(enhanced) }.getOrDefault("").trim()
                if (enhanced !== frame) enhanced.recycle()
                if (text.isNotBlank()) {
                    val freshLines = text.lines().map { it.trim() }.filter { it.isNotBlank() && seenLines.add(it) }
                    if (freshLines.isNotEmpty()) {
                        sb.appendLine("--- Page ${idx + 1} ---")
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

    private suspend fun extractPdfPagesAndText(
        file: File,
        onPageProgress: (String, Float) -> Unit = { _, _ -> }
    ): PdfExtractionOutput {
        val sb = StringBuilder()
        val pages = mutableListOf<PageOcrSegment>()
        val keptBitmaps = mutableListOf<Bitmap>()

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val totalPages = renderer.pageCount.coerceAtLeast(0)
                val scanCount = totalPages.coerceAtMost(MAX_PDF_PAGES_FULL_SCAN)
                val sampleIndices = when {
                    scanCount <= 0 -> emptySet()
                    scanCount <= 3 -> (0 until scanCount).toSet()
                    else -> setOf(0, 1, scanCount - 1)
                }

                for (i in 0 until scanCount) {
                    onPageProgress("SCANNING PAGE ${i + 1}/$scanCount", (i + 1).toFloat() / scanCount.coerceAtLeast(1))
                    renderer.openPage(i).use { page ->
                        val scale = 2.2f
                        val width = (page.width * scale).toInt().coerceIn(720, 1800)
                        val height = (page.height * scale).toInt().coerceIn(720, 2500)
                        val rawBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        rawBmp.eraseColor(Color.WHITE)
                        page.render(rawBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val enhanced = ImagePreprocessor.enhanceForOcr(rawBmp)
                        rawBmp.recycle()

                        val pageText = recognizeBilingualText(enhanced).trim()
                        if (pageText.isNotBlank()) {
                            sb.appendLine("--- Page ${i + 1} ---")
                            sb.appendLine(pageText)
                            pages.add(
                                PageOcrSegment(
                                    pageNumber = i + 1,
                                    text = pageText,
                                    detectedHeading = pageText.lines().firstOrNull { it.isNotBlank() }?.take(60),
                                    confidenceHint = if (pageText.length > 80) "HIGH" else "MEDIUM"
                                )
                            )
                        }
                        if (i in sampleIndices) {
                            keptBitmaps.add(enhanced)
                        } else {
                            enhanced.recycle()
                        }
                    }
                }
            }
        }
        return PdfExtractionOutput(
            representativeBitmaps = keptBitmaps,
            combinedText = sb.toString(),
            pages = pages
        )
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
        var slideIdx = 1
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                    sb.appendLine("--- Page $slideIdx ---")
                    slideIdx++
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
            .take(32_000)
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
        return sb.toString().replace(Regex("\\s+"), " ").trim().take(32_000)
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "").trim()
        return if (v.isBlank() || v.equals("null", ignoreCase = true)) null else v
    }
}
