package com.kosherscan.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OuRepositoryRegressionTest {
    private fun fixture(name: String) = javaClass.getResource("/ou-catalogue/$name.json")!!.readText().removePrefix("\uFEFF")
    @Test fun capturedOffAndOuRecordsResolveThroughTheProductionParser() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            s.enqueue(MockResponse().setBody(fixture("corn-flakes-off")))
            s.enqueue(MockResponse().setBody(fixture("corn-flakes-ou")))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(), enableOuFallback = false)
                .lookup("5050083393693")
            assertEquals(KosherStatus.KOSHER, result.verdict.status)
            assertEquals("OU", result.verdict.sourceLabel)
            assertTrue(result.verdict.displayText.contains("לא מתאים לפסח"))
        }
    }
    private val product = """{"status":1,"product":{"product_name":"Specific spread","brands":"Brand"}}"""
    private val row = """{"agencyUniqueId":"1","productName":"Specific spread","brandName":"Brand","symbol":["OU"],"conditions":"Symbol required. Not Kosher for Passover."}"""
    @Test fun exactRecordBeyondTheOldHundredRowLimitCanBeFound() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(product))
            repeat(2) { s.enqueue(MockResponse().setBody("""{"results":[],"total":250}""")) }
            s.enqueue(MockResponse().setBody("""{"results":[$row],"total":250}"""))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(), enableOuFallback = false).lookup("12345678")
            assertEquals(KosherStatus.KOSHER, result.verdict.status)
            s.takeRequest(); s.takeRequest(); s.takeRequest()
            val third = s.takeRequest().requestUrl!!
            assertEquals("3", third.queryParameter("page"))
            assertEquals("100", third.queryParameter("limit"))
        }
    }
    @Test fun laterPageRestrictionCannotBeHiddenByAnEarlierPositive() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(product))
            s.enqueue(MockResponse().setBody("""{"results":[$row],"total":250}"""))
            s.enqueue(MockResponse().setBody("""{"results":[],"total":250}"""))
            s.enqueue(MockResponse().setBody("""{"results":[${row.replace("Symbol required.", "Only lot 42.")}],"total":250}"""))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(), enableOuFallback = false).lookup("12345678")
            assertEquals(KosherStatus.UNKNOWN, result.verdict.status)
        }
    }
}
