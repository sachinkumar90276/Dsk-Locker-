package com.example

import com.example.ai.DeterministicDocumentParser
import com.example.ai.DocumentFieldProfile
import com.example.ai.ExtendedOcrMetadata
import org.junit.Assert.*
import org.junit.Test

class AdvancedOcrTest {

    @Test
    fun `bilingual hindi english aadhaar card extracts all present fields and splits address components`() {
        val raw = """
            भारत सरकार
            Government of India
            Unique Identification Authority of India
            नाम: रमेश कुमार शर्मा
            Name: Ramesh Kumar Sharma
            S/O: सुरेश शर्मा
            जन्म तिथि: 14/08/1992
            Gender: Male
            Address: Village: Rampur, City: Prayagraj, Dist: Prayagraj, Uttar Pradesh - 211001
            Mobile: +91 9876543210
            4567 8912 3456
            VID: 9123 4567 8901 2345
        """.trimIndent()

        val meta = DeterministicDocumentParser.parseLocal(
            fileId = "file-aadhaar",
            rawText = raw,
            originalFilename = "IMG_20261122.jpg",
            mimeType = "image/jpeg"
        )

        assertEquals("Aadhaar Card", meta.documentType)
        assertEquals("Aadhaar Card", meta.documentName)
        assertEquals("4567 8912 3456", meta.documentNumber)
        assertEquals("Ramesh Kumar Sharma", meta.ownerName)
        assertEquals("सुरेश शर्मा", meta.fatherName)
        assertEquals("1992-08-14", meta.dateOfBirth)
        assertEquals("Male", meta.gender)
        assertEquals("+91 9876543210", meta.mobileNumber)
        assertEquals("Rampur", meta.village)
        assertEquals("Prayagraj", meta.district)
        assertEquals("Uttar Pradesh", meta.state)
        assertEquals("211001", meta.pinCode)
        assertEquals("9123 4567 8901 2345", meta.documentSpecificFields["VID"])
        assertEquals("HIGH", meta.fieldConfidences[DocumentFieldProfile.KEY_DOC_NUMBER])
        assertTrue(meta.confidenceScore >= 75)
    }

    @Test
    fun `pan card repairs subtle ocr digit noise and extracts owner and father name`() {
        val raw = """
            INCOME TAX DEPARTMENT
            GOVT. OF INDIA
            Permanent Account Number Card
            ABCDE1Z34F
            PRIYA VERMA
            RAJESH VERMA
            Date of Birth: 05/03/1995
        """.trimIndent()

        val meta = DeterministicDocumentParser.parseLocal(
            fileId = "file-pan",
            rawText = raw,
            originalFilename = "scan_01.png",
            mimeType = "image/png"
        )

        assertEquals("PAN Card", meta.documentType)
        assertEquals("ABCDE1234F", meta.documentNumber)
        assertEquals("PRIYA VERMA", meta.ownerName)
        assertEquals("RAJESH VERMA", meta.fatherName)
        assertEquals("1995-03-05", meta.dateOfBirth)
        assertTrue(DocumentFieldProfile.KEY_DOC_NUMBER in meta.needsReviewFields)
    }

    @Test
    fun `passport mrz and printed metadata extract full holder identity and expiry`() {
        val raw = """
            REPUBLIC OF INDIA
            PASSPORT
            Place of Birth: JAIPUR
            File No: JP106482910221
            P<INDMEHTA<<AARAV<<<<<<<<<<<<<<<<<<<<<<<<<<<
            Z4829103<8IND9407124M3205192<<<<<<<<<<<<<<<4
        """.trimIndent()

        val meta = DeterministicDocumentParser.parseLocal(
            fileId = "file-passport",
            rawText = raw,
            originalFilename = "passport.pdf",
            mimeType = "application/pdf"
        )

        assertEquals("Passport", meta.documentType)
        assertEquals("Z4829103", meta.documentNumber)
        assertEquals("AARAV MEHTA", meta.ownerName)
        assertEquals("1994-07-12", meta.dateOfBirth)
        assertEquals("Male", meta.gender)
        assertEquals("2032-05-19", meta.expiryDate)
        assertEquals("IND", meta.documentSpecificFields["Nationality"])
        assertEquals("JAIPUR", meta.documentSpecificFields["Place of Birth"])
        assertEquals("JP106482910221", meta.documentSpecificFields["Passport File No"])
    }

