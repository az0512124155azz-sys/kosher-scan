package com.kosherscan.app

import java.text.Normalizer
import java.util.Locale

enum class KosherStatus { KOSHER, NOT_KOSHER, UNKNOWN }
// Evidence stays available internally; the screen uses separate, plain-language copy.
data class Verdict(val status: KosherStatus, val reason: String, val sourceUrl: String = "", val sourceLabel: String = "",
    val displayText: String = "")
data class Product(
    val barcode: String, val name: String, val brand: String,
    val englishName: String = "", val imageUrl: String = "",
    val labels: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val ingredients: String = "", val englishIngredients: String = "",
    val labelsText: String = ""
)
data class OuRecord(val id: String, val name: String, val brand: String,
    val symbols: List<String>, val conditions: String, val officialStatus: String = "",
    val dairyEquipment: Boolean = false, val yoshon: String = "")

object KosherPolicy {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT).replace(Regex("\\p{M}+"), "").replace(Regex("['’]"), "")
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
        .replace(Regex("\\b(?:\\d+\\s*[x×]\\s*)?\\d+(?:[.,]\\d+)?\\s*(?:fl\\s*oz|kg|g|mg|ml|cl|l|oz|lb|qt|gallon|ct)\\b", RegexOption.IGNORE_CASE), " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun productIdentity(value: String, brand: String): String {
        val normalized = normalize(searchName(value))
        val brandPrefix = "$brand "
        return if (normalized.startsWith(brandPrefix)) normalized.removePrefix(brandPrefix) else normalized
    }

    fun strongMatch(p: Product, r: OuRecord): Boolean {
        if (!identityMatch(p, r)) return false
        return certificationRecognized(r)
    }

    /** An exact branded product name can identify a product line even when OU
     * lists the parent/licensing brand instead of the retail brand. No substring
     * product matches, brand aliases, translations or variant deletion. */
    fun identityMatch(p: Product, r: OuRecord): Boolean {
        val brand = normalize(r.brand)
        val brands = p.brand.split(',').map(::normalize).filter { it.isNotBlank() }
        val composedBrand = brands.flatMap { it.split(' ') }.distinct().sorted()
        if (r.id.isBlank() || brand.isBlank() || brands.isEmpty()) return false
        val sameBrand = brands.any { it == brand } || brand.split(' ').distinct().sorted() == composedBrand
        val names = listOf(p.name, p.englishName).filter { it.isNotBlank() }
        val rowName = productIdentity(r.name, brand)
        if (rowName.isBlank()) return false
        if (!sameBrand) {
            // The retail brand must be explicitly present in OU's product name.
            // At least one other token is required: a brand-only row cannot certify.
            return brands.any { retailBrand ->
                val retailTokens = retailBrand.split(' ')
                val rowTokens = rowName.split(' ')
                val startsWithRetail = rowName.startsWith("$retailBrand ")
                startsWithRetail && rowTokens.size > retailTokens.size && names.any { value ->
                    val normalized = normalize(searchName(value))
                    val branded = if (normalized == retailBrand || normalized.startsWith("$retailBrand "))
                        normalized else "$retailBrand $normalized"
                    branded.split(' ').sorted() == rowTokens.sorted()
                }
            }
        }
        val generic = setOf("milk", "water", "chocolate", "bread", "coffee", "tea", "salt", "sugar")
        if (rowName in generic) return false
        fun identityTokens(value: String): List<String> = productIdentity(value, brand).split(' ')
            .filterNot { it == "cereal" && "en:breakfast-cereals" in p.categories }.sorted()
        return names.any { identityTokens(it) == identityTokens(r.name) }
    }

    fun certificationRecognized(r: OuRecord): Boolean {
        if (r.symbols.isEmpty() || r.symbols.any { it !in setOf("OU", "OU-D", "OU-DE", "OU-M", "OU-P", "OU-Fish") }) return false
        // Fail closed on new/unknown restrictions, revoked entries, dates or batch conditions.
        val clauses = r.conditions.split('.').map { normalize(it) }.filter { it.isNotBlank() }
        if (clauses.isEmpty() || clauses.any { it !in setOf("symbol required", "not kosher for passover", "kosher for passover") }) return false
        // OU renders supplemental DE/Yoshon fields in `status`. They are not
        // additional certification restrictions. Never ignore arbitrary status text.
        if (r.officialStatus.isNotBlank()) {
            var status = normalize(r.officialStatus)
            if (r.yoshon.isNotBlank()) {
                val date = "(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}"
                val knownYoshon = r.yoshon in setOf("Certified Yoshon", "Yoshon Always (Made with Winter Wheat)") ||
                    Regex("Yoshon with Best Before date \\(or earlier\\) of (?:$date)?(?:\\s*\\(best by dates through $date\\))?").matches(r.yoshon)
                if (!knownYoshon) return false
                status = status.removeSuffix(normalize(r.yoshon)).trim()
            }
            if (r.dairyEquipment) status = status.removeSuffix("dairy equipment").trim()
            if (status != normalize(r.conditions)) return false
        }
        return true
    }

    fun resolve(p: Product, records: List<OuRecord>, related: Boolean = false): Verdict {
        if (explicitlyNotKosher(p)) return Verdict(KosherStatus.NOT_KOSHER,
            "המוצר מסומן במפורש כלא כשר ב־Open Food Facts (מאגר קהילתי).")
        val matches = if (related) emptyList() else records.filter { strongMatch(p, it) }
        // An identity-equivalent revoked/restricted/unrecognized row must not be
        // hidden by filtering it out before deciding whether matches conflict.
        if (!related && records.any { identityMatch(p, it) && !certificationRecognized(it) })
            return Verdict(KosherStatus.UNKNOWN, "Conflicting or restricted certification records")
        val distinct = matches.map { Triple(it.symbols.sorted(), it.conditions.split('.').map(::normalize).filter { clause -> clause.isNotBlank() }.distinct().sorted(), it.dairyEquipment) }.distinct()
        return if (matches.isNotEmpty() && distinct.size == 1) Verdict(KosherStatus.KOSHER,
            "התאמת שם ומותג ב־OU · ${matches.first().symbols.joinToString()}\nיש לוודא שהסמל מופיע על האריזה. ${if (matches.first().conditions.split('.').any { normalize(it) == "not kosher for passover" }) "לא לפסח." else ""}", "https://oukosher.org/product-search/", "OU",
            listOfNotNull(
                if (matches.first().dairyEquipment || matches.first().symbols == listOf("OU-DE")) "ציוד חלבי."
                else if (matches.first().symbols == listOf("OU-D")) "חלבי."
                else if (matches.first().symbols == listOf("OU-Fish")) "מכיל דגים." else null,
                if (matches.first().conditions.split('.').any { normalize(it) == "not kosher for passover" }) "לא מתאים לפסח." else null
            ).joinToString("\n"))
        else if (explicitlyKosher(p)) Verdict(KosherStatus.KOSHER,
            "מסומן ככשר ב־Open Food Facts · דיווח קהילתי.\nיש לוודא סימון כשרות על האריזה; זה אינו אישור OU.", "https://world.openfoodfacts.org/product/${p.barcode}", "Open Food Facts",
            "המוצר מסומן ככשר." +
                if (labelValues(p).any { it == "not kosher for passover" }) "\nלא מתאים לפסח." else "")
        else if (PlainWaterPolicy.matches(p)) PlainWaterPolicy.verdict()
        else Verdict(KosherStatus.UNKNOWN, "לא נמצאה התאמה חד־משמעית ב־OU או סימון כשרות מפורש. היעדר התאמה אינו מעיד שהמוצר אינו כשר.")
    }
}
