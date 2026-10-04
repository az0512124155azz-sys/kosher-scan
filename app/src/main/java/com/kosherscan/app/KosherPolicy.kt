package com.kosherscan.app

import java.text.Normalizer
import java.util.Locale

enum class KosherStatus { KOSHER, NOT_KOSHER, UNKNOWN }
data class Verdict(val status: KosherStatus, val reason: String)
data class Product(
    val barcode: String, val name: String, val brand: String,
    val englishName: String = "", val imageUrl: String = "",
    val labels: List<String> = emptyList()
)
data class OuRecord(val id: String, val name: String, val brand: String,
    val symbols: List<String>, val conditions: String)

object KosherPolicy {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT).replace(Regex("\\p{M}+"), "")
        .replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    // Exact labels only: 'not kosher for passover' is NOT a year-round rejection.
    fun explicitlyNotKosher(p: Product) = p.labels.any {
        normalize(it.substringAfter(':')) in setOf("not kosher", "non kosher", "לא כשר")
    }

    fun strongMatch(p: Product, r: OuRecord): Boolean {
        val brand = normalize(r.brand)
        if (r.id.isBlank() || brand.isBlank() || p.brand.split(',').none { normalize(it) == brand }) return false
        if (r.symbols.isEmpty() || r.symbols.any { it !in setOf("OU", "OU-D", "OU-DE", "OU-M", "OU-P") }) return false
        // Fail closed on new/unknown restrictions, revoked entries, dates or batch conditions.
        val clauses = r.conditions.split('.').map { normalize(it) }.filter { it.isNotBlank() }
        if (clauses.isEmpty() || clauses.any { it !in setOf("symbol required", "not kosher for passover", "kosher for passover") }) return false
        val name = normalize(r.name)
        if (name.isBlank() || name == brand) return false // a brand alone cannot identify a variant
        val generic = setOf("milk", "water", "chocolate", "bread", "coffee", "tea", "salt", "sugar")
        if (name in generic) return false
        return listOf(p.name, p.englishName).any { normalize(it) == name }
    }

    fun resolve(p: Product, records: List<OuRecord>, related: Boolean = false): Verdict {
        if (explicitlyNotKosher(p)) return Verdict(KosherStatus.NOT_KOSHER,
            "המוצר מסומן במפורש כלא כשר ב־Open Food Facts (מאגר קהילתי).")
        val matches = if (related) emptyList() else records.filter { strongMatch(p, it) }
        val distinct = matches.map { it.symbols.sorted() to it.conditions }.distinct()
        return if (matches.isNotEmpty() && distinct.size == 1) Verdict(KosherStatus.KOSHER,
            "התאמת שם ומותג ב־OU · ${matches.first().symbols.joinToString()}\nיש לוודא שהסמל מופיע על האריזה. ${if (matches.first().conditions.contains("Not Kosher for Passover", true)) "לא לפסח." else ""}")
        else Verdict(KosherStatus.UNKNOWN, "לא נמצאה התאמה חד־משמעית ב־OU. היעדר התאמה אינו מעיד שהמוצר אינו כשר.")
    }
}
