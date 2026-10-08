package com.kosherscan.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in network audit, never silently substitutes cached fixtures for live data. */
class LiveSourceAuditTest {
    @Test fun liveOuPagesAndDirectSources() = runBlocking {
        assumeTrue(System.getenv("KOSHER_LIVE_AUDIT") == "1")
        val report = mutableListOf("Live UTC timestamp: ${java.time.Instant.now()}")
        val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        val failures = mutableListOf<String>()
        for (query in listOf("Oreo", "Heinz", "Kellogg", "Barilla", "Quaker", "Twinings")) {
            var checked = 0; var eligible = 0; var total = 0
            for (page in 1..5) {
                val url = "https://productsearch-v2.oukosher.org/api/v1/product?page=$page&limit=100&query=$query"
                val body = client.newCall(Request.Builder().url(url).build()).execute().use {
                    check(it.isSuccessful) { "OU HTTP ${it.code}" }; it.body!!.string()
                }
                val json = JSONObject(body); assertFalse(json.optBoolean("relatedResults"))
                val rows = json.getJSONArray("results"); total = json.getInt("total")
                for (i in 0 until rows.length()) {
                    val row = OuRecords.parse(rows.getJSONObject(i))
                    checked++
                    if (KosherPolicy.certificationRecognized(row)) {
                        eligible++
                        val p = Product("12345678", row.name, row.brand)
                        val result = DecisionEngine.resolve(p, listOf(LookupResult(p, KosherPolicy.resolve(p, listOf(row)))))
                        if (result.verdict.status != KosherStatus.KOSHER) failures += "$query/${row.id}/${row.name}: rejected exact eligible row"
                    } else report += "OU restricted/unrecognized: ${row.id} | ${row.name} | ${row.officialStatus} | yoshon=${row.yoshon}"
                }
                if (checked >= total) break
            }
            report += "OU $query: checked=$checked eligible=$eligible total=$total complete=${checked == total}"
        }
        fun fixture(name: String) = javaClass.getResource("/authorities/$name")!!.readText().removePrefix("\uFEFF")
        val okRow = AuthoritySources.parseOk(fixture("ok.html")).first { it.eligible }
        val govJson = JSONObject(fixture("government.json")).getJSONObject("result").getJSONArray("records").getJSONObject(0)
        val govRow = AuthoritySources.parseGovernment(govJson, System.currentTimeMillis()).first { it.eligible }
        val samples = listOf(
            "OK" to Product("12345678", okRow.name, okRow.brand),
            "Rabbanut" to Product("12345678", govRow.name, govRow.brand),
            "STAR-K" to Product("12345678", "Lemon Sorbet", "Graeter's Sorbet"),
            "KLBD" to Product("12345678", "Crunchy Bran", "Weetabix")
        )
        for ((source, p) in samples) {
            val trace = mutableListOf<String>()
            val result = AuthoritySources(market = { if (source == "KLBD") "GB" else "IL" },
                pdfText = { bytes -> org.apache.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
                    (1..doc.numberOfPages).map { page -> org.apache.pdfbox.text.PDFTextStripper().apply {
                        sortByPosition = true; startPage = page; endPage = page
                    }.getText(doc) }
                } },
                diagnostic = { synchronized(trace) { trace += it } }).lookup(p)
            report += "$source probe: ${result.verdict.status}, returned=${result.verdict.sourceLabel}, issue=${result.issue}; ${trace.joinToString("; ")}"
            if (result.verdict.status != KosherStatus.KOSHER || result.verdict.sourceLabel != source) failures += "$source live probe failed; see report"
        }
        File("build/reports/live-sources").mkdirs()
        File("build/reports/live-sources/summary.txt").writeText(report.joinToString("\n") + "\nLimited live audit; not worldwide catalogue coverage.\n")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