    @Test
    fun `driving licence bank insurance certificate and bill extract document specific fields`() {
        val dlMeta = DeterministicDocumentParser.parseLocal(
            fileId = "dl-1",
            rawText = """
                INDIAN UNION DRIVING LICENCE
                DL No: MH12 20190045123
                Name: Kabir Kulkarni
                DOB: 18-09-1991
                Issue Date: 10/01/2019
                Valid Till: 09/01/2039
                Blood Group: O+
                MCWG LMV
                RTO Pune
            """.trimIndent(),
            originalFilename = "dl.jpg",
            mimeType = "image/jpeg"
        )
        assertEquals("Driving Licence", dlMeta.documentType)
        assertEquals("MH12 20190045123", dlMeta.documentNumber)
        assertEquals("O+", dlMeta.documentSpecificFields["Blood Group"])
        assertEquals("MCWG, LMV", dlMeta.documentSpecificFields["Vehicle Class"])
        assertEquals("2039-01-09", dlMeta.expiryDate)

        val bankMeta = DeterministicDocumentParser.parseLocal(
            fileId = "bank-1",
            rawText = """
                HDFC BANK ACCOUNT STATEMENT
                Account Holder: Ananya Rao
                Account No: 50100234891234
                IFSC: HDFC0001234
                Branch: Indiranagar Bengaluru
                Email: ananya.rao@example.com
            """.trimIndent(),
            originalFilename = "statement.pdf",
            mimeType = "application/pdf"
        )
        assertEquals("Bank Document", bankMeta.documentType)
        assertEquals("50100234891234", bankMeta.documentNumber)
        assertEquals("HDFC0001234", bankMeta.documentSpecificFields["IFSC Code"])
        assertEquals("Indiranagar Bengaluru", bankMeta.documentSpecificFields["Branch"])
        assertEquals("ananya.rao@example.com", bankMeta.email)

        val insMeta = DeterministicDocumentParser.parseLocal(
            fileId = "ins-1",
            rawText = """
                LIFE INSURANCE CORPORATION OF INDIA
                Policy No: LIC-88991122
                Policyholder: Vikram Nair
                Nominee: Meera Nair
                Sum Assured: Rs. 25,00,000
                Maturity Date: 31/12/2035
            """.trimIndent(),
            originalFilename = "policy.pdf",
            mimeType = "application/pdf"
        )
        assertEquals("Insurance Document", insMeta.documentType)
        assertEquals("LIC-88991122", insMeta.documentNumber)
        assertEquals("Meera Nair", insMeta.documentSpecificFields["Nominee"])
        assertEquals("Rs. 25,00,000", insMeta.documentSpecificFields["Sum Assured"])
        assertEquals("2035-12-31", insMeta.expiryDate)

        val billMeta = DeterministicDocumentParser.parseLocal(
            fileId = "bill-1",
            rawText = """
                TAX INVOICE
                Apollo Pharmacy Retail Ltd
                GSTIN: 29AABCA1234F1Z5
                Invoice No: INV-2026-4410
                Invoice Date: 15/11/2026
                Total Amount: INR 1,450.00
            """.trimIndent(),
            originalFilename = "receipt.jpg",
            mimeType = "image/jpeg"
        )
        assertEquals("Bill / Invoice", billMeta.documentType)
        assertEquals("INV-2026-4410", billMeta.documentNumber)
        assertEquals("29AABCA1234F1Z5", billMeta.documentSpecificFields["GSTIN"])
        assertEquals("INR 1,450.00", billMeta.documentSpecificFields["Total Amount"])
        assertEquals("2026-11-15", billMeta.issueDate)
    }

