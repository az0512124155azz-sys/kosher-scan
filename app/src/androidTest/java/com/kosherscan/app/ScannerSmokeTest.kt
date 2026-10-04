package com.kosherscan.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScannerSmokeTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @Test fun bundledMlKitDecodesEan13WithoutModelDownload() {
        val code = "3017620422003"
        val matrix = MultiFormatWriter().encode(code, BarcodeFormat.EAN_13, 640, 320)
        val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        for (y in 0 until 320) for (x in 0 until 640) bitmap.setPixel(x, y, if (matrix[x,y]) Color.BLACK else Color.WHITE)
        val scanner = BarcodeScanning.getClient()
        try {
            val results = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS)
            assertTrue(results.any { it.rawValue == code })
        } finally { scanner.close(); bitmap.recycle() }
    }
    @Test fun threeStatusesRenderAndStateSurvivesRecreationThenResets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (status in KosherStatus.entries) {
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[ScanModel::class.java].state.value = ScanState("3017620422003", result = LookupResult(
                        Product("3017620422003", "מוצר בדיקה", "מותג בדיקה"), Verdict(status, "בדיקת תצוגה")))
                }
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.resultCard).visibility)
                    val title = activity.findViewById<TextView>(R.id.statusTitle).text.toString()
                    assertTrue(title.contains(when (status) { KosherStatus.KOSHER -> "✓"; KosherStatus.NOT_KOSHER -> "לא כשר"; KosherStatus.UNKNOWN -> "לא ידוע" }))
                    assertEquals("3017620422003", activity.findViewById<TextView>(R.id.productBarcode).text.toString())
                }
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(KosherStatus.UNKNOWN, ViewModelProvider(activity)[ScanModel::class.java].state.value.result!!.verdict.status)
                activity.findViewById<View>(R.id.scanAgainButton).performClick()
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity -> assertEquals(View.GONE, activity.findViewById<View>(R.id.resultCard).visibility) }
        }
    }
}
