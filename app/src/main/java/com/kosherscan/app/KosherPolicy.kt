package com.kosherscan.app

import java.text.Normalizer
import java.util.Locale

enum class KosherStatus { KOSHER, NOT_KOSHER, UNKNOWN }
data class Verdict(val status: KosherStatus, val reason: String)
data class Product(
    val barcode: String, val name: String, val brand: String,
    val englishName: String = "", val imageUrl: String = "",
    val labels: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val ingredients: String = "", val englishIngredients: String = "",
    val labelsText: String = ""
)
data class OuRecord(val id: String, val name: String, val brand: String,
    val symbols: List<String>, val conditions: String)

object KosherPolicy {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT).replace(Regex("\\p{M}+"), "")
        .replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun labelValues(p: Product): List<String> =
        (p.labels + p.categories + p.labelsText.split(',', ';', '\n'))
            .map { normalize(it.substringAfter(':')) }

    // Exact labels only: 'not kosher for passover' is NOT a year-round rejection.
    fun explicitlyNotKosher(p: Product) = labelValues(p).any {
        it in setOf("not kosher", "non kosher", "לא כשר")
    }

    // Explicit certification labels from OFF's taxonomy, not arbitrary text that
    // happens to contain 'kosher'. OFF reports remain community evidence.
    // https://github.com/openfoodfacts/openfoodfacts-server/blob/main/taxonomies/labels.txt
    private val kosherLabels = setOf(
        "kosher", "kasher", "כשר", "kosher parve", "kosher pareve", "kosher dairy", "kosher meat",
        "kosher for passover", "כשר פרווה", "כשר חלבי", "כשר לפסח",
        "orthodox union kosher", "orthodox union", "ou kosher", "ou", "ou d", "ou de", "ou p",
        "organized kashrut kosher", "organized kashrut", "ok kosher",
        "star k kosher", "star d kosher", "kosher lbd", "mk kosher", "cor kosher",
        "kosher supervision of america", "ksa kosher", "ksa", "manchester beth din", "manchester kosher",
        "kashrut division of the london beth din", "london beth din", "klbd", "kosher london beth din",
        "kosher check", "bc kosher", "tablet k kosher", "kosher under supervision of rabbi weitman tnuva",
        "כשר בהשגחת רבי ויטמן תנובה", "sephardi kashrut autority", "sephardi beth din",
        "union of orthodox synagogues of south africa")

    fun explicitlyKosher(p: Product) = labelValues(p).any { it in kosherLabels }

    // Ignore packaging quantities and a repeated brand prefix, never flavors/variants.
    fun searchName(value: String): String = value
        .replace(Regex("\\b(?:\\d+\\s*[x×]\\s*)?\\d+(?:[.,]\\d+)?\\s*(?:kg|g|mg|ml|cl|l|oz|lb)\\b", RegexOption.IGNORE_CASE), " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun productIdentity(value: String, brand: String): String {
        val normalized = normalize(searchName(value))
        val brandPrefix = "$brand "
        return if (normalized.startsWith(brandPrefix)) normalized.removePrefix(brandPrefix) else normalized
    }

    fun strongMatch(p: Product, r: OuRecord): Boolean {
        val brand = normalize(r.brand)
        if (r.id.isBlank() || brand.isBlank() || p.brand.split(',').none { normalize(it) == brand }) return false
        if (r.symbols.isEmpty() || r.symbols.any { it !in setOf("OU", "OU-D", "OU-DE", "OU-M", "OU-P") }) return false
        // Fail closed on new/unknown restrictions, revoked entries, dates or batch conditions.
        val clauses = r.conditions.split('.').map { normalize(it) }.filter { it.isNotBlank() }
        if (clauses.isEmpty() || clauses.any { it !in setOf("symbol required", "not kosher for passover", "kosher for passover") }) return false
        val name = productIdentity(r.name, brand)
        if (name.isBlank()) return false
        val generic = setOf("milk", "water", "chocolate", "bread", "coffee", "tea", "salt", "sugar")
        if (name in generic) return false
        return listOf(p.name, p.englishName).filter { it.isNotBlank() }
            .any { productIdentity(it, brand) == name }
    }

    fun resolve(p: Product, records: List<OuRecord>, related: Boolean = false): Verdict {
        if (explicitlyNotKosher(p)) return Verdict(KosherStatus.NOT_KOSHER,
            "המוצר מסומן במפורש כלא כשר ב־Open Food Facts (מאגר קהילתי).")
        val matches = if (related) emptyList() else records.filter { strongMatch(p, it) }
        val distinct = matches.map { it.symbols.sorted() to it.conditions }.distinct()
        return if (matches.isNotEmpty() && distinct.size == 1) Verdict(KosherStatus.KOSHER,
            "התאמת שם ומותג ב־OU · ${matches.first().symbols.joinToString()}\nיש לוודא שהסמל מופיע על האריזה. ${if (matches.first().conditions.contains("Not Kosher for Passover", true)) "לא לפסח." else ""}")
        else if (explicitlyKosher(p)) Verdict(KosherStatus.KOSHER,
            "מסומן ככשר ב־Open Food Facts · דיווח קהילתי.\nיש לוודא סימון כשרות על האריזה; זה אינו אישור OU.")
        else if (PlainWaterPolicy.matches(p)) PlainWaterPolicy.verdict()
        else Verdict(KosherStatus.UNKNOWN, "לא נמצאה התאמה חד־משמעית ב־OU או סימון כשרות מפורש. היעדר התאמה אינו מעיד שהמוצר אינו כשר.")
    }
}
