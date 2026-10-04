package com.kosherscan.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** Recorded live responses, not hand-picked verdict overrides. See coverage/manifest.json. */
@RunWith(Parameterized::class)
class CoverageCorpusTest(private val item: JSONObject) {
    companion object {
        fun fixture(name: String): String = CoverageCorpusTest::class.java.getResource("/coverage/$name")!!.readText().removePrefix("\uFEFF")
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun samples(): List<Array<Any>> {
            val manifest = JSONArray(fixture("manifest.json"))
            return (0 until manifest.length()).map { arrayOf<Any>(manifest.getJSONObject(it)) }
        }
    }
    private fun strings(json: JSONObject, key: String): List<String> = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    private fun baseline(code: String, off: JSONObject): Verdict {
        val j = off.optJSONObject("product") ?: return Verdict(KosherStatus.UNKNOWN, "OFF missing")
        val p = Product(code, j.optString("product_name_he").ifBlank { j.optString("product_name").ifBlank { j.optString("product_name_en") } },
            j.optString("brands"), j.optString("product_name_en"), labels = strings(j,"labels_tags"), categories = strings(j,"categories_tags"),
            ingredients = j.optString("ingredients_text"), englishIngredients = j.optString("ingredients_text_en"), labelsText = j.optString("labels"))
        val ou = JSONObject(fixture("$code-ou.json"))
        val rows = ou.optJSONArray("results") ?: JSONArray()
        val records = (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            OuRecord(r.optString("agencyUniqueId"), r.optString("productName"), r.optString("brandName"), strings(r,"symbol"),
                listOf(r.optString("status"),r.optString("conditions")).filter { it.isNotBlank() }.distinct().joinToString(". "))
        }
        return KosherPolicy.resolve(p, records, ou.optBoolean("relatedResults"))
    }
    @Test fun recordedBarcodeWorksThroughIndependentSources() = runBlocking {
        val code = item.getString("barcode")
        val off = JSONObject(fixture("$code-off.json"))
        MockWebServer().use { authority -> MockWebServer().use { metadata ->
            authority.start(); metadata.start()
            authority.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/privateServer.php")) {
                    assertTrue(request.body.readUtf8().contains("barcode=$code"))
                    MockResponse().setBody(fixture("$code-ikr.json"))
                } else MockResponse().setBody(fixture("$code-ikr.html"))
            }
            metadata.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/api/v2/product/"))
                    MockResponse().setBody(off.toString()).setResponseCode(if (off.optInt("status") == 0) 404 else 200)
                else MockResponse().setBody(fixture("$code-ou.json"))
            }
            val result = ProductRepository(offBase = metadata.url("/").toString(), ouBase = metadata.url("/").toString(),
                barcodeLookup = IkrRepository(base = authority.url("/").toString()), enableOuFallback = false).lookup(code)
            val expected = if (item.getString("status") in setOf("כשר", "כשר למהדרין", "כשרות רגילה", "כשרות מהדרין", "מהדרין מן המהדרין") &&
                code.matches(Regex("[0-9]{8}|[0-9]{12,14}"))) KosherStatus.KOSHER else KosherStatus.UNKNOWN
            assertEquals(item.toString(), expected, result.verdict.status)
            if (expected == KosherStatus.KOSHER) {
                assertEquals("כושרות", result.verdict.sourceLabel); assertTrue(result.verdict.reason.contains("האריזה"))
            }
            val report = File("build/reports/coverage/$code.json")
            report.parentFile!!.mkdirs()
            report.writeText(JSONObject().put("barcode",code).put("category",item.getString("category")).put("name",item.getString("name"))
                .put("before",baseline(code, off).status.name).put("after",result.verdict.status.name).put("offFound",off.optInt("status") == 1)
                .put("source",item.getString("source")).toString(2))
        } }
    }
}
