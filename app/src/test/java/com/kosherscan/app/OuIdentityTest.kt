package com.kosherscan.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OuIdentityTest {
    private val conditions = "Symbol required. Not Kosher for Passover."
    private fun row(name: String, brand: String = "Parent Company") =
        OuRecord("official-id", name, brand, listOf("OU-D"), conditions)

    @Test fun retailLineMustBeExplicitAndTheEntireBrandedNameMustMatch() {
        for (name in listOf("Chocolate Hazelnut Spread", "Tomato Ketchup", "Original Sandwich Cookies",
            "Corn Flakes", "Earl Grey Tea", "Olive Oil", "Salted Crackers", "Strawberry Yogurt")) {
            val p = Product("12345678", "Retail $name 400g", "Retail")
            assertTrue(name, KosherPolicy.strongMatch(p, row("Retail $name")))
            assertFalse(name, KosherPolicy.strongMatch(p, row(name)))
            assertFalse(name, KosherPolicy.strongMatch(p, row("Other $name")))
            assertFalse(name, KosherPolicy.strongMatch(p, row("Retail $name sugar free")))
            assertFalse(name, KosherPolicy.strongMatch(p.copy(name = p.name + " vanilla"), row("Retail $name")))
            assertFalse(name, KosherPolicy.strongMatch(p, row("Other With Retail $name")))
        }
    }

    @Test fun ingredientMentionsAndBrandOnlyNamesDoNotIdentifyTheProduct() {
        val p = Product("12345678", "Original", "Retail")
        assertFalse(KosherPolicy.strongMatch(p, row("Retail")))
        assertFalse(KosherPolicy.strongMatch(p, row("Ice Cream with Retail Original")))
        assertFalse(KosherPolicy.strongMatch(p, row("Retail Original Vanilla")))
        assertFalse(KosherPolicy.strongMatch(p, row("Retailish Original")))
    }

    @Test fun revokedIdentityEquivalentRowCannotBeFilteredOutOfConflictDetection() {
        val p = Product("12345678", "Retail Original", "Retail")
        val positive = row(p.name)
        val revoked = positive.copy(id = "other", conditions = "Revoked.")
        assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p, listOf(positive, revoked)).status)
    }

    @Test fun winterWheatAnnotationRequiresAnExactStructuredAndDisplayedMatch() {
        val p = Product("12345678", "Specific Spread", "Retail")
        val r = row(p.name, "Retail")
        val annotation = "Yoshon Always (Made with Winter Wheat)"
        assertTrue(KosherPolicy.strongMatch(p, r.copy(yoshon = annotation,
            officialStatus = "$conditions $annotation")))
        assertFalse(KosherPolicy.strongMatch(p, r.copy(yoshon = annotation,
            officialStatus = "$conditions Revoked $annotation")))
        assertFalse(KosherPolicy.strongMatch(p, r.copy(yoshon = "Winter Wheat - Not certified",
            officialStatus = "$conditions Winter Wheat - Not certified")))
    }

    private fun fixture(name: String) = javaClass.getResource("/ou-catalogue/$name.json")!!
        .readText().removePrefix("\uFEFF")

    @Test fun capturedRetailBarcodeAndOfficialParentBrandResolveWithoutOverrides() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            s.enqueue(MockResponse().setBody(fixture("oreo-original-off")))
            s.enqueue(MockResponse().setBody("""{"results":[],"total":0}"""))
            s.enqueue(MockResponse().setBody(fixture("oreo-original-ou")))
            val events = mutableListOf<String>()
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(),
                onOuEvent = { events += it }).lookup("7622300336738")
            assertEquals(KosherStatus.KOSHER, result.verdict.status)
            assertEquals("OU", result.verdict.sourceLabel)
            assertEquals("ציוד חלבי.\nלא מתאים לפסח.", result.verdict.displayText)
            assertTrue(events.any { it.contains("identities=1") && it.contains("eligible=1") })
        }
    }

    @Test fun nameOnlyQueryIsIncludedBeforeBroadBrandSearches() {
        val queries = ProductRepository.ouQueries(Product("12345678", "Retail Specific Spread", "Manufacturer"))
        assertTrue(queries.indexOf("Retail Specific Spread") < queries.indexOf("Manufacturer"))
        assertTrue(queries.indexOf("Retail Specific Spread") < 6)
    }

    @Test fun recordedRelatedResultsStillCannotCertify() {
        val json = JSONObject(fixture("oreo-original-ou"))
        val rows = json.getJSONArray("results")
        val records = (0 until rows.length()).map { OuRecords.parse(rows.getJSONObject(it)) }
        val p = Product("7622300336738", "Oreo Original", "Oreo")
        assertEquals(KosherStatus.KOSHER, KosherPolicy.resolve(p, records).status)
        assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p, records, related = true).status)
        assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(p.copy(name = "Oreo Original Vanilla"), records).status)
    }

    @Test fun additionalParentBrandCatalogueRetainsAllRecognizedRows() {
        val rows = JSONObject(fixture("Christie")).getJSONArray("results")
        assertEquals(100, rows.length())
        for (i in 0 until rows.length()) {
            val row = OuRecords.parse(rows.getJSONObject(i))
            assertTrue("${row.id}: ${row.officialStatus}", KosherPolicy.certificationRecognized(row))
            assertEquals(row.name, KosherStatus.KOSHER,
                KosherPolicy.resolve(Product("12345678", row.name, row.brand), listOf(row)).status)
        }
    }

    @Test fun failedFirstQueryDoesNotPreventASuccessfulSecondQuery() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            s.enqueue(MockResponse().setBody(fixture("oreo-original-off")))
            s.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            s.enqueue(MockResponse().setBody(fixture("oreo-original-ou")))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString())
                .lookup("7622300336738")
            assertEquals(KosherStatus.KOSHER, result.verdict.status)
            assertNull(result.issue)
        }
    }

    @Test fun slowFirstQueryCanRecoverWithinTheExistingOverallBudget() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            s.enqueue(MockResponse().setBody(fixture("oreo-original-off")))
            s.enqueue(MockResponse().setBody("{}").setBodyDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS))
            s.enqueue(MockResponse().setBody(fixture("oreo-original-ou")))
            val client = okhttp3.OkHttpClient.Builder().callTimeout(150, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            val result = ProductRepository(client = client, offBase = s.url("/").toString(), ouBase = s.url("/").toString())
                .lookup("7622300336738")
            assertEquals(KosherStatus.KOSHER, result.verdict.status)
            assertNull(result.issue)
        }
    }

    @Test fun aRestrictedEarlierIdentityIsNotHiddenByASuccessfulLaterQuery() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(fixture("oreo-original-off")))
            s.enqueue(MockResponse().setBody("""{"results":[{"agencyUniqueId":"restricted","brandName":"Christie","productName":"Oreo Original","symbol":["OU-D"],"conditions":"Only lot 42."}],"total":1}"""))
            repeat(5) { s.enqueue(MockResponse().setBody(fixture("oreo-original-ou"))) }
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString())
                .lookup("7622300336738")
            assertEquals(KosherStatus.UNKNOWN, result.verdict.status)
        }
    }
}
