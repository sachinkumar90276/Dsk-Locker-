package com.example.ai

import com.example.data.local.DocumentExtractionEntity
import com.example.data.local.DskCategories

/**
 * Universal Bilingual (Hindi + English) Document Parser, Validator & Anti-Hallucination Engine.
 *
 * Rules strictly enforced:
 * 1. Never invent, guess, or fabricate missing fields.
 * 2. Never confuse Father/Mother/Spouse/Officer/Authority/Organization/Filename with Owner Name.
 * 3. Ground and validate AI-extracted values against the raw OCR text (source of truth).
 * 4. Compute independent per-field confidence (0% when not detected) — never copy overall score.
 * 5. Support any document type cleanly via predefined profiles + Generic Key-Value fallback.
 */
object DeterministicDocumentParser {

    private val indianStatesAndUts = listOf(
        "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa",
        "Gujarat", "Haryana", "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala",
        "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram", "Nagaland",
        "Odisha", "Orissa", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana",
        "Tripura", "Uttar Pradesh", "Uttarakhand", "Uttaranchal", "West Bengal",
        "Delhi", "New Delhi", "NCT of Delhi", "Chandigarh", "Puducherry", "Pondicherry",
        "Jammu and Kashmir", "Jammu & Kashmir", "Ladakh", "Lakshadweep",
        "Dadra and Nagar Haveli and Daman and Diu", "Dadra and Nagar Haveli", "Daman and Diu",
        "Andaman and Nicobar Islands", "Andaman & Nicobar Islands", "Andaman and Nicobar",
        "आंध्र प्रदेश", "अरुणाचल प्रदेश", "असम", "बिहार", "छत्तीसगढ़", "गोवा",
        "गुजरात", "हरियाणा", "हिमाचल प्रदेश", "झारखंड", "झारखण्ड", "कर्नाटक", "केरल",
        "मध्य प्रदेश", "महाराष्ट्र", "मणिपुर", "मेघालय", "मिजोरम", "नागालैंड",
        "ओडिशा", "उड़ीसा", "पंजाब", "राजस्थान", "सिक्किम", "तमिलनाडु", "तेलंगाना",
        "त्रिपुरा", "उत्तर प्रदेश", "उत्तराखंड", "उत्तराखण्ड", "पश्चिम बंगाल",
        "दिल्ली", "नई दिल्ली", "चंडीगढ़", "पुदुचेरी", "लद्दाख",
        "जम्मू और कश्मीर", "जम्मू एवं कश्मीर", "लक्षद्वीप",
        "अंडमान और निकोबार", "दादरा और नगर हवेली", "दमन और दीव"
    )

    private val ocrStateAndCountryVariants = setOf(
        "india", "bharat", "republic of india", "hindustan",
        "भारत", "भारत गणराज्य", "हिन्दुस्तान",
        "tamilnadu", "maharastra", "utter pradesh", "chattisgarh", "chatisgarh",
        "u.p.", "m.p.", "a.p.", "h.p.", "t.n.", "w.b.", "j&k", "bengal",
        "union territory", "national capital territory of delhi"
    )

    private val multiWordStatesAndUts = indianStatesAndUts.filter { it.contains(' ') }

    private val noiseHeadingRegex = Regex(
        """(?i)^(government of india|govt\.? of india|govt\.? of .*|government of .*|भारत सरकार|.* शासन|unique identification authority.*|income tax department.*|आयकर विभाग.*|permanent account number.*|स्थायी लेखा संख्या.*|election commission.*|भारत निर्वाचन आयोग.*|driving licence.*|indian union.*|republic of india|aadhaar|आधार|मेरा आधार|पहचान|निवास प्रमाण.*|मूल निवास प्रमाण.*|जाति प्रमाण.*|आय प्रमाण.*|जन्म प्रमाण.*|मृत्यु प्रमाण.*|विवाह प्रमाण.*|residence certificate|domicile certificate|caste certificate|income certificate|birth certificate|to whomsoever it may concern)$"""
    )

    private val nonHolderRoleKeywords = listOf(
        "tehsildar", "tahsildar", "naib tehsildar", "nayab tehsildar",
        "sub divisional magistrate", "sub-divisional magistrate", "district magistrate",
        "collector", "registrar", "officer", "signatory", "authorized", "authorised",
        "competent authority", "issuing authority", "department", "ministry", "government",
        "govt", "commission", "authority", "uidai", "income tax", "board", "university",
        "institute", "limited", "pvt", "private", "corporation", "municipal",
        "तहसीलदार", "नायब तहसीलदार", "उपजिलाधिकारी", "जिलाधिकारी", "सक्षम अधिकारी",
        "अधिकारी", "हस्ताक्षर", "विभाग", "सरकार", "शासन", "मंत्रालय", "प्राधिकरण"
    )

    private val nonHolderLocationWordRegex = Regex(
        """(?i)$BOUNDARY_START(?:state|district|dist|tehsil|tahsil|taluk|taluka|village|gram|city|town|country|address|pincode|pin\s*code|domicile|residence|resident|राज्य|प्रदेश|जिला|जनपद|तहसील|ग्राम|गांव|शहर|देश|पता|निवास|निवासी)$BOUNDARY_END"""
    )

    private val nonHolderAddressCueRegex = Regex(
        """(?i)\b(?:road|street|lane|marg|colony|sector|vihar|enclave|mohalla|post\s+office|p\.?o\.?|house\s+no|flat\s+no|plot\s+no)\b"""
    )

    private val hindiMonthMap = mapOf(
        "जनवरी" to 1, "फ़रवरी" to 2, "फरवरी" to 2, "मार्च" to 3, "अप्रैल" to 4,
        "मई" to 5, "जून" to 6, "जुलाई" to 7, "अगस्त" to 8,
        "सितंबर" to 9, "सितम्बर" to 9, "अक्टूबर" to 10, "अक्तूबर" to 10,
        "नवंबर" to 11, "नवम्बर" to 11, "दिसंबर" to 12, "दिसम्बर" to 12
    )

    private const val BOUNDARY_START = """(?<![A-Za-z0-9\u0900-\u097F])"""
    private const val BOUNDARY_END = """(?![A-Za-z0-9\u0900-\u097F])"""