    @Test
    fun `reconcileWithAi recovers fields left blank by ai without inventing absent data`() {
        val multiPageText = """
            --- Page 1 ---
            Government of India
            Name: Neha Singh
            --- Page 2 ---
            DOB: 22/06/1998
            Gender: Female
            Address: Sector 62, Noida, Dist: Gautam Buddha Nagar, Uttar Pradesh - 201301
            --- Page 6 ---
            7890 1234 5678
            Unique Identification Authority of India
        """.trimIndent()

        val pages = DeterministicDocumentParser.splitRawTextToPages(multiPageText)
        assertEquals(3, pages.size)
        assertEquals(6, pages[2].pageNumber)

        val local = DeterministicDocumentParser.parseLocal(
            fileId = "multi-1",
            rawText = multiPageText,
            originalFilename = "aadhaar_full.pdf",
            mimeType = "application/pdf",
            pages = pages
        )

        // Simulate partial AI extraction that left several present fields blank
        val partialAi = ExtendedOcrMetadata(
            fileId = "multi-1",
            documentType = "Aadhaar Card",
            ownerName = "Neha Singh"
        )

        val merged = DeterministicDocumentParser.reconcileWithAi(
            ai = partialAi,
            localFallback = local,
            rawText = multiPageText,
            stageSummary = "6-Stage OCR (3 pages)"
        )

        assertEquals("7890 1234 5678", merged.documentNumber)
        assertEquals("1998-06-22", merged.dateOfBirth)
        assertEquals("Female", merged.gender)
        assertEquals("Uttar Pradesh", merged.state)
        assertEquals("201301", merged.pinCode)
        // Mother's name wasn't present in the document; must stay null
        assertNull(merged.motherName)
        assertTrue(merged.missingExpectedFields.isEmpty())
    }

    @Test
    fun `pure hindi document extracts hindi labels cleanly`() {
        val raw = """
            भारत सरकार
            आधार
            नाम: सुनीता देवी
            पिता का नाम: रामप्रसाद यादव
            जन्म तिथि: 10/04/1989
            महिला
            पता: ग्राम: बेलवा, जिला: वाराणसी, उत्तर प्रदेश 221001
            6543 2109 8765
        """.trimIndent()

        val meta = DeterministicDocumentParser.parseLocal(
            fileId = "hi-1",
            rawText = raw,
            originalFilename = "hindi_aadhaar.jpg",
            mimeType = "image/jpeg"
        )

        assertEquals("Aadhaar Card", meta.documentType)
        assertEquals("सुनीता देवी", meta.ownerName)
        assertEquals("रामप्रसाद यादव", meta.fatherName)
        assertEquals("1989-04-10", meta.dateOfBirth)
        assertEquals("Female", meta.gender)
        assertEquals("बेलवा", meta.village)
        assertEquals("वाराणसी", meta.district)
        assertEquals("221001", meta.pinCode)
        assertEquals("6543 2109 8765", meta.documentNumber)
    }

    @Test
    fun `personal photograph without document text stays unclassified of document fields`() {
        val photoMeta = DeterministicDocumentParser.parseLocal(
            fileId = "pic-1",
            rawText = "",
            originalFilename = "IMG_vacation.jpg",
            mimeType = "image/jpeg"
        )
        assertEquals("Personal Photograph", photoMeta.documentType)
        assertNull(photoMeta.ownerName)
        assertNull(photoMeta.documentNumber)
        assertTrue(photoMeta.missingExpectedFields.isEmpty())
    }

