package com.kosherscan.app

import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Exact barcode search used by Kosharot's own scanner. No name-based inference. */
class IkrRepository(
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build(),
    private val base: String = "https://www.ikr.org.il/"
) : ProductLookup {
    private val http = BoundedHttp(client)
    override suspend fun lookup(code: String): LookupResult = withTimeoutOrNull(12_000) {
        try {
            val request = Request.Builder().url(base.toHttpUrl().resolve("privateServer.php")!!)
                .post(FormBody.Builder().add("action", "productIdByBarcode").add("barcode", code).add("fromPage", "2").build()).build()
            val (status, body) = http.request(request)
            if (status != 200) return@withTimeoutOrNull unavailable()
            val json = JSONObject(body)
            if (!json.has("productId")) return@withTimeoutOrNull unavailable()
            val id = json.get("productId").toString().toLongOrNull() ?: return@withTimeoutOrNull unavailable()
            // 0 means no proper listed certification, -1 means absent/error. Neither is a negative verdict.
            if (id <= 0) return@withTimeoutOrNull LookupResult(null, Verdict(KosherStatus.UNKNOWN, "לא נמצאה רשומת ברקוד בכושרות."))
            val url = base.toHttpUrl().newBuilder().addPathSegment("index2.php")
                .addQueryParameter("id", "20").addQueryParameter("productId", id.toString())
                .addQueryParameter("lang", "HEB").addQueryParameter("from", "2").build()
            val (detailStatus, html) = http.request(Request.Builder().url(url).build())
            if (detailStatus != 200) return@withTimeoutOrNull unavailable()
            parse(code, html, url.toString()) ?: unavailable()
        } catch (_: IOException) { unavailable() }
          catch (_: org.json.JSONException) { unavailable() }
    } ?: unavailable()

    private fun unavailable() = LookupResult(null, Verdict(KosherStatus.UNKNOWN,
        "מאגר כושרות אינו זמין כרגע."), LookupIssue.SERVICE_UNAVAILABLE)

    companion object {
        fun sameBarcode(a: String, b: String): Boolean = listOf(a, b).all {
            it.matches(Regex("[0-9]{8}|[0-9]{12,14}")) && it.any { digit -> digit != '0' }
        } && a.padStart(14, '0') == b.padStart(14, '0')

        fun parse(code: String, html: String, sourceUrl: String): LookupResult? {
            val sections = Jsoup.parse(html, sourceUrl).select("section.main-product")
            if (sections.size != 1) return null
            val section = sections.first()!!
            val rows = section.select(".productDetail table tr").mapNotNull { row ->
                val key = row.selectFirst("th")?.text()?.trim()?.removeSuffix(":") ?: return@mapNotNull null
                val value = row.selectFirst("td")?.text()?.trim() ?: return@mapNotNull null
                key to value
            }
            if (rows.map { it.first }.distinct().size != rows.size) return null
            val fields = rows.toMap()
            if (!sameBarcode(code, fields["ברקוד"].orEmpty())) return null
            val agencies = fields["גופי כשרות"].orEmpty()
            val status = when (fields["כשרות"]) {
                "כשר", "כשר למהדרין", "כשרות רגילה", "כשרות מהדרין", "מהדרין מן המהדרין" -> KosherStatus.KOSHER
                "לא כשר" -> KosherStatus.NOT_KOSHER
                "לא מאושר ע\"פ נהלי כשרות", "לא מאושר", "לא ידוע", "אין כשרות" -> KosherStatus.UNKNOWN
                else -> return null
            }
            if (status != KosherStatus.UNKNOWN && agencies.isBlank()) return null
            val name = section.selectFirst("h2.primary-title")?.text()?.substringBefore(" - ")?.trim().orEmpty()
            if (name.isBlank()) return null
            val manufacturer = fields["שם מפעל"].orEmpty().ifBlank { fields["שם ספק"].orEmpty() }
            val image = section.selectFirst(".productDetail img[src]")?.absUrl("src").orEmpty().takeIf { it.startsWith("https://") }.orEmpty()
            val product = Product(code, name, manufacturer, imageUrl = image)
            val detail = fields.filterKeys { it != "ברקוד" && it != "שם מפעל" && it != "שם ספק" && it != "גופי כשרות" && it != "כשרות" }
                .entries.joinToString(" · ") { "${it.key}: ${it.value}" }
            val reason = "התאמת ברקוד במאגר כושרות · $agencies\n" + when (status) {
                KosherStatus.KOSHER -> "$detail\nיש לבדוק שסימון הכשרות והפרטים על האריזה תואמים למקור."
                KosherStatus.NOT_KOSHER -> "המוצר מסומן במקור במפורש כלא כשר."
                KosherStatus.UNKNOWN -> "הרשומה אינה מספקת אישור כשרות; זה אינו קובע שהמוצר לא כשר."
            }
            return LookupResult(product, Verdict(status, reason, sourceUrl, "כושרות"))
        }
    }
}
