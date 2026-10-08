package com.kosherscan.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 12,000 distinct generated cases. These are synthetic regressions, not live certifications. */
@RunWith(Parameterized::class)
class AdversarialMatrixTest(private val case: Case) {
    data class Case(val family: String, val sample: Int, val mutation: Int) {
        override fun toString() = "$family/sample-$sample/mutation-$mutation"
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = buildList {
            for ((family, count) in listOf("OU" to 200, "IKR" to 200, "OFF" to 100, "WATER" to 100))
                for (sample in 0 until count) for (mutation in 0 until 20)
                    add(arrayOf<Any>(Case(family, sample, mutation)))
            check(size == 12_000)
            check(map { it[0] }.distinct().size == size)
        }
        private val flavors = listOf("Hazelnut", "Vanilla", "Almond", "Cocoa", "Strawberry", "Caramel", "Peanut", "Sesame",
            "Cinnamon", "Banana", "Cherry", "Raspberry", "Mint", "Orange", "Mango", "Pistachio", "Honey", "Maple", "Coconut", "Apple")
        private val types = listOf("spread", "cookies", "cereal", "crackers", "cake", "snack", "wafer", "biscuit", "granola", "dessert")
        private val brands = listOf("Cedar", "Birch", "Pine", "Oak", "Maple", "Ash", "Willow", "Elm", "Alder", "Beech",
            "Aspen", "Fir", "Juniper", "Larch", "Olive", "Poplar", "Spruce", "Walnut", "Yew", "Acacia")
        // Generate EAN-13 values with an independently computed check digit.
        private fun barcode(sample: Int): String {
            val digits = "7290100" + sample.toString().padStart(5, '0')
            val sum = digits.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }.sum()
            return digits + ((10 - sum % 10) % 10)
        }
    }
    private fun product(): Product = Product(barcode(case.sample),
        "${flavors[case.sample % flavors.size]} ${types[(case.sample / flavors.size) % types.size]}",
        brands[case.sample % brands.size])

    @Test fun verdictAndEvidenceRemainConsistentUnderMutation() {
        when (case.family) {
            "OU" -> ou()
            "IKR" -> ikr()
            "OFF" -> off()
            "WATER" -> water()
            else -> error("Unrecognized matrix family")
        }
    }

    private fun ou() {
        var p = product()
        var row = OuRecord("OU-${case.sample}", p.name, p.brand, listOf("OU-D"), "Symbol required. Not Kosher for Passover.")
        var rows: List<OuRecord>? = null
        var related = false
        val positive = case.mutation in setOf(0, 1, 2, 3, 13, 16, 17, 18)
        when (case.mutation) {
            0 -> Unit
            1 -> {
                p = p.copy(name = "  ${p.name.uppercase().replace(" ", "   ")}  ", brand = " ${p.brand.uppercase()} ")
                row = row.copy(conditions = "SYMBOL   REQUIRED. NOT   KOSHER  FOR  PASSOVER.")
            }
            2 -> p = p.copy(name = "${p.brand} ${p.name} ${100 + case.sample}g")
            3 -> p = p.copy(name = "מוצר תצוגה ${case.sample}", englishName = p.name)
            4 -> row = row.copy(brand = p.brand + " Other")
            5 -> row = row.copy(name = p.name + " sugar free")
            6 -> row = row.copy(name = p.name + " family variety")
            7 -> row = row.copy(id = "")
            8 -> row = row.copy(symbols = emptyList())
            9 -> row = row.copy(symbols = listOf("OU-UNKNOWN-${case.sample}"))
            10 -> row = row.copy(conditions = "Symbol required. Only lot ${case.sample}.")
            11 -> row = row.copy(conditions = "Revoked.")
            12 -> row = row.copy(conditions = "")
            13 -> { p = p.copy(name = "milk"); row = row.copy(name = "milk") }
            14 -> rows = emptyList()
            15 -> related = true
            16 -> rows = listOf(row, row.copy(id = "second-${case.sample}", symbols = listOf("OU")))
            17 -> rows = listOf(row, row.copy(id = "second-${case.sample}", conditions = "not kosher for passover. SYMBOL REQUIRED."))
            18 -> p = p.copy(brand = "Parent ${case.sample}, ${p.brand}")
            19 -> { p = p.copy(name = "מוצר ${case.sample}", englishName = p.name); row = row.copy(name = row.name + " original") }
        }
        val result = KosherPolicy.resolve(p, rows ?: listOf(row), related)
        assertEquals(case.toString(), if (positive) KosherStatus.KOSHER else KosherStatus.UNKNOWN, result.status)
        if (positive) {
            assertEquals("OU", result.sourceLabel)
            assertTrue(result.reason.contains("האריזה"))
            assertTrue(result.reason.contains("לא לפסח"))
        }
    }

    private fun ikr() {
        val p = product()
        var recordedCode = p.barcode
        var status = "כשר למהדרין"
        var agency = "רבנות בדיקה ${case.sample}"
        var title = p.name
        var extra = ""
        var omitCode = false
        var omitStatus = false
        var expected: KosherStatus? = KosherStatus.KOSHER
        when (case.mutation) {
            0 -> Unit
            1 -> recordedCode = "0" + p.barcode
            2 -> { status = "לא כשר"; expected = KosherStatus.NOT_KOSHER }
            3 -> { status = "לא מאושר"; expected = KosherStatus.UNKNOWN }
            4 -> extra = "<tr><th>כשרות פסח:</th><td>לא כשל\"פ</td></tr>"
            5 -> { extra = "<tr><th>כשרות:</th><td>לא כשר</td></tr>"; expected = null }
            6 -> expected = null // Duplicate structured product sections below.
            7 -> { recordedCode = barcode(case.sample + 1_000); expected = null }
            8 -> { omitCode = true; expected = null }
            9 -> { agency = ""; expected = null }
            10 -> expected = null // No structured section below.
            11 -> { status = "כנראה כשר"; expected = null }
            12 -> { status = "עד אצווה ${case.sample}"; expected = null }
            13 -> { title = ""; expected = null }
            14 -> { status = "לא ידוע"; expected = KosherStatus.UNKNOWN }
            15 -> { omitStatus = true; expected = null }
            16 -> agency = "רבנות &amp; בדיקה ${case.sample}"
            17 -> { recordedCode = "12" + p.barcode; expected = null }
            18 -> { agency = ""; status = "לא ידוע"; expected = KosherStatus.UNKNOWN }
            19 -> { status = "לא כשר לפסח"; expected = null }
        }
        val codeRow = if (omitCode) "" else "<tr><th>ברקוד:</th><td>$recordedCode</td></tr>"
        val statusRow = if (omitStatus) "<!-- כשר -->" else "<tr><th>כשרות:</th><td>$status</td></tr>"
        val section = """<section class="main-product"><h2 class="primary-title">$title</h2>
            <div class="productDetail"><table>$codeRow$statusRow<tr><th>גופי כשרות:</th><td>$agency</td></tr>
            <tr><th>שם מפעל:</th><td>${p.brand}</td></tr>$extra</table></div></section>"""
        val html = "<header>כשר רבנות ${p.barcode}</header>" + when (case.mutation) {
            6 -> section + section
            10 -> "<p>${p.barcode} כשר $agency</p>"
            else -> section
        }
        val source = "https://www.ikr.org.il/index2.php?productId=${case.sample}"
        val result = IkrRepository.parse(p.barcode, html, source)
        if (expected == null) assertNull(case.toString(), result)
        else {
            assertNotNull(case.toString(), result)
            assertEquals(case.toString(), expected, result!!.verdict.status)
            assertEquals(p.barcode, result.product!!.barcode)
            assertEquals(source, result.verdict.sourceUrl)
            if (expected == KosherStatus.KOSHER) assertTrue(result.verdict.reason.contains("האריזה"))
            if (case.mutation == 4) assertTrue(result.verdict.reason.contains("לא כשל"))
            if (case.mutation == 16) assertTrue(result.verdict.reason.contains("&"))
        }
    }

    private fun off() {
        val p = product()
        val input = when (case.mutation) {
            0 -> p.copy(labels = listOf("en:kosher"))
            1 -> p.copy(labels = listOf("en:orthodox-union-kosher"))
            2 -> p.copy(labelsText = "Organic, Kosher parve")
            3 -> p.copy(categories = listOf("en:kosher-parve"))
            4 -> p.copy(labels = listOf("en:not-kosher"))
            5 -> p.copy(labelsText = "לא כשר")
            6 -> p.copy(labels = listOf("en:kosher", "en:not-kosher"))
            7 -> p.copy(labelsText = "OU Kosher, not kosher for passover")
            8 -> p.copy(labels = listOf("en:not-kosher-for-passover"))
            9 -> p.copy(labelsText = "possibly kosher")
            10 -> p.copy(labelsText = "kosher style")
            11 -> p.copy(labels = listOf("en:vegan"))
            12 -> p.copy(labelsText = "not certified kosher")
            13 -> p.copy(labelsText = "kosher-free")
            14 -> p.copy(labelsText = "kosher; not kosher")
            15 -> p.copy(labelsText = "  OU KOSHER ; Organic  ")
            16 -> p.copy(labels = listOf("en:star-k-kosher"))
            17 -> p.copy(labels = listOf("fr:kosher"))
            18 -> p.copy(labelsText = "לא כשר לפסח")
            19 -> p.copy(labelsText = "koshering")
            else -> error("Unknown mutation")
        }
        val expected = when (case.mutation) {
            0, 1, 2, 3, 7, 15, 16, 17 -> KosherStatus.KOSHER
            4, 5 -> KosherStatus.NOT_KOSHER
            else -> KosherStatus.UNKNOWN
        }
        val result = KosherPolicy.resolve(input, emptyList())
        assertEquals(case.toString(), expected, result.status)
        if (expected == KosherStatus.KOSHER) {
            assertEquals("Open Food Facts", result.sourceLabel)
            assertTrue(result.reason.contains("דיווח קהילתי"))
        }
    }

    private fun water() {
        val p = product().copy(name = "${brands[case.sample % brands.size]} mineral water ${case.sample}",
            categories = listOf("en:waters"), ingredients = "water")
        val input = when (case.mutation) {
            0 -> p
            1 -> p.copy(ingredients = "מים מינרליים")
            2 -> p.copy(categories = listOf("en:spring-waters"), ingredients = "изворна вода")
            3 -> p.copy(categories = listOf("en:mineral-waters"), ingredients = "eau minérale")
            4 -> p.copy(ingredients = "")
            5 -> p.copy(ingredients = "water, lemon")
            6 -> p.copy(categories = p.categories + "en:fruit-juices")
            7 -> p.copy(name = "${p.name} lemon")
            8 -> p.copy(englishName = "Vitamin water")
            9 -> p.copy(englishIngredients = "water with minerals")
            10 -> p.copy(categories = emptyList())
            11 -> p.copy(categories = p.categories + "en:unclassified-${case.sample}")
            12 -> p.copy(ingredients = "water extract")
            13 -> p.copy(name = "${p.name} בטעם")
            14 -> p.copy(categories = p.categories + "en:flavored-waters")
            15 -> p.copy(ingredients = "mineral water; potassium")
            16 -> p.copy(ingredients = "", englishIngredients = "natural spring water")
            17 -> p.copy(ingredients = "eau", englishIngredients = "apple juice")
            18 -> p.copy(categories = listOf("en:beverages"))
            19 -> p.copy(ingredients = "\"water\"")
            else -> error("Unknown mutation")
        }
        val positive = case.mutation in setOf(0, 1, 2, 3, 16, 19)
        assertEquals(case.toString(), positive, PlainWaterPolicy.matches(input))
        val result = KosherPolicy.resolve(input, emptyList())
        assertEquals(case.toString(), KosherStatus.UNKNOWN, result.status)
    }
}
