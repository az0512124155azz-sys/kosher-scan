package com.kosherscan.app

import org.junit.Assert.*
import org.junit.Test

class DecisionEngineTest {
    private val p = Product("7290100687109", "Chocolate cookies", "Example")
    private fun result(status: KosherStatus, source: String, detail: String = "") =
        LookupResult(p, Verdict(status, "evidence", sourceLabel = source, displayText = detail))
    @Test fun directAuthorityOutranksCommunityInBothDirections() {
        for (status in listOf(KosherStatus.KOSHER, KosherStatus.NOT_KOSHER)) {
            val other = if (status == KosherStatus.KOSHER) KosherStatus.NOT_KOSHER else KosherStatus.KOSHER
            assertEquals(status, DecisionEngine.resolve(p, listOf(result(other, "Open Food Facts"), result(status, "OK"))).verdict.status)
        }
    }
    @Test fun positiveSurvivesUnrelatedSourceFailure() {
        val known = result(KosherStatus.KOSHER, "OU")
        for (issue in LookupIssue.entries) {
            assertEquals(known, DecisionEngine.resolve(p, listOf(known, result(KosherStatus.UNKNOWN, "OK").copy(issue = issue))))
        }
    }
    @Test fun equallyStrongContradictionsRemainUnknownInEitherOrder() {
        val results = listOf(result(KosherStatus.KOSHER, "OU"), result(KosherStatus.NOT_KOSHER, "KLBD"))
        for (order in listOf(results, results.reversed())) assertEquals(KosherStatus.UNKNOWN, DecisionEngine.resolve(p, order).verdict.status)
    }
    @Test fun differentBarcodeCannotCertifyTheScannedProduct() {
        val wrong = result(KosherStatus.KOSHER, "כושרות").copy(product = p.copy(barcode = "3017620422003"))
        assertEquals(KosherStatus.UNKNOWN, DecisionEngine.resolve(p, listOf(wrong)).verdict.status)
    }
    @Test fun dairyDisagreementDoesNotMeanTheProductIsNotCertified() {
        val resolved = DecisionEngine.resolve(p, listOf(result(KosherStatus.KOSHER, "OU", "חלבי."), result(KosherStatus.KOSHER, "OK", "פרווה.")))
        assertEquals(KosherStatus.KOSHER, resolved.verdict.status)
        assertEquals("", resolved.verdict.displayText)
    }
    @Test fun noRecordsNeverMeansNotKosher() {
        assertEquals(KosherStatus.UNKNOWN, DecisionEngine.resolve(p, emptyList()).verdict.status)
    }
}
