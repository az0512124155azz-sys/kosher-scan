package com.kosherscan.app

import java.util.Locale

/** Authority-published permissions without a product certificate. No AI or
 * inference that an arbitrary processed food is kosher by ingredient exclusion.
 * Reviewed 2026-10-11. STAR-K excludes Israeli products and Passover.
 */
object GeneralApprovalPolicy {
    const val STAR_URL = "https://www.star-k.org/articles/kosher-lists/3502/no-hechsher-required/"
    const val WATER_URL = "https://oukosher.org/passover/guidelines/food-items/seltzer-water/"
    private data class Rule(val id: String, val categories: Set<String>, val declarations: Set<String>)
    private fun words(vararg values: String) = values.map(KosherPolicy::normalize).toSet()
    private val rules = listOf(
        Rule("coffee", setOf("en:coffees", "en:coffee-beans", "en:ground-coffees", "en:instant-coffees", "en:decaffeinated-coffees"),
            words("coffee", "coffee beans", "ground coffee", "roasted coffee", "instant coffee", "decaffeinated coffee", "קפה", "פולי קפה", "קפה טחון", "קפה נמס", "cafe", "cafe moulu", "kaffee")),
        Rule("tea", setOf("en:black-teas", "en:green-teas", "en:white-teas"),
            words("black tea", "green tea", "white tea", "black tea leaves", "green tea leaves", "white tea leaves", "תה שחור", "תה ירוק", "תה לבן", "the noir", "the vert", "schwarzer tee", "gruner tee")),
        Rule("honey", setOf("en:honeys"), words("honey", "pure honey", "דבש", "דבש טהור", "miel", "honig")),
        Rule("sugar", setOf("en:sugars", "en:white-sugars", "en:brown-sugars", "en:cane-sugars", "en:beet-sugars"),
            words("sugar", "cane sugar", "beet sugar", "brown sugar", "סוכר", "סוכר קנים", "סוכר חום", "sucre", "zucker")),
        Rule("salt", setOf("en:salts", "en:sea-salts", "en:table-salts"),
            words("salt", "sea salt", "sodium chloride", "מלח", "מלח ים", "sel", "sel de mer", "salz")),
        Rule("cocoa", setOf("en:cocoa-powders", "en:unsweetened-cocoa-powders"),
            words("cocoa", "cocoa powder", "pure cocoa powder", "קקאו", "אבקת קקאו", "cacao", "poudre de cacao", "kakaopulver")),
        Rule("cornstarch", setOf("en:corn-starches"),
            words("corn starch", "cornstarch", "maize starch", "עמילן תירס", "קורנפלור", "amidon de mais", "maisstarke"))
    )
    private val parents = setOf("en:plant-based-foods-and-beverages", "en:plant-based-foods", "en:groceries",
        "en:beverages", "en:beverages-and-beverages-preparations", "en:beverage-preparations", "en:hot-beverages",
        "en:non-alcoholic-beverages", "en:coffees", "en:teas", "en:sweeteners", "en:bee-products",
        "en:cocoa-and-chocolate-products", "en:starches", "en:cereal-products", "en:cereals-and-potatoes")
    private val excludedNames = Regex("(?<!\\p{L})(flavou?r\\p{L}*|aroma\\p{L}*|vanilla|caramel|syrup|mix|creamer|latte|cappuccino|chai|herbal|earl grey|rooibos|iced|ready to drink|season\\p{L}*|infused)(?!\\p{L})|בטעם|סירופ|וניל|קרמל|תוספת|חליטה")
    private val foreignCountries = Locale.getISOCountries().filter { it != "IL" }.flatMap { code ->
        listOf(KosherPolicy.normalize(Locale("", code).getDisplayCountry(Locale.ENGLISH)), code.lowercase(Locale.ROOT))
    }.toSet() + setOf("uk", "usa", "united states of america")
    private fun foreignOrigin(p: Product): Boolean {
        val locations = (p.origins + p.manufacturingPlaces).map { KosherPolicy.normalize(it.substringAfter(':')) }
        // Unknown origin is not proof of a foreign product. Sale country and EAN
        // prefix are deliberately never used as production/ingredient origin.
        return p.manufacturingPlaces.isNotEmpty() && locations.all { it in foreignCountries }
    }
    private fun declaration(value: String) = KosherPolicy.normalize(value.trim()
        .replace(Regex("^100\\s*%\\s*|\\s*100\\s*%$"), ""))
    fun resolve(p: Product): Verdict? {
        if (KosherPolicy.explicitlyNotKosher(p)) return null
        if (PlainWaterPolicy.matches(p)) return Verdict(KosherStatus.KOSHER,
            "OU permits plain water without certification; API category and complete ingredients match.", WATER_URL,
            "General guidance", "מותר גם ללא סימון כשרות.")
        if (!foreignOrigin(p) || excludedNames.containsMatchIn(KosherPolicy.normalize(p.name + " " + p.englishName))) return null
        val ingredients = listOf(p.ingredients, p.englishIngredients).filter { it.isNotBlank() }.map(::declaration)
        if (ingredients.isEmpty()) return null
        val rule = rules.singleOrNull { rule -> p.categories.any { it in rule.categories } &&
            p.categories.all { it in rule.categories || it in parents } && ingredients.all { it in rule.declarations } } ?: return null
        return Verdict(KosherStatus.KOSHER, "STAR-K general permission: ${rule.id}; complete API ingredients and foreign origin match.",
            STAR_URL, "General guidance", "מותר גם ללא סימון כשרות.\nלימות השנה.")
    }
}
