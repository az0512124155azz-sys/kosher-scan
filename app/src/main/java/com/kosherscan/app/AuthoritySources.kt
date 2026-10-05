package com.kosherscan.app

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

interface IdentifiedLookup { suspend fun lookup(product: Product): LookupResult }
data class AuthorityRecord(val id: String, val name: String, val brand: String,
    val status: KosherStatus, val details: String = "", val eligible: Boolean = true)

/** These website integrations are deliberately isolated from UI/status copy. */
class AuthoritySources(
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build(),
    private val market: () -> String = { "IL" },
    private val govBase: String = "https://data.gov.il/",
    private val okBase: String = "https://www.ok.org/",
    private val starBase: String = "https://www.star-k.org/",
    private val starLetterBase: String = "https://apiservice.star-k.org/",
    private val klbdBase: String = "https://isitkosher.uk/",
    private val pdfText: (ByteArray) -> List<String> = StarPdfText::pages,
    private val now: () -> Long = System::currentTimeMillis,
    private val diagnostic: (String) -> Unit = {}
) : IdentifiedLookup {
    private val http = BoundedHttp(client)
    private suspend fun read(url: HttpUrl): String {
        val (status, text) = http.request(Request.Builder().url(url).header("User-Agent", "KosherScan/1.5.0 Android").build())
        if (status != 200) throw IOException("HTTP $status")
        return text
    }
    override suspend fun lookup(product: Product): LookupResult = coroutineScope {
        val scope = market() // Freeze scope for this lookup, even if preferences change.
        val jobs = listOf(
            async { check("OK", product) { ok(product) } },
            async { check("STAR-K", product) { star(product) } },
            async { if (scope == "IL") check("Rabbanut", product) { government(product) } else unknown(product) },
            async { if (scope == "GB") check("KLBD", product) { klbd(product) } else unknown(product) }
        )
        merge(product, jobs.awaitAll())
    }
    private suspend fun check(source: String, p: Product, fetch: suspend () -> List<AuthorityRecord>): LookupResult {
        return try {
            withTimeoutOrNull(6_000) {
                val records = fetch()
                val result = resolve(p, records, source)
                diagnostic("$source rows=${records.size} status=${result.verdict.status}")
                result
            } ?: unknown(p, LookupIssue.TIMEOUT)
        } catch (e: IOException) {
            diagnostic("$source transport_error")
            unknown(p, if (e is java.io.InterruptedIOException) LookupIssue.TIMEOUT else LookupIssue.SERVICE_UNAVAILABLE)
        } catch (e: org.json.JSONException) {
            diagnostic("$source invalid_response"); unknown(p, LookupIssue.INVALID_RESPONSE)
        } catch (e: IllegalArgumentException) {
            diagnostic("$source invalid_response"); unknown(p, LookupIssue.INVALID_RESPONSE)
        }
    }
    private fun names(p: Product) = listOf(p.englishName, p.name).filter { it.isNotBlank() }
        .map(KosherPolicy::searchName).distinct().take(2)
    private fun brands(p: Product) = (p.brand.split(',') + p.manufacturer).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(2)

    private suspend fun ok(p: Product): List<AuthorityRecord> {
        val records = mutableListOf<AuthorityRecord>()
        // A complete brand search includes variants; the parser never certifies a company row.
        for (query in (brands(p) + names(p)).distinct().take(2)) {
            val html = read(okBase.toHttpUrl().newBuilder().addPathSegments("product-search/").addQueryParameter("term", query).build())
            records += parseOk(html)
            if (records.any { identity(p, it) }) break
        }
        return records
    }
    private suspend fun government(p: Product): List<AuthorityRecord> {
        val meta = JSONObject(read(govBase.toHttpUrl().newBuilder().addPathSegments("api/3/action/package_show")
            .addQueryParameter("id", "mazon").build()))
        require(meta.optBoolean("success"))
        val resources = meta.getJSONObject("result").getJSONArray("resources")
        require(resources.length() == 1) // A changed/ambiguous publication needs explicit handling.
        val resource = resources.getJSONObject(0)
        require(resource.optString("format").equals("CSV", true) && resource.optBoolean("datastore_active"))
        val id = resource.getString("id")
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val result = mutableListOf<AuthorityRecord>()
        for (query in names(p)) {
            val json = JSONObject(read(govBase.toHttpUrl().newBuilder().addPathSegments("api/3/action/datastore_search")
                .addQueryParameter("resource_id", id).addQueryParameter("q", query).addQueryParameter("limit", "100").build()))
            require(json.optBoolean("success"))
            val data = json.getJSONObject("result"); val rows = data.getJSONArray("records")
            if (data.getInt("total") > rows.length()) continue // Never hide competing certificates by truncation.
            result += (0 until rows.length()).flatMap { parseGovernment(rows.getJSONObject(it), now()) }
            if (result.any { identity(p, it) }) break
        }
        return result
    }
    private suspend fun klbd(p: Product): List<AuthorityRecord> {
        val messages = JSONObject(read(klbdBase.toHttpUrl().resolve("api/messages")!!))
        require(messages.has("outofdate") && !messages.getBoolean("outofdate"))
        val query = brands(p).firstOrNull() ?: names(p).firstOrNull() ?: return emptyList()
        val json = JSONObject(read(klbdBase.toHttpUrl().newBuilder().addPathSegments("api/query")
            .addQueryParameter("q", query).addQueryParameter("grouped", "false").addQueryParameter("cat", "false").build()))
        return parseKlbd(json)
    }
    private suspend fun star(p: Product): List<AuthorityRecord> {
        val query = brands(p).firstOrNull() ?: return emptyList()
        val url = starBase.toHttpUrl().newBuilder().addPathSegments("listings/star-k").build()
        val (status, html) = http.request(Request.Builder().url(url).post(FormBody.Builder().add("q", query).build()).build())
        if (status != 200) throw IOException("HTTP $status")
        val doc = Jsoup.parse(html)
        require(doc.selectFirst("#pages.listings") != null)
        // Only opaque certificate identifiers from this official listing are used;
        // arbitrary HTML links never become fetch destinations.
        val ids = doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            Regex("https://apiservice\\.star-k\\.org/api/Loc/LoadLoc/([A-Z0-9]{8})").matchEntire(href)?.groupValues?.get(1)
        }.distinct()
        if (ids.size > 2) return emptyList() // Ambiguous broad/company search cannot certify.
        val records = mutableListOf<AuthorityRecord>()
        for (id in ids) {
            val (code, bytes) = http.bytes(Request.Builder().url(starLetterBase.toHttpUrl().newBuilder()
                .addPathSegments("api/Loc/LoadLoc").addPathSegment(id).build()).build())
            if (code != 200 || !bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) throw IOException("Invalid certificate")
            records += parseStar(pdfText(bytes), id, now())
        }
        return records
    }

    companion object {
        private fun unknown(p: Product, issue: LookupIssue? = null) = LookupResult(p, Verdict(KosherStatus.UNKNOWN, "Insufficient authoritative evidence"), issue)
        fun identity(p: Product, r: AuthorityRecord): Boolean {
            val brands = (p.brand.split(',') + p.manufacturer).filter { it.isNotBlank() }.map(::companyName).joinToString(",")
            return KosherPolicy.identityMatch(p.copy(brand = brands), OuRecord(r.id, r.name, companyName(r.brand), listOf("OU"), "Symbol required."))
        }
        private fun companyName(value: String): String {
            val normalized = KosherPolicy.normalize(value)
            val tokens = normalized.split(' ').filterNot { it in setOf("nv", "bv", "llc", "ltd", "limited", "inc", "gmbh", "corp", "corporation", "chocolaterie") }
            return if (tokens.joinToString(" ").length >= 4) tokens.joinToString(" ") else normalized
        }
        fun resolve(p: Product, records: List<AuthorityRecord>, source: String): LookupResult {
            val matches = records.filter { identity(p, it) }
            if (matches.isEmpty() || matches.any { !it.eligible || it.status == KosherStatus.UNKNOWN }) return unknown(p)
            if (matches.map { it.status to it.details }.distinct().size != 1) return unknown(p)
            val row = matches.first()
            return LookupResult(p, Verdict(row.status, "Exact product record", sourceLabel = source, displayText = row.details))
        }
        fun merge(p: Product, results: List<LookupResult>): LookupResult {
            val known = results.filter { it.verdict.status != KosherStatus.UNKNOWN }
            if (known.map { it.verdict.status }.distinct().size > 1) return unknown(p)
            if (known.isNotEmpty()) return known.firstOrNull { it.verdict.sourceLabel in setOf("OU", "OK", "STAR-K", "KLBD", "Rabbanut") } ?: known.first()
            return unknown(p, results.firstOrNull { it.issue != null }?.issue)
        }
        private fun dateValid(value: String, pattern: String, now: Long): Boolean {
            if (value.isBlank()) return false
            val f = SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
            return try {
                val date = f.parse(value) ?: return false
                f.format(date) == value && date.time / 86_400_000 >= now / 86_400_000 &&
                    date.time - now < 10L * 366 * 86_400_000 // Reject malformed years, e.g. 2226.
            } catch (_: java.text.ParseException) { false }
        }
        fun parseOk(html: String): List<AuthorityRecord> {
            val doc = Jsoup.parse(html)
            val table = doc.selectFirst("#filtersTable")
            if (table == null) {
                require(doc.selectFirst(".filters-search__search-input") != null)
                return emptyList()
            }
            require(table.select("thead th").map { it.text() } == listOf("Brand Name", "Product Name", "Symbol", "Status", "KID"))
            require(doc.select(".pagination, a[rel=next]").isEmpty())
            return table.select("tbody tr").map { tr ->
                val cells = tr.select("td"); require(cells.size == 5)
                val kid = cells[4].select("a[href]").firstOrNull()?.attr("href")?.toHttpUrl()?.queryParameter("kidSearchText") ?: ""
                val status = cells[3].text()
                val symbols = cells[2].select("img[alt]").map { it.attr("alt") }.toSet()
                val details = when (status) { "Dairy" -> "חלבי."; "Pareve" -> "פרווה."; "Meat" -> "בשרי."; "Passover" -> "כשר לפסח."; else -> "" }
                val expected = when (status) { "Dairy" -> "OK D SYMBOL"; "Pareve" -> "OK SYMBOL"; "Meat" -> "OK M SYMBOL"; "Passover" -> "OK P SYMBOL"; else -> "" }
                AuthorityRecord(kid, cells[1].text(), cells[0].text(), KosherStatus.KOSHER, details,
                    kid.matches(Regex("[A-Z0-9]{7}")) && expected.isNotEmpty() && symbols == setOf(expected))
            }
        }
        fun parseGovernment(r: JSONObject, now: Long): List<AuthorityRecord> {
            val makers = r.optString("name6").split('|').map { it.trim() }.filter { it.isNotEmpty() }
            val names = listOf(r.optString("name4"), r.optString("name5")).filter { it.isNotBlank() }
            val nature = r.optString("name10")
            val detail = when { nature == "פרווה | לימות השנה" -> "פרווה."; nature == "חלבי | לימות השנה" -> "חלבי."; else -> "" }
            val extra = listOf("name11", "name14", "name12", "name15").any { r.optString(it).isNotBlank() }
            val notes = r.optString("description").split('|').map { it.trim() }.filter { it.isNotEmpty() }
            val allowed = notes.all { it in setOf("הערות לתעודה:", "הערות לתעודה: סמל הכשרות חייב להופיע על גבי האריזה המקורית",
                "שם היצרן וארץ הייצור חייבים להופיע על גבי האריזה המקורית") ||
                (it.startsWith("נוסח תווית: כשר") && it.endsWith("ובאישור הרבנות הראשית לישראל") &&
                    !Regex("רק|אצוו|ייצור|תאריך|עד |ללא|לאוכלי|אריז").containsMatchIn(it)) }
            val valid = r.optString("name7").isNotBlank() && r.optString("name3").isNotBlank() && r.optString("name2").isNotBlank() &&
                detail.isNotBlank() && !extra && allowed && notes.isNotEmpty() && dateValid(r.optString("name8"), "dd/MM/yyyy", now)
            return makers.flatMap { maker -> names.map { name -> AuthorityRecord(r.optString("name7") + ":" + r.optString("_id"), name, maker, KosherStatus.KOSHER, detail, valid) } }
        }
        fun parseKlbd(json: JSONObject): List<AuthorityRecord> {
            val rows = json.getJSONArray("results")
            require(json.getInt("count") == rows.length())
            return (0 until rows.length()).map { i ->
                val r = rows.getJSONObject(i)
                val raw = r.getString("kosher_raw_data")
                val status = when (raw) {
                    "Not Kosher" -> KosherStatus.NOT_KOSHER
                    "KLBD Parev", "KLBD Dairy", "KLBD Dairy Equipment", "Parev", "Dairy" -> KosherStatus.KOSHER
                    else -> KosherStatus.UNKNOWN // 'Not Approved' and missing info are never negative.
                }
                val detail = when (r.optString("milkmeat")) { "parev" -> "פרווה."; "dairy" -> "חלבי."; "parev-de" -> "ציוד חלבי."; else -> "" }
                val notes = listOf(r.optString("moreinfo"), r.optString("extrainfo"), r.optString("pubNote"))
                    .map { Jsoup.parse(it).text().trim() }.filter { it.isNotEmpty() && it != "~~" && it != "Yoshon" }
                val eligible = notes.isEmpty() && (status == KosherStatus.NOT_KOSHER ||
                    (r.optBoolean("simple") && detail.isNotBlank() && r.optString("certification") in setOf("klbd", "")))
                AuthorityRecord("klbd:$i:${r.getString("brand")}:${r.getString("product")}", r.getString("product"), r.getString("brand"), status, detail, eligible)
            }
        }
        fun parseStar(pages: List<String>, account: String, now: Long): List<AuthorityRecord> {
            require(pages.isNotEmpty() && pages.size <= 30)
            val result = mutableListOf<AuthorityRecord>()
            for (page in pages) {
                val before = result.size
                require(page.contains("Account: $account") && page.contains("The product(s) listed below") &&
                    page.contains("are certified kosher and under our supervision.") && page.contains("CONDITIONS OF CERTIFICATION"))
                val compact = page.replace(Regex("\\s+"), " ")
                val expiry = Regex("VALID THROUGH.{0,160}?((?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4})").find(compact)?.groupValues?.get(1) ?: ""
                val valid = dateValid(expiry, "MMMM d, yyyy", now)
                var brand = ""
                for (line in page.lines().map { it.trim() }) {
                    if (line.startsWith("Brand: ")) { brand = line.removePrefix("Brand: ").trim(); continue }
                    val m = Regex("^(.+?)\\s+(Pareve|Dairy; Non-Cholov Yisroel|Dairy|Meat)\\s+(.+?)\\s+(SK[A-Z0-9]{9})$").matchEntire(line) ?: continue
                    val (name, status, conditions, id) = m.destructured
                    val allowed = conditions in setOf("Star-K symbol required.", "Star-D symbol required.", "Star-S symbol required.")
                    val details = when (status) { "Pareve" -> "פרווה."; "Meat" -> "בשרי."; else -> "חלבי." }
                    result += AuthorityRecord(id, name, brand, KosherStatus.KOSHER, details, valid && allowed && brand.isNotBlank())
                }
                // A wrapped or changed table must not silently hide competing rows.
                require(Regex("\\bSK[A-Z0-9]{9}\\b").findAll(page).count() == result.size - before)
            }
            return result
        }
    }
}
