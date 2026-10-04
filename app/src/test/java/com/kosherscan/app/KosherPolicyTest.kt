package com.kosherscan.app

import org.junit.Assert.*
import org.junit.Test

class KosherPolicyTest {
    private val p = Product("1234567890123", "Hazelnut spread with cocoa", "Example")
    private val r = OuRecord("OU123", p.name, p.brand, listOf("OU-D"), "Symbol required. Not Kosher for Passover.")
    @Test fun exactRecordMatches() { assertEquals(KosherStatus.KOSHER, KosherPolicy.resolve(p, listOf(r)).status) }
    @Test fun missingNeverMeansNotKosher() { assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p, emptyList()).status) }
    @Test fun relatedResultsNeverCertify() { assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p, listOf(r), true).status) }
    @Test fun brandMustMatchWholeField() { assertFalse(KosherPolicy.strongMatch(p, r.copy(brand = "Example Other"))) }
    @Test fun variantsAreNotDropped() { assertFalse(KosherPolicy.strongMatch(p, r.copy(name = p.name + " sugar free"))) }
    @Test fun originalIsSignificant() { assertFalse(KosherPolicy.strongMatch(p.copy(name = "Original " + p.name), r)) }
    @Test fun genericBrandNameIsNotProduct() { assertFalse(KosherPolicy.strongMatch(p.copy(name = "Example"), r.copy(name = "Example"))) }
    @Test fun revokedAndConditionalEntriesFailClosed() {
        listOf("Revoked", "Only with lot 123", "Symbol required. Until January 2025.", "").forEach { assertFalse(KosherPolicy.strongMatch(p, r.copy(conditions = it))) }
    }
    @Test fun symbolRequired() { assertFalse(KosherPolicy.strongMatch(p, r.copy(symbols = emptyList()))) }
    @Test fun passoverNegativeIsNotYearRoundNegative() { assertFalse(KosherPolicy.explicitlyNotKosher(p.copy(labels = listOf("en:not-kosher-for-passover")))) }
    @Test fun explicitNegativeOnly() { assertEquals(KosherStatus.NOT_KOSHER, KosherPolicy.resolve(p.copy(labels = listOf("en:not-kosher")), listOf(r)).status) }
    @Test fun communityPositiveDoesNotCertify() { assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p.copy(labels = listOf("en:kosher")), emptyList()).status) }
    @Test fun conflictingSymbolsRemainUnknown() { assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p, listOf(r, r.copy(symbols = listOf("OU")))).status) }
    @Test fun normalizationPreservesIdentity() { assertTrue(KosherPolicy.strongMatch(p.copy(name = "HAZELNUT   SPREAD WITH COCOA"), r)) }
    @Test fun hebrewDisplayCanMatchEnglishName() { assertTrue(KosherPolicy.strongMatch(p.copy(name = "ממרח", englishName = p.name), r)) }
}