    @Test
    fun `hindi and english residence certificate extracts holder tehsil district authority and verification without role confusion`() {
        val raw = """
            उत्तर प्रदेश शासन
            निवास प्रमाण पत्र (Residence Certificate)
            ई-डिस्ट्रिक्ट पोर्टल
            आवेदन क्रमांक: 241560020118945
            प्रमाण पत्र संख्या: 661241009876
            जारी करने की तिथि: 18 अगस्त 2024
            यह प्रमाणित किया जाता है कि प्रिया सिंह पुत्री रमेश सिंह
            माता का नाम: मीना सिंह
            पति का नाम: अमित कुमार
            ग्राम: मोहनपुर
            तहसील: सदर
            जिला: लखनऊ
            राज्य: उत्तर प्रदेश
            पिन कोड: 226001
            डिजिटली हस्ताक्षरित: तहसीलदार सदर
            Verification: https://edistrict.up.gov.in/verify
        """.trimIndent()

        val meta = DeterministicDocumentParser.parseLocal(
            fileId = "res-1",
            rawText = raw,
            originalFilename = "tehsildar_scan_final.pdf",
            mimeType = "application/pdf"
        )

        assertEquals("Residence Certificate", meta.documentType)
        assertEquals("661241009876", meta.documentNumber)
        assertEquals("241560020118945", meta.registrationNumber)
        assertEquals("प्रिया सिंह", meta.ownerName)
        assertNotEquals("रमेश सिंह", meta.ownerName)
        assertNotEquals("तहसीलदार सदर", meta.ownerName)
        assertEquals("रमेश सिंह", meta.fatherName)
        assertEquals("मीना सिंह", meta.motherName)
        assertEquals("अमित कुमार", meta.spouseName)
        assertEquals("मोहनपुर", meta.village)
        assertEquals("सदर", meta.tehsil)
        assertEquals("लखनऊ", meta.district)
        assertEquals("226001", meta.pinCode)
        assertEquals("2024-08-18", meta.issueDate)
        assertNotNull(meta.issuingAuthority)
        assertTrue(meta.issuingAuthority!!.contains("तहसीलदार"))
        assertTrue(meta.documentSpecificFields["Verification Details"]?.contains("edistrict.up.gov.in") == true)
    }

    @Test
    fun `caste income and birth certificates extract domain specific details accurately`() {
        val casteMeta = DeterministicDocumentParser.parseLocal(
            fileId = "caste-1",
            rawText = """
                GOVERNMENT OF BIHAR
                CASTE CERTIFICATE
                Certificate No: BCCCO/2025/448120
                Application No: BRCCO/2025/1022
                Applicant Name: Rohit Paswan
                Father Name: Suresh Paswan
                Caste / Category: Scheduled Caste (Paswan)
                Tehsil: Danapur
                District: Patna
                State: Bihar
                Issue Date: 12/03/2025
                Issuing Authority: Circle Officer Danapur
            """.trimIndent(),
            originalFilename = "caste.pdf",
            mimeType = "application/pdf"
        )
        assertEquals("Caste Certificate", casteMeta.documentType)
        assertEquals("Rohit Paswan", casteMeta.ownerName)
        assertEquals("Suresh Paswan", casteMeta.fatherName)
        assertEquals("BCCCO/2025/448120", casteMeta.documentNumber)
        assertEquals("Danapur", casteMeta.tehsil)
        assertEquals("Patna", casteMeta.district)
        assertEquals("Bihar", casteMeta.state)
        assertEquals("2025-03-12", casteMeta.issueDate)

        val incomeMeta = DeterministicDocumentParser.parseLocal(
            fileId = "inc-1",
            rawText = """
                आय प्रमाण पत्र (Income Certificate)
                Certificate No: INC-2025-99104
                Applicant Name: Kavita Verma
                Father's Name: Harish Verma
                Annual Income: Rs. 72,000
                Tehsil: Hazratganj
                District: Lucknow
                Issue Date: 05/04/2025
                Issuing Authority: Tehsildar
            """.trimIndent(),
            originalFilename = "income.jpg",
            mimeType = "image/jpeg"
        )
        assertEquals("Income Certificate", incomeMeta.documentType)
        assertEquals("Kavita Verma", incomeMeta.ownerName)
        assertEquals("INC-2025-99104", incomeMeta.documentNumber)
        assertEquals("Rs. 72,000", incomeMeta.documentSpecificFields["Annual Income"])

        val birthMeta = DeterministicDocumentParser.parseLocal(
            fileId = "birth-1",
            rawText = """
                BIRTH CERTIFICATE
                Certificate No: BC-DEL-2024-7781
                Name: Aarav Gupta
                DOB: 19/01/2024
                Gender: Male
                Father's Name: Manish Gupta
                Mother's Name: Pooja Gupta
                Issue Date: 02/02/2024
                Issuing Authority: Municipal Corporation Registrar
            """.trimIndent(),
            originalFilename = "birth_cert.pdf",
            mimeType = "application/pdf"
        )
        assertEquals("Birth Certificate", birthMeta.documentType)
        assertEquals("Aarav Gupta", birthMeta.ownerName)
        assertEquals("2024-01-19", birthMeta.dateOfBirth)
        assertEquals("Manish Gupta", birthMeta.fatherName)
        assertEquals("Pooja Gupta", birthMeta.motherName)
    }

