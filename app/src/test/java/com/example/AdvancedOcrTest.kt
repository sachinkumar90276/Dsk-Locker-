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
}
