package com.kosherscan.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AdditionalLookupIntegrationTest {
    private fun run(labels: String, status: KosherStatus) = runBlocking {
        MockWebServer().use { s ->
            s.start()
            s.enqueue(MockResponse().setBody("""{"status":1,"product":{"product_name":"Specific Spread","brands":"Brand","brand_owner":"Manufacturer","labels_tags":[$labels],"image_front_small_url":"https://example.org/photo.jpg"}}"""))
            s.enqueue(MockResponse().setBody("""{"results":[],"total":0}"""))
            val extra = object : IdentifiedLookup {
                override suspend fun lookup(product: Product): LookupResult {
                    assertEquals("Manufacturer", product.manufacturer)
                    return LookupResult(product, Verdict(status, "official record", sourceLabel = "OK", displayText = "חלבי."))
                }
            }
            val repo = ProductRepository(offBase = s.url("/").toString(), ouBase = s.url("/").toString(),
                enableOuFallback = false, additionalLookup = extra)
            repo.lookup("12345678").also { assertEquals("https://example.org/photo.jpg", it.product!!.imageUrl) }
        }
    }
    @Test fun anAdditionalAuthorityCanResolveAnOffProductAbsentFromOu() {
        val result = run("", KosherStatus.KOSHER)
        assertEquals(KosherStatus.KOSHER, result.verdict.status); assertEquals("OK", result.verdict.sourceLabel)
        assertEquals("חלבי.", ResultCopy.text(result))
    }
    @Test fun explicitCommunityNegativeConflictsWithPositiveAuthority() {
        assertEquals(KosherStatus.UNKNOWN, run("\"en:not-kosher\"", KosherStatus.KOSHER).verdict.status)
    }
    @Test fun explicitCommunityPositiveConflictsWithNegativeAuthority() {
        assertEquals(KosherStatus.UNKNOWN, run("\"en:kosher\"", KosherStatus.NOT_KOSHER).verdict.status)
    }
}
