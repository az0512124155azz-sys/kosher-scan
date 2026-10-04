package com.kosherscan.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var hintText: TextView
    private lateinit var loadingText: TextView
    private lateinit var resultCard: View
    private lateinit var productName: TextView
    private lateinit var productBrand: TextView
    private lateinit var productBarcode: TextView
    private lateinit var statusBox: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var scanAgainButton: MaterialButton

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageAnalysis: ImageAnalysis? = null
    private var busy = false
    private var lastCode = ""
    private var lastCodeAt = 0L

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_CODABAR
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showFatal("כדי לסרוק ברקודים צריך לאפשר גישה למצלמה.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        hintText = findViewById(R.id.hintText)
        loadingText = findViewById(R.id.loadingText)
        resultCard = findViewById(R.id.resultCard)
        productName = findViewById(R.id.productName)
        productBrand = findViewById(R.id.productBrand)
        productBarcode = findViewById(R.id.productBarcode)
        statusBox = findViewById(R.id.statusBox)
        statusTitle = findViewById(R.id.statusTitle)
        statusText = findViewById(R.id.statusText)
        scanAgainButton = findViewById(R.id.scanAgainButton)

        scanAgainButton.setOnClickListener { resetScanner() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    if (busy) {
                        proxy.close()
                        return@setAnalyzer
                    }

                    val mediaImage = proxy.image
                    if (mediaImage == null) {
                        proxy.close()
                        return@setAnalyzer
                    }

                    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val code = barcodes.firstOrNull()?.rawValue?.trim().orEmpty()
                            if (code.isNotBlank()) maybeHandleBarcode(code)
                        }
                        .addOnCompleteListener { proxy.close() }
                }

                imageAnalysis = analysis
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                showFatal("לא הצלחתי לפתוח את המצלמה. נסה לסגור אפליקציות אחרות שמשתמשות במצלמה.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun maybeHandleBarcode(code: String) {
        val now = System.currentTimeMillis()
        if (busy) return
        if (code == lastCode && now - lastCodeAt < 2500) return

        busy = true
        lastCode = code
        lastCodeAt = now

        runOnUiThread {
            loadingText.visibility = View.VISIBLE
            loadingText.text = "מזהה את המוצר…"
            hintText.visibility = View.INVISIBLE
        }

        lifecycleScope.launch {
            val product = withContext(Dispatchers.IO) { fetchProduct(code) }

            if (product == null) {
                showUnknownProduct(code, "המוצר לא נמצא ב-Open Food Facts.")
                return@launch
            }

            productName.text = product.name.ifBlank { "מוצר ללא שם" }
            productBrand.text = product.brand.ifBlank { "יצרן לא ידוע" }
            productBarcode.text = code
            resultCard.visibility = View.VISIBLE
            loadingText.text = "בודק כשרות מול OU…"

            val verdict = withContext(Dispatchers.IO) { resolveKosher(product) }
            showVerdict(verdict)
            loadingText.visibility = View.GONE
        }
    }

    private fun fetchProduct(code: String): Product? {
        val fields = listOf(
            "code", "product_name", "product_name_en", "product_name_he",
            "brands", "manufacturing_places", "countries", "ingredients_text",
            "labels", "labels_tags", "categories_tags"
        ).joinToString(",")

        val url = "https://world.openfoodfacts.org/api/v2/product/" +
            URLEncoder.encode(code, "UTF-8") + ".json?fields=" + URLEncoder.encode(fields, "UTF-8")

        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "KosherScan/1.0 (Android)")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                if (json.optInt("status") != 1) return null
                val p = json.optJSONObject("product") ?: return null

                val labelsArray = p.optJSONArray("labels_tags")
                val labels = buildList {
                    if (labelsArray != null) {
                        for (i in 0 until labelsArray.length()) add(labelsArray.optString(i))
                    }
                }

                Product(
                    barcode = code,
                    name = p.optString("product_name_he").ifBlank {
                        p.optString("product_name").ifBlank { p.optString("product_name_en") }
                    },
                    brand = p.optString("brands").substringBefore(",").trim(),
                    labels = labels,
                    labelsText = p.optString("labels"),
                    ingredients = p.optString("ingredients_text"),
                    countries = p.optString("countries"),
                    manufacturingPlaces = p.optString("manufacturing_places")
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveKosher(product: Product): Verdict {
        val labelText = normalize((product.labels + product.labelsText).joinToString(" "))
        if ("non kosher" in labelText || "not kosher" in labelText) {
            return Verdict.NotKosher("המוצר מסומן במפורש כלא כשר ב-Open Food Facts.")
        }

        val offKosher = "kosher" in labelText || "כשר" in labelText

        val queries = listOf(
            listOf(product.brand, product.name).filter { it.isNotBlank() }.joinToString(" "),
            listOf(product.brand, product.nameWords().take(5).joinToString(" "))
                .filter { it.isNotBlank() }.joinToString(" ")
        ).distinct().filter { it.isNotBlank() }

        for (query in queries) {
            val ou = searchOu(query, product)
            if (ou != null) return ou
        }

        if (offKosher) {
            return Verdict.Kosher("המוצר מסומן ככשר ב-Open Food Facts.")
        }

        return Verdict.Unknown("לא נמצאה התאמה מאומתת במאגר OU או ב-Open Food Facts.")
    }

    private fun searchOu(query: String, product: Product): Verdict? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://oukosher.org/search/?q=$encoded"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/html")
                .header("User-Agent", "Mozilla/5.0 KosherScan/1.0")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string().orEmpty()
                if (html.length < 400) return null

                val text = Jsoup.parse(html).text()
                val normalized = normalize(text)
                val brand = normalize(product.brand)
                val nameWords = product.nameWords()

                if (brand.isBlank() || nameWords.isEmpty()) return null
                if (!normalized.contains(brand)) return null

                val matched = nameWords.count { normalized.contains(it) }
                val score = matched.toDouble() / max(1, nameWords.size)
                if (score < 0.60) return null

                val type = when {
                    Regex("\\bou[ -]?d\\b|\\bdairy\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                        "חלבי"
                    Regex("\\bpareve\\b|\\bparve\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                        "פרווה"
                    Regex("\\bmeat\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                        "בשרי"
                    else -> ""
                }

                val suffix = if (type.isBlank()) "" else " · $type"
                Verdict.Kosher("אומת מול מאגר OU Kosher$suffix")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showVerdict(verdict: Verdict) {
        when (verdict) {
            is Verdict.Kosher -> {
                statusBox.setBackgroundResource(R.drawable.status_kosher)
                statusTitle.text = "✅ כשר"
                statusTitle.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                statusText.text = verdict.reason
            }
            is Verdict.NotKosher -> {
                statusBox.setBackgroundResource(R.drawable.status_not_kosher)
                statusTitle.text = "❌ לא כשר"
                statusTitle.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                statusText.text = verdict.reason
            }
            is Verdict.Unknown -> {
                statusBox.setBackgroundResource(R.drawable.status_unknown)
                statusTitle.text = "❓ לא ידוע"
                statusTitle.setTextColor(0xFFFFE58F.toInt())
                statusText.text = verdict.reason
            }
        }
    }

    private fun showUnknownProduct(code: String, reason: String) {
        productName.text = "מוצר חדש"
        productBrand.text = "לא נמצא במאגר המוצרים"
        productBarcode.text = code
        resultCard.visibility = View.VISIBLE
        loadingText.visibility = View.GONE
        showVerdict(Verdict.Unknown(reason))
    }

    private fun resetScanner() {
        resultCard.visibility = View.GONE
        loadingText.visibility = View.GONE
        hintText.visibility = View.VISIBLE
        hintText.text = "כוון את הברקוד למרכז המסגרת"
        lastCode = ""
        busy = false
    }

    private fun showFatal(message: String) {
        loadingText.visibility = View.VISIBLE
        loadingText.text = message
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace("&amp;", "&")
            .replace(Regex("[^a-z0-9א-ת]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun Product.nameWords(): List<String> {
        val stop = setOf("the", "and", "with", "for", "from", "original", "flavor", "flavoured", "flavored")
        return normalize(name).split(" ")
            .filter { it.length > 2 && it !in stop }
            .take(8)
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.close()
        cameraExecutor.shutdown()
    }

    data class Product(
        val barcode: String,
        val name: String,
        val brand: String,
        val labels: List<String>,
        val labelsText: String,
        val ingredients: String,
        val countries: String,
        val manufacturingPlaces: String
    )

    sealed class Verdict(open val reason: String) {
        data class Kosher(override val reason: String) : Verdict(reason)
        data class NotKosher(override val reason: String) : Verdict(reason)
        data class Unknown(override val reason: String) : Verdict(reason)
    }
}