    @Test
    fun `holder name guard refuses father officer or filename when no valid holder is present`() {
        val raw = """
            GOVERNMENT CERTIFICATE
            Certificate No: CERT-99881
            Father Name: Mahendra Pratap
            Tehsildar: Ashok Tiwari
            Authorized Signatory
            District: Bhopal
            Issue Date: 11/02/2025
        """.trimIndent()

        val meta = DeterministicDocumentParser.parseLocal(
            fileId = "guard-1",
            rawText = raw,
            originalFilename = "IMG_999.jpg",
            mimeType = "image/jpeg"
        )

        assertNull(meta.ownerName)
        assertEquals(0, meta.scoreForField(DocumentFieldProfile.KEY_OWNER_NAME))
        assertTrue(DocumentFieldProfile.KEY_OWNER_NAME in meta.needsReviewFields)
    }

    @Test
    fun `reconcileWithAi rejects hallucinated ai values unsupported by raw ocr and scores each field independently`() {
        val raw = """
            RESIDENCE CERTIFICATE
            Certificate No: RC-2025-1102
            Applicant Name: Anjali Mishra
            Father Name: Rajesh Mishra
            District: Varanasi
            State: Uttar Pradesh
            Issue Date: 14/05/2025
        """.trimIndent()

        val local = DeterministicDocumentParser.parseLocal(
            fileId = "anti-hallucination",
            rawText = raw,
            originalFilename = "doc.jpg",
            mimeType = "image/jpeg"
        )

        // Simulated hallucinating AI trying to fabricate ownerName, documentNumber, mobile, and issueDate
        val hallucinatedAi = ExtendedOcrMetadata(
            fileId = "anti-hallucination",
            documentType = "Residence Certificate",
            ownerName = "John Doe",
            fatherName = "Rajesh Mishra",
            documentNumber = "9999 8888 7777",
            mobileNumber = "9999999999",
            issueDate = "1999-01-01"
        )

        val verified = DeterministicDocumentParser.reconcileWithAi(
            ai = hallucinatedAi,
            localFallback = local,
            rawText = raw,
            stageSummary = "9-Stage Universal OCR",
            originalFilename = "doc.jpg"
        )

        // Local grounded values win over fabricated AI guesses; non-existent mobile remains null with 0% confidence
        assertEquals("Anjali Mishra", verified.ownerName)
        assertEquals("RC-2025-1102", verified.documentNumber)
        assertEquals("2025-05-14", verified.issueDate)
        assertNull(verified.mobileNumber)
        assertEquals(0, verified.scoreForField(DocumentFieldProfile.KEY_MOBILE))
        assertEquals(0, verified.scoreForField(DocumentFieldProfile.KEY_MOTHER_NAME))
        assertTrue(verified.scoreForField(DocumentFieldProfile.KEY_FATHER_NAME) >= 95)
        assertTrue(DocumentFieldProfile.KEY_MOBILE in verified.needsReviewFields)
    }

