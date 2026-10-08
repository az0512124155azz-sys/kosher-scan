package com.kosherscan.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Every recorded row is accounted for, including rejected/expired records. */
class DatabaseCoverageTest {
    private fun fixture(path: String) = javaClass.getResource("/$path")!!.readText().removePrefix("\uFEFF")
    @Test fun everyAuthorityFixtureRowReachesTheDecisionAndDisplayBoundary() {
        val now = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse("2026-10-05")!!.time
        val government = JSONObject(fixture("authorities/government.json")).getJSONObject("result").getJSONArray("records")
        val starPages = Regex("(?=Graeter's Ice Cream\\s+July 30, 2026)").split(fixture("authorities/star-text.txt")).filter { it.contains("VALID THROUGH") }
        val sources = linkedMapOf(
            "OK" to AuthoritySources.parseOk(fixture("authorities/ok.html")),
            "KLBD" to AuthoritySources.parseKlbd(JSONObject(fixture("authorities/klbd.json"))),
            "Rabbanut" to (0 until government.length()).flatMap { AuthoritySources.parseGovernment(government.getJSONObject(it), now) },
            "STAR-K" to AuthoritySources.parseStar(starPages, "ZGCOQH37", now)
        )
        val report = mutableListOf("source\tid\tname\teligible\texpected\tactual")
        val failures = mutableListOf<String>()
        for ((source, rows) in sources) {
            assertTrue(source, rows.isNotEmpty())
            for (row in rows) {
                val p = Product("12345678", row.name, row.brand)
                val expected = if (row.eligible) row.status else KosherStatus.UNKNOWN
                val parsed = AuthoritySources.resolve(p, listOf(row), source)
                val result = DecisionEngine.resolve(p, listOf(parsed))
                report += "$source\t${row.id}\t${row.name}\t${row.eligible}\t$expected\t${result.verdict.status}"
                if (expected != result.verdict.status) failures += "$source/${row.name}: $expected -> ${result.verdict.status}"
                assertFalse(ResultCopy.text(result).contains("```"))
                assertFalse(ResultCopy.text(result).contains("suggestedStatus"))
            }
        }
        File("build/reports/database-coverage").mkdirs()
        File("build/reports/database-coverage/records.tsv").writeText(report.joinToString("\n"))
        File("build/reports/database-coverage/summary.txt").writeText(sources.entries.joinToString("\n") { (name, rows) ->
            "$name: ${rows.size} rows; ${rows.count { it.eligible && it.status != KosherStatus.UNKNOWN }} eligible known; ${rows.count { !it.eligible || it.status == KosherStatus.UNKNOWN }} restricted/expired/unknown"
        } + "\nFixture coverage only. Not exhaustive live database or barcode coverage.\n")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
