package com.kosherscan.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class OuSearchTest {
    private val p = """{"status":1,"product":{"product_name":"Test Spread","brands":"Parent, Actual"}}"""
    private val row = """{"agencyUniqueId":"OUD123","productName":"Test Spread","brandName":"Actual","symbol":["OU-D"],"conditions":"Symbol required. Not Kosher for Passover."}"""
    @Test fun laterBrandAndBrandOnlySearchCanFindExactRecord() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(p))
            s.enqueue(MockResponse().setBody("""{"results":[],"total":0}""")); s.enqueue(MockResponse().setBody("""{"results":[$row],"total":1}"""))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString()).lookup("12345678")
            assertEquals(KosherStatus.UNKNOWN, result.verdict.status)
            s.takeRequest(); assertEquals("Parent Test Spread", s.takeRequest().requestUrl!!.queryParameter("query"))
            assertEquals("Actual Test Spread", s.takeRequest().requestUrl!!.queryParameter("query"))
        }
    }
    @Test fun exactMatchOnSecondPageIsNotMissed() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(p))
            s.enqueue(MockResponse().setBody("""{"results":[],"total":51}""")); s.enqueue(MockResponse().setBody("""{"results":[$row],"total":51}"""))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString()).lookup("12345678")
            assertEquals(KosherStatus.UNKNOWN, result.verdict.status)
            s.takeRequest(); s.takeRequest(); assertEquals("2", s.takeRequest().requestUrl!!.queryParameter("page"))
        }
    }
    @Test fun queryPlanRetainsVariantsAndBothLanguages() {
        val queries = ProductRepository.ouQueries(Product("12345678", "ממרח ללא סוכר", "Parent, Actual", "Test Spread sugar free 400g"))
        assertTrue(queries.contains("Actual Test Spread sugar free"))
        assertTrue(queries.contains("Actual ממרח ללא סוכר")); assertTrue(queries.contains("Actual"))
    }
    @Test fun truncatedResultsCannotCertifyAndRequestsAreBounded() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(p))
            repeat(6) { s.enqueue(MockResponse().setBody("""{"results":[$row],"total":101}""")) }
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString()).lookup("12345678")
            assertEquals(KosherStatus.UNKNOWN, result.verdict.status); assertEquals(7, s.requestCount)
        }
    }
}