    @Test
    fun `unknown custom document triggers generic document ocr fallback with dynamic key value extraction`() {
        val raw = """
            SOCIETY MAINTENANCE CIRCULAR
            Ref No: SMC/2026/19
            Building Block: Tower B
            Parking Slot: B2-104
            Issued By: Residents Welfare Association
            Contact Email: rwa.office@example.org
            Issue Date: 01/10/2026
        """.trimIndent()

        val meta = DeterministicDocumentParser.parseLocal(
            fileId = "gen-1",
            rawText = raw,
            originalFilename = "society_notice.jpg",
            mimeType = "image/jpeg"
        )

        assertEquals("Generic Document", meta.documentType)
        assertEquals("2026-10-01", meta.issueDate)
        assertEquals("rwa.office@example.org", meta.email)
        assertEquals("Tower B", meta.documentSpecificFields["Building Block"])
        assertEquals("B2-104", meta.documentSpecificFields["Parking Slot"])
    }

    @Test
    fun `state name and location labels before owner or holder name are never extracted as ownerName`() {
        val ownerDoc = DeterministicDocumentParser.parseLocal(
            fileId = "state-owner-1",
            rawText = """
                State Name: Uttar Pradesh
                State of Residence: Uttar Pradesh
                District: Lucknow
                City: Lucknow
                Country Name: India
                Father's Name: Ramesh Kumar
                Owner Name: Deepak Kumar
                PIN Code: 226001
            """.trimIndent(),
            originalFilename = "property_doc.pdf",
            mimeType = "application/pdf"
        )
        assertEquals("Deepak Kumar", ownerDoc.ownerName)
        assertEquals("Uttar Pradesh", ownerDoc.state)
        assertEquals("Ramesh Kumar", ownerDoc.fatherName)

        val cardholderDoc = DeterministicDocumentParser.parseLocal(
            fileId = "state-card-1",
            rawText = """
                State: Maharashtra
                District: Pune
                Cardholder Name: Sneha Kulkarni
                State Name: Maharashtra
            """.trimIndent(),
            originalFilename = "card.jpg",
            mimeType = "image/jpeg"
        )
        assertEquals("Sneha Kulkarni", cardholderDoc.ownerName)
        assertEquals("Maharashtra", cardholderDoc.state)

        val splitLineAndTwoColDoc = DeterministicDocumentParser.parseLocal(
            fileId = "state-2col-1",
            rawText = """
                State: Gujarat   Name: Kiran Patel
                District Name: Ahmedabad   City Name: Ahmedabad
            """.trimIndent(),
            originalFilename = "doc_2col.jpg",
            mimeType = "image/jpeg"
        )
        assertEquals("Kiran Patel", splitLineAndTwoColDoc.ownerName)
        assertEquals("Gujarat", splitLineAndTwoColDoc.state)

        val splitLineOwner = DeterministicDocumentParser.parseLocal(
            fileId = "state-split-1",
            rawText = """
                State Name: Rajasthan
                Holder Name:
                Pooja Rathore
                District: Jaipur
            """.trimIndent(),
            originalFilename = "split_holder.pdf",
            mimeType = "application/pdf"
        )
        assertEquals("Pooja Rathore", splitLineOwner.ownerName)
        assertEquals("Rajasthan", splitLineOwner.state)
    }

