package com.kosherscan.app

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class IkrRepositoryTest {
    @Test fun authorityPositiveSurvivesOffServiceFailure() = runBlocking {
        val direct = object : ProductLookup { override suspend fun lookup(code: String) = IkrRepository.parse(code, html(), "https://example.org/")!! }
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(503))
            val result = ProductRepository(offBase = server.url("/").toString(), barcodeLookup = direct).lookup(code)
            assertEquals(KosherStatus.KOSHER, result.verdict.status); assertNull(result.issue)
        }
    }
    @Test fun offPositiveSurvivesAuthorityServiceFailure() = runBlocking {
        val direct = object : ProductLookup { override suspend fun lookup(code: String) = LookupResult(null, Verdict(KosherStatus.UNKNOWN,"unavailable"),LookupIssue.SERVICE_UNAVAILABLE) }
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"status":1,"product":{"product_name":"Test","labels_tags":["en:kosher"]}}"""))
            val result = ProductRepository(offBase = server.url("/").toString(), barcodeLookup = direct).lookup(code)
            assertEquals(KosherStatus.KOSHER, result.verdict.status); assertNull(result.issue)
        }
    }
    @Test fun offlineMakesNoAuthorityRequest() = runBlocking {
        val direct = object : ProductLookup { override suspend fun lookup(code: String): LookupResult { fail("Offline must not request authority"); error("unreachable") } }
        assertEquals(LookupIssue.OFFLINE, ProductRepository(hasNetwork = { false }, barcodeLookup = direct).lookup(code).issue)
    }
    private val code = "7290100687109"
    private fun html(barcode: String = code, status: String = "כשר למהדרין", agency: String = "רבנות שדרות") = """
        <header>כשר למהדרין $code</header><section class="main-product"><h2 class="primary-title">חטיף נוגט - מומלץ כושרות</h2>
        <div class="productDetail"><table><tr><th>ברקוד:</th><td>$barcode</td></tr><tr><th>כשרות:</th><td>$status</td></tr>
        <tr><th>גופי כשרות:</th><td>$agency</td></tr><tr><th>כשרות פסח:</th><td>לא כשל"פ</td></tr><tr><th>שם מפעל:</th><td>אסם</td></tr></table></div></section>
    """
    @Test fun exactRecordKeepsPassoverNegativeSeparateAndSourceVisible() {
        val result = IkrRepository.parse(code, html(), "https://www.ikr.org.il/index2.php?productId=123")!!
        assertEquals(KosherStatus.KOSHER, result.verdict.status)
        assertTrue(result.verdict.reason.contains("לא כשל")); assertTrue(result.verdict.reason.contains("רבנות שדרות"))
        assertTrue(result.verdict.sourceUrl.startsWith("https://www.ikr.org.il/"))
    }
    @Test fun barcodeMismatchCannotBorrowCertificationFromNearbyContent() {
        assertNull(IkrRepository.parse(code, html("7290115209198"), "https://example.org/"))
    }
    @Test fun explicitNegativeRowOnlyCanProduceRed() {
        assertEquals(KosherStatus.NOT_KOSHER, IkrRepository.parse(code, html(status = "לא כשר"), "https://example.org/")!!.verdict.status)
        assertEquals(KosherStatus.UNKNOWN, IkrRepository.parse(code, html(status = "לא ידוע"), "https://example.org/")!!.verdict.status)
    }
    @Test fun missingAgencyAndMissingStructuredSectionFailClosed() {
        assertNull(IkrRepository.parse(code, html(agency = ""), "https://example.org/"))
        assertNull(IkrRepository.parse(code, "<p>$code כשר רבנות שדרות</p>", "https://example.org/"))
    }
    @Test fun duplicateRecordsAndDuplicateFieldsFailClosed() {
        assertNull(IkrRepository.parse(code, html() + html(), "https://example.org/"))
        assertNull(IkrRepository.parse(code, html().replace("</table>", "<tr><th>כשרות:</th><td>לא כשר</td></tr></table>"), "https://example.org/"))
    }
    @Test fun leadingZeroGtinRepresentationsAreEquivalentButNotPrefixes() {
        assertTrue(IkrRepository.sameBarcode("012345678905", "0012345678905"))
        assertFalse(IkrRepository.sameBarcode("12345678", "1234567890123"))
        assertFalse(IkrRepository.sameBarcode("00000000", "0000000000000"))
    }
    @Test fun resolverZeroAndMinusOneNeverMeanNotKosher() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"productId":0}""")); server.enqueue(MockResponse().setBody("""{"productId":-1}"""))
            val repo = IkrRepository(base = server.url("/").toString())
            repeat(2) { val result = repo.lookup(code); assertEquals(KosherStatus.UNKNOWN, result.verdict.status); assertNull(result.issue) }
        }
    }
    @Test fun resolverAndDetailAreBothRequired() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"productId":"123"}""")); server.enqueue(MockResponse().setBody(html()))
            assertEquals(KosherStatus.KOSHER, IkrRepository(base = server.url("/").toString()).lookup(code).verdict.status)
            val request = server.takeRequest(); assertEquals("POST", request.method)
            assertTrue(request.body.readUtf8().contains("barcode=$code"))
            assertEquals("123", server.takeRequest().requestUrl!!.queryParameter("productId"))
        }
    }
    @Test fun challengePagesAndHttpFailureAreServiceErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("<html>captcha</html>")); server.enqueue(MockResponse().setResponseCode(503))
            val repo = IkrRepository(base = server.url("/").toString())
            repeat(2) { assertEquals(LookupIssue.SERVICE_UNAVAILABLE, repo.lookup(code).issue) }
        }
    }
    @Test fun cancellationCancelsNetworkRatherThanReturningUnknown() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}").setBodyDelay(1, TimeUnit.SECONDS))
            val job = launch { IkrRepository(base = server.url("/").toString()).lookup(code); fail("Must cancel") }
            delay(50); job.cancelAndJoin(); assertTrue(job.isCancelled)
        }
    }
    @Test fun exactSourceStillWorksIfOffIsMissing() = runBlocking {
        val direct = object : ProductLookup { override suspend fun lookup(code: String) = IkrRepository.parse(code, html(), "https://example.org/")!! }
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status":0}"""))
            val result = ProductRepository(offBase = server.url("/").toString(), barcodeLookup = direct).lookup(code)
            assertEquals(KosherStatus.KOSHER, result.verdict.status); assertEquals("חטיף נוגט", result.product!!.name)
            assertNull(result.issue)
        }
    }
    @Test fun exactAuthorityOutranksCommunityLabel() = runBlocking {
        val direct = object : ProductLookup { override suspend fun lookup(code: String) = IkrRepository.parse(code, html(), "https://example.org/")!! }
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"status":1,"product":{"product_name":"Test","brands":"Test","labels_tags":["en:not-kosher"]}}"""))
            val result = ProductRepository(offBase = server.url("/").toString(), barcodeLookup = direct).lookup(code)
            assertEquals(KosherStatus.KOSHER, result.verdict.status); assertEquals("כושרות", result.verdict.sourceLabel)
        }
    }
}