    fun normalizeIndicDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '\u0966'..'\u096F') {
                sb.append(('0'.code + (ch.code - '\u0966'.code)).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

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
        val normalizedDigitRaw = normalizeIndicDigits(cleanRaw)
        val lines = cleanRaw.lines().map { it.trim() }.filter { it.isNotBlank() }

        val detectedType = classifyDocumentType(cleanRaw, originalFilename, mimeType)
        val detectedName = canonicalDocumentName(detectedType, cleanRaw, originalFilename)

        val fieldConf = linkedMapOf<String, String>()
        val fieldScores = linkedMapOf<String, Int>()
        val needsReview = linkedSetOf<String>()
        val specifics = linkedMapOf<String, String>()

        // Extract relation/authority/org roles BEFORE holder name so holder guard never confuses them
        var fatherName = extractRelationName(
            lines,
            listOf(
                "Father's Name", "Fathers Name", "Father Name", "Father", "S/O", "D/O",
                "पिता का नाम", "पिता श्री", "पिता", "पुत्र श्री", "पुत्री श्री", "पुत्र", "पुत्री", "आत्मज", "आत्मजा"
            )
        )
        var motherName = extractRelationName(
            lines,
            listOf("Mother's Name", "Mothers Name", "Mother Name", "Mother", "माता का नाम", "माता श्रीमती", "माता")
        )
        var spouseName = extractRelationName(
            lines,
            listOf(
                "Husband's Name", "Husband Name", "Wife's Name", "Wife Name", "Spouse Name", "W/O", "H/O",
                "पति का नाम", "पत्नी का नाम", "पति श्री", "पत्नी श्रीमती", "पति/पत्नी का नाम", "पत्नी"
            )
        )

        var dateOfBirth = extractLabeledDate(lines, listOf("Date of Birth", "Birth Date", "DOB", "D.O.B", "जन्म तिथि", "जन्म की तारीख", "जन्म दिनांक", "Year of Birth", "YOB"))
        var gender = extractGender(cleanRaw, lines)
        var docNumber: String? = null
        var regNumber = extractLabelledValue(
            lines,
            listOf(
                "Application Number", "Application No", "App No", "आवेदन क्रमांक", "आवेदन संख्या",
                "Registration Number", "Registration No", "Reg No", "Reg. No",
                "Enrollment No", "Enrolment No", "Roll No", "File No", "CIF No",
                "पंजीकरण संख्या", "नामांकन संख्या"
            ),
            stopAtComma = true
        )?.let { normalizeIndicDigits(it) }

        val mobile = extractMobile(normalizedDigitRaw)
        val email = extractEmail(cleanRaw)

        var issueDate = extractLabeledDate(
            lines,
            listOf("Issue Date", "Date of Issue", "Issued On", "Invoice Date", "Bill Date", "Statement Date", "Commencement Date", "जारी करने की तिथि", "जारी दिनांक", "निर्गमन दिनांक", "दिनांक")
        )
        var expiryDate = extractLabeledDate(
            lines,
            listOf("Expiry Date", "Date of Expiry", "Valid Till", "Valid Upto", "Validity", "Valid Until", "Due Date", "Maturity Date", "समाप्ति तिथि", "वैधता")
        )

        var orgName = extractLabelledValue(
            lines,
            listOf("Organization", "Organisation", "Department", "Institution", "University", "Board", "Bank Name", "Company", "Vendor", "Insurer", "School", "College", "Employer", "विभाग", "संस्था", "कार्यालय")
        )
        var issuingAuth = extractLabelledValue(
            lines,
            listOf("Issuing Authority", "Issued By", "Digitally Signed By", "Signing Authority", "जारीकर्ता अधिकारी", "सक्षम अधिकारी", "हस्ताक्षरकर्ता", "Place of Issue")
        )

        val pinCode = extractPinCode(normalizedDigitRaw)
        val state = extractIndianState(cleanRaw)
            ?: extractLabelledValue(
                lines,
                listOf("State Name", "State of Residence", "State / UT", "State", "राज्य का नाम", "राज्य", "प्रदेश"),
                stopAtComma = true
            )?.let(::truncateAtInlineLabel)?.trim()?.takeIf { it.isNotBlank() && it.length <= 40 }
        var district = extractLabelledValue(lines, listOf("District", "Dist", "Dist.", "जिला", "जनपद"), stopAtComma = true)
            ?: extractNarrativeLocationToken(cleanRaw, listOf("जिला", "जनपद", "District", "Dist"))
        var tehsil = extractLabelledValue(lines, listOf("Tehsil", "Tahsil", "Taluka", "Taluk", "Block", "Sub-Division", "तहसील", "तालुका", "प्रखण्ड", "उपखंड"), stopAtComma = true)
            ?: extractNarrativeLocationToken(cleanRaw, listOf("तहसील", "तालुका", "प्रखण्ड", "Tehsil", "Tahsil", "Taluka"))
        var city = extractLabelledValue(lines, listOf("City", "Town", "नगर", "शहर"), stopAtComma = true)
        var village = extractLabelledValue(lines, listOf("Village", "VTC", "Gram", "Town/Village", "ग्राम", "गांव", "मौजा"), stopAtComma = true)
            ?: extractNarrativeLocationToken(cleanRaw, listOf("ग्राम", "गांव", "मौजा", "Village", "Vill"))
        var address = extractAddressBlock(lines, pinCode, state)

        // Document-type specific extraction
        when (detectedType) {
            "Aadhaar Card" -> {
                val uid = extractAadhaarNumber(normalizedDigitRaw, mobile)
                if (uid != null) {
                    docNumber = uid.first
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = uid.second
                    fieldScores[DocumentFieldProfile.KEY_DOC_NUMBER] = if (uid.second == "HIGH") 96 else 72
                    if (uid.second != "HIGH") needsReview.add(DocumentFieldProfile.KEY_DOC_NUMBER)
                }
                Regex("""(?i)\bVID\s*[:\-]?\s*(\d{4}\s?\d{4}\s?\d{4}\s?\d{4})\b""").find(normalizedDigitRaw)?.groupValues?.getOrNull(1)?.let {
                    specifics["VID"] = it.replace(Regex("\\s+"), " ").trim()
                }
                if (issuingAuth == null) issuingAuth = "Unique Identification Authority of India (UIDAI)"
                if (orgName == null) orgName = "UIDAI"
            }

            "PAN Card" -> {
                val pan = extractPanNumber(normalizedDigitRaw)
                if (pan != null) {
                    docNumber = pan.first
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = pan.second
                    fieldScores[DocumentFieldProfile.KEY_DOC_NUMBER] = if (pan.second == "HIGH") 97 else 68
                    if (pan.second != "HIGH") needsReview.add(DocumentFieldProfile.KEY_DOC_NUMBER)
                }
                if (issuingAuth == null) issuingAuth = "Income Tax Department, Govt. of India"
                if (orgName == null) orgName = "Income Tax Department"
            }

            "Residence Certificate", "Caste Certificate", "Income Certificate",
            "Birth Certificate", "Death Certificate", "Marriage Certificate" -> {
                val certNo = extractLabelledValue(
                    lines,
                    listOf(
                        "Certificate Number", "Certificate No", "Cert No",
                        "प्रमाण पत्र क्रमांक", "प्रमाण-पत्र संख्या", "प्रमाणपत्र संख्या", "प्रमाण पत्र संख्या", "क्रमांक"
                    ),
                    stopAtComma = true
                )?.let { normalizeIndicDigits(it) }
                if (certNo != null) {
                    docNumber = certNo
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = "HIGH"
                    fieldScores[DocumentFieldProfile.KEY_DOC_NUMBER] = 94
                }
                if (issuingAuth == null) {
                    issuingAuth = inferCertificateAuthority(lines, cleanRaw)
                }
                if (orgName == null) {
                    orgName = lines.firstOrNull {
                        it.contains("e-district", true) || it.contains("ई-डिस्ट्रिक्ट") ||
                            it.contains("शासन") || it.contains("Government of", true)
                    }
                }
                extractLabelledValue(
                    lines,
                    listOf("Annual Income", "Total Annual Income", "Family Income", "वार्षिक आय", "कुल वार्षिक आय")
                )?.let { specifics["Annual Income"] = normalizeIndicDigits(it) }
                extractLabelledValue(
                    lines,
                    listOf("Caste / Category", "Caste", "Category", "Sub-Caste", "जाति", "उपजाति", "वर्ग")
                )?.let { specifics["Caste / Category"] = it }
                extractVerificationDetails(cleanRaw, lines)?.let { specifics["Verification Details"] = it }
            }

            "Voter ID" -> {
                val epic = Regex("""\b([A-Z]{3}\s?[0-9]{7})\b""").find(normalizedDigitRaw)?.groupValues?.getOrNull(1)
                if (epic != null) {
                    docNumber = epic.replace(" ", "")
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = "HIGH"
                    fieldScores[DocumentFieldProfile.KEY_DOC_NUMBER] = 96
                }
                if (issuingAuth == null) issuingAuth = "Election Commission of India"
                if (orgName == null) orgName = "Election Commission of India"
            }

            "Driving Licence" -> {
                val dl = Regex("""\b([A-Z]{2}[- ]?\d{2}[- ]?(?:19|20)\d{2}[- ]?\d{6,7})\b""").find(normalizedDigitRaw)?.groupValues?.getOrNull(1)
                    ?: extractLabelledValue(lines, listOf("DL No", "DL Number", "Licence No", "License No"))
                if (dl != null) {
                    docNumber = dl.trim()
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = "HIGH"
                    fieldScores[DocumentFieldProfile.KEY_DOC_NUMBER] = 95
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
                    if (dateOfBirth == null) dateOfBirth = mrz.dobIso
                    if (expiryDate == null) expiryDate = mrz.expiryIso
                    if (gender == null) gender = mrz.gender
                    specifics["Nationality"] = mrz.nationality
                    fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = "HIGH"
                    fieldScores[DocumentFieldProfile.KEY_DOC_NUMBER] = 97
                } else {
                    val pp = Regex("""\b([A-Z][1-9]\d{6,7})\b""").find(normalizedDigitRaw)?.groupValues?.getOrNull(1)
                    if (pp != null) {
                        docNumber = pp
                        fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = "MEDIUM"
                        fieldScores[DocumentFieldProfile.KEY_DOC_NUMBER] = 80
                    }
                }
                extractLabelledValue(lines, listOf("Place of Birth"))?.let { specifics["Place of Birth"] = it }
                extractLabelledValue(lines, listOf("File No", "File Number"))?.let { specifics["Passport File No"] = it }
                if (issuingAuth == null) issuingAuth = "Republic of India / Ministry of External Affairs"
                if (orgName == null) orgName = "Ministry of External Affairs"
            }

            "Bank Document" -> {
                val ifsc = Regex("""\b([A-Z]{4}0[A-Z0-9]{6})\b""").find(normalizedDigitRaw)?.groupValues?.getOrNull(1)
                if (ifsc != null) specifics["IFSC Code"] = ifsc
                val acct = extractLabelledValue(lines, listOf("Account Number", "Account No", "A/C No", "Ac No"), stopAtComma = true)
                    ?: Regex("""(?i)(?:A/C|Account)\s*(?:No\.?|Number)?\s*[:\-]?\s*([X*\d]{6,20})""").find(normalizedDigitRaw)?.groupValues?.getOrNull(1)
                if (acct != null) {
                    docNumber = acct.trim()
                    fieldScores[DocumentFieldProfile.KEY_DOC_NUMBER] = 92
                }
                extractLabelledValue(lines, listOf("Branch", "Branch Name"))?.let { specifics["Branch"] = it }
                if (orgName == null) orgName = inferBankName(lines)
            }

            "Insurance Document" -> {
                val pol = extractLabelledValue(lines, listOf("Policy Number", "Policy No", "Certificate No"), stopAtComma = true)
                    ?: Regex("""(?i)Policy\s*(?:No\.?|Number)\s*[:\-]?\s*([A-Z0-9/-]{5,24})""").find(normalizedDigitRaw)?.groupValues?.getOrNull(1)
                if (pol != null) {
                    docNumber = pol.trim()
                    fieldScores[DocumentFieldProfile.KEY_DOC_NUMBER] = 92
                }
                extractLabelledValue(lines, listOf("Nominee", "Nominee Name"))?.let { specifics["Nominee"] = it }
                extractLabelledValue(lines, listOf("Sum Assured", "Sum Insured", "Coverage"))?.let { specifics["Sum Assured"] = it }
                extractLabelledValue(lines, listOf("Plan Name", "Policy Plan", "Product"))?.let { specifics["Plan Name"] = it }
            }

            "Educational Certificate", "Mark Sheet", "Certificate" -> {
                if (docNumber == null) {
                    docNumber = extractLabelledValue(lines, listOf("Certificate No", "Serial No", "Sl No", "S.No", "क्रमांक"), stopAtComma = true)
                }
                extractLabelledValue(lines, listOf("Course", "Degree", "Programme", "Examination"))?.let { specifics["Course / Exam"] = it }
                extractLabelledValue(lines, listOf("Percentage", "CGPA", "GPA", "Grade", "Division", "Result"))?.let { specifics["Score / Result"] = it }
                if (orgName == null) orgName = lines.firstOrNull { it.contains("university", true) || it.contains("board", true) || it.contains("institute", true) || it.contains("महाविद्यालय") || it.contains("विश्वविद्यालय") }
            }

            "Bill / Invoice" -> {
                docNumber = extractLabelledValue(lines, listOf("Invoice No", "Invoice Number", "Bill No", "Receipt No", "Order No"), stopAtComma = true)
                Regex("""\b(\d{2}[A-Z]{5}\d{4}[A-Z][A-Z\d]Z[A-Z\d])\b""").find(normalizedDigitRaw)?.groupValues?.getOrNull(1)?.let {
                    specifics["GSTIN"] = it
                    if (regNumber == null) regNumber = it
                }
                extractLabelledValue(lines, listOf("Total Amount", "Grand Total", "Net Amount", "Amount Paid", "Amount Due"))?.let {
                    specifics["Total Amount"] = it
                }
                if (orgName == null) orgName = lines.firstOrNull { it.length in 3..55 && !it.contains("invoice", true) }
            }

            else -> {
                // Universal Document Number lookup for Ration Card, Land Docs, Govt Orders, Generic Docs, etc.
                docNumber = extractLabelledValue(
                    lines,
                    listOf(
                        "Certificate No", "Certificate Number", "Ration Card No", "Order No",
                        "Reference No", "Ref No", "Document No", "ID No", "Khata No", "Khasra No",
                        "पत्रांक", "आदेश संख्या", "प्रमाण पत्र क्रमांक", "राशन कार्ड संख्या", "क्रम संख्या"
                    ),
                    stopAtComma = true
                )?.let { normalizeIndicDigits(it) }
            }
        }

        // Parse address subcomponents BEFORE holder validation so address locations are never mistaken for holder name
        if (address != null) {
            if (district == null) district = extractTokenAfter(address, listOf("Dist", "District", "जिला", "जनपद"))
            if (tehsil == null) tehsil = extractTokenAfter(address, listOf("Tehsil", "Tahsil", "Taluka", "Block", "तहसील", "प्रखण्ड"))
            if (village == null) village = extractTokenAfter(address, listOf("Village", "Vill", "VTC", "Gram", "ग्राम", "गांव"))
            if (city == null && district != null) city = inferCityFromAddress(address, district, tehsil, state, pinCode)
        }

        // Strict Holder / Owner Name extraction (Explicit label -> Narrative clause -> Strict DOB anchor, never guessing)
        val holderResult = extractValidatedHolderName(
            lines = lines,
            cleanRaw = cleanRaw,
            detectedType = detectedType,
            fatherName = fatherName,
            motherName = motherName,
            spouseName = spouseName,
            orgName = orgName,
            issuingAuth = issuingAuth,
            detectedName = detectedName,
            originalFilename = originalFilename,
            state = state,
            district = district,
            city = city,
            village = village,
            tehsil = tehsil,
            address = address
        )
        val ownerName = holderResult?.first
        val ownerConfidence = holderResult?.second ?: 0

        // Passport MRZ holder fallback ONLY if explicit holder label absent
        val finalOwnerName = ownerName ?: if (detectedType == "Passport") {
            parsePassportMrz(lines)?.fullName?.let { cleanPersonName(it) }
        } else null
        val finalOwnerScore = if (ownerName != null) ownerConfidence else if (finalOwnerName != null) 92 else 0

        // Infer PAN Father name if PAN structural anchor placed owner directly before father
        if (detectedType == "PAN Card" && fatherName == null && finalOwnerName != null) {
            fatherName = inferPanFatherAfterOwner(lines, finalOwnerName, dateOfBirth)
        }

        if (docNumber == null && regNumber != null && detectedType !in setOf("Residence Certificate", "Caste Certificate", "Income Certificate")) {
            docNumber = regNumber
        }

        // Universal Generic Key-Value extraction so unknown/arbitrary documents stay structured
        if (detectedType in setOf("Generic Document", "Other Document", "Government Order", "Land Document", "Medical Document", "Employment Document", "Legal Document", "Application Form", "Identity Card")) {
            extractGenericKeyValuePairs(lines, specifics)
        }

        // Clean fields & assign independent per-field confidence scores (0% if missing!)
        val cleanedOwner = cleanPersonName(finalOwnerName)
        val cleanedFather = cleanPersonName(fatherName)
        val cleanedMother = cleanPersonName(motherName)
        val cleanedSpouse = cleanPersonName(spouseName)

        if (cleanedOwner != null) {
            fieldScores[DocumentFieldProfile.KEY_OWNER_NAME] = finalOwnerScore
            fieldConf[DocumentFieldProfile.KEY_OWNER_NAME] = levelForScore(finalOwnerScore)
            if (finalOwnerScore < 70) needsReview.add(DocumentFieldProfile.KEY_OWNER_NAME)
        } else if (detectedType != "Personal Photograph" && cleanRaw.isNotBlank()) {
            fieldScores[DocumentFieldProfile.KEY_OWNER_NAME] = 0
            fieldConf[DocumentFieldProfile.KEY_OWNER_NAME] = "NONE"
            needsReview.add(DocumentFieldProfile.KEY_OWNER_NAME)
        }

        if (cleanedFather != null) {
            val sc = scoreNameConfidenceNumeric(cleanedFather, explicit = true)
            fieldScores[DocumentFieldProfile.KEY_FATHER_NAME] = sc
            fieldConf[DocumentFieldProfile.KEY_FATHER_NAME] = levelForScore(sc)
            if (sc < 70) needsReview.add(DocumentFieldProfile.KEY_FATHER_NAME)
        }
        if (cleanedMother != null) {
            val sc = scoreNameConfidenceNumeric(cleanedMother, explicit = true)
            fieldScores[DocumentFieldProfile.KEY_MOTHER_NAME] = sc
            fieldConf[DocumentFieldProfile.KEY_MOTHER_NAME] = levelForScore(sc)
        }
        if (cleanedSpouse != null) {
            val sc = scoreNameConfidenceNumeric(cleanedSpouse, explicit = true)
            fieldScores[DocumentFieldProfile.KEY_SPOUSE_NAME] = sc
            fieldConf[DocumentFieldProfile.KEY_SPOUSE_NAME] = levelForScore(sc)
        }
        if (dateOfBirth != null) {
            val sc = if (dateOfBirth.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) 95 else 78
            fieldScores[DocumentFieldProfile.KEY_DOB] = sc
            fieldConf[DocumentFieldProfile.KEY_DOB] = levelForScore(sc)
        }
        if (gender != null) {
            fieldScores[DocumentFieldProfile.KEY_GENDER] = 96
            fieldConf[DocumentFieldProfile.KEY_GENDER] = "HIGH"
        }
        if (docNumber != null && !fieldScores.containsKey(DocumentFieldProfile.KEY_DOC_NUMBER)) {
            fieldScores[DocumentFieldProfile.KEY_DOC_NUMBER] = 90
            fieldConf[DocumentFieldProfile.KEY_DOC_NUMBER] = "HIGH"
        }
        if (regNumber != null) {
            fieldScores[DocumentFieldProfile.KEY_REG_NUMBER] = 91
            fieldConf[DocumentFieldProfile.KEY_REG_NUMBER] = "HIGH"
        }
        if (mobile != null) {
            fieldScores[DocumentFieldProfile.KEY_MOBILE] = 96
            fieldConf[DocumentFieldProfile.KEY_MOBILE] = "HIGH"
        }
        if (email != null) {
            fieldScores[DocumentFieldProfile.KEY_EMAIL] = 97
            fieldConf[DocumentFieldProfile.KEY_EMAIL] = "HIGH"
        }
        if (pinCode != null) {
            fieldScores[DocumentFieldProfile.KEY_PIN_CODE] = 95
            fieldConf[DocumentFieldProfile.KEY_PIN_CODE] = "HIGH"
        }
        if (state != null) {
            fieldScores[DocumentFieldProfile.KEY_STATE] = 94
            fieldConf[DocumentFieldProfile.KEY_STATE] = "HIGH"
        }
        if (district != null) {
            fieldScores[DocumentFieldProfile.KEY_DISTRICT] = 90
            fieldConf[DocumentFieldProfile.KEY_DISTRICT] = "HIGH"
        }
        if (tehsil != null) {
            fieldScores[DocumentFieldProfile.KEY_TEHSIL] = 90
            fieldConf[DocumentFieldProfile.KEY_TEHSIL] = "HIGH"
        }
        if (village != null) {
            fieldScores[DocumentFieldProfile.KEY_VILLAGE] = 90
            fieldConf[DocumentFieldProfile.KEY_VILLAGE] = "HIGH"
        }
        if (city != null) {
            fieldScores[DocumentFieldProfile.KEY_CITY] = 85
            fieldConf[DocumentFieldProfile.KEY_CITY] = "HIGH"
        }
        if (address != null) {
            val sc = if (pinCode != null || state != null || district != null) 91 else 78
            fieldScores[DocumentFieldProfile.KEY_ADDRESS] = sc
            fieldConf[DocumentFieldProfile.KEY_ADDRESS] = levelForScore(sc)
        }
        if (issueDate != null) {
            fieldScores[DocumentFieldProfile.KEY_ISSUE_DATE] = 92
            fieldConf[DocumentFieldProfile.KEY_ISSUE_DATE] = "HIGH"
        }
        if (expiryDate != null) {
            fieldScores[DocumentFieldProfile.KEY_EXPIRY_DATE] = 92
            fieldConf[DocumentFieldProfile.KEY_EXPIRY_DATE] = "HIGH"
        }
        if (issuingAuth != null) {
            fieldScores[DocumentFieldProfile.KEY_ISSUING_AUTH] = 88
            fieldConf[DocumentFieldProfile.KEY_ISSUING_AUTH] = "HIGH"
        }
        if (orgName != null) {
            fieldScores[DocumentFieldProfile.KEY_ORG_NAME] = 88
            fieldConf[DocumentFieldProfile.KEY_ORG_NAME] = "HIGH"
        }
        fieldScores[DocumentFieldProfile.KEY_DOC_TYPE] = if (detectedType == "Generic Document" || detectedType == "Other Document") 65 else 95
        if (detectedName != null) fieldScores[DocumentFieldProfile.KEY_DOC_NAME] = 90

        val baseMeta = ExtendedOcrMetadata(
            fileId = fileId,
            documentName = detectedName,
            documentType = detectedType,
            ownerName = cleanedOwner,
            fatherName = cleanedFather,
            motherName = cleanedMother,
            spouseName = cleanedSpouse,
            dateOfBirth = dateOfBirth,
            gender = gender,
            documentNumber = docNumber,
            registrationNumber = regNumber,
            mobileNumber = mobile,
            email = email,
            address = address,
            village = village,
            tehsil = tehsil,
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
            fieldConfidenceScores = fieldScores,
            needsReviewFields = needsReview,
            pageExtractions = pages,
            orientationAppliedDeg = orientationAppliedDeg,
            enhancedContrastApplied = enhancedContrastApplied
        )
        return finalizeScoresAndMissing(baseMeta, cleanRaw)
    }

    /**
     * Step 8: Anti-Hallucination Grounding & Second-Pass Recovery.
     * Verifies every AI-extracted value against the raw OCR text & role rules, rejecting fabricated values.
     */
    fun reconcileWithAi(
        ai: ExtendedOcrMetadata,
        localFallback: ExtendedOcrMetadata,
        rawText: String,
        stageSummary: String,
        originalFilename: String = ""
    ): ExtendedOcrMetadata {
        val mergedSpecifics = linkedMapOf<String, String>().apply {
            putAll(localFallback.documentSpecificFields)
            ai.documentSpecificFields.forEach { (k, v) ->
                if (v.isNotBlank() && isValueSupportedByRawText(v, rawText, isDate = false)) {
                    put(k, v)
                }
            }
        }

        val resolvedType = ai.documentType?.takeIf {
            it.isNotBlank() && !it.equals("Unclassified Document", true) && !it.equals("Other Document", true)
        } ?: localFallback.documentType ?: ai.documentType

        val resolvedName = ai.documentName?.takeIf {
            it.isNotBlank() && !it.equals("Unclassified Document", true)
        } ?: localFallback.documentName ?: resolvedType

        val confMap = linkedMapOf<String, String>().apply { putAll(localFallback.fieldConfidences) }
        val scoreMap = linkedMapOf<String, Int>().apply { putAll(localFallback.fieldConfidenceScores) }
        val reviewSet = linkedSetOf<String>().apply { addAll(localFallback.needsReviewFields) }

        fun pickGrounded(
            key: String,
            aiVal: String?,
            localVal: String?,
            isDate: Boolean = false,
            strictRoleCheck: ((String) -> Boolean)? = null
        ): String? {
            val cleanAi = aiVal?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            val cleanLocal = localVal?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }

            val aiGrounded = cleanAi != null &&
                isValueSupportedByRawText(cleanAi, rawText, isDate = isDate) &&
                (strictRoleCheck?.invoke(cleanAi) != false)

            if (aiGrounded) {
                val agreed = cleanLocal != null && cleanLocal.equals(cleanAi, ignoreCase = true)
                val score = if (agreed) 98 else 90
                scoreMap[key] = score
                confMap[key] = levelForScore(score)
                reviewSet.remove(key)
                return cleanAi
            }

            if (cleanLocal != null && (strictRoleCheck?.invoke(cleanLocal) != false)) {
                val score = scoreMap[key] ?: 82
                scoreMap[key] = score
                confMap[key] = levelForScore(score)
                if (cleanAi != null) reviewSet.add(key) // AI and OCR disagreed; flag for visual verify
                return cleanLocal
            }

            // Reject unsupported AI guess! Mark 0% confidence + Needs Review
            scoreMap[key] = 0
            confMap[key] = "NONE"
            if (cleanAi != null) reviewSet.add(key)
            return null
        }

        val verifiedFather = pickGrounded(DocumentFieldProfile.KEY_FATHER_NAME, ai.fatherName, localFallback.fatherName)
        val verifiedMother = pickGrounded(DocumentFieldProfile.KEY_MOTHER_NAME, ai.motherName, localFallback.motherName)
        val verifiedSpouse = pickGrounded(DocumentFieldProfile.KEY_SPOUSE_NAME, ai.spouseName, localFallback.spouseName)
        val verifiedOrg = pickGrounded(DocumentFieldProfile.KEY_ORG_NAME, ai.organizationName, localFallback.organizationName)
        val verifiedAuth = pickGrounded(DocumentFieldProfile.KEY_ISSUING_AUTH, ai.issuingAuthority, localFallback.issuingAuthority)
        val verifiedAddress = pickGrounded(DocumentFieldProfile.KEY_ADDRESS, ai.address, localFallback.address)
        val verifiedVillage = pickGrounded(DocumentFieldProfile.KEY_VILLAGE, ai.village, localFallback.village)
        val verifiedTehsil = pickGrounded(DocumentFieldProfile.KEY_TEHSIL, ai.tehsil, localFallback.tehsil)
        val verifiedCity = pickGrounded(DocumentFieldProfile.KEY_CITY, ai.city, localFallback.city)
        val verifiedDistrict = pickGrounded(DocumentFieldProfile.KEY_DISTRICT, ai.district, localFallback.district)
        val verifiedState = pickGrounded(DocumentFieldProfile.KEY_STATE, ai.state, localFallback.state)

        val verifiedOwner = pickGrounded(
            key = DocumentFieldProfile.KEY_OWNER_NAME,
            aiVal = ai.ownerName,
            localVal = localFallback.ownerName
        ) { candidate ->
            isValidHolderCandidate(
                candidate = candidate,
                fatherName = verifiedFather,
                motherName = verifiedMother,
                spouseName = verifiedSpouse,
                orgName = verifiedOrg,
                issuingAuth = verifiedAuth,
                docName = resolvedName,
                originalFilename = originalFilename,
                state = verifiedState,
                district = verifiedDistrict,
                city = verifiedCity,
                village = verifiedVillage,
                tehsil = verifiedTehsil,
                address = verifiedAddress
            )
        }

        val merged = ai.copy(
            documentName = resolvedName,
            documentType = resolvedType,
            ownerName = verifiedOwner,
            fatherName = verifiedFather,
            motherName = verifiedMother,
            spouseName = verifiedSpouse,
            dateOfBirth = pickGrounded(DocumentFieldProfile.KEY_DOB, normalizeDateOrKeep(ai.dateOfBirth), localFallback.dateOfBirth, isDate = true),
            gender = pickGrounded(DocumentFieldProfile.KEY_GENDER, ai.gender, localFallback.gender),
            documentNumber = pickGrounded(DocumentFieldProfile.KEY_DOC_NUMBER, ai.documentNumber?.let(::normalizeIndicDigits), localFallback.documentNumber),
            registrationNumber = pickGrounded(DocumentFieldProfile.KEY_REG_NUMBER, ai.registrationNumber?.let(::normalizeIndicDigits), localFallback.registrationNumber),
            mobileNumber = pickGrounded(DocumentFieldProfile.KEY_MOBILE, ai.mobileNumber?.let(::normalizeIndicDigits), localFallback.mobileNumber),
            email = pickGrounded(DocumentFieldProfile.KEY_EMAIL, ai.email, localFallback.email) { it.contains("@") },
            address = verifiedAddress,
            village = verifiedVillage,
            tehsil = verifiedTehsil,
            city = verifiedCity,
            district = verifiedDistrict,
            state = verifiedState,
            pinCode = pickGrounded(DocumentFieldProfile.KEY_PIN_CODE, ai.pinCode?.let(::normalizeIndicDigits), localFallback.pinCode) { it.matches(Regex("""[1-9]\d{5}""")) },
            organizationName = verifiedOrg,
            issueDate = pickGrounded(DocumentFieldProfile.KEY_ISSUE_DATE, normalizeDateOrKeep(ai.issueDate), localFallback.issueDate, isDate = true),
            expiryDate = pickGrounded(DocumentFieldProfile.KEY_EXPIRY_DATE, normalizeDateOrKeep(ai.expiryDate), localFallback.expiryDate, isDate = true),
            issuingAuthority = verifiedAuth,
            documentSpecificFields = mergedSpecifics,
            fieldConfidences = confMap,
            fieldConfidenceScores = scoreMap,
            needsReviewFields = reviewSet,
            pageExtractions = localFallback.pageExtractions,
            stageSummary = stageSummary,
            orientationAppliedDeg = localFallback.orientationAppliedDeg,
            enhancedContrastApplied = localFallback.enhancedContrastApplied
        )
        return finalizeScoresAndMissing(merged, rawText)
    }

    /**
     * Checks whether a candidate value actually appears in the raw OCR text
     * (invariant to punctuation, whitespace, case, Indic numerals, and ISO/DD-MM-YYYY date reformatting).
     */
    fun isValueSupportedByRawText(candidate: String, rawText: String, isDate: Boolean): Boolean {
        val rawTrim = rawText.trim()
        if (rawTrim.isBlank()) return true // Image-only AI visual read fallback when OCR engine yielded zero chars
        val cleanCand = candidate.trim()
        if (cleanCand.isBlank()) return false

        val normRaw = normalizeForGrounding(rawTrim)
        val normCand = normalizeForGrounding(cleanCand)
        if (normCand.length >= 2 && normRaw.contains(normCand)) return true

        if (isDate) {
            val normDigitsRaw = normalizeIndicDigits(rawTrim)
            // Check YYYY-MM-DD against DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY, DD MMM YYYY, or Hindi month
            val isoMatch = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(cleanCand)
            if (isoMatch != null) {
                val y = isoMatch.groupValues[1]
                val m = isoMatch.groupValues[2].toInt()
                val d = isoMatch.groupValues[3].toInt()
                val dayPad = "%02d".format(d)
                val monPad = "%02d".format(m)
                val patterns = listOf(
                    "$dayPad$monPad$y", "$d$monPad$y", "$y$monPad$dayPad"
                )
                val digitsOnlyRaw = normDigitsRaw.replace(Regex("""[^\d]"""), "")
                if (patterns.any { digitsOnlyRaw.contains(it) }) return true
                // Check day & year + any month word match
                if (normDigitsRaw.contains(y) && (normDigitsRaw.contains(dayPad) || normDigitsRaw.contains(d.toString()))) {
                    return true
                }
            }
        }

        // Token-level overlap for multi-word names / addresses (must match >= 70% significant tokens)
        val tokens = normCand.split(" ")
            .filter { it.length >= 2 }
        if (tokens.isEmpty()) return false
        val matched = tokens.count { tok -> normRaw.contains(tok) }
        return (matched.toFloat() / tokens.size.toFloat()) >= 0.7f
    }

    private fun normalizeForGrounding(text: String): String {
        return normalizeIndicDigits(text)
            .lowercase()
            .replace(Regex("""[,.;:/\-_|()'"`\[\]]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractValidatedHolderName(
        lines: List<String>,
        cleanRaw: String,
        detectedType: String,
        fatherName: String?,
        motherName: String?,
        spouseName: String?,
        orgName: String?,
        issuingAuth: String?,
        detectedName: String?,
        originalFilename: String,
        state: String? = null,
        district: String? = null,
        city: String? = null,
        village: String? = null,
        tehsil: String? = null,
        address: String? = null
    ): Pair<String, Int>? {
        // Tier 1: Explicit Holder / Applicant Labels (highest confidence)
        val holderLabels = listOf(
            "Applicant Name", "Applicant's Name", "Name of Applicant",
            "Certificate Holder Name", "Certificate Holder",
            "Beneficiary Name", "Name of Beneficiary",
            "Holder Name", "Name of Holder",
            "Card Holder Name", "Card Holder", "Cardholder Name", "Name of Cardholder", "Cardholder",
            "Owner Name", "Owner's Name", "Name of Owner",
            "Account Holder Name", "Account Holder", "Name of Account Holder",
            "Policyholder Name", "Policyholder", "Student Name", "Candidate Name",
            "Employee Name", "Patient Name", "Customer Name", "Issued To",
            "आवेदक का नाम", "धारक का नाम", "कार्डधारक का नाम", "खाताधारक का नाम",
            "मालिक का नाम", "स्वामी का नाम", "नाम धारक", "लाभार्थी का नाम", "प्रमाणित व्यक्ति",
            "Name", "नाम"
        )
        for (label in holderLabels) {
            val candidates = extractSafeNamesForLabel(lines, label)
            for (found in candidates) {
                val cleaned = cleanPersonName(found)
                if (cleaned != null && isValidHolderCandidate(
                        candidate = cleaned,
                        fatherName = fatherName,
                        motherName = motherName,
                        spouseName = spouseName,
                        orgName = orgName,
                        issuingAuth = issuingAuth,
                        docName = detectedName,
                        originalFilename = originalFilename,
                        state = state,
                        district = district,
                        city = city,
                        village = village,
                        tehsil = tehsil,
                        address = address
                    )
                ) {
                    return cleaned to 96
                }
            }
        }

        // Tier 2: Narrative Clause inside Government Certificates (Hindi & English)
        val hindiClause = Regex(
            """(?:यह\s+)?प्रमाणित\s+किया\s+जाता\s+है\s+कि\s+(?:श्री|श्रीमती|सुश्री|कुमारी|कु\.?)?\s*([A-Za-z\u0900-\u097F.\s]{2,55}?)(?=\s+(?:पुत्र|पुत्री|पत्नी|पत्नि|आत्मज|आत्मजा|निवासी|उर्फ|आयु|वर्ष|S/O|D/O|W/O|C/O)|,|$|\n)"""
        ).find(cleanRaw)?.groupValues?.getOrNull(1)?.let(::cleanPersonName)
        if (hindiClause != null && isValidHolderCandidate(
                candidate = hindiClause,
                fatherName = fatherName,
                motherName = motherName,
                spouseName = spouseName,
                orgName = orgName,
                issuingAuth = issuingAuth,
                docName = detectedName,
                originalFilename = originalFilename,
                state = state,
                district = district,
                city = city,
                village = village,
                tehsil = tehsil,
                address = address
            )
        ) {
            return hindiClause to 91
        }

        val englishClause = Regex(
            """(?i)(?:this\s+is\s+to\s+certify\s+that|certified\s+that)\s+(?:Shri|Smt\.?|Km\.?|Kumari|Mr\.?|Mrs\.?|Ms\.?)?\s*([A-Za-z.\s]{2,55}?)(?=\s+(?:S/O|D/O|W/O|C/O|R/O|son\s+of|daughter\s+of|wife\s+of|resident\s+of)|,|$|\n)"""
        ).find(cleanRaw)?.groupValues?.getOrNull(1)?.let(::cleanPersonName)
        if (englishClause != null && isValidHolderCandidate(
                candidate = englishClause,
                fatherName = fatherName,
                motherName = motherName,
                spouseName = spouseName,
                orgName = orgName,
                issuingAuth = issuingAuth,
                docName = detectedName,
                originalFilename = originalFilename,
                state = state,
                district = district,
                city = city,
                village = village,
                tehsil = tehsil,
                address = address
            )
        ) {
            return englishClause to 91
        }

        // Tier 3: Strict Structural Anchor for Unlabelled ID Cards ONLY (directly tied to DOB / Father / PAN block)
        if (detectedType == "Aadhaar Card" || detectedType == "PAN Card") {
            val structural = inferCardHolderFromDobAnchor(
                lines = lines,
                isPan = (detectedType == "PAN Card"),
                fatherName = fatherName,
                motherName = motherName,
                spouseName = spouseName,
                orgName = orgName,
                issuingAuth = issuingAuth,
                detectedName = detectedName,
                originalFilename = originalFilename,
                state = state,
                district = district,
                city = city,
                village = village,
                tehsil = tehsil,
                address = address
            )
            if (structural != null) return structural to 82
        }

        // Never guess or invent holder name if uncertain
        return null
    }

    /**
     * Prevents "Name" / "नाम" from matching "State Name", "Father's Name", "District Name", etc.,
     * isolates multi-column horizontal rows, and collects all candidate values in reading order.
     */
    private fun extractSafeNamesForLabel(lines: List<String>, label: String): List<String> {
        val disallowedPrefixes = listOf(
            "father", "mother", "husband", "wife", "spouse", "guardian", "parent",
            "bank", "company", "organization", "organisation", "institution", "school",
            "college", "university", "department", "village", "district", "tehsil",
            "state", "residence", "domicile", "country", "nation", "city", "town",
            "ward", "taluk", "taluka", "block", "sub-division", "subdivision",
            "zone", "region", "circle", "division", "constituency", "panchayat",
            "municipality", "address", "postal", "pin", "pincode", "zip", "place", "location",
            "document", "certificate", "form", "scheme", "branch", "issuing", "signing",
            "hospital", "clinic", "product", "plan", "course", "exam", "subject",
            "dealer", "seller", "buyer", "witness",
            "officer", "authority", "nominee", "vendor", "employer", "shop", "store",
            "पिता", "माता", "पति", "पत्नी", "ग्राम", "गांव", "जिला", "जनपद", "तहसील",
            "राज्य", "प्रदेश", "देश", "शहर", "नगर", "कस्बा", "पता", "निवास", "निवासी",
            "वार्ड", "ब्लॉक", "प्रखण्ड", "तालुका", "पिन", "डाक", "दस्तावेज़", "प्रमाण",
            "प्रमाणपत्र", "अभिभावक", "संरक्षक", "शाखा", "योजना", "प्रदानकर्ता",
            "संस्था", "विभाग", "अधिकारी", "कार्यालय"
        )
        val isGenericNameLabel = label.equals("Name", ignoreCase = true) || label == "नाम"
        val sameLineRegex = Regex("""(?i)$BOUNDARY_START${Regex.escape(label)}$BOUNDARY_END\s*[:\-–]\s*(.+)$""")
        val emptyLineWithColonRegex = Regex("""(?i)$BOUNDARY_START${Regex.escape(label)}$BOUNDARY_END\s*[:\-–]\s*$""")
        val standaloneExplicitRegex = Regex("""(?i)^${Regex.escape(label)}\s*$""")

        val results = mutableListOf<String>()
        for (i in lines.indices) {
            val line = lines[i]
            val m = sameLineRegex.find(line)
            if (m != null) {
                val localPrefix = immediatePrefixBeforeMatch(line, m.range.first)
                if (isGenericNameLabel && disallowedPrefixes.any { hasPrefixKeyword(localPrefix, it) }) {
                    continue
                }
                val truncated = truncateAtInlineLabel(m.groupValues[1])
                val valRaw = truncated.trim().trim(':', '-', '–', '|', ',', ' ')
                if (valRaw.isNotBlank() && !looksLikeAnotherLabel(valRaw)) {
                    results.add(valRaw)
                    continue
                }
            }

            // Next-line value fallback when "Label:" (or standalone multi-word explicit label) sits on its own line
            val emptyColonMatch = emptyLineWithColonRegex.find(line)
            val standaloneMatch = if (!isGenericNameLabel) standaloneExplicitRegex.find(line) else null
            val boundaryMatch = emptyColonMatch ?: standaloneMatch
            if (boundaryMatch != null && i + 1 < lines.size) {
                val localPrefix = immediatePrefixBeforeMatch(line, boundaryMatch.range.first)
                if (isGenericNameLabel && disallowedPrefixes.any { hasPrefixKeyword(localPrefix, it) }) {
                    continue
                }
                val nextLine = truncateAtInlineLabel(lines[i + 1]).trim().trim(':', '-', '–', '|', ',', ' ')
                if (nextLine.isNotBlank() && !looksLikeAnotherLabel(nextLine) && !noiseHeadingRegex.matches(nextLine)) {
                    results.add(nextLine)
                }
            }
        }
        return results
    }

    private fun immediatePrefixBeforeMatch(line: String, matchStart: Int): String {
        val before = line.substring(0, matchStart)
        return before
            .substringAfterLast(':')
            .substringAfterLast(';')
            .substringAfterLast('|')
            .split(Regex("""\s{2,}"""))
            .lastOrNull()
            .orEmpty()
            .lowercase()
    }

    private fun hasPrefixKeyword(localPrefix: String, keyword: String): Boolean {
        if (localPrefix.isBlank()) return false
        return Regex("""(?i)$BOUNDARY_START${Regex.escape(keyword)}$BOUNDARY_END""").containsMatchIn(localPrefix)
    }

    private fun truncateAtInlineLabel(raw: String): String {
        val pipeCut = raw.split(Regex("""\s*\|\s*"""), limit = 2).first()
        val multiSpaceLabelCut = pipeCut.split(
            Regex("""\s{2,}(?=[A-Za-z\u0900-\u097F'()./\s]{2,32}[:\-–])"""),
            limit = 2
        ).first()
        val inlineFieldCut = multiSpaceLabelCut.split(
            Regex("""(?i)\s+(?=(?:State(?:\s+Name|\s+of\s+Residence|\s*/\s*UT)?|District|Dist\.?|City|Town|Village|Tehsil|Tahsil|Taluka|Address|Country|PIN(?:\s*Code)?|Pin\s*Code|DOB|Date\s+of\s+Birth|Gender|Sex|Mobile|Phone|Email|Father(?:'s)?\s+Name|Mother(?:'s)?\s+Name|Husband(?:'s)?\s+Name|Wife(?:'s)?\s+Name|Spouse\s+Name|S/O|D/O|W/O|C/O|राज्य(?:\s+का\s+नाम)?|प्रदेश|जिला|जनपद|पता|ग्राम|गांव|तहसील|शहर|देश|पिन|पिता(?:\s+का\s+नाम)?|माता(?:\s+का\s+नाम)?|पति|पत्नी|जन्म\s*तिथि)\s*[:\-–])"""),
            limit = 2
        ).first()
        return inlineFieldCut
    }

    private fun isValidHolderCandidate(
        candidate: String,
        fatherName: String?,
        motherName: String?,
        spouseName: String?,
        orgName: String?,
        issuingAuth: String?,
        docName: String?,
        originalFilename: String,
        state: String? = null,
        district: String? = null,
        city: String? = null,
        village: String? = null,
        tehsil: String? = null,
        address: String? = null
    ): Boolean {
        val clean = candidate.trim()
        if (clean.length < 2 || clean.length > 60) return false
        if (clean.any(Char::isDigit)) return false
        if (clean.contains(',')) return false
        if (noiseHeadingRegex.matches(clean) || looksLikeAnotherLabel(clean)) return false

        val normalized = cleanPersonName(clean) ?: clean
        val lower = normalized.lowercase()
        if (nonHolderRoleKeywords.any { kw -> lower.contains(kw) }) return false
        if (nonHolderLocationWordRegex.containsMatchIn(normalized)) return false
        if (nonHolderAddressCueRegex.containsMatchIn(normalized)) return false

        // Reject any State / Union Territory / Country name
        if (indianStatesAndUts.any { s -> normalized.equals(s, ignoreCase = true) || clean.equals(s, ignoreCase = true) }) {
            return false
        }
        if (ocrStateAndCountryVariants.any { v -> lower == v || clean.equals(v, ignoreCase = true) }) {
            return false
        }
        if (multiWordStatesAndUts.any { ms ->
                Regex("""(?i)$BOUNDARY_START${Regex.escape(ms)}$BOUNDARY_END""").containsMatchIn(normalized)
            }
        ) {
            return false
        }

        val fileStem = originalFilename.substringBeforeLast('.').trim()
        if (fileStem.isNotBlank() && (clean.equals(fileStem, ignoreCase = true) || normalized.equals(fileStem, ignoreCase = true))) return false
        if (clean.equals(originalFilename.trim(), ignoreCase = true)) return false

        val conflictingLocations = listOfNotNull(state, district, city, village, tehsil)
        if (conflictingLocations.any { loc ->
                val l = loc.trim()
                l.isNotBlank() && (
                    normalized.equals(l, ignoreCase = true) ||
                        clean.equals(l, ignoreCase = true) ||
                        (l.contains(' ') && normalized.contains(l, ignoreCase = true))
                    )
            }
        ) {
            return false
        }
        if (!address.isNullOrBlank() && normalized.equals(address.trim(), ignoreCase = true)) {
            return false
        }

        val conflictingRoles = listOfNotNull(fatherName, motherName, spouseName, orgName, issuingAuth, docName)
        if (conflictingRoles.any { role ->
                val r = role.trim()
                r.isNotBlank() && (clean.equals(r, ignoreCase = true) || normalized.equals(r, ignoreCase = true) || (r.length >= 6 && clean.contains(r, ignoreCase = true)))
            }
        ) {
            return false
        }
        return true
    }

    private fun inferCardHolderFromDobAnchor(
        lines: List<String>,
        isPan: Boolean,
        fatherName: String?,
        motherName: String?,
        spouseName: String?,
        orgName: String?,
        issuingAuth: String?,
        detectedName: String?,
        originalFilename: String,
        state: String? = null,
        district: String? = null,
        city: String? = null,
        village: String? = null,
        tehsil: String? = null,
        address: String? = null
    ): String? {
        val dobIdx = lines.indexOfFirst {
            it.contains("DOB", true) || it.contains("Date of Birth", true) ||
                it.contains("Year of Birth", true) || it.contains("जन्म तिथि") || it.contains("जन्म")
        }

        if (isPan && dobIdx > 0) {
            // Walk up above DOB (usually Father line is just above DOB, Owner line is above Father)
            val window = lines.subList((dobIdx - 4).coerceAtLeast(0), dobIdx)
            val validNames = window.mapNotNull { cleanPersonName(it) }.filter {
                !it.contains("/") && isValidHolderCandidate(
                    it, fatherName, motherName, spouseName, orgName, issuingAuth, detectedName, originalFilename,
                    state, district, city, village, tehsil, address
                )
            }
            if (validNames.isNotEmpty()) return validNames.first()
        }

        if (!isPan && dobIdx > 0) {
            // On unlabelled Aadhaar, look strictly 1-2 lines directly above DOB / S/O
            val upWindow = lines.subList((dobIdx - 2).coerceAtLeast(0), dobIdx).reversed()
            val candidate = upWindow.mapNotNull { cleanPersonName(it) }.firstOrNull {
                isValidHolderCandidate(
                    it, fatherName, motherName, spouseName, orgName, issuingAuth, detectedName, originalFilename,
                    state, district, city, village, tehsil, address
                )
            }
            if (candidate != null) return candidate
        }
        return null
    }

    private fun inferPanFatherAfterOwner(lines: List<String>, owner: String, dob: String?): String? {
        val idx = lines.indexOfFirst { it.equals(owner, ignoreCase = true) }
        if (idx < 0) return null
        return lines.drop(idx + 1).take(2).firstOrNull { cand ->
            val cleaned = cleanPersonName(cand) ?: return@firstOrNull false
            !cleaned.equals(owner, true) &&
                (dob == null || !cand.contains(dob)) &&
                !cand.contains("DOB", true) &&
                !cand.contains("Birth", true) &&
                nonHolderRoleKeywords.none { cleaned.lowercase().contains(it) }
        }
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

        val trimmed = rawText.trim()
        val isImage = mimeType.lowercase().startsWith("image/") ||
            originalFilename.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic")
        if (isImage && trimmed.length < 25) {
            return "Personal Photograph"
        }
        // Universal fallback: any readable unknown document goes through Generic Document OCR
        if (trimmed.length >= 25) {
            return "Generic Document"
        }
        return "Other Document"
    }

    private fun canonicalDocumentName(docType: String, rawText: String, originalFilename: String): String? {
        if (docType != "Other Document" && docType != "Generic Document" && docType != "Personal Photograph") {
            return docType
        }
        if (docType == "Personal Photograph") return "Personal Photograph"
        val firstMeaningful = rawText.lines()
            .map { it.trim() }
            .firstOrNull { it.length in 5..60 && !noiseHeadingRegex.matches(it) && it.any(Char::isLetter) }
        return firstMeaningful ?: originalFilename.substringBeforeLast('.').takeIf { it.isNotBlank() }
    }

    fun finalizeScoresAndMissing(meta: ExtendedOcrMetadata, rawText: String): ExtendedOcrMetadata {
        val expected = DocumentFieldProfile.expectedCoreKeysForMissingCheck(meta.documentType)
        val missing = expected.filter { key -> valueForKey(meta, key).isNullOrBlank() }
            .map { DocumentFieldProfile.labelForKey(it) }

        val updatedScores = linkedMapOf<String, Int>().apply { putAll(meta.fieldConfidenceScores) }
        val updatedConfs = linkedMapOf<String, String>().apply { putAll(meta.fieldConfidences) }

        // Ensure every blank field explicitly carries 0% confidence (never copy overall doc confidence!)
        DocumentFieldProfile.allFieldKeys.forEach { key ->
            val v = valueForKey(meta, key)
            if (v.isNullOrBlank()) {
                updatedScores[key] = 0
                updatedConfs[key] = "NONE"
            } else if (!updatedScores.containsKey(key)) {
                updatedScores[key] = 85
                updatedConfs[key] = "HIGH"
            }
        }

        val populatedCount = DocumentFieldProfile.allFieldKeys.count { !valueForKey(meta, it).isNullOrBlank() }
        val avgPopulatedScore = DocumentFieldProfile.allFieldKeys
            .filter { !valueForKey(meta, it).isNullOrBlank() }
            .mapNotNull { updatedScores[it] }
            .average()
            .takeIf { !it.isNaN() }?.toFloat() ?: 0f

        val overallScore = when {
            expected.isEmpty() -> if (rawText.isBlank()) 80 else 78
            else -> {
                val coverage = ((expected.size - missing.size).toFloat() / expected.size.toFloat())
                val rawBonus = (rawText.length.coerceAtMost(600) / 600f) * 14f
                val reviewPenalty = (meta.needsReviewFields.size * 5).coerceAtMost(20)
                (coverage * 62f + (avgPopulatedScore / 100f) * 22f + rawBonus - reviewPenalty)
                    .toInt()
                    .coerceIn(12, 99)
            }
        }
        val label = levelForScore(overallScore)
        val summary = meta.stageSummary.ifBlank {
            "Extracted $populatedCount fields across ${meta.pageExtractions.size.coerceAtLeast(1)} page(s)"
        }
        return meta.copy(
            confidenceScore = overallScore,
            confidenceLabel = label,
            fieldConfidences = updatedConfs,
            fieldConfidenceScores = updatedScores,
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
        DocumentFieldProfile.KEY_SPOUSE_NAME -> meta.spouseName
        DocumentFieldProfile.KEY_DOB -> meta.dateOfBirth
        DocumentFieldProfile.KEY_GENDER -> meta.gender
        DocumentFieldProfile.KEY_DOC_NUMBER -> meta.documentNumber
        DocumentFieldProfile.KEY_REG_NUMBER -> meta.registrationNumber
        DocumentFieldProfile.KEY_MOBILE -> meta.mobileNumber
        DocumentFieldProfile.KEY_EMAIL -> meta.email
        DocumentFieldProfile.KEY_ADDRESS -> meta.address
        DocumentFieldProfile.KEY_VILLAGE -> meta.village
        DocumentFieldProfile.KEY_TEHSIL -> meta.tehsil
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
        for (label in labels) {
            val regex = if (stopAtComma) {
                Regex("""(?i)$BOUNDARY_START${Regex.escape(label)}$BOUNDARY_END\s*[:\-–]?\s*([^,;\n]+)""")
            } else {
                Regex("""(?i)$BOUNDARY_START${Regex.escape(label)}$BOUNDARY_END\s*[:\-–]?\s*(.*)$""")
            }
            for (i in lines.indices) {
                val line = lines[i]
                val match = regex.find(line) ?: continue
                val sameLineVal = match.groupValues[1].trim().trim(':', '-', ' ', ',')
                if (sameLineVal.isNotBlank() && sameLineVal.length >= 2 && !looksLikeAnotherLabel(sameLineVal)) {
                    return sameLineVal
                }
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
        for (label in labels) {
            val pattern = if (label.contains("/")) {
                Regex("""(?i)$BOUNDARY_START${Regex.escape(label)}\s*[:\-]?\s*([A-Za-z\u0900-\u097F.\s]{2,55})""")
            } else {
                Regex("""(?i)$BOUNDARY_START${Regex.escape(label)}$BOUNDARY_END\s*[:\-]?\s*([A-Za-z\u0900-\u097F.\s]{2,55})(?=$|,|\s+(?:निवासी|R/O|Address|Tehsil|तहसील|Dist|जिला))""")
            }
            for (line in lines) {
                val m = pattern.find(line) ?: continue
                val raw = m.groupValues[1]
                    .split(",", "Address", "House", "Ward", "Dist", "Pin", "Tehsil", "तहसील", "जिला", "निवासी")
                    .firstOrNull()?.trim()
                if (!raw.isNullOrBlank() && raw.length >= 2 && !looksLikeAnotherLabel(raw) && !noiseHeadingRegex.matches(raw)) {
                    return raw
                }
            }
        }
        return null
    }

    private fun extractNarrativeLocationToken(cleanRaw: String, prefixes: List<String>): String? {
        for (p in prefixes) {
            val rx = Regex("""(?i)$BOUNDARY_START${Regex.escape(p)}\s*[:\-–]?\s*([A-Za-z\u0900-\u097F\s]{2,32}?)(?=,|;|।|\n|\s+(?:तहसील|जिला|जनपद|राज्य|Tehsil|District|State|PIN|$))""")
            val m = rx.find(cleanRaw)?.groupValues?.getOrNull(1)?.trim()
            if (!m.isNullOrBlank() && m.length >= 2 && !looksLikeAnotherLabel(m)) return m
        }
        return null
    }

    private fun extractCertificateAuthority(lines: List<String>, cleanRaw: String): String? =
        inferCertificateAuthority(lines, cleanRaw)

    private fun inferCertificateAuthority(lines: List<String>, cleanRaw: String): String? {
        val keywords = listOf(
            "तहसीलदार", "नायब तहसीलदार", "उपजिलाधिकारी", "जिलाधिकारी", "सक्षम अधिकारी",
            "Tehsildar", "Naib Tehsildar", "Sub Divisional Magistrate", "District Magistrate", "Registrar", "SDM"
        )
        for (kw in keywords) {
            val line = lines.firstOrNull { it.contains(kw, ignoreCase = true) }
            if (line != null) return line.take(70)
        }
        return if (cleanRaw.contains("e-District", true) || cleanRaw.contains("ई-डिस्ट्रिक्ट")) {
            "e-District Competent Authority"
        } else null
    }

    private fun extractVerificationDetails(cleanRaw: String, lines: List<String>): String? {
        val url = Regex("""(?i)https?://[^\s]+|edistrict\.[a-z0-9.\-/]+|serviceonline[^\s]+""").find(cleanRaw)?.value
        val signedLine = lines.firstOrNull {
            it.contains("Digitally Signed", true) || it.contains("हस्ताक्षरित") || it.contains("Verification", true)
        }
        return listOfNotNull(signedLine?.take(60), url).joinToString(" • ").takeIf { it.isNotBlank() }
    }

    private fun extractGenericKeyValuePairs(lines: List<String>, specifics: MutableMap<String, String>) {
        val skipKeys = setOf(
            "name", "नाम", "father", "father's name", "mother", "dob", "date of birth",
            "address", "पता", "mobile", "email", "issue date", "expiry date", "document no"
        )
        for (line in lines) {
            if (specifics.size >= 14) break
            val m = Regex("""^([A-Za-z\u0900-\u097F0-9/.\s]{2,32}?)\s*[:\-–]\s*(.{2,80})$""").find(line) ?: continue
            val k = m.groupValues[1].trim()
            val v = m.groupValues[2].trim()
            if (k.lowercase() !in skipKeys && !k.all(Char::isDigit) && v.isNotBlank() && !specifics.containsKey(k)) {
                specifics[k] = v
            }
        }
    }

    private fun extractLabeledDate(lines: List<String>, labels: List<String>): String? {
        for (label in labels) {
            for (line in lines) {
                if (line.contains(label, ignoreCase = true)) {
                    val normLine = normalizeIndicDigits(line)
                    extractDateIsoFromSnippet(normLine)?.let { return it }
                    val yobMatch = Regex("""(?i)(?:Year of Birth|YOB|जन्म वर्ष)\s*[:\-]?\s*((?:19|20)\d{2})\b""").find(normLine)
                    if (yobMatch != null) return yobMatch.groupValues[1]
                }
            }
        }
        return null
    }

    fun normalizeDateOrKeep(raw: String?): String? {
        val v = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return extractDateIsoFromSnippet(normalizeIndicDigits(v)) ?: v
    }

    private fun extractDateIsoFromSnippet(text: String): String? {
        Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.]((?:19|20)\d{2})\b""").find(text)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = m.groupValues[2].toIntOrNull() ?: return@let
            val y = m.groupValues[3]
            if (d in 1..31 && mo in 1..12) return "%s-%02d-%02d".format(y, mo, d)
        }
        Regex("""\b((?:19|20)\d{2})[/\-](\d{1,2})[/\-](\d{1,2})\b""").find(text)?.let { m ->
            val y = m.groupValues[1]
            val mo = m.groupValues[2].toIntOrNull() ?: return@let
            val d = m.groupValues[3].toIntOrNull() ?: return@let
            if (d in 1..31 && mo in 1..12) return "%s-%02d-%02d".format(y, mo, d)
        }
        val months = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
        )
        Regex("""(?i)\b(\d{1,2})[\s\-]+([A-Za-z]{3,9})[\s,\-]+((?:19|20)\d{2})\b""").find(text)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = months[m.groupValues[2].lowercase().take(3)] ?: return@let
            val y = m.groupValues[3]
            if (d in 1..31) return "%s-%02d-%02d".format(y, mo, d)
        }
        // Hindi month dates (e.g. 15 अगस्त 2024)
        for ((hiMonth, mo) in hindiMonthMap) {
            Regex("""(\d{1,2})[\s\-]+${Regex.escape(hiMonth)}[\s,\-]+((?:19|20)\d{2})""").find(text)?.let { m ->
                val d = m.groupValues[1].toIntOrNull() ?: return@let
                val y = m.groupValues[2]
                if (d in 1..31) return "%s-%02d-%02d".format(y, mo, d)
            }
        }
        return null
    }

    private fun extractGender(rawText: String, lines: List<String>): String? {
        val joined = lines.joinToString(" ")
        return when {
            Regex("""(?i)\bFEMALE\b|महिला|स्त्री""").containsMatchIn(joined) -> "Female"
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
        if (rawText.contains("Income Tax", true) || rawText.contains("Permanent Account", true) || rawText.contains("PAN", true)) {
            Regex("""\b([A-Z0-9]{10})\b""").findAll(rawText).forEach { m ->
                val repaired = repairPanCandidate(m.groupValues[1])
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
            Regex("""(?i)$BOUNDARY_START${Regex.escape(state)}$BOUNDARY_END""").containsMatchIn(rawText)
        }

    private fun extractAddressBlock(lines: List<String>, pinCode: String?, state: String?): String? {
        val idx = lines.indexOfFirst {
            it.contains("Address", ignoreCase = true) ||
                it.contains("Residential Address", ignoreCase = true) ||
                it.contains("पता") || it.contains("निवासी") ||
                it.startsWith("S/O", ignoreCase = true) ||
                it.startsWith("C/O", ignoreCase = true) ||
                it.startsWith("D/O", ignoreCase = true) ||
                it.startsWith("W/O", ignoreCase = true)
        }
        if (idx >= 0) {
            val slice = lines.drop(idx).take(5).joinToString(", ")
                .replace(Regex("""(?i)^(?:residential\s+)?address\s*[:\-]?\s*"""), "")
                .replace(Regex("""^पता\s*[:\-]?\s*"""), "")
                .trim()
            if (slice.length >= 10) return slice
        }
        if (pinCode != null) {
            val pinIdx = lines.indexOfFirst { normalizeIndicDigits(it).contains(pinCode) }
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
            val m = Regex("""(?i)$BOUNDARY_START${Regex.escape(k)}[.:]?\s*([A-Za-z\u0900-\u097F\s]{2,32}?)(?:,|$)""").find(address)
            if (m != null) {
                val candidate = m.groupValues[1].trim()
                if (candidate.isNotBlank() && !looksLikeAnotherLabel(candidate)) return candidate
            }
        }
        return null
    }

    private fun inferCityFromAddress(address: String, district: String, tehsil: String?, state: String?, pinCode: String?): String? {
        val parts = address.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return parts.lastOrNull { part ->
            !part.equals(district, true) &&
                (tehsil == null || !part.equals(tehsil, true)) &&
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
        val dobIso = mrzDateToIso(line2.substring(13, 19), isBirth = true)
        val mrzGender = when (line2.getOrNull(20)) {
            'M' -> "Male"
            'F' -> "Female"
            else -> null
        }
        val expIso = mrzDateToIso(line2.substring(21, 27), isBirth = false)

        if (passportNo.isBlank() || fullName.isBlank()) return null
        return PassportMrz(passportNo, fullName, nationality, dobIso, mrzGender, expIso)
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
        val v = raw?.trim()
            ?.replace(Regex("""[|~_=]+"""), " ")
            ?.replace(Regex("""^(?:Shri|Smt\.?|Km\.?|Mr\.?|Mrs\.?|Ms\.?|श्री|श्रीमती|सुश्री|कुमारी)\s+""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
        return v?.takeIf { it.length >= 2 && it.any(Char::isLetter) && !noiseHeadingRegex.matches(it) }
    }

    private fun scoreNameConfidenceNumeric(name: String, explicit: Boolean): Int = when {
        name.any(Char::isDigit) || name.contains("?") -> 45
        name.length < 4 -> 68
        explicit -> 94
        else -> 82
    }

    private fun levelForScore(score: Int): String = when {
        score <= 0 -> "NONE"
        score >= 85 -> "HIGH"
        score >= 60 -> "MEDIUM"
        else -> "LOW"
    }

    private fun looksLikeAnotherLabel(text: String): Boolean {
        val trimmed = text.trim()
        if (Regex(
                """(?i)^(?:state|district|city|town|village|tehsil|address|country|pin(?:\s*code)?|dob|date\s+of\s+birth|gender|father|mother|husband|wife|spouse|राज्य|प्रदेश|जिला|जनपद|पता|ग्राम|गांव|तहसील|शहर|देश)\b[^:]{0,25}[:\-–]"""
            ).containsMatchIn(trimmed)
        ) {
            return true
        }
        val lower = trimmed.lowercase().trimEnd(':', '-', '–', ' ')
        return lower in setOf(
            "name", "owner name", "holder name", "cardholder name", "card holder name",
            "applicant name", "account holder", "account holder name",
            "father name", "father's name", "mother name", "mother's name",
            "husband name", "husband's name", "spouse name",
            "dob", "date of birth", "gender", "address", "mobile", "email",
            "issue date", "expiry date", "tehsil", "district", "village", "city", "town",
            "state", "state name", "state of residence", "country", "country name", "pin", "pincode", "pin code",
            "नाम", "धारक का नाम", "आवेदक का नाम", "कार्डधारक का नाम", "खाताधारक का नाम", "मालिक का नाम",
            "पता", "जन्म तिथि", "पिता", "पिता का नाम", "माता", "माता का नाम", "पति का नाम", "पत्नी का नाम",
            "तहसील", "जिला", "ग्राम", "शहर", "नगर", "राज्य", "राज्य का नाम", "प्रदेश", "देश", "पिन कोड", "आवेदन संख्या"
        )
    }
}
