package com.example.ai

import com.example.data.local.DocumentExtractionEntity
import com.example.data.local.DskCategories

/**
 * Stage 3, 4b & 5: Bilingual (English + Hindi) Deterministic Extraction,
 * High-Confidence OCR Correction, Validation, Confidence Scoring & AI Recovery.
 *
 * Strictly adheres to: NEVER invent or guess missing fields.
 */
object DeterministicDocumentParser {

    private val indianStatesAndUts = listOf(
        "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa",
        "Gujarat", "Haryana", "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala",
        "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram", "Nagaland",
        "Odisha", "Orissa", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana",
        "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal", "Delhi", "New Delhi",
        "Chandigarh", "Puducherry", "Jammu and Kashmir", "Jammu & Kashmir", "Ladakh",
        "Lakshadweep", "Dadra and Nagar Haveli", "Daman and Diu", "Andaman and Nicobar"
    )

    private val noiseHeadingRegex = Regex(
        """(?i)^(government of india|govt\.? of india|भारत सरकार|unique identification authority.*|income tax department.*|आयकर विभाग.*|permanent account number.*|स्थायी लेखा संख्या.*|election commission.*|भारत निर्वाचन आयोग.*|driving licence.*|indian union.*|republic of india|aadhaar|आधार|मेरा आधार|पहचान)$"""
    )

