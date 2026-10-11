package com.kosherscan.app

import android.Manifest
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneralApprovalSmokeTest {
    @get:Rule val camera = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @Test fun generalPermissionsReachTheNativeCardThroughTheApiParser() = runBlocking {
        val samples = listOf("en:ground-coffees" to "ground coffee", "en:green-teas" to "green tea",
            "en:honeys" to "honey", "en:cane-sugars" to "cane sugar", "en:sea-salts" to "sea salt",
            "en:cocoa-powders" to "cocoa powder", "en:corn-starches" to "corn starch", "en:waters" to "water")
        MockWebServer().use { server ->
            server.start()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: ScanModel
                scenario.onActivity { model = ViewModelProvider(it)[ScanModel::class.java] }
                for ((category, ingredient) in samples) {
                    val metadata = JSONObject().put("product_name", ingredient).put("brands", "Brand")
                        .put("ingredients_text", ingredient).put("categories_tags", org.json.JSONArray(listOf(category)))
                        .put("manufacturing_places_tags", org.json.JSONArray(listOf("en:france")))
                    server.enqueue(MockResponse().setBody(JSONObject().put("status", 1).put("product", metadata).toString()))
                    server.enqueue(MockResponse().setBody("{\"results\":[],\"total\":0}"))
                    val repo = ProductRepository(offBase = server.url("/").toString(), ouBase = server.url("/").toString(), enableOuFallback = false)
                    scenario.onActivity { model.reset(); model.lookup("12345678", repo) }
                    withTimeout(10000) { while (model.state.value.result == null) delay(50) }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    assertEquals(ingredient, KosherStatus.KOSHER, model.state.value.result!!.verdict.status)
                    assertEquals("General guidance", model.state.value.result!!.verdict.sourceLabel)
                    scenario.onActivity { assertTrue(it.findViewById<TextView>(R.id.statusTitle).text.contains("כשר")) }
                    assertTrue(ResultCopy.text(model.state.value.result!!).contains("מותר גם ללא סימון כשרות"))
                }
            }
        }
    }
}
