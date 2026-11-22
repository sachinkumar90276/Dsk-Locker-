package com.example.ai

import android.content.Context
import com.example.data.local.DocumentExtractionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class FieldConfidence { HIGH, MEDIUM, LOW }

data class PageOcrSegment(
    val pageNumber: Int,
    val text: String,
    val detectedHeading: String? = null,
    val confidenceHint: String = "MEDIUM"
)

data class ExtendedOcrMetadata(
    val fileId: String,
    val documentName: String? = null,
    val documentType: String? = null,
    val ownerName: String? = null,
    val fatherName: String? = null,
    val motherName: String? = null,
    val dateOfBirth: String? = null,
    val gender: String? = null,
    val documentNumber: String? = null,
    val registrationNumber: String? = null,
    val mobileNumber: String? = null,
    val email: String? = null,
    val address: String? = null,
    val village: String? = null,
    val city: String? = null,
    val district: String? = null,
    val state: String? = null,
    val pinCode: String? = null,
    val organizationName: String? = null,
    val issueDate: String? = null,
    val expiryDate: String? = null,
    val issuingAuthority: String? = null,
    val documentSpecificFields: Map<String, String> = emptyMap(),
    val confidenceScore: Int = 0,
    val confidenceLabel: String = "LOW",
    val fieldConfidences: Map<String, String> = emptyMap(),
    val needsReviewFields: Set<String> = emptySet(),
    val missingExpectedFields: List<String> = emptyList(),
    val pageExtractions: List<PageOcrSegment> = emptyList(),
    val stageSummary: String = "",
    val orientationAppliedDeg: Int = 0,
    val enhancedContrastApplied: Boolean = false
) {
    fun toDocumentExtractionEntity(rawText: String, isVerified: Boolean = false): DocumentExtractionEntity {
        val cleanAuthorityOrOrg = organizationName?.takeIf { it.isNotBlank() }
            ?: issuingAuthority?.takeIf { it.isNotBlank() }
        val composedAddress = address?.takeIf { it.isNotBlank() } ?: listOfNotNull(
            village?.takeIf { it.isNotBlank() },
            city?.takeIf { it.isNotBlank() },
            district?.takeIf { it.isNotBlank() },
            state?.takeIf { it.isNotBlank() },
            pinCode?.takeIf { it.isNotBlank() }
        ).joinToString(", ").takeIf { it.isNotBlank() }

        return DocumentExtractionEntity(
            fileId = fileId,
            documentName = documentName,
            documentType = documentType,
            documentNumber = documentNumber,
            ownerName = ownerName,
            fatherName = fatherName,
            motherName = motherName,
            dateOfBirth = dateOfBirth,
            issueDate = issueDate,
            expiryDate = expiryDate,
            address = composedAddress,
            mobileNumber = mobileNumber,
            organizationName = cleanAuthorityOrOrg,
            registrationNumber = registrationNumber,
            rawText = rawText,
            isVerified = isVerified,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun toJsonString(): String {
        val obj = JSONObject()
        obj.put("fileId", fileId)
        obj.putOpt("documentName", documentName)
        obj.putOpt("documentType", documentType)
        obj.putOpt("ownerName", ownerName)
        obj.putOpt("fatherName", fatherName)
        obj.putOpt("motherName", motherName)
        obj.putOpt("dateOfBirth", dateOfBirth)
        obj.putOpt("gender", gender)
        obj.putOpt("documentNumber", documentNumber)
        obj.putOpt("registrationNumber", registrationNumber)
        obj.putOpt("mobileNumber", mobileNumber)
        obj.putOpt("email", email)
        obj.putOpt("address", address)
        obj.putOpt("village", village)
        obj.putOpt("city", city)
        obj.putOpt("district", district)
        obj.putOpt("state", state)
        obj.putOpt("pinCode", pinCode)
        obj.putOpt("organizationName", organizationName)
        obj.putOpt("issueDate", issueDate)
        obj.putOpt("expiryDate", expiryDate)
        obj.putOpt("issuingAuthority", issuingAuthority)
        obj.put("confidenceScore", confidenceScore)
        obj.put("confidenceLabel", confidenceLabel)
        obj.put("stageSummary", stageSummary)
        obj.put("orientationAppliedDeg", orientationAppliedDeg)
        obj.put("enhancedContrastApplied", enhancedContrastApplied)

        val specificObj = JSONObject()
        documentSpecificFields.forEach { (k, v) -> specificObj.put(k, v) }
        obj.put("documentSpecificFields", specificObj)

        val confObj = JSONObject()
        fieldConfidences.forEach { (k, v) -> confObj.put(k, v) }
        obj.put("fieldConfidences", confObj)

        val reviewArr = JSONArray()
        needsReviewFields.forEach { reviewArr.put(it) }
        obj.put("needsReviewFields", reviewArr)

        val missingArr = JSONArray()
        missingExpectedFields.forEach { missingArr.put(it) }
        obj.put("missingExpectedFields", missingArr)

        val pagesArr = JSONArray()
        pageExtractions.forEach { page ->
            pagesArr.put(
                JSONObject()
                    .put("pageNumber", page.pageNumber)
                    .put("text", page.text)
                    .putOpt("detectedHeading", page.detectedHeading)
                    .put("confidenceHint", page.confidenceHint)
            )
        }
        obj.put("pageExtractions", pagesArr)
        return obj.toString()
    }

    companion object {
        fun fromJsonString(jsonStr: String): ExtendedOcrMetadata? = runCatching {
            val obj = JSONObject(jsonStr)
            val specific = linkedMapOf<String, String>()
            obj.optJSONObject("documentSpecificFields")?.let { sObj ->
                val keys = sObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = sObj.optString(k, "").trim()
                    if (v.isNotBlank()) specific[k] = v
                }
            }
            val confs = linkedMapOf<String, String>()
            obj.optJSONObject("fieldConfidences")?.let { cObj ->
                val keys = cObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = cObj.optString(k, "").trim()
                    if (v.isNotBlank()) confs[k] = v
                }
            }
            val review = linkedSetOf<String>()
            obj.optJSONArray("needsReviewFields")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { review.add(it) }
                }
            }
            val missing = mutableListOf<String>()
            obj.optJSONArray("missingExpectedFields")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { missing.add(it) }
                }
            }
            val pages = mutableListOf<PageOcrSegment>()
            obj.optJSONArray("pageExtractions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    pages.add(
                        PageOcrSegment(
                            pageNumber = p.optInt("pageNumber", i + 1),
                            text = p.optString("text", ""),
                            detectedHeading = p.optString("detectedHeading").takeIf { it.isNotBlank() && it != "null" },
                            confidenceHint = p.optString("confidenceHint", "MEDIUM")
                        )
                    )
                }
            }
            ExtendedOcrMetadata(
                fileId = obj.getString("fileId"),
                documentName = obj.optNull("documentName"),
                documentType = obj.optNull("documentType"),
                ownerName = obj.optNull("ownerName"),
                fatherName = obj.optNull("fatherName"),
                motherName = obj.optNull("motherName"),
                dateOfBirth = obj.optNull("dateOfBirth"),
                gender = obj.optNull("gender"),
                documentNumber = obj.optNull("documentNumber"),
                registrationNumber = obj.optNull("registrationNumber"),
                mobileNumber = obj.optNull("mobileNumber"),
                email = obj.optNull("email"),
                address = obj.optNull("address"),
                village = obj.optNull("village"),
                city = obj.optNull("city"),
                district = obj.optNull("district"),
                state = obj.optNull("state"),
                pinCode = obj.optNull("pinCode"),
                organizationName = obj.optNull("organizationName"),
                issueDate = obj.optNull("issueDate"),
                expiryDate = obj.optNull("expiryDate"),
                issuingAuthority = obj.optNull("issuingAuthority"),
                documentSpecificFields = specific,
                confidenceScore = obj.optInt("confidenceScore", 0),
                confidenceLabel = obj.optString("confidenceLabel", "LOW"),
                fieldConfidences = confs,
                needsReviewFields = review,
                missingExpectedFields = missing,
                pageExtractions = pages,
                stageSummary = obj.optString("stageSummary", ""),
                orientationAppliedDeg = obj.optInt("orientationAppliedDeg", 0),
                enhancedContrastApplied = obj.optBoolean("enhancedContrastApplied", false)
            )
        }.getOrNull()

        private fun JSONObject.optNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key, "").trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
        }
    }
}

