package com.kosherscan.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class InternationalApprovalTest {
    private fun fixture() = javaClass.getResource("/authorities/thai-local.html")!!.readText()

    @Test fun everyRecordedRowPassesThroughMatchingAndDecision() {
        val rows = AuthoritySources.parseTks(fixture())
        assertEquals(123, rows.size)
        println("TKS recorded coverage: rows=${rows.size}, eligible=${rows.count { it.eligible }}")
        assertTrue("Eligible rows=${rows.count { it.eligible }}", rows.any { it.eligible })
        for (r in rows) {
            val p = Product("12345678", r.name, r.brand)
            val result = AuthoritySources.resolve(p, listOf(r), "TKS")
            assertEquals(r.name, if (r.eligible) KosherStatus.KOSHER else KosherStatus.UNKNOWN, result.verdict.status)
            if (r.eligible) {
                assertEquals(DecisionEngine.Strength.AUTHORITY_NAME, DecisionEngine.evidence(result).strength)
                assertEquals(KosherStatus.UNKNOWN, AuthoritySources.resolve(p.copy(name = p.name + " chocolate"), listOf(r), "TKS").verdict.status)
                assertEquals(KosherStatus.UNKNOWN, AuthoritySources.resolve(p.copy(brand = "Another brand"), listOf(r), "TKS").verdict.status)
            }
        }
        assertTrue(rows.any { it.eligible && it.name.contains("syrup", true) })
    }

    @Test fun partialOrUnfilteredTablesCannotApprove() {
        for (html in listOf("<html>login</html>", fixture().replace("123 Item(s)", "124 Item(s)"),
            fixture().replace("Local List/ Kosher Symbol Not Required</li>", "All Products</li>"))) {
            try { AuthoritySources.parseTks(html); fail("Invalid source accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unsupportedCountryDoesNotUseLocalCatalogAndThaiCatalogNeedsNoBot() = runBlocking {
        val rows = AuthoritySources.parseTks(fixture())
        val r = rows.first { it.eligible && it.name.contains("syrup", true) }
        val p = Product("12345678", r.name, r.brand)
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.requestUrl!!.pathSegments.first() == "category.html") MockResponse().setBody(fixture())
                    else MockResponse().setResponseCode(503)
            }
            server.start()
            fun source(market: String) = AuthoritySources(market = { market }, okBase = server.url("/").toString(),
                starBase = server.url("/").toString(), tksBase = server.url("/").toString())
            assertEquals(KosherStatus.KOSHER, source("TH").lookup(p).verdict.status)
            assertEquals(KosherStatus.UNKNOWN, source("US").lookup(p).verdict.status)
            assertEquals(KosherStatus.UNKNOWN, source("").lookup(p).verdict.status)
        }
    }

    @Test fun networkScopeNeverUsesLanguageOrHomeSimFallback() {
        assertEquals("GB", MarketScope.normalize("gb"))
        assertEquals("TH", MarketScope.normalize("th"))
        assertEquals("US", MarketScope.normalize("us"))
        assertEquals("", MarketScope.normalize(null))
        assertEquals("", MarketScope.normalize("en"))
        assertEquals("", MarketScope.normalize("UK"))
    }
}
