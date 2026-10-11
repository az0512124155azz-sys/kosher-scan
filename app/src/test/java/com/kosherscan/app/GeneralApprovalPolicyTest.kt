package com.kosherscan.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeneralApprovalPolicyTest {
    private val samples = listOf(
        "en:ground-coffees" to "ground coffee", "en:green-teas" to "green tea",
        "en:honeys" to "honey", "en:cane-sugars" to "cane sugar",
        "en:sea-salts" to "sea salt", "en:cocoa-powders" to "cocoa powder",
        "en:corn-starches" to "corn starch")
    private fun product(category: String, ingredient: String) = Product("12345678", ingredient, "Brand",
        categories = listOf(category), ingredients = ingredient, manufacturingPlaces = listOf("en:france"))
    @Test fun eachPublishedPermissionWorksWithoutACertificateLabel() {
        for ((category, ingredient) in samples) {
            val p = product(category, ingredient)
            val verdict = KosherPolicy.resolve(p, emptyList())
            assertEquals(ingredient, KosherStatus.KOSHER, verdict.status)
            assertEquals("General guidance", verdict.sourceLabel)
            assertEquals(GeneralApprovalPolicy.STAR_URL, verdict.sourceUrl)
            assertFalse(ResultCopy.text(LookupResult(p, verdict)).contains("יש לבדוק"))
            if (ingredient == "ground coffee") {
                assertNotNull(GeneralApprovalPolicy.resolve(p.copy(name = "House blend coffee")))
                assertNotNull(GeneralApprovalPolicy.resolve(p.copy(name = "תערובת קפה")))
                assertNull(GeneralApprovalPolicy.resolve(p.copy(name = "House blend coffee", ingredients = "coffee, milk")))
            }
        }
    }
    @Test fun missingOrConflictingMetadataNeverInventsGeneralPermission() {
        for ((category, ingredient) in samples) {
            val p = product(category, ingredient)
            val invalid = listOf(p.copy(ingredients = ""), p.copy(categories = emptyList()),
                p.copy(ingredients = "$ingredient, natural flavor"), p.copy(englishIngredients = "$ingredient, milk"),
                p.copy(manufacturingPlaces = emptyList()), p.copy(manufacturingPlaces = listOf("en:israel")),
                p.copy(origins = listOf("en:israel")), p.copy(origins = listOf("en:france"), manufacturingPlaces = emptyList()),
                p.copy(manufacturingPlaces = listOf("en:world")),
                p.copy(name = p.name + " vanilla"), p.copy(categories = p.categories + "en:ready-to-drink-beverages"))
            for (variant in invalid) assertNull("$ingredient: $variant", GeneralApprovalPolicy.resolve(variant))
        }
    }
    @Test fun labelOrSpecificAuthorityRejectionWinsOverGeneralPermission() {
        val p = product("en:ground-coffees", "ground coffee")
        assertEquals(KosherStatus.NOT_KOSHER, KosherPolicy.resolve(p.copy(labels = listOf("en:not-kosher")), emptyList()).status)
        val general = LookupResult(p, GeneralApprovalPolicy.resolve(p)!!)
        val negative = LookupResult(p, Verdict(KosherStatus.NOT_KOSHER, "explicit rejection", sourceLabel = "OK"))
        assertEquals(KosherStatus.NOT_KOSHER, DecisionEngine.resolve(p, listOf(general, negative)).verdict.status)
        val restriction = LookupResult(p, Verdict(KosherStatus.UNKNOWN, "specific restriction", blocksGeneralApproval = true))
        assertEquals(KosherStatus.UNKNOWN, DecisionEngine.resolve(p, listOf(general, restriction)).verdict.status)
        val unrelated = LookupResult(p, Verdict(KosherStatus.UNKNOWN, "no record"))
        val merged = AuthoritySources.merge(p, listOf(restriction, unrelated))
        assertTrue(merged.verdict.blocksGeneralApproval)
        assertEquals(KosherStatus.UNKNOWN, DecisionEngine.resolve(p, listOf(general, merged)).verdict.status)
    }
    @Test fun processedFoodAndVeganLabelsAreNotEquivalentToAnAuthorityPermission() {
        for (name in listOf("cookies", "chocolate", "wine", "gelatin", "yogurt", "juice", "vegan burger"))
            assertNull(GeneralApprovalPolicy.resolve(product("en:plant-based-foods", name).copy(labels = listOf("en:vegan"))))
    }
    @Test fun teaLeavesAreDifferentFromBottledOrFlavouredTea() {
        val p = product("en:green-teas", "green tea")
        for (name in listOf("Earl Grey", "Chai", "Iced tea", "Rooibos", "Herbal tea", "תה בטעם לימון"))
            assertNull(GeneralApprovalPolicy.resolve(p.copy(name = name)))
        assertNull(GeneralApprovalPolicy.resolve(p.copy(ingredients = "green tea, water")))
    }
    @Test fun theActualApiParserFeedsEveryRuleAndKeepsKnownResultsOnSourceFailure() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for ((category, ingredient) in samples) {
                val p = JSONObject().put("product_name", ingredient).put("brands", "Brand")
                    .put("ingredients_text", ingredient).put("categories_tags", listOf(category))
                    .put("manufacturing_places_tags", listOf("en:france"))
                server.enqueue(MockResponse().setBody(JSONObject().put("status", 1).put("product", p).toString()))
                server.enqueue(MockResponse().setResponseCode(503))
                val repo = ProductRepository(offBase = server.url("/").toString(), ouBase = server.url("/").toString(), enableOuFallback = false)
                val result = repo.lookup("12345678")
                assertEquals(ingredient, KosherStatus.KOSHER, result.verdict.status)
                assertNull(result.issue)
                val fields = server.takeRequest().requestUrl!!.queryParameter("fields")!!
                assertTrue(fields.contains("manufacturing_places_tags")); server.takeRequest()
            }
        }
    }
}
