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
    val loadingMessage: String = "בודק במאגרי כשרות", val agentMessage: String = "")
class ScanModel : ViewModel() {
    val state = MutableStateFlow(ScanState())
    private var job: Job? = null
    private var agent: AgentApi? = null
    private var agentMarket = "IL"
    fun lookup(code: String, repository: ProductLookup, loadingMessage: String = "בודק במאגרי כשרות",
        agentApi: AgentApi? = null, outbox: AgentOutbox? = null, market: String = "IL", photo: ByteArray? = null) {
        if (state.value.loading) return
        job?.cancel(); agent = agentApi; agentMarket = market
        state.value = ScanState(code, true, loadingMessage = loadingMessage)
        job = viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            val result = repository.lookup(code)
            android.util.Log.d("KosherScan", "Lookup completed in ${android.os.SystemClock.elapsedRealtime() - started} ms; source=${result.verdict.sourceLabel}; status=${result.verdict.status}")
            val sent = if (result.verdict.status == KosherStatus.UNKNOWN) try { outbox?.enqueue(code, market, result, photo) } catch (_: java.io.IOException) { null } else null
            state.value = ScanState(code, result = result, agentMessage = if (sent != null) "נשמר לבדיקה נוספת" else "")
            if (sent != null && agentApi != null) repeat(6) {
                kotlinx.coroutines.delay(5000)
                if (state.value.code != code) return@launch
                when (outbox?.state(sent)) {
                    androidx.work.WorkInfo.State.SUCCEEDED -> state.value = state.value.copy(agentMessage = "נשלח לבדיקה נוספת")
                    androidx.work.WorkInfo.State.FAILED, androidx.work.WorkInfo.State.CANCELLED -> {
                        state.value = state.value.copy(agentMessage = "לא ניתן לשלוח לבדיקה נוספת")
                        return@launch
                    }
                    else -> Unit
                }
                val updated = agentApi.result(code, market)
                if (updated != null) {
                    state.value = state.value.copy(result = AgentAwareLookup.combine(result, updated), agentMessage = "")
                    return@launch
                }
            }
        }
    }
    fun refreshAgent() {
        val api = agent ?: return
        val snapshot = state.value
        job?.cancel()
        job = viewModelScope.launch {
            val result = api.result(snapshot.code, agentMarket)
            if (state.value.code == snapshot.code) state.value = snapshot.copy(
                result = result?.let { AgentAwareLookup.combine(snapshot.result!!, it) } ?: snapshot.result,
                agentMessage = if (result != null) "" else "עדיין אין תשובה מאומתת")
        }
    }
    fun reset() { job?.cancel(); state.value = ScanState() }
}

class MainActivity : AppCompatActivity() {
    private lateinit var agentPreferences: android.content.SharedPreferences
    private lateinit var marketProvider: () -> String
    private fun agentConnection() = AgentConnection(agentPreferences.getString("url", "").orEmpty(), agentPreferences.getString("token", "").orEmpty())
    private fun agentApi(): AgentApi? = agentConnection().takeIf { it.valid(BuildConfig.DEBUG) }?.let { AgentApi(it) }
    private fun scan(code: String, selectedRepository: ProductLookup = repository, photo: ByteArray? = null) {
        val connection = agentConnection().takeIf { it.valid(BuildConfig.DEBUG) }
        model.lookup(code, selectedRepository, agentApi = connection?.let { AgentApi(it) },
            outbox = connection?.let { AgentOutbox(applicationContext, it) }, market = marketProvider(), photo = photo)
    }
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
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        model = ViewModelProvider(this)[ScanModel::class.java]
        val preferences = getSharedPreferences("market", MODE_PRIVATE)
        fun market() = preferences.getString("country", "IL") ?: "IL"
        marketProvider = ::market
        agentPreferences = getSharedPreferences("agent", MODE_PRIVATE)
        findViewById<View>(R.id.agentSettingsButton).setOnClickListener { if (!model.state.value.loading) showAgentSettings() }
        findViewById<View>(R.id.agentRefreshButton).setOnClickListener { model.refreshAgent() }
        val marketButton = findViewById<TextView>(R.id.marketButton)
        fun showMarket() { marketButton.text = when (market()) { "IL" -> "מדינת רכישה: ישראל ▾"; "GB" -> "מדינת רכישה: בריטניה ▾"; else -> "מדינת רכישה: אחרת ▾" } }
        showMarket()
        marketButton.setOnClickListener {
            if (!model.state.value.loading) androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("איפה נקנה המוצר?").setItems(arrayOf("ישראל", "בריטניה", "מדינה אחרת")) { _, position ->
                    preferences.edit().putString("country", listOf("IL", "GB", "OTHER")[position]).apply()
                    showMarket(); model.reset()
                }.show()
        }
        repository = AgentAwareLookup(ProductRepository(barcodeLookup = IkrRepository(), additionalLookup = AuthoritySources(market = ::market,
            diagnostic = { android.util.Log.d("KosherScan", "Authority: $it") }), onOuEvent = {
            android.util.Log.d("KosherScan", "OU: $it")
        }, hasNetwork = {
            val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }), ::agentApi, ::market)
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
            scan(model.state.value.code,
                if (extended) (repository as? AgentAwareLookup)?.extended() ?: repository else repository)
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
                                    codes.firstOrNull { it.rawValue?.matches(Regex("[0-9]{8,14}")) == true }?.let { barcode ->
                                        if (busy.compareAndSet(false, true)) scan(barcode.rawValue!!, photo =
                                            if (agentConnection().valid(BuildConfig.DEBUG)) BarcodePhoto.capture(proxy, barcode.boundingBox) else null)
                                    }
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
            else { busy.set(true); scan(code); dialog.dismiss() }
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
        findViewById<TextView>(R.id.agentStatusText).apply { visibility = if (s.agentMessage.isNotBlank()) View.VISIBLE else View.GONE; text = s.agentMessage }
        findViewById<View>(R.id.agentRefreshButton).visibility = if (s.agentMessage.isNotBlank()) View.VISIBLE else View.GONE
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
    private fun showAgentSettings() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_agent)
        val url = dialog.findViewById<EditText>(R.id.agentUrl)
        val token = dialog.findViewById<EditText>(R.id.agentToken)
        val error = dialog.findViewById<TextView>(R.id.agentSettingsError)
        url.setText(agentConnection().url); token.setText(agentConnection().token)
        dialog.findViewById<View>(R.id.agentSave).setOnClickListener {
            val connection = AgentConnection(url.text.toString().trim().trimEnd('/') + "/", token.text.toString().trim())
            if (!connection.valid(BuildConfig.DEBUG)) error.text = "יש להזין כתובת מאובטחת וקוד חיבור תקין"
            else { agentPreferences.edit().putString("url", connection.url).putString("token", connection.token).apply(); model.reset(); dialog.dismiss() }
        }
        dialog.findViewById<View>(R.id.agentDisconnect).setOnClickListener {
            androidx.work.WorkManager.getInstance(applicationContext).cancelAllWorkByTag("agent-upload")
            agentPreferences.edit().clear().apply()
            java.io.File(noBackupFilesDir, "agent-outbox").listFiles()?.forEach { it.delete() }
            model.reset(); dialog.dismiss()
        }
        dialog.findViewById<View>(R.id.agentCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels - 48 * resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}
