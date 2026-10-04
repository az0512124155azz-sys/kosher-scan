package com.kosherscan.app

import org.junit.Assert.*
import org.junit.Test

class ResultCopyTest {
    private fun text(verdict: Verdict, issue: LookupIssue? = null) = ResultCopy.text(LookupResult(null, verdict, issue))

    @Test fun ouConditionsRemainVisibleWithoutSourceDiagnostics() {
        val verdict = KosherPolicy.resolve(Product("3017620422003", "Nutella", "Ferrero"),
            listOf(OuRecord("1", "Nutella", "Ferrero", listOf("OU-D"), "Symbol required. Not kosher for passover.")))
        val copy = text(verdict)
        assertTrue(copy.contains("חלבי"))
        assertTrue(copy.contains("לא מתאים לפסח"))
        assertTrue(copy.contains("סימון כשרות"))
        assertFalse(copy.contains("OU"))
        assertFalse(copy.contains("התאמת"))
        assertTrue(verdict.sourceUrl.isNotBlank())
    }

    @Test fun optionalMetadataFailureDoesNotEraseCertificationConditions() {
        val verdict = Verdict(KosherStatus.KOSHER, "internal", displayText = "לא מתאים לפסח.")
        assertEquals("לא מתאים לפסח.", text(verdict, LookupIssue.TIMEOUT))
    }

    @Test fun waterRemainsConditional() {
        val copy = text(PlainWaterPolicy.verdict())
        assertTrue(copy.contains("בתנאי"))
        assertTrue(copy.contains("ללא טעמים ותוספים"))
        assertFalse(copy.contains("OU"))
    }

    @Test fun communityEvidenceDoesNotBecomeAnUnqualifiedCertification() {
        val verdict = KosherPolicy.resolve(Product("12345678", "Bread", "Brand",
            labels = listOf("en:kosher", "en:not-kosher-for-passover")), emptyList())
        assertTrue(text(verdict).contains("דיווח"))
        assertTrue(text(verdict).contains("לא מתאים לפסח"))
        assertFalse(text(verdict).contains("Open Food Facts"))
    }

    @Test fun missingEvidenceIsNotPresentedAsNetworkFailureOrNegativeVerdict() {
        val verdict = Verdict(KosherStatus.UNKNOWN, "OU diagnostic")
        assertFalse(text(verdict).contains("אינטרנט"))
        assertFalse(text(verdict).contains("לא כשר"))
        assertFalse(text(verdict).contains("OU"))
    }

    @Test fun lookupProblemsHaveDistinctSourceFreeExplanations() {
        val copies = LookupIssue.entries.map { text(Verdict(KosherStatus.UNKNOWN, "Open Food Facts"), it) }
        assertEquals(LookupIssue.entries.size, copies.distinct().size)
        assertTrue(copies.all { !it.contains("Open Food Facts") })
        assertTrue(text(Verdict(KosherStatus.UNKNOWN, ""), LookupIssue.OFFLINE).contains("אין חיבור"))
    }

    @Test fun barcodeConditionsSurviveRemovalOfAdministrativeMetadata() {
        val html = """<section class="main-product"><h2 class="primary-title">מוצר</h2><div class="productDetail"><table>
            <tr><th>ברקוד:</th><td>12345678</td></tr><tr><th>כשרות:</th><td>כשר</td></tr>
            <tr><th>גופי כשרות:</th><td>Agency</td></tr><tr><th>שם היבואן:</th><td>Importer</td></tr>
            <tr><th>כשרות פסח:</th><td>לא</td></tr><tr><th>הערות:</th><td>רק באריזה סגורה</td></tr>
            </table></div></section>"""
        val verdict = IkrRepository.parse("12345678", html, "https://www.ikr.org.il/")!!.verdict
        val copy = text(verdict)
        assertTrue(copy.contains("לא מתאים לפסח"))
        assertTrue(copy.contains("רק באריזה סגורה"))
        assertFalse(copy.contains("Agency"))
        assertFalse(copy.contains("Importer"))
        assertFalse(copy.contains("כושרות"))
    }
}
