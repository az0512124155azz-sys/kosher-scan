package com.kosherscan.app

/**
 * OU's general water guidance, not certification of a brand or barcode:
 * https://oukosher.org/passover/guidelines/food-items/seltzer-water/
 * Applies only to unflavored water without additives. OFF metadata is community
 * supplied, so the result asks users to verify the actual package ingredients.
 */
object PlainWaterPolicy {
    private val waterCategories = setOf("en:waters", "en:spring-waters", "en:mineral-waters", "en:natural-mineral-waters")
    private val allowedCategories = waterCategories + setOf(
        "en:beverages-and-beverages-preparations", "en:beverages", "en:non-alcoholic-beverages")
    // Whole ingredient declarations only. No substring or ingredient inference.
    private val waterDeclarations = setOf(
        "water", "spring water", "natural spring water", "mineral water", "natural mineral water",
        "מים", "מי מעיין", "מים מינרליים", "מים מינרליים טבעיים",
        "изворна вода", "минерална вода", "натурална минерална вода",
        "eau", "eau de source", "eau minerale", "eau minerale naturelle")
    private val conflictingNames = Regex(
        "(?<!\\p{L})(flavou?r(?:ed)?|vitamin\\p{L}*|electrolyte\\p{L}*|juice|lemon|lime|fruit|coconut|sweetened|aroma\\p{L}*|" +
            "вкус\\p{L}*|витамин\\p{L}*|аромат\\p{L}*|лимон\\p{L}*|плод\\p{L}*)(?!\\p{L})|בטעם|ויטמין|ויטמינים|לימון|ממותק|קוקוס")

    fun matches(p: Product): Boolean {
        if (p.categories.none { it in waterCategories } || p.categories.any { it !in allowedCategories }) return false
        val declarations = listOf(p.ingredients, p.englishIngredients).filter { it.isNotBlank() }
        if (declarations.isEmpty() || declarations.any { KosherPolicy.normalize(it) !in waterDeclarations }) return false
        return !conflictingNames.containsMatchIn(KosherPolicy.normalize(p.name + " " + p.englishName))
    }

    fun verdict() = Verdict(KosherStatus.KOSHER,
        "לפי הנחיית OU למים רגילים ללא טעמים ותוספים.\n" +
            "פרטי הרכיבים ממאגר קהילתי; יש לוודא שעל האריזה הרכיב היחיד הוא מים. זה אינו אישור OU למותג.",
        displayText = "מים ללא טעמים ותוספים.")
}
