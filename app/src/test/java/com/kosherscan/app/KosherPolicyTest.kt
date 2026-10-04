package com.kosherscan.app

import org.junit.Assert.*
import org.junit.Test

class KosherPolicyTest {
    @Test fun specificCertificationLabelsNeedNotCarryGenericParent() {
        listOf("en:orthodox-union-kosher", "en:kosher-parve", "en:star-k-kosher", "en:organized-kashrut-kosher", "en:mk-kosher").forEach {
            assertTrue(it, KosherPolicy.explicitlyKosher(Product("12345678", "Test", "Test", labels = listOf(it))))
        }
    }
    @Test fun rawLabelsAndLegacyCategoriesAreReadAsWholeLabels() {
        assertTrue(KosherPolicy.explicitlyKosher(Product("12345678", "Test", "Test", labelsText = "Organic, OU Kosher")))
        assertTrue(KosherPolicy.explicitlyKosher(Product("12345678", "Test", "Test", categories = listOf("en:kosher-parve"))))
    }
    @Test fun rawNegativeHasPriorityOverPositive() {
        val product = Product("12345678", "Test", "Test", labels = listOf("en:kosher"), labelsText = "Organic; not kosher")
        assertEquals(KosherStatus.NOT_KOSHER, KosherPolicy.resolve(product, emptyList()).status)
    }
    @Test fun arbitraryKosherSubstringAndSocialLabelsDoNotCertify() {
        listOf("not kosher for passover", "possibly kosher", "kosher style", "en:magen-tzedek", "not certified kosher", "ללא הכשר").forEach {
            val product = Product("12345678", "Test", "Test", labelsText = it)
            assertEquals(it, KosherStatus.UNKNOWN, KosherPolicy.resolve(product, emptyList()).status)
        }
    }
    @Test fun passoverNegativeRawLabelDoesNotOverrideYearRoundCertification() {
        val product = Product("12345678", "Test", "Test", labelsText = "OU Kosher, not kosher for passover")
        assertEquals(KosherStatus.KOSHER, KosherPolicy.resolve(product, emptyList()).status)
    }
    private val p = Product("1234567890123", "Hazelnut spread with cocoa", "Example")
    private val r = OuRecord("OU123", p.name, p.brand, listOf("OU-D"), "Symbol required. Not Kosher for Passover.")
    @Test fun exactRecordMatches() { assertEquals(KosherStatus.KOSHER, KosherPolicy.resolve(p, listOf(r)).status) }
    @Test fun missingNeverMeansNotKosher() { assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p, emptyList()).status) }
    @Test fun relatedResultsNeverCertify() { assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p, listOf(r), true).status) }
    @Test fun brandMustMatchWholeField() { assertFalse(KosherPolicy.strongMatch(p, r.copy(brand = "Example Other"))) }
    @Test fun variantsAreNotDropped() { assertFalse(KosherPolicy.strongMatch(p, r.copy(name = p.name + " sugar free"))) }
    @Test fun originalIsSignificant() { assertFalse(KosherPolicy.strongMatch(p.copy(name = "Original " + p.name), r)) }
    @Test fun productNamedAfterBrandMatchesOnlyExactNamedRow() {
        val nutella = p.copy(name = "Nutella", brand = "Nutella, Ferrero")
        assertTrue(KosherPolicy.strongMatch(nutella, r.copy(name = "Nutella", brand = "Nutella")))
        assertFalse(KosherPolicy.strongMatch(nutella, r.copy(name = "Nutella Biscuits", brand = "Nutella")))
    }
    @Test fun revokedAndConditionalEntriesFailClosed() {
        listOf("Revoked", "Only with lot 123", "Symbol required. Until January 2025.", "").forEach { assertFalse(KosherPolicy.strongMatch(p, r.copy(conditions = it))) }
    }
    @Test fun symbolRequired() { assertFalse(KosherPolicy.strongMatch(p, r.copy(symbols = emptyList()))) }
    @Test fun passoverNegativeIsNotYearRoundNegative() { assertFalse(KosherPolicy.explicitlyNotKosher(p.copy(labels = listOf("en:not-kosher-for-passover")))) }
    @Test fun explicitNegativeOnly() { assertEquals(KosherStatus.NOT_KOSHER, KosherPolicy.resolve(p.copy(labels = listOf("en:not-kosher")), listOf(r)).status) }
    @Test fun explicitCommunityPositiveDisclosesSource() {
        val verdict = KosherPolicy.resolve(p.copy(labels = listOf("en:kosher")), emptyList())
        assertEquals(KosherStatus.KOSHER, verdict.status)
        assertTrue(verdict.reason.contains("דיווח קהילתי")); assertTrue(verdict.reason.contains("אינו אישור OU"))
    }
    @Test fun vegetarianOrPassoverNegativeCannotBecomePositive() {
        assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p.copy(labels = listOf("en:vegetarian", "en:not-kosher-for-passover")), emptyList()).status)
    }
    @Test fun quantityAndRepeatedBrandPrefixDoNotHideExactProduct() {
        assertTrue(KosherPolicy.strongMatch(p.copy(name = "Example Hazelnut spread with cocoa 400g"), r))
        assertFalse(KosherPolicy.strongMatch(p.copy(name = "Example Hazelnut spread with cocoa sugar free 400g"), r))
    }
    @Test fun conflictingSymbolsRemainUnknown() { assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p, listOf(r, r.copy(symbols = listOf("OU")))).status) }
    @Test fun normalizationPreservesIdentity() { assertTrue(KosherPolicy.strongMatch(p.copy(name = "HAZELNUT   SPREAD WITH COCOA"), r)) }
    @Test fun hebrewDisplayCanMatchEnglishName() { assertTrue(KosherPolicy.strongMatch(p.copy(name = "ממרח", englishName = p.name), r)) }
}