object DocumentFieldProfile {
    const val KEY_DOC_NAME = "documentName"
    const val KEY_DOC_TYPE = "documentType"
    const val KEY_OWNER_NAME = "ownerName"
    const val KEY_FATHER_NAME = "fatherName"
    const val KEY_MOTHER_NAME = "motherName"
    const val KEY_DOB = "dateOfBirth"
    const val KEY_GENDER = "gender"
    const val KEY_DOC_NUMBER = "documentNumber"
    const val KEY_REG_NUMBER = "registrationNumber"
    const val KEY_MOBILE = "mobileNumber"
    const val KEY_EMAIL = "email"
    const val KEY_ADDRESS = "address"
    const val KEY_VILLAGE = "village"
    const val KEY_CITY = "city"
    const val KEY_DISTRICT = "district"
    const val KEY_STATE = "state"
    const val KEY_PIN_CODE = "pinCode"
    const val KEY_ORG_NAME = "organizationName"
    const val KEY_ISSUE_DATE = "issueDate"
    const val KEY_EXPIRY_DATE = "expiryDate"
    const val KEY_ISSUING_AUTH = "issuingAuthority"

    val allFieldKeys = listOf(
        KEY_DOC_NAME, KEY_DOC_TYPE, KEY_DOC_NUMBER, KEY_REG_NUMBER,
        KEY_OWNER_NAME, KEY_FATHER_NAME, KEY_MOTHER_NAME, KEY_DOB, KEY_GENDER,
        KEY_MOBILE, KEY_EMAIL, KEY_ADDRESS, KEY_VILLAGE, KEY_CITY, KEY_DISTRICT,
        KEY_STATE, KEY_PIN_CODE, KEY_ORG_NAME, KEY_ISSUE_DATE, KEY_EXPIRY_DATE,
        KEY_ISSUING_AUTH
    )

