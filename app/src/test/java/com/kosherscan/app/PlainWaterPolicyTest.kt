package com.kosherscan.app

import org.junit.Assert.*
import org.junit.Test

class PlainWaterPolicyTest {
    private val water = Product("3800000602733", "Devin", "Devin", categories = listOf(
        "en:beverages-and-beverages-preparations", "en:beverages", "en:waters", "en:spring-waters"), ingredients = "Изворна вода")
    @Test fun categoryAloneCannotReplaceACertificationRecord() {
        assertTrue(PlainWaterPolicy.matches(water))
        assertTrue(PlainWaterPolicy.matches(water.copy(barcode = "12345678", name = "Other", brand = "Other")))
        val verdict = KosherPolicy.resolve(water, emptyList())
        assertEquals(KosherStatus.UNKNOWN, verdict.status)
    }
    @Test fun missingIngredientsOrCategoryCannotCertify() {
        assertFalse(PlainWaterPolicy.matches(water.copy(ingredients = "")))
        assertFalse(PlainWaterPolicy.matches(water.copy(categories = emptyList())))
    }
    @Test fun additivesAndEvenUnknownPunctuationCannotBeGuessedAway() {
        listOf("water, natural flavors", "water, vitamins", "water, minerals", "water (99%)", "water and sugar", "Изворна вода, аромат").forEach {
            assertFalse(it, PlainWaterPolicy.matches(water.copy(ingredients = it)))
        }
    }
    @Test fun conflictingEnglishIngredientsBlockApproval() {
        assertFalse(PlainWaterPolicy.matches(water.copy(englishIngredients = "Water, lemon")))
        assertTrue(PlainWaterPolicy.matches(water.copy(englishIngredients = "Spring water")))
    }
    @Test fun conflictingCategoryBlocksApproval() {
        listOf("en:flavoured-waters", "en:vitamin-waters", "en:fruit-juices", "en:coconut-waters").forEach {
            assertFalse(PlainWaterPolicy.matches(water.copy(categories = water.categories + it)))
        }
    }
    @Test fun flavorAndVitaminNamesBlockEvenIncompleteIngredientReports() {
        listOf("Devin lemon", "Devin flavored", "Vitamin water", "Devin с вкус на лимон", "מים בטעם", "Devin קוקוס").forEach {
            assertFalse(it, PlainWaterPolicy.matches(water.copy(name = it)))
        }
        assertFalse(PlainWaterPolicy.matches(water.copy(englishName = "Lemon water")))
    }
    @Test fun explicitNegativeTakesPrecedenceOverWaterRule() {
        assertEquals(KosherStatus.NOT_KOSHER, KosherPolicy.resolve(water.copy(labels = listOf("en:not-kosher")), emptyList()).status)
    }
    @Test fun unsupportedIngredientLanguageStaysUnknown() {
        assertEquals(KosherStatus.UNKNOWN, KosherPolicy.resolve(water.copy(ingredients = "unknown declaration"), emptyList()).status)
    }
}
