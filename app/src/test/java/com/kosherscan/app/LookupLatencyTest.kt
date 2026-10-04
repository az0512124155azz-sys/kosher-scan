package com.kosherscan.app

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class LookupLatencyTest {
    private val code = "7290100687109"
    private val product = """{"status":1,"product":{"product_name":"Test spread","brands":"Example"}}"""
    private val row = """{"results":[{"agencyUniqueId":"OUD123","productName":"Test spread","brandName":"Example","symbol":["OU-D"],"conditions":"Symbol required. Not Kosher for Passover."}],"total":1}"""
    private fun authority(status: KosherStatus = KosherStatus.KOSHER) = LookupResult(
        Product(code, "Authority product", "Example"), Verdict(status, "Exact barcode", "https://www.ikr.org.il/", "כושרות"))
    private fun direct(result: LookupResult) = object : ProductLookup {
        override suspend fun lookup(code: String) = result
    }

    @Test fun exactAuthorityDoesNotWaitForSlowOptionalMetadata() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(product).setBodyDelay(2, TimeUnit.SECONDS))
            val result = withTimeout(1_000) {
                ProductRepository(offBase = s.url("/").toString(), barcodeLookup = direct(authority()),
                    metadataGraceMs = 100).lookup(code)
            }
            assertEquals(KosherStatus.KOSHER, result.verdict.status)
            assertEquals("Authority product", result.product!!.name)
            assertTrue(result.verdict.reason.contains("לא השיב בזמן"))
            assertEquals(1, s.requestCount)
        }
    }

    @Test fun ouStartsWhileAuthorityIsStillPending() = runBlocking {
        val release = CompletableDeferred<LookupResult>()
        val pending = object : ProductLookup { override suspend fun lookup(code: String) = release.await() }
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(product)); s.enqueue(MockResponse().setBody(row))
            val task = async { ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(),
                barcodeLookup = pending, enableOuFallback = false).lookup(code) }
            try {
                withContext(Dispatchers.IO) {
                    assertNotNull(s.takeRequest(2, TimeUnit.SECONDS))
                    val ou = s.takeRequest(2, TimeUnit.SECONDS)
                    assertNotNull("OU must start before the authority responds", ou)
                    assertTrue(ou!!.path!!.startsWith("/api/v1/product"))
                }
                assertFalse(task.isCompleted)
                release.complete(LookupResult(null, Verdict(KosherStatus.UNKNOWN, "No record")))
                assertEquals(KosherStatus.KOSHER, task.await().verdict.status)
            } finally { task.cancelAndJoin() }
        }
    }

    @Test fun conflictArrivingWithinGraceStillPreventsPositive() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody("""{"status":1,"product":{"product_name":"Test","labels_tags":["en:not-kosher"]}}""")
                .setBodyDelay(100, TimeUnit.MILLISECONDS))
            val result = ProductRepository(offBase = s.url("/").toString(), barcodeLookup = direct(authority()),
                metadataGraceMs = 1_000).lookup(code)
            assertEquals(KosherStatus.UNKNOWN, result.verdict.status)
            assertTrue(result.verdict.reason.contains("סותר"))
        }
    }

    @Test fun deadlinePreservesProductAndOffersRetryWithoutInventingCertification() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(product))
            s.enqueue(MockResponse().setBody(row).setBodyDelay(2, TimeUnit.SECONDS))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(),
                lookupTimeoutMs = 250).lookup(code)
            assertEquals("Test spread", result.product!!.name)
            assertEquals(KosherStatus.UNKNOWN, result.verdict.status)
            assertEquals(LookupIssue.TIMEOUT, result.issue)
        }
    }

    @Test fun deadlineKeepsExplicitCommunityVerdictWithItsSourceDisclosure() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(product.replace("\"brands\":\"Example\"", "\"brands\":\"Example\",\"labels_tags\":[\"en:kosher\"]")))
            s.enqueue(MockResponse().setBody(row).setBodyDelay(2, TimeUnit.SECONDS))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(),
                lookupTimeoutMs = 250).lookup(code)
            assertEquals(KosherStatus.KOSHER, result.verdict.status)
            assertTrue(result.verdict.reason.contains("דיווח קהילתי"))
            assertEquals(LookupIssue.TIMEOUT, result.issue)
        }
    }

    @Test fun extendedRetryRetainsLongerSearchInsteadOfDroppingSlowExactMatch() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(product))
            s.enqueue(MockResponse().setBody(row).setBodyDelay(500, TimeUnit.MILLISECONDS))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(),
                lookupTimeoutMs = 100, ouTimeoutMs = 100, enableOuFallback = false).extended().lookup(code)
            assertEquals(KosherStatus.KOSHER, result.verdict.status)
            assertEquals("OU", result.verdict.sourceLabel)
            assertNull(result.issue)
        }
    }

    @Test fun completedOuVerdictIsNotLostWhenAuthorityExceedsOverallDeadline() = runBlocking {
        val pending = object : ProductLookup {
            override suspend fun lookup(code: String): LookupResult { delay(2_000); return authority() }
        }
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(product)); s.enqueue(MockResponse().setBody(row))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(),
                barcodeLookup = pending, lookupTimeoutMs = 500, enableOuFallback = false).lookup(code)
            assertEquals(KosherStatus.KOSHER, result.verdict.status)
            assertEquals("OU", result.verdict.sourceLabel)
            assertEquals(LookupIssue.TIMEOUT, result.issue)
        }
    }

    @Test fun ouDeadlineStillOffersExtendedSearchWhenAuthorityIsUnavailable() = runBlocking {
        MockWebServer().use { s ->
            s.start(); s.enqueue(MockResponse().setBody(product))
            s.enqueue(MockResponse().setBody(row).setBodyDelay(2, TimeUnit.SECONDS))
            val result = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(),
                barcodeLookup = direct(LookupResult(null, Verdict(KosherStatus.UNKNOWN, "Unavailable"), LookupIssue.SERVICE_UNAVAILABLE)),
                ouTimeoutMs = 100).lookup(code)
            assertEquals(KosherStatus.UNKNOWN, result.verdict.status)
            assertEquals(LookupIssue.TIMEOUT, result.issue)
        }
    }
}
