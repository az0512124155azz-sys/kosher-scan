package com.kosherscan.app

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
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
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build(),
    private val hasNetwork: () -> Boolean = { true },
    private val offBase: String = "https://world.openfoodfacts.org/",
    private val ouBase: String = "https://productsearch-v2.oukosher.org/",
    private val barcodeLookup: ProductLookup? = null,
    private val enableOuFallback: Boolean = true,
    private val metadataTimeoutMs: Long = 5_000,
    private val metadataGraceMs: Long = 1_500,
    private val ouTimeoutMs: Long = 8_000,
    private val lookupTimeoutMs: Long = 12_000,
    private val onOuEvent: (String) -> Unit = {},
    private val additionalLookup: IdentifiedLookup? = null
) : ProductLookup {
    private class ServiceException(val issue: LookupIssue) : IOException()
    private suspend fun get(url: HttpUrl): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "KosherScan/1.4.1 (Android; github.com/az0512124155azz-sys/kosher-scan)").build())
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

    /** Explicit user-requested retry keeps the full search available on a slow connection. */
    fun extended() = ProductRepository(client.newBuilder().readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build(), hasNetwork, offBase, ouBase, barcodeLookup, enableOuFallback,
        metadataTimeoutMs = 15_000, metadataGraceMs = 1_500, ouTimeoutMs = 20_000, lookupTimeoutMs = 35_000,
        onOuEvent = onOuEvent, additionalLookup = additionalLookup)

    private suspend fun fetchOff(code: String): LookupResult {
        val product: Product
        try {
            val url = offBase.toHttpUrl().newBuilder().addPathSegments("api/v2/product").addPathSegment("$code.json")
                .addQueryParameter("fields", "code,product_name,product_name_he,product_name_en,brands,brand_owner,image_front_small_url,labels,labels_tags,categories_tags,ingredients_text,ingredients_text_en").build()
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
                p.optString("ingredients_text"), p.optString("ingredients_text_en"), p.optString("labels"), p.optString("brand_owner"))
        } catch (e: IOException) { return failure(classify(e)) }
          catch (e: org.json.JSONException) { return failure(LookupIssue.INVALID_RESPONSE) }
        return LookupResult(product, KosherPolicy.resolve(product, emptyList()))
    }

    private suspend fun lookupProduct(code: String): LookupResult {
        if (!hasNetwork()) return failure(LookupIssue.OFFLINE)
        var lastMetadata: LookupResult? = null
        var lastAuthority: LookupResult? = null
        var lastResolved: LookupResult? = null
        return withTimeoutOrNull(lookupTimeoutMs) {
            coroutineScope {
                val direct = async { barcodeLookup?.lookup(code).also { lastAuthority = it } }
                val off = async {
                    (withTimeoutOrNull(metadataTimeoutMs) { fetchOff(code) } ?: failure(LookupIssue.TIMEOUT))
                        .also { lastMetadata = it }
                }
                // OU can start as soon as metadata arrives, while the barcode source is still checking.
                val fallback = async {
                    val metadata = off.await()
                    (if (strongAuthority(code, lastAuthority)) null else (metadata.product ?: direct.await()?.product)?.let {
                        resolveIdentified(it) { partial -> lastResolved = partial }
                    })
                        .also { lastResolved = it }
                }
                val authority = direct.await()
                val metadata = if (strongAuthority(code, authority)) {
                    // Optional image/community metadata cannot hold an exact authority result indefinitely.
                    withTimeoutOrNull(metadataGraceMs) { off.await() } ?: failure(LookupIssue.TIMEOUT)
                } else off.await()
                if (strongAuthority(code, authority)) {
                    fallback.cancel()
                    off.cancel()
                    combine(code, metadata, authority, null)
                } else combine(code, metadata, authority, fallback.await())
            }
        } ?: run {
            val metadata = lastMetadata ?: failure(LookupIssue.TIMEOUT)
            if (strongAuthority(code, lastAuthority)) combine(code, metadata, lastAuthority, null)
            else {
                val partial = lastResolved ?: metadata.product?.let { LookupResult(it, KosherPolicy.resolve(it, emptyList())) }
                val result = combine(code, metadata, lastAuthority, partial)
                if (result.verdict.status != KosherStatus.UNKNOWN) result else result.copy(verdict = result.verdict.copy(reason = result.verdict.reason +
                    "\nהבדיקה במאגרים לא הושלמה בזמן. אפשר לנסות שוב."), issue = LookupIssue.TIMEOUT)
            }
        }
    }

    private fun strongAuthority(code: String, authority: LookupResult?) = authority?.product != null &&
        IkrRepository.sameBarcode(code, authority.product.barcode) && authority.verdict.status != KosherStatus.UNKNOWN

    private fun combine(code: String, metadata: LookupResult, authority: LookupResult?, fallback: LookupResult?): LookupResult {
        val validAuthority = authority?.takeIf { it.product == null || IkrRepository.sameBarcode(code, it.product.barcode) }
        val identity = (if (strongAuthority(code, validAuthority)) validAuthority?.product else metadata.product)
            ?: validAuthority?.product ?: fallback?.product
        val product = identity?.copy(imageUrl = metadata.product?.imageUrl?.ifBlank { identity.imageUrl } ?: identity.imageUrl)
        return DecisionEngine.resolve(product, listOfNotNull(metadata, validAuthority, fallback))
    }

    private suspend fun resolveIdentified(product: Product, progress: (LookupResult) -> Unit = {}): LookupResult {
        val completed = mutableListOf(LookupResult(product, KosherPolicy.resolve(product, emptyList())))
        fun record(result: LookupResult) = synchronized(completed) {
            completed += result
            progress(DecisionEngine.resolve(product, completed))
        }
        return coroutineScope {
            val ou = async { resolveOu(product).also(::record) }
            val other = async { additionalLookup?.lookup(product)?.also(::record) }
            ou.await()
            other.await()
            DecisionEngine.resolve(product, completed)
        }
    }

    private suspend fun resolveOu(product: Product): LookupResult {
        if (product.brand.isBlank() || product.name.isBlank()) return LookupResult(product, KosherPolicy.resolve(product, emptyList()))
        try {
            return withTimeoutOrNull(ouTimeoutMs) {
                var requests = 0
                var lastIssue: LookupIssue? = null
                val observedRecords = mutableListOf<OuRecord>()
                for (query in ouQueries(product).take(if (enableOuFallback) 6 else 1)) {
                    val records = mutableListOf<OuRecord>()
                    var complete = false
                    try {
                        for (page in 1..5) {
                            if (++requests > 12) break
                            val url = ouBase.toHttpUrl().newBuilder().addPathSegments("api/v1/product")
                                .addQueryParameter("page", page.toString()).addQueryParameter("limit", "100").addQueryParameter("query", query).build()
                            val (status, body) = get(url)
                            if (status != 200) {
                                onOuEvent("http_error code=$status request=$requests")
                                throw ServiceException(LookupIssue.SERVICE_UNAVAILABLE)
                            }
                            val json = JSONObject(body)
                            if (json.optString("status") == "error") throw ServiceException(LookupIssue.SERVICE_UNAVAILABLE)
                            val rows = json.getJSONArray("results")
                            if (json.optBoolean("relatedResults")) {
                                onOuEvent("related_only request=$requests rows=${rows.length()}")
                                break
                            }
                            records += (0 until rows.length()).map { OuRecords.parse(rows.getJSONObject(it)) }
                            if (json.optInt("total", rows.length()) <= page * 100) { complete = true; break }
                        }
                    } catch (e: IOException) {
                        lastIssue = classify(e)
                        onOuEvent("query_failed request=$requests kind=$lastIssue")
                    } catch (e: org.json.JSONException) {
                        lastIssue = LookupIssue.INVALID_RESPONSE
                        onOuEvent("query_failed request=$requests kind=$lastIssue")
                    }
                    // Incomplete pages can hide conflicting records; they cannot certify.
                    // Keep restrictions/conflicts discovered by earlier queries, too.
                    observedRecords += if (complete) records else records.filterNot { KosherPolicy.certificationRecognized(it) }
                    val verdict = KosherPolicy.resolve(product, observedRecords)
                    onOuEvent("query_completed request=$requests rows=${records.size} complete=$complete " +
                        "identities=${records.count { KosherPolicy.identityMatch(product, it) }} " +
                        "eligible=${records.count { KosherPolicy.strongMatch(product, it) }} status=${verdict.status}")
                    if (verdict.status != KosherStatus.UNKNOWN) return@withTimeoutOrNull LookupResult(product, verdict)
                    if (requests >= 12) break
                }
                LookupResult(product, KosherPolicy.resolve(product, observedRecords), lastIssue)
            } ?: ouFailure(product, timedOut = true)
        } catch (e: IOException) { onOuEvent("transport_error kind=${classify(e)}"); return ouFailure(product, issue = classify(e)) }
          catch (e: org.json.JSONException) { onOuEvent("invalid_response"); return ouFailure(product, issue = LookupIssue.INVALID_RESPONSE) }
    }
    companion object {
        fun ouQueries(product: Product): List<String> {
            val brands = product.brand.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)
            val names = listOf(product.englishName, product.name).map { KosherPolicy.searchName(it) }.filter { it.isNotBlank() }.distinct()
            val full = names.flatMap { name -> brands.map { brand ->
                val n = KosherPolicy.normalize(name); val b = KosherPolicy.normalize(brand)
                if (n == b || n.startsWith("$b ")) name else "$brand $name"
            } }
            // Name-only search matters when metadata supplies the manufacturer's
            // name as brand, while OU indexes the retail line under another brand.
            return (full.take(3) + names + full.drop(3) + brands).distinct()
        }
    }
    private fun classify(e: IOException) = when {
        e is ServiceException -> e.issue
        !hasNetwork() -> LookupIssue.OFFLINE
        e is SocketTimeoutException || e is java.io.InterruptedIOException -> LookupIssue.TIMEOUT
        else -> LookupIssue.NETWORK
    }
    private fun ouFailure(p: Product, timedOut: Boolean = false, issue: LookupIssue = LookupIssue.SERVICE_UNAVAILABLE): LookupResult {
        val fallback = KosherPolicy.resolve(p, emptyList())
        val message = if (timedOut) "בדיקת OU לא הושלמה בזמן." else "שירות OU אינו זמין כרגע. אפשר לנסות שוב; לא נקבעה כשרות."
        val verdict = if (fallback.status == KosherStatus.KOSHER) fallback.copy(reason = fallback.reason + "\n" + message)
            else Verdict(KosherStatus.UNKNOWN, "המוצר זוהה, אך $message")
        return LookupResult(p, verdict, if (timedOut) LookupIssue.TIMEOUT else issue)
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
