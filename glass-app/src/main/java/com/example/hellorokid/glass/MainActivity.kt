package com.example.hellorokid.glass

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.hellorokid.glass.ble.GlassBleServer
import com.example.hellorokid.glass.camera.FramingCameraController
import com.example.hellorokid.glass.camera.RokidCameraManager
import com.example.hellorokid.glass.ui.CardFramingOverlay
import com.example.hellorokid.shared.camera.CameraCropMapper
import com.example.hellorokid.shared.data.BusinessCard
import com.example.hellorokid.shared.image.ImageBleProcessor
import com.example.hellorokid.shared.image.NormalizedRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rokid 眼镜端：取景 preview + 裁切传图 + BLE 发送 + AI 结果展示
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RokidGlassMain"
        /** 定焦镜头：等待预览曝光稳定后再拍（非 AF） */
        private const val FRAMING_READY_MS = 800L
    }

    private enum class ScanState {
        READY, FRAMING, SCANNING, WAITING, RESULT, CONNECTING
    }

    private lateinit var framingContainer: View
    private lateinit var previewView: TextureView
    private lateinit var framingOverlay: CardFramingOverlay
    private lateinit var framingHint: TextView
    private lateinit var contentPanel: View
    private lateinit var readyHint: TextView
    private lateinit var scanningHint: TextView
    private lateinit var resultPanel: View
    private lateinit var statusText: TextView
    private lateinit var scrollView: ScrollView

    private lateinit var personNameText: TextView
    private lateinit var personTitleText: TextView
    private lateinit var personAuthorityText: TextView
    private lateinit var companyNameText: TextView
    private lateinit var companyIndustryText: TextView
    private lateinit var companySizeText: TextView
    private lateinit var companyRevenueText: TextView
    private lateinit var businessCoreText: TextView
    private lateinit var businessMarketsText: TextView
    private lateinit var businessPartnersText: TextView
    private lateinit var oppNeedsText: TextView
    private lateinit var oppInvestmentText: TextView
    private lateinit var oppTimingText: TextView

    private var currentState = ScanState.READY
    private var pendingEnterFraming = false
    private var previewReady = false
    private var framingEnteredAtMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bleServer: GlassBleServer
    private val framingCamera by lazy { FramingCameraController(this) }
    private val fallbackCamera by lazy { RokidCameraManager(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            enterFraming()
        } else {
            showToast("需要相机和蓝牙权限")
        }
    }

    private val previewListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (pendingEnterFraming) {
                pendingEnterFraming = false
                startFramingSession()
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            if (currentState == ScanState.FRAMING) {
                lifecycleScope.launch {
                    framingCamera.applyPreviewTransform(previewView)
                    updateFramingOverlayForPreview()
                }
            }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            lifecycleScope.launch { framingCamera.stop() }
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        setupBleServer()
        renderState(ScanState.READY)
    }

    override fun onDestroy() {
        lifecycleScope.launch { framingCamera.stop() }
        bleServer.stop()
        super.onDestroy()
    }

    private fun setupBleServer() {
        bleServer = GlassBleServer(this)
        bleServer.setListener(object : GlassBleServer.Listener {
            override fun onAdvertisingStarted() {
                showToast("等待手机连接…")
            }

            override fun onAdvertisingFailed(errorCode: Int) {
                showToast("蓝牙广播失败 ($errorCode)")
            }

            override fun onClientConnected() {
                showToast("手机已连接")
            }

            override fun onClientDisconnected() {
                showToast("手机已断开")
                if (currentState == ScanState.WAITING || currentState == ScanState.FRAMING) {
                    lifecycleScope.launch {
                        framingCamera.stop()
                        renderState(ScanState.READY)
                    }
                }
            }

            override fun onCardResultReceived(card: BusinessCard) {
                runOnUiThread {
                    val isFirstResult = currentState != ScanState.RESULT
                    val isPartial = isPartialResult(card)
                    updateResultUI(card, scrollToTop = isFirstResult)
                    renderState(ScanState.RESULT)
                    if (isFirstResult) {
                        statusText.setText(
                            if (isPartial) R.string.status_result_partial
                            else R.string.status_result
                        )
                        showToast(
                            if (isPartial) getString(R.string.toast_contact_ready)
                            else getString(R.string.toast_analysis_complete)
                        )
                    } else if (!isPartial) {
                        statusText.setText(R.string.status_result)
                        showToast(getString(R.string.toast_intel_updated))
                    }
                }
            }

            override fun onCardResultError(message: String) {
                runOnUiThread {
                    showToast("分析失败: $message")
                    lifecycleScope.launch {
                        framingCamera.stop()
                        renderState(ScanState.READY)
                    }
                }
            }
        })
        if (!bleServer.start()) {
            showToast("蓝牙启动失败，请检查蓝牙是否开启")
        }
    }

    private fun initViews() {
        framingContainer = findViewById(R.id.framingContainer)
        previewView = findViewById(R.id.previewView)
        framingOverlay = findViewById(R.id.framingOverlay)
        framingHint = findViewById(R.id.framingHint)
        contentPanel = findViewById(R.id.contentPanel)
        readyHint = findViewById(R.id.readyHint)
        scanningHint = findViewById(R.id.scanningHint)
        resultPanel = findViewById(R.id.resultPanel)
        statusText = findViewById(R.id.statusText)
        scrollView = findViewById(R.id.scrollView)

        personNameText = findViewById(R.id.personNameText)
        personTitleText = findViewById(R.id.personTitleText)
        personAuthorityText = findViewById(R.id.personAuthorityText)
        companyNameText = findViewById(R.id.companyNameText)
        companyIndustryText = findViewById(R.id.companyIndustryText)
        companySizeText = findViewById(R.id.companySizeText)
        companyRevenueText = findViewById(R.id.companyRevenueText)
        businessCoreText = findViewById(R.id.businessCoreText)
        businessMarketsText = findViewById(R.id.businessMarketsText)
        businessPartnersText = findViewById(R.id.businessPartnersText)
        oppNeedsText = findViewById(R.id.oppNeedsText)
        oppInvestmentText = findViewById(R.id.oppInvestmentText)
        oppTimingText = findViewById(R.id.oppTimingText)

        previewView.surfaceTextureListener = previewListener
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "Key pressed: $keyCode")

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                when (currentState) {
                    ScanState.READY -> checkPermissionsAndEnterFraming()
                    ScanState.FRAMING -> captureAndSend()
                    ScanState.SCANNING, ScanState.WAITING, ScanState.CONNECTING -> {}
                    ScanState.RESULT -> {
                        lifecycleScope.launch {
                            framingCamera.stop()
                            renderState(ScanState.READY)
                        }
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (currentState == ScanState.FRAMING) {
                    cancelFraming()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (currentState != ScanState.FRAMING) {
                    scrollView.scrollBy(0, -100)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (currentState != ScanState.FRAMING) {
                    scrollView.scrollBy(0, 100)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun checkPermissionsAndEnterFraming() {
        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (neededPermissions.isEmpty()) {
            enterFraming()
        } else {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    private fun enterFraming() {
        if (!bleServer.isConnected()) {
            showToast("手机未连接，请先在手机端点击「连接眼镜」")
            return
        }

        previewReady = false
        framingEnteredAtMs = System.currentTimeMillis()
        renderState(ScanState.FRAMING)

        if (previewView.isAvailable) {
            startFramingSession()
        } else {
            pendingEnterFraming = true
        }
    }

    private fun startFramingSession() {
        lifecycleScope.launch {
            val result = framingCamera.startFraming(
                previewView,
                object : FramingCameraController.PreviewReadyListener {
                    override fun onPreviewReady(ready: Boolean) {
                        runOnUiThread {
                            if (currentState != ScanState.FRAMING) return@runOnUiThread
                            previewReady = ready
                            if (ready) {
                                statusText.setText(R.string.status_framing_ready)
                            } else {
                                statusText.setText(R.string.status_framing)
                            }
                        }
                    }
                }
            )

            result.onSuccess {
                updateFramingOverlayForPreview()
            }

            result.onFailure { error ->
                Log.e(TAG, "Framing session failed, fallback to direct capture", error)
                showToast("取景预览失败，使用直接拍照")
                framingCamera.stop()
                captureFallbackDirect()
            }
        }
    }

    private fun updateFramingOverlayForPreview() {
        val transform = framingCamera.getPreviewTransform()
        val rotation = transform?.rotationDegrees ?: 0
        val portraitPreview = rotation == 0 || rotation == 180 || previewView.height >= previewView.width
        framingOverlay.setPreviewPortrait(portraitPreview)
    }

    private fun cancelFraming() {
        pendingEnterFraming = false
        lifecycleScope.launch {
            framingCamera.stop()
            renderState(ScanState.READY)
        }
    }

    private fun captureAndSend() {
        val framingElapsed = System.currentTimeMillis() - framingEnteredAtMs
        val canCapture = previewReady || framingElapsed >= FRAMING_READY_MS
        if (!canCapture) {
            showToast(getString(R.string.toast_wait_stabilize))
            return
        }

        renderState(ScanState.SCANNING)

        lifecycleScope.launch {
            try {
                val viewNorm = framingOverlay.getNormalizedFramingRect()
                val captureResult = framingCamera.captureStill()
                val captureInfo = captureResult.getOrElse { error ->
                    Log.e(TAG, "Framed capture failed", error)
                    showToast("拍照失败: ${error.message ?: "未知错误"}")
                    framingCamera.stop()
                    renderState(ScanState.READY)
                    return@launch
                }

                framingCamera.stop()

                val cropRect = CameraCropMapper.mapNormalizedViewRectToCapture(
                    viewNorm = viewNorm,
                    viewWidth = previewView.width,
                    viewHeight = previewView.height,
                    previewTransform = captureInfo.previewTransform,
                    captureSize = captureInfo.captureSize
                )
                Log.i(TAG, "Crop rect: $cropRect (viewNorm=$viewNorm)")

                processAndSend(
                    captureInfo.jpegBytes,
                    cropRect,
                    captureInfo.previewTransform.rotationDegrees
                )
            } catch (e: Exception) {
                Log.e(TAG, "captureAndSend failed", e)
                framingCamera.stop()
                showToast("扫描失败: ${e.message}")
                renderState(ScanState.READY)
            }
        }
    }

    private fun captureFallbackDirect() {
        renderState(ScanState.SCANNING)
        lifecycleScope.launch {
            try {
                val captureResult = fallbackCamera.capturePhotoJpeg()
                val rawJpeg = captureResult.getOrElse { error ->
                    showToast("拍照失败: ${error.message}")
                    renderState(ScanState.READY)
                    return@launch
                }
                processAndSend(rawJpeg, cropRect = null)
            } catch (e: Exception) {
                showToast("扫描失败: ${e.message}")
                renderState(ScanState.READY)
            }
        }
    }

    private suspend fun processAndSend(
        rawJpeg: ByteArray,
        cropRect: NormalizedRect?,
        outputRotationDegrees: Int? = null
    ) {
        renderState(ScanState.CONNECTING)
        statusText.text = "处理中..."

        val processed = withContext(Dispatchers.Default) {
            ImageBleProcessor.prepareForBleTransfer(rawJpeg, cropRect, outputRotationDegrees)
        }

        statusText.text = "发送中..."
        showToast("发送中（${processed.outputSize / 1024}KB）…")

        val sendResult = bleServer.sendJpeg(processed.jpegBytes)
        sendResult.fold(
            onSuccess = {
                showToast("图片已发送，等待 AI 分析…")
                renderState(ScanState.WAITING)
            },
            onFailure = { error ->
                Log.e(TAG, "BLE send failed", error)
                showToast("发送失败: ${error.message}")
                renderState(ScanState.READY)
            }
        )
    }

    private fun isPartialResult(card: BusinessCard): Boolean {
        return card.industry.isBlank() &&
            card.companySize.isBlank() &&
            card.revenue.isBlank() &&
            card.coreBusiness.isBlank() &&
            card.markets.isBlank() &&
            card.partners.isBlank() &&
            card.opportunities.isBlank() &&
            card.investmentReadiness.isBlank() &&
            card.timing.isBlank()
    }

    private fun updateResultUI(card: BusinessCard, scrollToTop: Boolean = true) {
        val partial = isPartialResult(card)
        setField(personNameText, card.name, partial)
        setField(personTitleText, card.title, partial)
        val contact = listOf(card.phone, card.mobile, card.fax, card.email)
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        setField(personAuthorityText, contact, partial && contact.isBlank())
        setField(companyNameText, card.company, partial)
        setField(companyIndustryText, card.industry)
        setField(companySizeText, card.companySize)
        setField(companyRevenueText, card.revenue)
        setField(businessCoreText, card.coreBusiness)
        setField(businessMarketsText, card.markets)
        setField(businessPartnersText, card.partners)
        setField(oppNeedsText, card.opportunities)
        setField(oppInvestmentText, card.investmentReadiness)
        setField(oppTimingText, card.timing)
        if (scrollToTop) {
            scrollView.scrollTo(0, 0)
        }
    }

    private fun setField(view: TextView, value: String, showPending: Boolean = false) {
        if (value.isNotBlank()) {
            view.visibility = View.VISIBLE
            view.text = "• $value"
        } else if (showPending) {
            view.visibility = View.VISIBLE
            view.text = "• …"
        } else {
            view.visibility = View.GONE
        }
    }

    private fun showToast(message: String) {
        Log.d(TAG, "Toast: $message")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun renderState(state: ScanState) {
        if (state == ScanState.READY) {
            handler.removeCallbacksAndMessages(null)
            scrollView.scrollTo(0, 0)
            pendingEnterFraming = false
            previewReady = false
        }
        currentState = state
        when (state) {
            ScanState.READY -> {
                framingContainer.visibility = View.GONE
                contentPanel.visibility = View.VISIBLE
                contentPanel.alpha = 1f
                readyHint.visibility = View.VISIBLE
                scanningHint.visibility = View.GONE
                scanningHint.setText(R.string.hint_scanning)
                resultPanel.visibility = View.GONE
                statusText.setText(R.string.status_ready)
            }
            ScanState.FRAMING -> {
                framingContainer.visibility = View.VISIBLE
                contentPanel.visibility = View.GONE
                framingHint.setText(R.string.hint_framing)
                statusText.setText(R.string.status_framing)
            }
            ScanState.SCANNING -> {
                framingContainer.visibility = View.VISIBLE
                contentPanel.visibility = View.GONE
                framingHint.setText(R.string.hint_scanning)
                statusText.setText(R.string.status_scanning)
            }
            ScanState.WAITING -> {
                framingContainer.visibility = View.GONE
                contentPanel.visibility = View.VISIBLE
                readyHint.visibility = View.GONE
                scanningHint.visibility = View.VISIBLE
                resultPanel.visibility = View.GONE
                scanningHint.text = getString(R.string.hint_waiting_ai)
                statusText.setText(R.string.status_waiting_ai)
            }
            ScanState.RESULT -> {
                framingContainer.visibility = View.GONE
                contentPanel.visibility = View.VISIBLE
                readyHint.visibility = View.GONE
                scanningHint.visibility = View.GONE
                resultPanel.visibility = View.VISIBLE
                statusText.setText(R.string.status_result)
            }
            ScanState.CONNECTING -> {
                framingContainer.visibility = View.VISIBLE
                contentPanel.visibility = View.GONE
                statusText.text = "处理中..."
            }
        }
    }
}
