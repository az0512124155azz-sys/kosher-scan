package com.kosherscan.app

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class LookupIssue { NOT_FOUND, OFFLINE, NETWORK, TIMEOUT, SERVICE_UNAVAILABLE, INVALID_RESPONSE }
data class LookupResult(val product: Product?, val verdict: Verdict, val issue: LookupIssue? = null)
interface ProductLookup { suspend fun lookup(code: String): LookupResult }

class ProductRepository(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build(),
    private val hasNetwork: () -> Boolean = { true },
    private val offBase: String = "https://world.openfoodfacts.org/",
    private val ouBase: String = "https://productsearch-v2.oukosher.org/"
) : ProductLookup {
    private class ServiceException(val issue: LookupIssue) : IOException()
    private suspend fun get(url: HttpUrl): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "KosherScan/1.3 (Android; github.com/az0512124155azz-sys/kosher-scan)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = it.body ?: throw ServiceException(LookupIssue.INVALID_RESPONSE)
                        val source = body.source()
                        if (source.request(2_000_001)) throw ServiceException(LookupIssue.INVALID_RESPONSE)
                        val text = source.readUtf8()
                        if (!continuation.isCancelled) continuation.resume(it.code to text)
                    }
                } catch (e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
            }
        })
    }

    override suspend fun lookup(code: String): LookupResult = withContext(Dispatchers.IO) { lookupProduct(code) }

    private suspend fun lookupProduct(code: String): LookupResult {
        if (!hasNetwork()) return failure(LookupIssue.OFFLINE)
        val product: Product
        try {
            val url = offBase.toHttpUrl().newBuilder().addPathSegments("api/v2/product").addPathSegment("$code.json")
                .addQueryParameter("fields", "code,product_name,product_name_he,product_name_en,brands,image_front_small_url,labels,labels_tags,categories_tags,ingredients_text,ingredients_text_en").build()
            val (status, body) = get(url)
            if (status != 200 && status != 404) return failure(LookupIssue.SERVICE_UNAVAILABLE)
            val json = JSONObject(body)
            if (json.optInt("status", -1) == 0) return failure(LookupIssue.NOT_FOUND)
            if (status != 200 || json.optInt("status") != 1) return failure(LookupIssue.INVALID_RESPONSE)
            val p = json.getJSONObject("product")
            val labels = p.optJSONArray("labels_tags")
            val categories = p.optJSONArray("categories_tags")
            product = Product(code, p.optString("product_name_he").ifBlank { p.optString("product_name").ifBlank { p.optString("product_name_en") } },
                p.optString("brands"), p.optString("product_name_en"), p.optString("image_front_small_url"),
                (0 until (labels?.length() ?: 0)).map { labels!!.getString(it) },
                (0 until (categories?.length() ?: 0)).map { categories!!.getString(it) },
                p.optString("ingredients_text"), p.optString("ingredients_text_en"), p.optString("labels"))
        } catch (e: IOException) { return failure(classify(e)) }
          catch (e: org.json.JSONException) { return failure(LookupIssue.INVALID_RESPONSE) }
        if (KosherPolicy.explicitlyNotKosher(product)) return LookupResult(product, KosherPolicy.resolve(product, emptyList()))
        if (PlainWaterPolicy.matches(product)) return LookupResult(product, KosherPolicy.resolve(product, emptyList()))
        if (product.brand.isBlank() || product.name.isBlank()) return LookupResult(product, KosherPolicy.resolve(product, emptyList()))
        try {
            val brand = product.brand.substringBefore(',').trim()
            val name = KosherPolicy.searchName(product.englishName.ifBlank { product.name })
            val normalizedName = KosherPolicy.normalize(name)
            val normalizedBrand = KosherPolicy.normalize(brand)
            val query = if (normalizedName == normalizedBrand || normalizedName.startsWith("$normalizedBrand ")) name else "$brand $name"
            val url = ouBase.toHttpUrl().newBuilder().addPathSegments("api/v1/product")
                .addQueryParameter("page", "1").addQueryParameter("limit", "50").addQueryParameter("query", query).build()
            val (status, body) = get(url)
            if (status != 200) throw ServiceException(LookupIssue.SERVICE_UNAVAILABLE)
            val json = JSONObject(body)
            if (json.optString("status") == "error") throw ServiceException(LookupIssue.SERVICE_UNAVAILABLE)
            val rows = json.getJSONArray("results")
            val records = (0 until rows.length()).map { i ->
                val r = rows.getJSONObject(i)
                val symbols = r.optJSONArray("symbol")
                OuRecord(r.optString("agencyUniqueId"), r.optString("productName"), r.optString("brandName"),
                    (0 until (symbols?.length() ?: 0)).map { symbols!!.getString(it) },
                    listOf(r.optString("status"), r.optString("conditions")).filter { it.isNotBlank() }.distinct().joinToString(". "))
            }
            return LookupResult(product, KosherPolicy.resolve(product, records, json.optBoolean("relatedResults")))
        } catch (e: IOException) { return ouFailure(product) }
          catch (e: org.json.JSONException) { return ouFailure(product) }
    }
    private fun classify(e: IOException) = when {
        e is ServiceException -> e.issue
        !hasNetwork() -> LookupIssue.OFFLINE
        e is SocketTimeoutException || e is java.io.InterruptedIOException -> LookupIssue.TIMEOUT
        else -> LookupIssue.NETWORK
    }
    private fun ouFailure(p: Product): LookupResult {
        val fallback = KosherPolicy.resolve(p, emptyList())
        val verdict = if (fallback.status == KosherStatus.KOSHER) fallback.copy(reason = fallback.reason + "\nשירות OU אינו זמין כרגע.")
            else Verdict(KosherStatus.UNKNOWN, "המוצר זוהה, אך שירות OU אינו זמין כרגע. אפשר לנסות שוב; לא נקבעה כשרות.")
        return LookupResult(p, verdict, LookupIssue.SERVICE_UNAVAILABLE)
    }
    private fun failure(issue: LookupIssue) = LookupResult(null, Verdict(KosherStatus.UNKNOWN, when (issue) {
        LookupIssue.NOT_FOUND -> "הברקוד לא נמצא ב־Open Food Facts. אין מידע לקביעת כשרות."
        LookupIssue.OFFLINE -> "אין חיבור רשת פעיל. התחברו לרשת ונסו שוב."
        LookupIssue.NETWORK -> "לא ניתן להגיע ל־Open Food Facts. ייתכן כשל תקשורת או DNS; נסו שוב."
        LookupIssue.TIMEOUT -> "Open Food Facts לא השיב בזמן. נסו שוב בעוד רגע."
        LookupIssue.SERVICE_UNAVAILABLE -> "שירות Open Food Facts אינו זמין כרגע. נסו שוב מאוחר יותר."
        LookupIssue.INVALID_RESPONSE -> "התקבלה תשובה לא תקינה מ־Open Food Facts. נסו שוב מאוחר יותר."
    }), issue)
}
