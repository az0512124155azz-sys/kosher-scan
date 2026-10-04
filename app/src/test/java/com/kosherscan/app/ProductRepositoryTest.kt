package com.kosherscan.app

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProductRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private fun repo(online: Boolean = true, timeout: Long = 2000) = ProductRepository(
        OkHttpClient.Builder().callTimeout(timeout, TimeUnit.MILLISECONDS).build(), { online }, server.url("/").toString(), server.url("/").toString())
    private val product = """{"status":1,"product":{"product_name":"Hazelnut spread with cocoa","brands":"Example","image_front_small_url":"https://images.openfoodfacts.org/test.jpg"}}"""
    @After fun close() { server.shutdown() }
    @Test fun missingProduct() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status":0}"""))
        assertEquals(LookupIssue.NOT_FOUND, repo().lookup("12345678").issue)
    }
    @Test fun serviceFailureIsNotMissingOrOffline() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        assertEquals(LookupIssue.SERVICE_UNAVAILABLE, repo().lookup("12345678").issue)
    }
    @Test fun invalidJsonIsNotMissing() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>maintenance</html>"))
        assertEquals(LookupIssue.INVALID_RESPONSE, repo().lookup("12345678").issue)
    }
    @Test fun offlineDoesNotCallService() = runBlocking {
        assertEquals(LookupIssue.OFFLINE, repo(false).lookup("12345678").issue); assertEquals(0, server.requestCount)
    }
    @Test fun timeoutIsDistinct() = runBlocking {
        server.enqueue(MockResponse().setBody(product).setBodyDelay(1, TimeUnit.SECONDS))
        assertEquals(LookupIssue.TIMEOUT, repo(timeout = 100).lookup("12345678").issue)
    }
    @Test fun ouUnavailablePreservesProductAndPhoto() = runBlocking {
        server.enqueue(MockResponse().setBody(product)); server.enqueue(MockResponse().setResponseCode(503))
        val result = repo().lookup("12345678")
        assertNotNull(result.product); assertTrue(result.product!!.imageUrl.startsWith("https://"))
        assertEquals(KosherStatus.UNKNOWN, result.verdict.status); assertTrue(result.verdict.reason.contains("OU"))
    }
    @Test fun exactOuJsonProducesQualifiedPositive() = runBlocking {
        server.enqueue(MockResponse().setBody(product))
        server.enqueue(MockResponse().setBody("""{"results":[{"agencyUniqueId":"OUD123","productName":"Hazelnut spread with cocoa","brandName":"Example","symbol":["OU-D"],"status":"Symbol required. Not Kosher for Passover.","conditions":"Symbol required. Not Kosher for Passover."}]}"""))
        val result = repo().lookup("12345678")
        assertEquals(KosherStatus.KOSHER, result.verdict.status)
        assertTrue(result.verdict.reason.contains("האריזה"))
        assertTrue(server.takeRequest().path!!.contains("image_front_small_url"))
        assertTrue(server.takeRequest().path!!.startsWith("/api/v1/product?"))
    }
    @Test fun emptyOuIsUnknownWithoutServiceError() = runBlocking {
        server.enqueue(MockResponse().setBody(product)); server.enqueue(MockResponse().setBody("""{"results":[],"total":0}"""))
        val result = repo().lookup("12345678"); assertEquals(KosherStatus.UNKNOWN, result.verdict.status); assertNull(result.issue)
    }
    @Test fun cancellationPropagates() = runBlocking {
        server.enqueue(MockResponse().setBody(product).setBodyDelay(1, TimeUnit.SECONDS))
        val job = launch { repo().lookup("12345678"); fail("Cancelled request must not return a verdict") }
        delay(50); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }
    @Test fun dnsFailureWhileOnlineIsNotOfflineOrMissing() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw java.net.UnknownHostException("test") }.build()
        val result = ProductRepository(client, { true }).lookup("12345678")
        assertEquals(LookupIssue.NETWORK, result.issue)
    }
    @Test fun malformedOuFailsClosedAndKeepsProduct() = runBlocking {
        server.enqueue(MockResponse().setBody(product)); server.enqueue(MockResponse().setBody("<html>Example Hazelnut spread with cocoa OU-D</html>"))
        val result = repo().lookup("12345678")
        assertEquals(KosherStatus.UNKNOWN, result.verdict.status); assertNotNull(result.product)
        assertEquals(LookupIssue.SERVICE_UNAVAILABLE, result.issue)
    }
    @Test fun ouTimeoutDoesNotEraseProduct() = runBlocking {
        server.enqueue(MockResponse().setBody(product)); server.enqueue(MockResponse().setBody("{}").setBodyDelay(1, TimeUnit.SECONDS))
        val result = repo(timeout = 100).lookup("12345678")
        assertNotNull(result.product); assertEquals(KosherStatus.UNKNOWN, result.verdict.status)
        assertEquals(LookupIssue.SERVICE_UNAVAILABLE, result.issue)
    }
}