    @Test
    fun `hindi state and location labels do not override holder or applicant name`() {
        val raw = """
            राज्य का नाम: उत्तर प्रदेश
            जिला: वाराणसी
            पिता का नाम: हरीश तिवारी
            आवेदक का नाम: विकास तिवारी
            पिन कोड: 221001
        """.trimIndent()

        val meta = DeterministicDocumentParser.parseLocal(
            fileId = "hi-state-1",
            rawText = raw,
            originalFilename = "hindi_form.jpg",
            mimeType = "image/jpeg"
        )

        assertEquals("विकास तिवारी", meta.ownerName)
        assertEquals("उत्तर प्रदेश", meta.state)
        assertEquals("वाराणसी", meta.district)

        val holderHindiRaw = """
            राज्य: मध्य प्रदेश
            धारक का नाम: मीना जोशी
            तहसील: हुजूर
            जिला: भोपाल
        """.trimIndent()
        val holderMeta = DeterministicDocumentParser.parseLocal(
            fileId = "hi-state-2",
            rawText = holderHindiRaw,
            originalFilename = "hindi_holder.jpg",
            mimeType = "image/jpeg"
        )
        assertEquals("मीना जोशी", holderMeta.ownerName)
        assertEquals("मध्य प्रदेश", holderMeta.state)
    }

    @Test
    fun `ownerName stays null when only state district address and relations exist without holder name`() {
        val raw = """
            GOVERNMENT FORM
            State Name: Bihar
            State of Residence: Bihar
            Country Name: India
            District: Patna
            City: Patna
            Village: Bihta
            Father's Name: Mohan Lal
            Mother's Name: Kamla Devi
            Husband's Name: Rakesh Lal
            Address: Ward 5, Bihta, Patna, Bihar - 801103
            PIN Code: 801103
        """.trimIndent()

        val meta = DeterministicDocumentParser.parseLocal(
            fileId = "no-holder-state",
            rawText = raw,
            originalFilename = "state_only.jpg",
            mimeType = "image/jpeg"
        )

        assertNull(meta.ownerName)
        assertEquals("Bihar", meta.state)
        assertEquals(0, meta.scoreForField(DocumentFieldProfile.KEY_OWNER_NAME))
        assertTrue(DocumentFieldProfile.KEY_OWNER_NAME in meta.needsReviewFields)
    }

    @Test
    fun `reconcileWithAi rejects state name returned as ownerName by ai`() {
        val raw = """
            RESIDENCE CERTIFICATE
            State Name: Maharashtra
            District: Nagpur
            Applicant Name: Rohan Deshpande
            Father Name: Anil Deshpande
        """.trimIndent()

        val local = DeterministicDocumentParser.parseLocal(
            fileId = "ai-state-guard",
            rawText = raw,
            originalFilename = "res.jpg",
            mimeType = "image/jpeg"
        )
        val aiReturningState = ExtendedOcrMetadata(
            fileId = "ai-state-guard",
            documentType = "Residence Certificate",
            ownerName = "Maharashtra",
            state = "Maharashtra"
        )
        val merged = DeterministicDocumentParser.reconcileWithAi(
            ai = aiReturningState,
            localFallback = local,
            rawText = raw,
            stageSummary = "9-Stage Universal OCR"
        )
        assertEquals("Rohan Deshpande", merged.ownerName)
        assertEquals("Maharashtra", merged.state)

        // Also when there is NO holder name in the document at all, AI returning State must yield null
        val noOwnerRaw = """
            APPLICATION RECORD
            State Name: Karnataka
            City Name: Bengaluru
            PIN Code: 560001
        """.trimIndent()
        val noOwnerLocal = DeterministicDocumentParser.parseLocal(
            fileId = "ai-state-only",
            rawText = noOwnerRaw,
            originalFilename = "rec.jpg",
            mimeType = "image/jpeg"
        )
        val aiStateOnly = ExtendedOcrMetadata(
            fileId = "ai-state-only",
            documentType = "Generic Document",
            ownerName = "Karnataka",
            state = "Karnataka"
        )
        val verifiedNoOwner = DeterministicDocumentParser.reconcileWithAi(
            ai = aiStateOnly,
            localFallback = noOwnerLocal,
            rawText = noOwnerRaw,
            stageSummary = "9-Stage Universal OCR"
        )
        assertNull(verifiedNoOwner.ownerName)
        assertEquals("Karnataka", verifiedNoOwner.state)
    }
}
