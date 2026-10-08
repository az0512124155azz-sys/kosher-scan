package com.kosherscan.app

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class AgentConnection(val url: String, val token: String) {
    fun valid(allowLocal: Boolean = false): Boolean = try {
        val u = url.toHttpUrl()
        token.length in 20..200 && u.username.isEmpty() && u.password.isEmpty() && u.encodedPath == "/" && u.query == null && u.fragment == null &&
            (u.isHttps || (allowLocal && u.host in setOf("10.0.2.2", "localhost", "127.0.0.1")))
    } catch (_: IllegalArgumentException) { false }
}
class AgentApi(private val connection: AgentConnection,
    client: OkHttpClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()) {
    private val http = BoundedHttp(client)
    suspend fun result(code: String, market: String): LookupResult? {
        if (!connection.valid(BuildConfig.DEBUG)) return null
        return try {
            val url = connection.url.toHttpUrl().newBuilder().addPathSegments("api/result")
                .addQueryParameter("barcode", code).addQueryParameter("market", market).build()
            val (status, text) = http.request(Request.Builder().url(url).header("Authorization", "Bearer ${connection.token}").build())
            if (status != 200) null else parseResult(JSONObject(text), code, market)
        } catch (_: java.io.IOException) { null } catch (_: org.json.JSONException) { null }
    }
    suspend fun submit(payload: String): Int {
        val url = connection.url.toHttpUrl().newBuilder().addPathSegments("api/cases").build()
        return http.request(Request.Builder().url(url).header("Authorization", "Bearer ${connection.token}")
            .post(payload.toRequestBody("application/json".toMediaType())).build()).first
    }
    companion object {
        fun parseResult(json: JSONObject, code: String, market: String, now: Long = System.currentTimeMillis()): LookupResult? {
            if (!json.optBoolean("approved") || json.optString("phase") != "approved" || json.optString("barcode") != code || json.optString("market") != market || json.optLong("reviewedAt") <= 0) return null
            val expires = json.optString("expiresAt")
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
            val date = try { format.parse(expires) } catch (_: java.text.ParseException) { null } ?: return null
            if (format.format(date) != expires || date.time / 86400000 < now / 86400000 || date.time - now > 366L * 86400000) return null
            val status = when (json.optString("status")) { "kosher" -> KosherStatus.KOSHER; "not_kosher" -> KosherStatus.NOT_KOSHER; else -> return null }
            val p = Product(code, json.optString("name").take(300), json.optString("brand").take(300))
            return LookupResult(p, Verdict(status, "Reviewed agent record", sourceLabel = "Reviewed agent"))
        }
    }
}
class AgentAwareLookup(private val primary: ProductRepository, private val agent: () -> AgentApi?, private val market: () -> String) : ProductLookup {
    fun extended(): AgentAwareLookup = AgentAwareLookup(primary.extended(), agent, market)
    override suspend fun lookup(code: String): LookupResult = coroutineScope {
        val currentMarket = market()
        val remote = async { agent()?.result(code, currentMarket) }
        val regular = async { primary.lookup(code) }
        val local = regular.await()
        if (local.verdict.status != KosherStatus.UNKNOWN) { remote.cancel(); local }
        else combine(local, remote.await())
    }
    companion object {
        fun combine(local: LookupResult, extra: LookupResult?): LookupResult {
            if (extra == null) return local
            val product = local.product?.copy(name = local.product.name.ifBlank { extra.product?.name.orEmpty() },
                brand = local.product.brand.ifBlank { extra.product?.brand.orEmpty() }) ?: extra.product
            return DecisionEngine.resolve(product, listOf(local, extra))
        }
    }
}
