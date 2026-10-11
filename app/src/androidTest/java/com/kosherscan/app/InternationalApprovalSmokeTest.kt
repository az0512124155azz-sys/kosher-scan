package com.kosherscan.app

import android.Manifest
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InternationalApprovalSmokeTest {
    @get:Rule val camera = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @Test fun processedProductWithoutCertificateReachesNativeCardFromDirectList(): Unit = runBlocking {
        val html = InstrumentationRegistry.getInstrumentation().context.assets.open("thai-local.html").bufferedReader().use { it.readText() }
        val row = AuthoritySources.parseTks(html).first { it.eligible && it.name.contains("syrup", true) }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/api/v2/product/") -> MockResponse().setBody(JSONObject().put("status", 1)
                        .put("product", JSONObject().put("product_name", row.name).put("brands", row.brand)).toString())
                    request.path!!.startsWith("/category.html/") -> MockResponse().setBody(html)
                    else -> MockResponse().setResponseCode(503)
                }
            }
            server.start()
            val base = server.url("/").toString()
            val repo = ProductRepository(offBase = base, ouBase = base, enableOuFallback = false,
                additionalLookup = AuthoritySources(market = { "TH" }, okBase = base, starBase = base, tksBase = base))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: ScanModel
                scenario.onActivity { model = ViewModelProvider(it)[ScanModel::class.java]; model.lookup("12345678", repo) }
                withTimeout(10000) { while (model.state.value.result == null) delay(50) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(KosherStatus.KOSHER, model.state.value.result!!.verdict.status)
                assertEquals("TKS", model.state.value.result!!.verdict.sourceLabel)
                assertEquals("מותר גם ללא סימון כשרות.", ResultCopy.text(model.state.value.result!!))
                scenario.onActivity { assertTrue(it.findViewById<TextView>(R.id.statusTitle).text.contains("כשר")) }
            }
        }
    }
}