    fun labelForKey(key: String): String = when (key) {
        KEY_DOC_NAME -> "Document Name"
        KEY_DOC_TYPE -> "Document Type"
        KEY_OWNER_NAME -> "Owner / Holder Name"
        KEY_FATHER_NAME -> "Father's Name"
        KEY_MOTHER_NAME -> "Mother's Name"
        KEY_DOB -> "Date of Birth"
        KEY_GENDER -> "Gender"
        KEY_DOC_NUMBER -> "Document Number"
        KEY_REG_NUMBER -> "Registration Number"
        KEY_MOBILE -> "Mobile Number"
        KEY_EMAIL -> "Email"
        KEY_ADDRESS -> "Address"
        KEY_VILLAGE -> "Village"
        KEY_CITY -> "City"
        KEY_DISTRICT -> "District"
        KEY_STATE -> "State"
        KEY_PIN_CODE -> "PIN Code"
        KEY_ORG_NAME -> "Organization Name"
        KEY_ISSUE_DATE -> "Issue Date"
        KEY_EXPIRY_DATE -> "Expiry Date"
        KEY_ISSUING_AUTH -> "Issuing Authority"
        else -> key
    }

    /**
     * Dynamically prioritizes relevant fields for the recognized document type
     * so irrelevant fields are omitted from the primary view.
     */
    fun relevantKeysForType(documentType: String?): List<String> {
        val t = documentType.orEmpty().lowercase()
        return when {
            t.contains("aadhaar") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_DOC_NUMBER, KEY_REG_NUMBER,
                KEY_OWNER_NAME, KEY_FATHER_NAME, KEY_DOB, KEY_GENDER,
                KEY_MOBILE, KEY_ADDRESS, KEY_VILLAGE, KEY_CITY, KEY_DISTRICT,
                KEY_STATE, KEY_PIN_CODE, KEY_ISSUE_DATE, KEY_ISSUING_AUTH
            )
            t.contains("pan") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_DOC_NUMBER, KEY_OWNER_NAME,
                KEY_FATHER_NAME, KEY_DOB, KEY_ISSUING_AUTH
            )
            t.contains("voter") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_DOC_NUMBER, KEY_OWNER_NAME,
                KEY_FATHER_NAME, KEY_DOB, KEY_GENDER, KEY_ADDRESS,
                KEY_CITY, KEY_DISTRICT, KEY_STATE, KEY_PIN_CODE, KEY_ISSUING_AUTH
            )
            t.contains("driving") || t.contains("licence") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_DOC_NUMBER, KEY_OWNER_NAME,
                KEY_FATHER_NAME, KEY_DOB, KEY_ADDRESS, KEY_CITY, KEY_DISTRICT,
                KEY_STATE, KEY_PIN_CODE, KEY_ISSUE_DATE, KEY_EXPIRY_DATE, KEY_ISSUING_AUTH
            )
            t.contains("passport") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_DOC_NUMBER, KEY_REG_NUMBER,
                KEY_OWNER_NAME, KEY_FATHER_NAME, KEY_MOTHER_NAME, KEY_DOB, KEY_GENDER,
                KEY_ADDRESS, KEY_CITY, KEY_STATE, KEY_PIN_CODE,
                KEY_ISSUE_DATE, KEY_EXPIRY_DATE, KEY_ISSUING_AUTH
            )
            t.contains("bank") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_DOC_NUMBER, KEY_REG_NUMBER,
                KEY_OWNER_NAME, KEY_MOBILE, KEY_EMAIL, KEY_ORG_NAME,
                KEY_ADDRESS, KEY_CITY, KEY_DISTRICT, KEY_STATE, KEY_PIN_CODE, KEY_ISSUE_DATE
            )
            t.contains("insurance") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_DOC_NUMBER, KEY_REG_NUMBER,
                KEY_OWNER_NAME, KEY_DOB, KEY_MOBILE, KEY_EMAIL, KEY_ORG_NAME,
                KEY_ADDRESS, KEY_CITY, KEY_STATE, KEY_PIN_CODE,
                KEY_ISSUE_DATE, KEY_EXPIRY_DATE
            )
            t.contains("certificate") || t.contains("marksheet") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_REG_NUMBER, KEY_DOC_NUMBER,
                KEY_OWNER_NAME, KEY_FATHER_NAME, KEY_MOTHER_NAME, KEY_DOB,
                KEY_ORG_NAME, KEY_ISSUE_DATE, KEY_ISSUING_AUTH
            )
            t.contains("resume") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_OWNER_NAME, KEY_MOBILE, KEY_EMAIL,
                KEY_ADDRESS, KEY_CITY, KEY_STATE, KEY_ORG_NAME
            )
            t.contains("bill") || t.contains("invoice") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_DOC_NUMBER, KEY_REG_NUMBER,
                KEY_OWNER_NAME, KEY_ORG_NAME, KEY_MOBILE, KEY_EMAIL,
                KEY_ADDRESS, KEY_CITY, KEY_STATE, KEY_PIN_CODE,
                KEY_ISSUE_DATE, KEY_EXPIRY_DATE
            )
            t.contains("photo") -> listOf(
                KEY_DOC_NAME, KEY_DOC_TYPE, KEY_OWNER_NAME, KEY_CITY, KEY_STATE
            )
            else -> allFieldKeys
        }
    }

    fun expectedCoreKeysForMissingCheck(documentType: String?): List<String> {
        val t = documentType.orEmpty().lowercase()
        return when {
            t.contains("aadhaar") -> listOf(KEY_DOC_NUMBER, KEY_OWNER_NAME, KEY_DOB, KEY_GENDER, KEY_ADDRESS, KEY_PIN_CODE)
            t.contains("pan") -> listOf(KEY_DOC_NUMBER, KEY_OWNER_NAME, KEY_FATHER_NAME, KEY_DOB)
            t.contains("voter") -> listOf(KEY_DOC_NUMBER, KEY_OWNER_NAME, KEY_FATHER_NAME, KEY_GENDER)
            t.contains("driving") || t.contains("licence") -> listOf(KEY_DOC_NUMBER, KEY_OWNER_NAME, KEY_DOB, KEY_ISSUE_DATE, KEY_EXPIRY_DATE)
            t.contains("passport") -> listOf(KEY_DOC_NUMBER, KEY_OWNER_NAME, KEY_DOB, KEY_GENDER, KEY_ISSUE_DATE, KEY_EXPIRY_DATE)
            t.contains("bank") -> listOf(KEY_DOC_NUMBER, KEY_OWNER_NAME, KEY_ORG_NAME)
            t.contains("insurance") -> listOf(KEY_DOC_NUMBER, KEY_OWNER_NAME, KEY_ORG_NAME, KEY_EXPIRY_DATE)
            t.contains("certificate") -> listOf(KEY_OWNER_NAME, KEY_REG_NUMBER, KEY_ORG_NAME, KEY_ISSUE_DATE)
            t.contains("bill") || t.contains("invoice") -> listOf(KEY_DOC_NUMBER, KEY_ORG_NAME, KEY_ISSUE_DATE)
            t.contains("photo") -> emptyList()
            else -> listOf(KEY_DOC_TYPE, KEY_OWNER_NAME, KEY_DOC_NUMBER)
        }
    }
}

