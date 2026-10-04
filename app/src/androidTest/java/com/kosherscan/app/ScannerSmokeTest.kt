package com.kosherscan.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.filters.SdkSuppress
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
    @Test fun resultCardDoesNotClipScanAgainAfterTextChanges() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (reason in listOf("בדיקת תצוגה", "לא נמצאה התאמה חד־משמעית ב־OU. היעדר התאמה אינו מעיד שהמוצר אינו כשר.")) {
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[ScanModel::class.java].state.value = ScanState("3017620422003", result = LookupResult(
                        Product("3017620422003", "Nutella", "Nutella, Ferrero"), Verdict(KosherStatus.UNKNOWN, reason)))
                }
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.resultCard).apply { animate().cancel(); translationY = 0f }
                    val button = activity.findViewById<View>(R.id.scanAgainButton)
                    val visible = Rect()
                    assertTrue(button.getGlobalVisibleRect(visible))
                    assertEquals("Scan-again button must be fully visible", button.height, visible.height())
                }
            }
        }
    }
    @Test @SdkSuppress(minSdkVersion = 29)
    fun customBarcodeDialogValidatesAndCancels() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.findViewById<View>(R.id.manualButton).performClick() }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val dialogRoot = WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.barcodeInput) != null }
                assertFalse(activity.findViewById<ScanOverlay>(R.id.scanFrame).animating)
                dialogRoot.findViewById<EditText>(R.id.barcodeInput).setText("123")
                dialogRoot.findViewById<View>(R.id.barcodeSubmit).performClick()
                assertEquals("יש להזין 8 עד 14 ספרות", dialogRoot.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.barcodeInputLayout).error.toString())
                dialogRoot.findViewById<View>(R.id.barcodeCancel).performClick()
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.manualButton).isShown)
                assertTrue(activity.findViewById<ScanOverlay>(R.id.scanFrame).animating)
            }
        }
    }
}
