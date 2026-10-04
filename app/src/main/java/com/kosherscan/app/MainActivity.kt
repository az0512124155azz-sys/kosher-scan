package com.kosherscan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.*
import coil.load
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ScanState(val code: String = "", val loading: Boolean = false, val result: LookupResult? = null,
    val loadingMessage: String = "בודק במאגרי כשרות")
class ScanModel : ViewModel() {
    val state = MutableStateFlow(ScanState())
    private var job: Job? = null
    fun lookup(code: String, repository: ProductLookup, loadingMessage: String = "בודק במאגרי כשרות") {
        if (state.value.loading) return
        state.value = ScanState(code, true, loadingMessage = loadingMessage)
        job = viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            val result = repository.lookup(code)
            android.util.Log.d("KosherScan", "Lookup completed in ${android.os.SystemClock.elapsedRealtime() - started} ms; source=${result.verdict.sourceLabel}; status=${result.verdict.status}")
            state.value = ScanState(code, result = result)
        }
    }
    fun reset() { job?.cancel(); state.value = ScanState() }
}

class MainActivity : AppCompatActivity() {
    private lateinit var model: ScanModel
    private lateinit var repository: ProductLookup
    private lateinit var preview: PreviewView
    private lateinit var overlay: ScanOverlay
    private lateinit var card: View
    private lateinit var errorPanel: View
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    @Volatile private var destroyed = false
    private var scannerFailures = 0
    private var cameraStarting = false
    private var needsSettings = false
    private var renderedImage = ""
    private var barcodeDialog: Dialog? = null
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
        Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
        Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_ITF).build())
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            needsSettings = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            cameraError("כדי לסרוק, יש לאפשר גישה למצלמה. אפשר גם להקליד ברקוד.")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        model = ViewModelProvider(this)[ScanModel::class.java]
        repository = ProductRepository(barcodeLookup = IkrRepository(), hasNetwork = {
            val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        })
        preview = findViewById(R.id.previewView)
        overlay = findViewById(R.id.scanFrame)
        card = findViewById(R.id.resultCard)
        errorPanel = findViewById(R.id.errorPanel)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.safeContent)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.scanAgainButton).setOnClickListener { model.reset() }
        findViewById<View>(R.id.retryLookupButton).setOnClickListener {
            val extended = model.state.value.result?.issue == LookupIssue.TIMEOUT
            model.lookup(model.state.value.code,
                if (extended) (repository as? ProductRepository)?.extended() ?: repository else repository)
        }
        findViewById<View>(R.id.manualButton).setOnClickListener { manualEntry() }
        findViewById<View>(R.id.cameraRetryButton).setOnClickListener {
            if (cameraGranted()) startCamera()
            else if (needsSettings) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            else permission.launch(Manifest.permission.CAMERA)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
        if (cameraGranted()) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }
    private fun cameraGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized) {
            overlay.animating = !busy.get()
            if (cameraGranted() && provider == null) startCamera()
            else if (!cameraGranted()) {
                provider?.unbindAll(); provider = null
                cameraError("יש לאפשר גישה למצלמה כדי לסרוק. אפשר גם להקליד ברקוד.")
            }
        }
    }
    override fun onPause() { overlay.animating = false; super.onPause() }
    override fun onStart() { super.onStart(); if (::overlay.isInitialized) overlay.animating = !busy.get() }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        if (cameraStarting || destroyed) return
        cameraStarting = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraStarting = false
            if (destroyed) return@addListener
            try {
                val cameraProvider = future.get()
                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analyzer = ImageAnalysis.Builder().setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analyzer.setAnalyzer(executor) { proxy ->
                    if (destroyed || busy.get() || !processing.compareAndSet(false, true)) { proxy.close(); return@setAnalyzer }
                    val media = proxy.image
                    if (media == null) { processing.set(false); proxy.close(); return@setAnalyzer }
                    try {
                        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { codes ->
                                scannerFailures = 0
                                if (!destroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                    codes.firstOrNull { it.rawValue?.matches(Regex("[0-9]{8,14}")) == true }
                                        ?.rawValue?.let { code -> if (busy.compareAndSet(false, true)) model.lookup(code, repository) }
                                }
                            }.addOnFailureListener {
                                if (!destroyed && ++scannerFailures >= 3) {
                                    analysis?.clearAnalyzer()
                                    cameraError("סורק הברקודים לא הצליח לפעול. נסו שוב או הקלידו ברקוד.")
                                }
                            }.addOnCompleteListener { proxy.close(); processing.set(false) }
                    } catch (_: Exception) { proxy.close(); processing.set(false) }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, cameraPreview, analyzer)
                provider = cameraProvider
                analysis = analyzer
                errorPanel.visibility = View.GONE
                overlay.visibility = View.VISIBLE
                findViewById<View>(R.id.hintText).visibility = View.VISIBLE
                needsSettings = false
            } catch (_: Exception) { cameraError("לא ניתן לפתוח את המצלמה. סגרו אפליקציות שמשתמשות בה ונסו שוב.") }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun cameraError(message: String) {
        errorPanel.visibility = View.VISIBLE
        findViewById<TextView>(R.id.cameraErrorText).text = message
        findViewById<TextView>(R.id.cameraRetryButton).text = if (needsSettings) "פתיחת הגדרות" else "ניסיון נוסף"
        overlay.visibility = View.INVISIBLE
        findViewById<View>(R.id.hintText).visibility = View.INVISIBLE
    }
    private fun manualEntry() {
        if (barcodeDialog?.isShowing == true) return
        busy.set(true)
        overlay.animating = false
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_barcode)
        val input = dialog.findViewById<EditText>(R.id.barcodeInput)
        val inputLayout = dialog.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.barcodeInputLayout)
        val submit = {
            val code = input.text.toString().trim()
            if (!code.matches(Regex("[0-9]{8,14}"))) inputLayout.error = "יש להזין 8 עד 14 ספרות"
            else { busy.set(true); model.lookup(code, repository); dialog.dismiss() }
        }
        dialog.findViewById<View>(R.id.barcodeSubmit).setOnClickListener { submit() }
        dialog.findViewById<View>(R.id.barcodeCancel).setOnClickListener { dialog.dismiss() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        barcodeDialog = dialog
        dialog.setOnDismissListener {
            barcodeDialog = null
            busy.set(model.state.value.loading || model.state.value.result != null)
            overlay.animating = !busy.get()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels - 48 * resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    private fun render(s: ScanState) {
        busy.set(s.loading || s.result != null)
        overlay.animating = !busy.get()
        overlay.alpha = if (busy.get()) 0.25f else 1f
        findViewById<View>(R.id.manualButton).isEnabled = !s.loading
        findViewById<View>(R.id.manualButton).visibility = if (s.result != null) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.hintText).visibility = if (busy.get() || errorPanel.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
        findViewById<TextView>(R.id.loadingText).apply { visibility = if (s.loading) View.VISIBLE else View.GONE; text = s.loadingMessage }
        val result = s.result
        if (result == null) { card.animate().cancel(); card.visibility = View.GONE; renderedImage = ""; return }
        findViewById<TextView>(R.id.productName).text = result.product?.name?.ifBlank { "מוצר ללא שם" } ?: "אין מידע על המוצר"
        findViewById<TextView>(R.id.productBrand).text = result.product?.brand?.ifBlank { "מותג לא ידוע" }.orEmpty()
        findViewById<TextView>(R.id.productBarcode).text = s.code
        val imageUrl = result.product?.imageUrl.orEmpty().takeIf { it.startsWith("https://") }.orEmpty()
        val image = findViewById<ImageView>(R.id.productImage)
        image.clipToOutline = true
        if (renderedImage != s.code + imageUrl) {
            renderedImage = s.code + imageUrl
            image.contentDescription = "תמונת ${result.product?.name ?: "מוצר"}"
            image.load(imageUrl.ifEmpty { null }) { crossfade(true); placeholder(R.drawable.ic_product); error(R.drawable.ic_product); fallback(R.drawable.ic_product) }
        }
        val (title, color, background) = when (result.verdict.status) {
            KosherStatus.KOSHER -> Triple("✓  כשר", 0xFF99F6C3.toInt(), R.drawable.status_kosher)
            KosherStatus.NOT_KOSHER -> Triple("×  לא כשר", 0xFFFFB0B0.toInt(), R.drawable.status_not_kosher)
            KosherStatus.UNKNOWN -> Triple("?  לא ידוע", 0xFFFFE590.toInt(), R.drawable.status_unknown)
        }
        findViewById<View>(R.id.statusBox).setBackgroundResource(background)
        findViewById<TextView>(R.id.statusTitle).apply { text = title; setTextColor(color) }
        findViewById<TextView>(R.id.statusText).text = ResultCopy.text(result)
        findViewById<View>(R.id.retryLookupButton).visibility = if (result.issue != null && result.issue != LookupIssue.NOT_FOUND) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.retryLookupButton).text = if (result.issue == LookupIssue.TIMEOUT) "בדיקה מעמיקה" else "ניסיון חוזר"
        if (card.visibility != View.VISIBLE) {
            card.visibility = View.VISIBLE; card.alpha = 0f
            card.post { if (card.visibility == View.VISIBLE) { card.translationY = card.height.toFloat() + 30; card.animate().translationY(0f).alpha(1f).setDuration(340).start() } }
        }
    }
    override fun onDestroy() {
        barcodeDialog?.dismiss()
        destroyed = true; analysis?.clearAnalyzer(); provider?.unbindAll(); executor.shutdown(); scanner.close()
        super.onDestroy()
    }
}