object OcrMetadataStore {
    private fun metaDir(context: Context): File =
        File(context.filesDir, "dsk_locker/ocr_meta").apply { mkdirs() }

    private fun metaFile(context: Context, fileId: String): File {
        val safe = fileId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(metaDir(context), "$safe.json")
    }

    fun save(context: Context, meta: ExtendedOcrMetadata) {
        runCatching {
            metaFile(context, meta.fileId).writeText(meta.toJsonString(), Charsets.UTF_8)
        }
    }

    fun load(context: Context, fileId: String): ExtendedOcrMetadata? {
        val f = metaFile(context, fileId)
        if (!f.exists()) return null
        return runCatching { ExtendedOcrMetadata.fromJsonString(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun loadOrReconstruct(context: Context, entity: DocumentExtractionEntity): ExtendedOcrMetadata {
        val stored = load(context, entity.fileId)
        if (stored != null) {
            return stored.copy(
                documentName = entity.documentName ?: stored.documentName,
                documentType = entity.documentType ?: stored.documentType,
                documentNumber = entity.documentNumber ?: stored.documentNumber,
                ownerName = entity.ownerName ?: stored.ownerName,
                fatherName = entity.fatherName ?: stored.fatherName,
                motherName = entity.motherName ?: stored.motherName,
                dateOfBirth = entity.dateOfBirth ?: stored.dateOfBirth,
                issueDate = entity.issueDate ?: stored.issueDate,
                expiryDate = entity.expiryDate ?: stored.expiryDate,
                address = entity.address ?: stored.address,
                mobileNumber = entity.mobileNumber ?: stored.mobileNumber,
                organizationName = entity.organizationName ?: stored.organizationName,
                registrationNumber = entity.registrationNumber ?: stored.registrationNumber
            )
        }
        return DeterministicDocumentParser.enrichFromEntity(entity)
    }

    fun delete(context: Context, fileId: String) {
        runCatching { metaFile(context, fileId).delete() }
    }
}