    fun parseLocal(
        fileId: String,
        rawText: String,
        originalFilename: String,
        mimeType: String,
        pages: List<PageOcrSegment> = emptyList(),
        orientationAppliedDeg: Int = 0,
        enhancedContrastApplied: Boolean = false
    ): ExtendedOcrMetadata {
        val cleanRaw = rawText.trim()
        val lines = cleanRaw.lines().map { it.trim() }.filter { it.isNotBlank() }

        val detectedType = classifyDocumentType(cleanRaw, originalFilename, mimeType)
        val detectedName = canonicalDocumentName(detectedType, cleanRaw, originalFilename)

        val fieldConf = linkedMapOf<String, String>()
        val needsReview = linkedSetOf<String>()
        val specifics = linkedMapOf<String, String>()

        var ownerName = extractLabelledValue(
            lines,
            listOf("Name", "Holder Name", "Cardholder", "Customer Name", "Account Holder", "Applicant Name", "Candidate Name", "Student Name", "Policyholder", "नाम")
        )
        var fatherName = extractRelationName(lines, listOf("S/O", "D/O", "Father's Name", "Father Name", "Father", "Fathers Name", "C/O", "पिता का नाम", "पिता"))
        var motherName = extractRelationName(lines, listOf("Mother's Name", "Mother Name", "Mother", "Mothers Name", "माता का नाम", "माता"))
        var dateOfBirth = extractLabeledDate(lines, listOf("DOB", "D.O.B", "Date of Birth", "Birth Date", "जन्म तिथि", "जन्म की तारीख", "Year of Birth", "YOB"))
        var gender = extractGender(cleanRaw, lines)
        var docNumber: String? = null
        var regNumber = extractLabelledValue(
            lines,
            listOf("Registration No", "Registration Number", "Reg No", "Reg. No", "Enrollment No", "Enrolment No", "Roll No", "File No", "Application No", "CIF No", "पंजीकरण संख्या", "नामांकन संख्या")
        )
        val mobile = extractMobile(cleanRaw)
        val email = extractEmail(cleanRaw)

        var issueDate = extractLabeledDate(lines, listOf("Issue Date", "Date of Issue", "Issued On", "Invoice Date", "Bill Date", "Statement Date", "Commencement Date", "जारी करने की तिथि"))
        var expiryDate = extractLabeledDate(lines, listOf("Expiry Date", "Date of Expiry", "Valid Till", "Valid Upto", "Validity", "Valid Until", "Due Date", "Maturity Date", "समाप्ति तिथि"))

        var orgName = extractLabelledValue(
            lines,
            listOf("Organization", "Institution", "University", "Board", "Bank Name", "Company", "Vendor", "Insurer", "School", "College", "Employer")
        )
        var issuingAuth = extractLabelledValue(lines, listOf("Issuing Authority", "Issued By", "Authority", "Place of Issue"))

        val pinCode = extractPinCode(cleanRaw)
        val state = extractIndianState(cleanRaw)
        var district = extractLabelledValue(lines, listOf("District", "Dist", "Dist.", "जिला"), stopAtComma = true)
        var city = extractLabelledValue(lines, listOf("City", "Town", "नगर", "शहर"), stopAtComma = true)
        var village = extractLabelledValue(lines, listOf("Village", "VTC", "Gram", "Town/Village", "ग्राम", "गांव"), stopAtComma = true)
        var address = extractAddressBlock(lines, pinCode, state)

        // Document-specific specialized parsing & MRZ recovery
        when (detectedType) {
            "Aadhaar Card" -> {
                val uid = extractAadhaarNumber(cleanRaw, mobile)
                if (uid != null) {
                    docNumber = uid.first
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = uid.second
                    if (uid.second != "HIGH") needsReview.add(DocumentFieldProfile.KEY_DOC_NUMBER)
                }
                Regex("""(?i)\bVID\s*[:\-]?\s*(\d{4}\s?\d{4}\s?\d{4}\s?\d{4})\b""").find(cleanRaw)?.groupValues?.getOrNull(1)?.let {
                    specifics["VID"] = it.replace(Regex("\\s+"), " ").trim()
                }
                if (issuingAuth == null) issuingAuth = "Unique Identification Authority of India (UIDAI)"
                if (orgName == null) orgName = "UIDAI"
                if (ownerName == null) ownerName = inferAadhaarOrPanName(lines, isPan = false)
            }

            "PAN Card" -> {
                val pan = extractPanNumber(cleanRaw)
                if (pan != null) {
                    docNumber = pan.first
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = pan.second
                    if (pan.second != "HIGH") needsReview.add(DocumentFieldProfile.KEY_DOC_NUMBER)
                }
                if (issuingAuth == null) issuingAuth = "Income Tax Department, Govt. of India"
                if (orgName == null) orgName = "Income Tax Department"
                if (ownerName == null) ownerName = inferAadhaarOrPanName(lines, isPan = true)
                if (fatherName == null && ownerName != null) {
                    fatherName = inferPanFatherAfterOwner(lines, ownerName)
                }
            }

            "Voter ID" -> {
                val epic = Regex("""\b([A-Z]{3}\s?[0-9]{7})\b""").find(cleanRaw)?.groupValues?.getOrNull(1)
                if (epic != null) {
                    docNumber = epic.replace(" ", "")
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = "HIGH"
                }
                if (issuingAuth == null) issuingAuth = "Election Commission of India"
                if (orgName == null) orgName = "Election Commission of India"
            }

            "Driving Licence" -> {
                val dl = Regex("""\b([A-Z]{2}[- ]?\d{2}[- ]?(?:19|20)\d{2}[- ]?\d{6,7})\b""").find(cleanRaw)?.groupValues?.getOrNull(1)
                    ?: extractLabelledValue(lines, listOf("DL No", "DL Number", "Licence No", "License No"))
                if (dl != null) {
                    docNumber = dl.trim()
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = "HIGH"
                }
                Regex("""(?i)\bBlood\s*Group\s*[:\-]?\s*([ABO]{1,2}[+-])(?=\s|$|,|;)""").find(cleanRaw)?.groupValues?.getOrNull(1)?.let {
                    specifics["Blood Group"] = it.uppercase()
                }
                val covMatches = Regex("""\b(MCWG|MCWOG|LMV|LMV-NT|TRANS|HMV|3W-NT)\b""").findAll(cleanRaw).map { it.value }.distinct().toList()
                if (covMatches.isNotEmpty()) specifics["Vehicle Class"] = covMatches.joinToString(", ")
                if (issuingAuth == null && cleanRaw.contains("RTO", ignoreCase = true)) {
                    issuingAuth = Regex("""(?i)(RTO[^\n,]{0,36})""").find(cleanRaw)?.groupValues?.getOrNull(1)?.trim()
                        ?: "Regional Transport Office (RTO)"
                }
            }

            "Passport" -> {
                val mrz = parsePassportMrz(lines)
                if (mrz != null) {
                    docNumber = mrz.passportNumber
                    if (ownerName == null) ownerName = mrz.fullName
                    if (dateOfBirth == null) dateOfBirth = mrz.dobIso
                    if (gender == null) gender = mrz.gender
                    if (expiryDate == null) expiryDate = mrz.expiryIso
                    specifics["Nationality"] = mrz.nationality
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = "HIGH"
                } else {
                    val pp = Regex("""\b([A-Z][1-9]\d{6,7})\b""").find(cleanRaw)?.groupValues?.getOrNull(1)
                    if (pp != null) docNumber = pp
                }
                extractLabelledValue(lines, listOf("Place of Birth"))?.let { specifics["Place of Birth"] = it }
                extractLabelledValue(lines, listOf("File No", "File Number"))?.let { specifics["Passport File No"] = it }
                if (issuingAuth == null) issuingAuth = "Republic of India / Ministry of External Affairs"
                if (orgName == null) orgName = "Ministry of External Affairs"
            }

            "Bank Document" -> {
                val ifsc = Regex("""\b([A-Z]{4}0[A-Z0-9]{6})\b""").find(cleanRaw)?.groupValues?.getOrNull(1)
                if (ifsc != null) specifics["IFSC Code"] = ifsc
                val acct = extractLabelledValue(lines, listOf("Account Number", "Account No", "A/C No", "Ac No"))
                    ?: Regex("""(?i)(?:A/C|Account)\s*(?:No\.?|Number)?\s*[:\-]?\s*([X*\d]{6,20})""").find(cleanRaw)?.groupValues?.getOrNull(1)
                if (acct != null) docNumber = acct.trim()
                extractLabelledValue(lines, listOf("Branch", "Branch Name"))?.let { specifics["Branch"] = it }
                if (orgName == null) orgName = inferBankName(lines)
            }

            "Insurance Document" -> {
                val pol = extractLabelledValue(lines, listOf("Policy Number", "Policy No", "Certificate No"))
                    ?: Regex("""(?i)Policy\s*(?:No\.?|Number)\s*[:\-]?\s*([A-Z0-9/-]{5,24})""").find(cleanRaw)?.groupValues?.getOrNull(1)
                if (pol != null) docNumber = pol.trim()
                extractLabelledValue(lines, listOf("Nominee", "Nominee Name"))?.let { specifics["Nominee"] = it }
                extractLabelledValue(lines, listOf("Sum Assured", "Sum Insured", "Coverage"))?.let { specifics["Sum Assured"] = it }
                extractLabelledValue(lines, listOf("Plan Name", "Policy Plan", "Product"))?.let { specifics["Plan Name"] = it }
            }

            "Certificate" -> {
                if (docNumber == null) {
                    docNumber = extractLabelledValue(lines, listOf("Certificate No", "Serial No", "Sl No", "S.No"))
                }
                extractLabelledValue(lines, listOf("Course", "Degree", "Programme", "Examination"))?.let { specifics["Course / Exam"] = it }
                extractLabelledValue(lines, listOf("Percentage", "CGPA", "GPA", "Grade", "Division", "Result"))?.let { specifics["Score / Result"] = it }
                if (orgName == null) orgName = lines.firstOrNull { it.contains("university", true) || it.contains("board", true) || it.contains("institute", true) }
            }

            "Bill / Invoice" -> {
                docNumber = extractLabelledValue(lines, listOf("Invoice No", "Invoice Number", "Bill No", "Receipt No", "Order No"))
                Regex("""\b(\d{2}[A-Z]{5}\d{4}[A-Z][A-Z\d]Z[A-Z\d])\b""").find(cleanRaw)?.groupValues?.getOrNull(1)?.let {
                    specifics["GSTIN"] = it
                    if (regNumber == null) regNumber = it
                }
                extractLabelledValue(lines, listOf("Total Amount", "Grand Total", "Net Amount", "Amount Paid", "Amount Due"))?.let {
                    specifics["Total Amount"] = it
                }
                if (orgName == null) orgName = lines.firstOrNull { it.length in 3..55 && !it.contains("invoice", true) }
            }
        }

        // Parse address subcomponents if not found via direct labels
        if (address != null) {
            if (district == null) district = extractTokenAfter(address, listOf("Dist", "District", "जिला"))
            if (village == null) village = extractTokenAfter(address, listOf("Village", "Vill", "VTC", "Gram", "ग्राम"))
            if (city == null && district != null) city = inferCityFromAddress(address, district, state, pinCode)
        }

        // Generic document number fallback if type didn't populate it
        if (docNumber == null && regNumber != null) {
            docNumber = regNumber
        }

        // Clean fields & compute confidences
        val cleanedOwner = cleanPersonName(ownerName)
        val cleanedFather = cleanPersonName(fatherName)
        val cleanedMother = cleanPersonName(motherName)

        if (cleanedOwner != null) {
            val conf = scoreNameConfidence(cleanedOwner)
            fieldConf[DocumentFieldProfile.KEY_OWNER_NAME] = conf
            if (conf == "LOW") needsReview.add(DocumentFieldProfile.KEY_OWNER_NAME)
        }
        if (cleanedFather != null) {
            val conf = scoreNameConfidence(cleanedFather)
            fieldConf[DocumentFieldProfile.KEY_FATHER_NAME] = conf
            if (conf == "LOW") needsReview.add(DocumentFieldProfile.KEY_FATHER_NAME)
        }
        if (cleanedMother != null) {
            val conf = scoreNameConfidence(cleanedMother)
            fieldConf[DocumentFieldProfile.KEY_MOTHER_NAME] = conf
            if (conf == "LOW") needsReview.add(DocumentFieldProfile.KEY_MOTHER_NAME)
        }
        if (dateOfBirth != null) fieldConf[DocumentFieldProfile.KEY_DOB] = if (dateOfBirth.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) "HIGH" else "MEDIUM"
        if (mobile != null) fieldConf[DocumentFieldProfile.KEY_MOBILE] = "HIGH"
        if (email != null) fieldConf[DocumentFieldProfile.KEY_EMAIL] = "HIGH"
        if (pinCode != null) fieldConf[DocumentFieldProfile.KEY_PIN_CODE] = "HIGH"
        if (address != null) fieldConf[DocumentFieldProfile.KEY_ADDRESS] = if (pinCode != null || state != null) "HIGH" else "MEDIUM"

        val baseMeta = ExtendedOcrMetadata(
            fileId = fileId,
            documentName = detectedName,
            documentType = detectedType,
            ownerName = cleanedOwner,
            fatherName = cleanedFather,
            motherName = cleanedMother,
            dateOfBirth = dateOfBirth,
            gender = gender,
            documentNumber = docNumber,
            registrationNumber = regNumber,
            mobileNumber = mobile,
            email = email,
            address = address,
            village = village,
            city = city,
            district = district,
            state = state,
            pinCode = pinCode,
            organizationName = orgName,
            issueDate = issueDate,
            expiryDate = expiryDate,
            issuingAuthority = issuingAuth,
            documentSpecificFields = specifics,
            fieldConfidences = fieldConf,
            needsReviewFields = needsReview,
            pageExtractions = pages,
            orientationAppliedDeg = orientationAppliedDeg,
            enhancedContrastApplied = enhancedContrastApplied
        )
        return finalizeScoresAndMissing(baseMeta, cleanRaw)
    }

