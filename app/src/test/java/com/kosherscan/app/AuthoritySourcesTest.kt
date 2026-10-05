package com.kosherscan.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class AuthoritySourcesTest {
    private fun fixture(name: String) = javaClass.getResource("/authorities/$name")!!.readText().removePrefix("\uFEFF")
    private val now = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse("2026-10-05")!!.time
    @Test fun recordedOkRowsRequireAProductKidStatusAndSymbol() {
        val rows = AuthoritySources.parseOk(fixture("ok.html"))
        assertTrue(rows.size > 20)
        val r = rows.first()
        assertTrue(r.eligible)
        val p = Product("12345678", r.name, "Guylian")
        assertEquals(KosherStatus.KOSHER, AuthoritySources.resolve(p, rows, "OK").verdict.status)
        assertEquals(KosherStatus.UNKNOWN, AuthoritySources.resolve(p.copy(name = p.name + " vanilla"), rows, "OK").verdict.status)
        assertFalse(AuthoritySources.identity(p.copy(brand = "Guylian Other"), r))
        val invalid = rows.map { it.copy(eligible = false) }
        assertEquals(KosherStatus.UNKNOWN, AuthoritySources.resolve(p, invalid, "OK").verdict.status)
    }
    @Test fun companyDirectoryAndMalformedHtmlNeverCertify() {
        assertTrue(AuthoritySources.parseOk("<input class='filters-search__search-input'><div>Guylian certified company</div>").isEmpty())
        try { AuthoritySources.parseOk("<html>login</html>"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun governmentUsesActualSchemaExpiryAndProducerNotCertificateNumberAsBarcode() {
        val rows = JSONObject(fixture("government.json")).getJSONObject("result").getJSONArray("records")
        val first = rows.getJSONObject(0)
        val records = AuthoritySources.parseGovernment(first, now)
        assertEquals(4, records.size)
        val p = Product("12345678", first.getString("name5"), "ADAMJEE LUKMANJEE")
        assertEquals(KosherStatus.KOSHER, AuthoritySources.resolve(p, records, "Rabbanut").verdict.status)
        assertEquals(KosherStatus.UNKNOWN, AuthoritySources.resolve(p.copy(brand = "Other Producer"), records, "Rabbanut").verdict.status)
        assertTrue(AuthoritySources.parseGovernment(rows.getJSONObject(2), now).all { !it.eligible }) // September expiry.
        val expired = JSONObject(first.toString()).put("name8", "01/01/2020")
        assertTrue(AuthoritySources.parseGovernment(expired, now).all { !it.eligible })
        val restriction = JSONObject(first.toString()).put("name11", "רק אצווה 42")
        assertTrue(AuthoritySources.parseGovernment(restriction, now).all { !it.eligible })
        val malformed = JSONObject(first.toString()).put("name8", "11/12/2226")
        assertTrue(AuthoritySources.parseGovernment(malformed, now).all { !it.eligible })
    }
    @Test fun klbdDistinguishesExplicitNegativeFromNotApprovedAndMissingInformation() {
        val rows = AuthoritySources.parseKlbd(JSONObject(fixture("klbd.json")))
        assertEquals(25, rows.size)
        assertTrue(rows.any { it.status == KosherStatus.KOSHER && it.eligible })
        assertTrue(rows.any { it.status == KosherStatus.NOT_KOSHER })
        assertEquals(KosherStatus.UNKNOWN, rows.first { it.name == "Crispy Minis Caramelised Biscuit" }.status)
        assertEquals(KosherStatus.UNKNOWN, rows.first { it.name == "Crunchy Weeties" }.status)
        for (r in rows.filter { it.status == KosherStatus.KOSHER && it.eligible }) {
            // A generic flavour alone cannot identify the cereal, even with a brand.
            if (!AuthoritySources.identity(Product("12345678", r.name, r.brand), r)) continue
            assertEquals(r.brand + ":" + r.name, KosherStatus.KOSHER, AuthoritySources.resolve(Product("12345678", r.name, r.brand), rows, "KLBD").verdict.status)
        }
        assertEquals(KosherStatus.UNKNOWN, AuthoritySources.resolve(Product("12345678", "Weetabix Chocolate", "Weetabix"), rows, "KLBD").verdict.status)
    }
    @Test fun conflictingMatchedAuthoritiesStayUnknownAndErrorsDoNotEraseKnownEvidence() {
        val p = Product("12345678", "Specific Spread", "Brand")
        fun result(s: KosherStatus, issue: LookupIssue? = null) = LookupResult(p, Verdict(s, "", sourceLabel = "authority"), issue)
        assertEquals(KosherStatus.UNKNOWN, AuthoritySources.merge(p, listOf(result(KosherStatus.KOSHER), result(KosherStatus.NOT_KOSHER))).verdict.status)
        val positive = AuthoritySources.merge(p, listOf(result(KosherStatus.UNKNOWN, LookupIssue.TIMEOUT), result(KosherStatus.KOSHER)))
        assertEquals(KosherStatus.KOSHER, positive.verdict.status); assertNull(positive.issue)
        assertEquals(LookupIssue.TIMEOUT, AuthoritySources.merge(p, listOf(result(KosherStatus.UNKNOWN, LookupIssue.TIMEOUT))).issue)
    }
    @Test fun pdfTableDatesBrandsAndRestrictionsAreRequired() {
        val page = """Account: ABCDEFGH
The product(s) listed below are certified kosher and under our supervision.
PRODUCT NAME STATUS CONDITIONS OF CERTIFICATION UKD #
Brand: Brand
Specific Spread Pareve Star-K symbol required. SK123456789
Restricted Spread Pareve Star-K symbol required. Only lot 42. SK987654321
VALID THROUGH Page 1 of 1
Rabbi Name January 31, 2027"""
        val rows = AuthoritySources.parseStar(listOf(page), "ABCDEFGH", now)
        assertEquals(2, rows.size); assertTrue(rows.first().eligible); assertFalse(rows.last().eligible)
        assertTrue(AuthoritySources.parseStar(listOf(page.replace("2027", "2020")), "ABCDEFGH", now).none { it.eligible })
        try { AuthoritySources.parseStar(listOf(page), "OTHER123", now); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun actualRecordedPdfContainsRecognizedProductRows() {
        val text = fixture("star-text.txt")
        // Recorded extraction, partition at each independent account header.
        val pages = Regex("(?=Graeter's Ice Cream\\s+July 30, 2026)").split(text).filter { it.contains("VALID THROUGH") }
        val rows = AuthoritySources.parseStar(pages, "ZGCOQH37", now)
        assertTrue("Recorded PDF rows=${rows.size}", rows.size > 50)
        assertTrue(rows.any { it.name == "Lemon Sorbet" && it.eligible })
        assertTrue(rows.any { it.name == "Black Raspberry Chip Pie" && !it.eligible })
    }
    @Test fun purchaseMarketControlsWhichScopedSourceIsCalled() = runBlocking {
        MockWebServer().use { s ->
            s.start(); val paths = mutableListOf<String>()
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    synchronized(paths) { paths += r.path!! }
                    return when {
                        r.path!!.startsWith("/product-search/") -> MockResponse().setBody(fixture("ok.html"))
                        r.path!!.startsWith("/listings/") -> MockResponse().setBody("<div id='pages' class='listings'></div>")
                        r.path!!.startsWith("/api/messages") -> MockResponse().setBody("{\"outofdate\":false}")
                        r.path!!.startsWith("/api/query") -> MockResponse().setBody(fixture("klbd.json"))
                        r.path!!.contains("package_show") -> MockResponse().setBody(fixture("government-package.json"))
                        r.path!!.contains("datastore_search") -> MockResponse().setBody("{\"success\":true,\"result\":{\"total\":0,\"records\":[]}}")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val base = s.url("/").toString()
            var country = "IL"
            val repo = AuthoritySources(market = { country }, govBase = base, okBase = base, starBase = base, klbdBase = base, now = { now })
            val p = Product("12345678", "Crunchy Bran", "Weetabix")
            repo.lookup(p)
            assertTrue(paths.any { it.contains("datastore_search") }); assertFalse(paths.any { it.startsWith("/api/query") })
            paths.clear(); country = "GB"
            assertEquals(KosherStatus.KOSHER, repo.lookup(p).verdict.status)
            assertTrue(paths.any { it.startsWith("/api/query") }); assertFalse(paths.any { it.contains("package_show") })
        }
    }
    @Test fun incompleteCataloguesAndUnparsedPdfRowsCannotCertify() {
        val klbd = JSONObject(fixture("klbd.json")).put("count", 26)
        try { AuthoritySources.parseKlbd(klbd); fail() } catch (_: IllegalArgumentException) { }
        val page = """Account: ABCDEFGH
The product(s) listed below are certified kosher and under our supervision.
PRODUCT NAME STATUS CONDITIONS OF CERTIFICATION UKD #
Brand: Brand
Specific Spread Pareve Star-K symbol required. SK123456789
Unrecognized row with a hidden restriction SK987654321
VALID THROUGH January 31, 2027"""
        try { AuthoritySources.parseStar(listOf(page), "ABCDEFGH", now); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun aFailedWebsiteDoesNotPreventAnotherAuthorityFromAnswering() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest) = when {
                    r.path!!.startsWith("/api/messages") -> MockResponse().setBody("{\"outofdate\":false}")
                    r.path!!.startsWith("/api/query") -> MockResponse().setBody(fixture("klbd.json"))
                    else -> MockResponse().setResponseCode(503)
                }
            }
            val base = s.url("/").toString()
            val result = AuthoritySources(market = { "GB" }, okBase = base, starBase = base, klbdBase = base)
                .lookup(Product("12345678", "Crunchy Bran", "Weetabix"))
            assertEquals(KosherStatus.KOSHER, result.verdict.status); assertNull(result.issue)
        }
    }
    @Test fun outOfDateKlbdIsRejectedBeforeProductSearch() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest) = when {
                    r.path!!.startsWith("/api/messages") -> MockResponse().setBody("{\"outofdate\":true}")
                    r.path!!.startsWith("/product-search/") -> MockResponse().setBody("<input class='filters-search__search-input'>")
                    else -> MockResponse().setBody("<div id='pages' class='listings'></div>")
                }
            }
            val base = s.url("/").toString()
            val result = AuthoritySources(market = { "GB" }, okBase = base, starBase = base, klbdBase = base)
                .lookup(Product("12345678", "Crunchy Bran", "Weetabix"))
            assertEquals(KosherStatus.UNKNOWN, result.verdict.status); assertEquals(LookupIssue.INVALID_RESPONSE, result.issue)
            repeat(s.requestCount) { assertFalse(s.takeRequest().path!!.startsWith("/api/query")) }
        }
    }
}