    /**
     * Stage 5: Reconciles AI extraction with local OCR recovery so AI misses never leave
     * genuinely present fields blank.
     */
    fun reconcileWithAi(
        ai: ExtendedOcrMetadata,
        localFallback: ExtendedOcrMetadata,
        rawText: String,
        stageSummary: String
    ): ExtendedOcrMetadata {
        val mergedSpecifics = linkedMapOf<String, String>().apply {
            putAll(localFallback.documentSpecificFields)
            ai.documentSpecificFields.forEach { (k, v) -> if (v.isNotBlank()) put(k, v) }
        }

        val resolvedType = ai.documentType?.takeIf {
            it.isNotBlank() && !it.equals("Unclassified Document", true)
        } ?: localFallback.documentType ?: ai.documentType

        val resolvedName = ai.documentName?.takeIf {
            it.isNotBlank() && !it.equals("Unclassified Document", true)
        } ?: localFallback.documentName ?: resolvedType

        val confMap = linkedMapOf<String, String>().apply {
            putAll(localFallback.fieldConfidences)
        }
        val reviewSet = linkedSetOf<String>().apply {
            addAll(localFallback.needsReviewFields)
        }

        fun pick(key: String, aiVal: String?, localVal: String?, validator: ((String) -> Boolean)? = null): String? {
            val cleanAi = aiVal?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            val cleanLocal = localVal?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }

            if (cleanAi != null) {
                val aiValid = validator?.invoke(cleanAi) ?: true
                if (aiValid) {
                    confMap[key] = "HIGH"
                    reviewSet.remove(key)
                    return cleanAi
                } else if (cleanLocal != null && validator.invoke(cleanLocal)) {
                    // Recover validated value from OCR text
                    confMap[key] = "MEDIUM"
                    reviewSet.add(key)
                    return cleanLocal
                }
                confMap[key] = "LOW"
                reviewSet.add(key)
                return cleanAi
            }
            if (cleanLocal != null) {
                confMap[key] = confMap[key] ?: "MEDIUM"
                return cleanLocal
            }
            return null
        }

        val merged = ai.copy(
            documentName = resolvedName,
            documentType = resolvedType,
            ownerName = pick(DocumentFieldProfile.KEY_OWNER_NAME, ai.ownerName, localFallback.ownerName),
            fatherName = pick(DocumentFieldProfile.KEY_FATHER_NAME, ai.fatherName, localFallback.fatherName),
            motherName = pick(DocumentFieldProfile.KEY_MOTHER_NAME, ai.motherName, localFallback.motherName),
            dateOfBirth = pick(DocumentFieldProfile.KEY_DOB, normalizeDateOrKeep(ai.dateOfBirth), localFallback.dateOfBirth),
            gender = pick(DocumentFieldProfile.KEY_GENDER, ai.gender, localFallback.gender),
            documentNumber = pick(DocumentFieldProfile.KEY_DOC_NUMBER, ai.documentNumber, localFallback.documentNumber),
            registrationNumber = pick(DocumentFieldProfile.KEY_REG_NUMBER, ai.registrationNumber, localFallback.registrationNumber),
            mobileNumber = pick(DocumentFieldProfile.KEY_MOBILE, ai.mobileNumber, localFallback.mobileNumber),
            email = pick(DocumentFieldProfile.KEY_EMAIL, ai.email, localFallback.email) { it.contains("@") },
            address = pick(DocumentFieldProfile.KEY_ADDRESS, ai.address, localFallback.address),
            village = pick(DocumentFieldProfile.KEY_VILLAGE, ai.village, localFallback.village),
            city = pick(DocumentFieldProfile.KEY_CITY, ai.city, localFallback.city),
            district = pick(DocumentFieldProfile.KEY_DISTRICT, ai.district, localFallback.district),
            state = pick(DocumentFieldProfile.KEY_STATE, ai.state, localFallback.state),
            pinCode = pick(DocumentFieldProfile.KEY_PIN_CODE, ai.pinCode, localFallback.pinCode) { it.matches(Regex("""[1-9]\d{5}""")) },
            organizationName = pick(DocumentFieldProfile.KEY_ORG_NAME, ai.organizationName, localFallback.organizationName),
            issueDate = pick(DocumentFieldProfile.KEY_ISSUE_DATE, normalizeDateOrKeep(ai.issueDate), localFallback.issueDate),
            expiryDate = pick(DocumentFieldProfile.KEY_EXPIRY_DATE, normalizeDateOrKeep(ai.expiryDate), localFallback.expiryDate),
            issuingAuthority = pick(DocumentFieldProfile.KEY_ISSUING_AUTH, ai.issuingAuthority, localFallback.issuingAuthority),
            documentSpecificFields = mergedSpecifics,
            fieldConfidences = confMap,
            needsReviewFields = reviewSet,
            pageExtractions = localFallback.pageExtractions,
            stageSummary = stageSummary,
            orientationAppliedDeg = localFallback.orientationAppliedDeg,
            enhancedContrastApplied = localFallback.enhancedContrastApplied
        )
        return finalizeScoresAndMissing(merged, rawText)
    }

    fun enrichFromEntity(entity: DocumentExtractionEntity): ExtendedOcrMetadata {
        val local = parseLocal(
            fileId = entity.fileId,
            rawText = entity.rawText,
            originalFilename = "",
            mimeType = "",
            pages = splitRawTextToPages(entity.rawText)
        )
        return reconcileWithAi(
            ai = ExtendedOcrMetadata(
                fileId = entity.fileId,
                documentName = entity.documentName,
                documentType = entity.documentType,
                ownerName = entity.ownerName,
                fatherName = entity.fatherName,
                motherName = entity.motherName,
                dateOfBirth = entity.dateOfBirth,
                documentNumber = entity.documentNumber,
                registrationNumber = entity.registrationNumber,
                mobileNumber = entity.mobileNumber,
                address = entity.address,
                organizationName = entity.organizationName,
                issueDate = entity.issueDate,
                expiryDate = entity.expiryDate
            ),
            localFallback = local,
            rawText = entity.rawText,
            stageSummary = if (entity.isVerified) "Verified by user" else "Reconstructed from saved OCR text"
        )
    }

    fun splitRawTextToPages(rawText: String): List<PageOcrSegment> {
        if (rawText.isBlank()) return emptyList()
        val pageMarker = Regex("""(?m)^---\s*Page\s+(\d+)\s*---$""")
        val matches = pageMarker.findAll(rawText).toList()
        if (matches.isEmpty()) {
            return listOf(
                PageOcrSegment(
                    pageNumber = 1,
                    text = rawText.trim(),
                    detectedHeading = rawText.lines().firstOrNull { it.isNotBlank() }?.take(60),
                    confidenceHint = if (rawText.length > 60) "HIGH" else "MEDIUM"
                )
            )
        }
        val segments = mutableListOf<PageOcrSegment>()
        for (i in matches.indices) {
            val pageNum = matches[i].groupValues[1].toIntOrNull() ?: (i + 1)
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else rawText.length
            val body = rawText.substring(start, end).trim()
            if (body.isNotBlank()) {
                segments.add(
                    PageOcrSegment(
                        pageNumber = pageNum,
                        text = body,
                        detectedHeading = body.lines().firstOrNull { it.isNotBlank() }?.take(60),
                        confidenceHint = if (body.length > 80) "HIGH" else "MEDIUM"
                    )
                )
            }
        }
        return segments
    }

    fun classifyDocumentType(rawText: String, originalFilename: String, mimeType: String): String {
        val detected = DskCategories.detectDocumentTypeFromContent(
            rawText = rawText,
            originalFilename = originalFilename,
            mimeType = mimeType
        )
        if (!detected.isNullOrBlank()) return detected

        val isImage = mimeType.lowercase().startsWith("image/") ||
            originalFilename.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic")
        if (isImage && rawText.trim().length < 25) {
            return "Personal Photograph"
        }
        return "Other Document"
    }

    private fun canonicalDocumentName(docType: String, rawText: String, originalFilename: String): String? {
        if (docType != "Other Document" && docType != "Personal Photograph") return docType
        if (docType == "Personal Photograph") return "Personal Photograph"
        val firstMeaningful = rawText.lines()
            .map { it.trim() }
            .firstOrNull { it.length in 5..55 && !noiseHeadingRegex.matches(it) && it.any(Char::isLetter) }
        return firstMeaningful ?: originalFilename.substringBeforeLast('.').takeIf { it.isNotBlank() }
    }

    fun finalizeScoresAndMissing(meta: ExtendedOcrMetadata, rawText: String): ExtendedOcrMetadata {
        val expected = DocumentFieldProfile.expectedCoreKeysForMissingCheck(meta.documentType)
        val missing = expected.filter { key -> valueForKey(meta, key).isNullOrBlank() }
            .map { DocumentFieldProfile.labelForKey(it) }

        val populatedCount = DocumentFieldProfile.allFieldKeys.count { !valueForKey(meta, it).isNullOrBlank() }
        val baseScore = when {
            expected.isEmpty() -> if (rawText.isBlank()) 80 else 75
            else -> {
                val coverage = ((expected.size - missing.size).toFloat() / expected.size.toFloat())
                val rawBonus = (rawText.length.coerceAtMost(600) / 600f) * 18f
                val reviewPenalty = (meta.needsReviewFields.size * 6).coerceAtMost(24)
                (coverage * 78f + rawBonus + 4f - reviewPenalty).toInt().coerceIn(15, 99)
            }
        }
        val label = when {
            baseScore >= 75 -> "HIGH"
            baseScore >= 45 -> "MEDIUM"
            else -> "LOW"
        }
        val summary = meta.stageSummary.ifBlank {
            "Extracted $populatedCount fields across ${meta.pageExtractions.size.coerceAtLeast(1)} page(s)"
        }
        return meta.copy(
            confidenceScore = baseScore,
            confidenceLabel = label,
            missingExpectedFields = missing,
            stageSummary = summary
        )
    }

    fun valueForKey(meta: ExtendedOcrMetadata, key: String): String? = when (key) {
        DocumentFieldProfile.KEY_DOC_NAME -> meta.documentName
        DocumentFieldProfile.KEY_DOC_TYPE -> meta.documentType
        DocumentFieldProfile.KEY_OWNER_NAME -> meta.ownerName
        DocumentFieldProfile.KEY_FATHER_NAME -> meta.fatherName
        DocumentFieldProfile.KEY_MOTHER_NAME -> meta.motherName
        DocumentFieldProfile.KEY_DOB -> meta.dateOfBirth
        DocumentFieldProfile.KEY_GENDER -> meta.gender
        DocumentFieldProfile.KEY_DOC_NUMBER -> meta.documentNumber
        DocumentFieldProfile.KEY_REG_NUMBER -> meta.registrationNumber
        DocumentFieldProfile.KEY_MOBILE -> meta.mobileNumber
        DocumentFieldProfile.KEY_EMAIL -> meta.email
        DocumentFieldProfile.KEY_ADDRESS -> meta.address
        DocumentFieldProfile.KEY_VILLAGE -> meta.village
        DocumentFieldProfile.KEY_CITY -> meta.city
        DocumentFieldProfile.KEY_DISTRICT -> meta.district
        DocumentFieldProfile.KEY_STATE -> meta.state
        DocumentFieldProfile.KEY_PIN_CODE -> meta.pinCode
        DocumentFieldProfile.KEY_ORG_NAME -> meta.organizationName
        DocumentFieldProfile.KEY_ISSUE_DATE -> meta.issueDate
        DocumentFieldProfile.KEY_EXPIRY_DATE -> meta.expiryDate
        DocumentFieldProfile.KEY_ISSUING_AUTH -> meta.issuingAuthority
        else -> null
    }

    private fun extractLabelledValue(
        lines: List<String>,
        labels: List<String>,
        stopAtComma: Boolean = false
    ): String? {
        val boundaryStart = """(?<![A-Za-z0-9\u0900-\u097F])"""
        val boundaryEnd = """(?![A-Za-z0-9\u0900-\u097F])"""
        for (label in labels) {
            val regex = if (stopAtComma) {
                Regex("""(?i)$boundaryStart${Regex.escape(label)}$boundaryEnd\s*[:\-–]?\s*([^,;\n]+)""")
            } else {
                Regex("""(?i)$boundaryStart${Regex.escape(label)}$boundaryEnd\s*[:\-–]?\s*(.*)$""")
            }
            for (i in lines.indices) {
                val line = lines[i]
                val match = regex.find(line) ?: continue
                val sameLineVal = match.groupValues[1].trim().trim(':', '-', ' ', ',')
                if (sameLineVal.isNotBlank() && sameLineVal.length >= 2 && !looksLikeAnotherLabel(sameLineVal)) {
                    return sameLineVal
                }
                // Check next line if label had value on the following line
                if (!stopAtComma && i + 1 < lines.size) {
                    val nextLine = lines[i + 1].trim()
                    if (nextLine.isNotBlank() && !looksLikeAnotherLabel(nextLine) && !noiseHeadingRegex.matches(nextLine)) {
                        return nextLine
                    }
                }
            }
        }
        return null
    }

    private fun extractRelationName(lines: List<String>, labels: List<String>): String? {
        val boundaryStart = """(?<![A-Za-z0-9\u0900-\u097F])"""
        val boundaryEnd = """(?![A-Za-z0-9\u0900-\u097F])"""
        for (line in lines) {
            for (label in labels) {
                val pattern = if (label.contains("/")) {
                    Regex("""(?i)$boundaryStart${Regex.escape(label)}\s*[:\-]?\s*([A-Za-z\u0900-\u097F.\s]{2,55})""")
                } else {
                    Regex("""(?i)$boundaryStart${Regex.escape(label)}$boundaryEnd\s*[:\-]?\s*([A-Za-z\u0900-\u097F.\s]{2,55})$""")
                }
                val m = pattern.find(line)
                if (m != null) {
                    val raw = m.groupValues[1].split(",", "Address", "House", "Ward", "Dist", "Pin").firstOrNull()?.trim()
                    if (!raw.isNullOrBlank() && raw.length >= 3 && !looksLikeAnotherLabel(raw)) {
                        return raw
                    }
                }
            }
        }
        return null
    }

    private fun extractLabeledDate(lines: List<String>, labels: List<String>): String? {
        for (line in lines) {
            for (label in labels) {
                if (line.contains(label, ignoreCase = true)) {
                    extractDateIsoFromSnippet(line)?.let { return it }
                    val yobMatch = Regex("""(?i)(?:Year of Birth|YOB)\s*[:\-]?\s*((?:19|20)\d{2})\b""").find(line)
                    if (yobMatch != null) return yobMatch.groupValues[1]
                }
            }
        }
        return null
    }

    fun normalizeDateOrKeep(raw: String?): String? {
        val v = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return extractDateIsoFromSnippet(v) ?: v
    }

    private fun extractDateIsoFromSnippet(text: String): String? {
        // DD/MM/YYYY or DD-MM-YYYY or DD.MM.YYYY
        Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.]((?:19|20)\d{2})\b""").find(text)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = m.groupValues[2].toIntOrNull() ?: return@let
            val y = m.groupValues[3]
            if (d in 1..31 && mo in 1..12) {
                return "%s-%02d-%02d".format(y, mo, d)
            }
        }
        // YYYY-MM-DD
        Regex("""\b((?:19|20)\d{2})[/\-](\d{1,2})[/\-](\d{1,2})\b""").find(text)?.let { m ->
            val y = m.groupValues[1]
            val mo = m.groupValues[2].toIntOrNull() ?: return@let
            val d = m.groupValues[3].toIntOrNull() ?: return@let
            if (d in 1..31 && mo in 1..12) {
                return "%s-%02d-%02d".format(y, mo, d)
            }
        }
        // DD MMM YYYY (e.g., 14 Aug 1996)
        val months = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
        )
        Regex("""(?i)\b(\d{1,2})[\s\-]+([A-Za-z]{3,9})[\s,\-]+((?:19|20)\d{2})\b""").find(text)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            val monKey = m.groupValues[2].lowercase().take(3)
            val mo = months[monKey] ?: return@let
            val y = m.groupValues[3]
            if (d in 1..31) return "%s-%02d-%02d".format(y, mo, d)
        }
        return null
    }

    private fun extractGender(rawText: String, lines: List<String>): String? {
        val joined = lines.joinToString(" ")
        return when {
            Regex("""(?i)\bFEMALE\b|महिला""").containsMatchIn(joined) -> "Female"
            Regex("""(?i)\bMALE\b|पुरुष""").containsMatchIn(joined) -> "Male"
            Regex("""(?i)\bTRANSGENDER\b|अन्य""").containsMatchIn(joined) -> "Transgender"
            Regex("""(?i)\bSex\s*[:\-]?\s*F\b""").containsMatchIn(rawText) -> "Female"
            Regex("""(?i)\bSex\s*[:\-]?\s*M\b""").containsMatchIn(rawText) -> "Male"
            else -> null
        }
    }

    private fun extractAadhaarNumber(rawText: String, mobile: String?): Pair<String, String>? {
        val candidates = Regex("""\b([2-9]\d{3}[\s-]?\d{4}[\s-]?\d{4})\b""").findAll(rawText)
            .map { it.groupValues[1].replace(Regex("""[\s-]+"""), " ") }
            .filter { cand ->
                val digits = cand.replace(" ", "")
                digits.length == 12 && (mobile == null || !mobile.endsWith(digits))
            }
            .toList()
        if (candidates.isNotEmpty()) return candidates.first() to "HIGH"
        return null
    }

    private fun extractPanNumber(rawText: String): Pair<String, String>? {
        Regex("""\b([A-Z]{5}[0-9]{4}[A-Z])\b""").find(rawText)?.let {
            return it.groupValues[1] to "HIGH"
        }
        // High-confidence OCR repair for 10-char PAN tokens near PAN cues
        if (rawText.contains("Income Tax", true) || rawText.contains("Permanent Account", true) || rawText.contains("PAN", true)) {
            Regex("""\b([A-Z0-9]{10})\b""").findAll(rawText).forEach { m ->
                val token = m.groupValues[1]
                val repaired = repairPanCandidate(token)
                if (repaired != null) return repaired to "MEDIUM"
            }
        }
        return null
    }

    private fun repairPanCandidate(token: String): String? {
        if (token.length != 10) return null
        val chars = token.toCharArray()
        for (i in 0..4) chars[i] = when (chars[i]) { '0' -> 'O'; '1' -> 'I'; '5' -> 'S'; '8' -> 'B'; else -> chars[i] }
        for (i in 5..8) chars[i] = when (chars[i]) { 'O', 'Q', 'D' -> '0'; 'I', 'L' -> '1'; 'S' -> '5'; 'B' -> '8'; 'Z' -> '2'; else -> chars[i] }
        chars[9] = when (chars[9]) { '0' -> 'O'; '1' -> 'I'; '5' -> 'S'; '8' -> 'B'; else -> chars[9] }
        val fixed = String(chars)
        return if (fixed.matches(Regex("""[A-Z]{5}[0-9]{4}[A-Z]"""))) fixed else null
    }

    private fun extractMobile(rawText: String): String? {
        Regex("""(?:\+91[\s-]?|0)?([6-9]\d{9})\b""").find(rawText)?.let {
            return "+91 ${it.groupValues[1]}"
        }
        return null
    }

    private fun extractEmail(rawText: String): String? =
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""").find(rawText)?.value

    private fun extractPinCode(rawText: String): String? =
        Regex("""(?<![\d-])([1-9]\d{5})(?![\d])""").findAll(rawText)
            .map { it.groupValues[1] }
            .firstOrNull()

    private fun extractIndianState(rawText: String): String? =
        indianStatesAndUts.firstOrNull { state ->
            Regex("""(?i)\b${Regex.escape(state)}\b""").containsMatchIn(rawText)
        }

    private fun extractAddressBlock(lines: List<String>, pinCode: String?, state: String?): String? {
        val idx = lines.indexOfFirst {
            it.contains("Address", ignoreCase = true) ||
                it.contains("पता") ||
                it.startsWith("S/O", ignoreCase = true) ||
                it.startsWith("C/O", ignoreCase = true) ||
                it.startsWith("D/O", ignoreCase = true) ||
                it.startsWith("W/O", ignoreCase = true)
        }
        if (idx >= 0) {
            val slice = lines.drop(idx).take(5).joinToString(", ")
                .replace(Regex("""(?i)^address\s*[:\-]?\s*"""), "")
                .replace(Regex("""^पता\s*[:\-]?\s*"""), "")
                .trim()
            if (slice.length >= 10) return slice
        }
        if (pinCode != null) {
            val pinIdx = lines.indexOfFirst { it.contains(pinCode) }
            if (pinIdx >= 0) {
                val fromIdx = (pinIdx - 2).coerceAtLeast(0)
                val slice = lines.subList(fromIdx, pinIdx + 1).joinToString(", ").trim()
                if (slice.length >= 10) return slice
            }
        }
        if (state != null) {
            val stLine = lines.firstOrNull { it.contains(state, ignoreCase = true) }
            if (stLine != null && stLine.length >= 10) return stLine
        }
        return null
    }

    private fun inferAadhaarOrPanName(lines: List<String>, isPan: Boolean): String? {
        val anchorIdx = lines.indexOfFirst { line ->
            if (isPan) line.contains("Income Tax", true) || line.contains("Permanent Account", true) || line.contains("स्थायी लेखा")
            else line.contains("Government of India", true) || line.contains("भारत सरकार")
        }
        val candidates = if (anchorIdx >= 0) lines.drop(anchorIdx + 1).take(6) else lines.take(6)
        return candidates.firstOrNull { cand ->
            cand.length in 3..45 &&
                !noiseHeadingRegex.matches(cand) &&
                !cand.any(Char::isDigit) &&
                !looksLikeAnotherLabel(cand) &&
                cand.count { it.isLetter() } >= 3
        }
    }

    private fun inferPanFatherAfterOwner(lines: List<String>, owner: String): String? {
        val idx = lines.indexOfFirst { it.equals(owner, ignoreCase = true) }
        if (idx < 0) return null
        return lines.drop(idx + 1).take(3).firstOrNull { cand ->
            cand.length in 3..45 &&
                !noiseHeadingRegex.matches(cand) &&
                !cand.any(Char::isDigit) &&
                !looksLikeAnotherLabel(cand)
        }
    }

    private fun inferBankName(lines: List<String>): String? =
        lines.firstOrNull { line ->
            line.length in 4..60 && (
                line.contains("Bank", ignoreCase = true) ||
                    line.contains("बैंक") ||
                    line.contains("SBI", ignoreCase = false) ||
                    line.contains("HDFC", ignoreCase = false) ||
                    line.contains("ICICI", ignoreCase = false)
                )
        }

    private fun extractTokenAfter(address: String, keys: List<String>): String? {
        for (k in keys) {
            val m = Regex("""(?i)\b${Regex.escape(k)}[.:]?\s*([A-Za-z\u0900-\u097F\s]{2,32}?)(?:,|$)""").find(address)
            if (m != null) {
                val candidate = m.groupValues[1].trim()
                if (candidate.isNotBlank()) return candidate
            }
        }
        return null
    }

    private fun inferCityFromAddress(address: String, district: String, state: String?, pinCode: String?): String? {
        val parts = address.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return parts.lastOrNull { part ->
            !part.equals(district, true) &&
                (state == null || !part.equals(state, true)) &&
                (pinCode == null || !part.contains(pinCode)) &&
                part.length in 3..30 &&
                part.none(Char::isDigit)
        }
    }

    private data class PassportMrz(
        val passportNumber: String,
        val fullName: String,
        val nationality: String,
        val dobIso: String?,
        val gender: String?,
        val expiryIso: String?
    )

    private fun parsePassportMrz(lines: List<String>): PassportMrz? {
        val cleaned = lines.map { it.replace(" ", "").uppercase() }
        val line1 = cleaned.firstOrNull { it.startsWith("P<") && it.length >= 25 } ?: return null
        val line1Idx = cleaned.indexOf(line1)
        val line2 = cleaned.getOrNull(line1Idx + 1)?.takeIf { it.length >= 28 } ?: return null

        val namesRaw = line1.drop(5).split("<<")
        val surname = namesRaw.getOrNull(0)?.replace("<", " ")?.trim().orEmpty()
        val given = namesRaw.getOrNull(1)?.replace("<", " ")?.trim().orEmpty()
        val fullName = listOf(given, surname).filter { it.isNotBlank() }.joinToString(" ").trim()

        val passportNo = line2.substring(0, 9).replace("<", "").trim()
        val nationality = line2.substring(10, 13).replace("<", "").trim()
        val dobRaw = line2.substring(13, 19)
        val sexChar = line2[20]
        val expRaw = line2.substring(21, 27)

        val dobIso = mrzDateToIso(dobRaw, isBirth = true)
        val expIso = mrzDateToIso(expRaw, isBirth = false)
        val gender = when (sexChar) {
            'M' -> "Male"
            'F' -> "Female"
            else -> null
        }
        if (passportNo.isBlank() || fullName.isBlank()) return null
        return PassportMrz(passportNo, fullName, nationality, dobIso, gender, expIso)
    }

    private fun mrzDateToIso(yymmdd: String, isBirth: Boolean): String? {
        if (yymmdd.length != 6 || !yymmdd.all(Char::isDigit)) return null
        val yy = yymmdd.substring(0, 2).toInt()
        val mm = yymmdd.substring(2, 4).toInt()
        val dd = yymmdd.substring(4, 6).toInt()
        if (mm !in 1..12 || dd !in 1..31) return null
        val yyyy = if (isBirth) {
            if (yy > 30) 1900 + yy else 2000 + yy
        } else {
            2000 + yy
        }
        return "%04d-%02d-%02d".format(yyyy, mm, dd)
    }

    private fun cleanPersonName(raw: String?): String? {
        val v = raw?.trim()?.replace(Regex("""[|~_=]+"""), " ")?.replace(Regex("""\s+"""), " ")?.trim()
        return v?.takeIf { it.length >= 2 && it.any(Char::isLetter) && !noiseHeadingRegex.matches(it) }
    }

    private fun scoreNameConfidence(name: String): String = when {
        name.any(Char::isDigit) || name.contains("?") -> "LOW"
        name.length < 4 -> "MEDIUM"
        else -> "HIGH"
    }

    private fun looksLikeAnotherLabel(text: String): Boolean {
        val lower = text.lowercase().trimEnd(':', '-', ' ')
        return lower in setOf(
            "name", "father name", "father's name", "mother name", "dob", "date of birth",
            "gender", "address", "mobile", "email", "issue date", "expiry date",
            "नाम", "पता", "जन्म तिथि", "पिता", "माता"
        )
    }
}
