package com.example.hellorokid.glass

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
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
import com.example.hellorokid.glass.camera.RokidCameraManager
import com.example.hellorokid.shared.data.BusinessCard
import com.example.hellorokid.shared.image.ImageBleProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Rokid 眼镜端：拍照并通过 BLE 发送给手机，接收 AI 结果并展示
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RokidGlassMain"
    }

    private enum class ScanState {
        READY, SCANNING, WAITING, RESULT, CONNECTING
    }

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
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bleServer: GlassBleServer
    private val cameraManager by lazy { RokidCameraManager(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            openCamera()
        } else {
            showToast("需要相机和蓝牙权限")
        }
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
                if (currentState == ScanState.WAITING) {
                    renderState(ScanState.READY)
                }
            }

            override fun onCardResultReceived(card: BusinessCard) {
                runOnUiThread {
                    updateResultUI(card)
                    renderState(ScanState.RESULT)
                    showToast("AI 分析完成")
                }
            }

            override fun onCardResultError(message: String) {
                runOnUiThread {
                    showToast("分析失败: $message")
                    renderState(ScanState.READY)
                }
            }
        })
        if (!bleServer.start()) {
            showToast("蓝牙启动失败，请检查蓝牙是否开启")
        }
    }

    private fun initViews() {
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
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "Key pressed: $keyCode")

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                when (currentState) {
                    ScanState.READY -> checkPermissionsAndScan()
                    ScanState.SCANNING, ScanState.WAITING, ScanState.CONNECTING -> {}
                    ScanState.RESULT -> renderState(ScanState.READY)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                scrollView.scrollBy(0, -100)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                scrollView.scrollBy(0, 100)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun checkPermissionsAndScan() {
        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (neededPermissions.isEmpty()) {
            openCamera()
        } else {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    private fun openCamera() {
        renderState(ScanState.SCANNING)
        statusText.text = "拍照中，请保持稳定…"

        lifecycleScope.launch {
            try {
                if (!bleServer.isConnected()) {
                    showToast("手机未连接，请先在手机端点击「连接眼镜」")
                    renderState(ScanState.READY)
                    return@launch
                }

                val captureResult = cameraManager.capturePhotoJpeg()
                val rawJpeg = captureResult.getOrElse { error ->
                    Log.e(TAG, "Camera failed", error)
                    val msg = error.message ?: "未知错误"
                    showToast(
                        if (msg.contains("超时")) "拍照超时，请重试"
                        else "拍照失败: $msg"
                    )
                    renderState(ScanState.READY)
                    return@launch
                }

                renderState(ScanState.CONNECTING)
                statusText.text = "处理中..."

                val processed = withContext(Dispatchers.Default) {
                    ImageBleProcessor.prepareForBleTransfer(rawJpeg)
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
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                showToast("扫描失败: ${e.message}")
                renderState(ScanState.READY)
            }
        }
    }

    private fun updateResultUI(card: BusinessCard) {
        setField(personNameText, card.name)
        setField(personTitleText, card.title)
        val contact = listOf(card.phone, card.mobile, card.fax, card.email)
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        setField(personAuthorityText, contact)
        setField(companyNameText, card.company)
        setField(companyIndustryText, card.industry)
        setField(companySizeText, card.companySize)
        setField(companyRevenueText, card.revenue)
        setField(businessCoreText, card.coreBusiness)
        setField(businessMarketsText, card.markets)
        setField(businessPartnersText, card.partners)
        setField(oppNeedsText, card.opportunities)
        setField(oppInvestmentText, card.investmentReadiness)
        setField(oppTimingText, card.timing)
        scrollView.scrollTo(0, 0)
    }

    private fun setField(view: TextView, value: String) {
        if (value.isBlank()) {
            view.visibility = View.GONE
        } else {
            view.visibility = View.VISIBLE
            view.text = "• $value"
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
        }
        currentState = state
        when (state) {
            ScanState.READY -> {
                readyHint.visibility = View.VISIBLE
                scanningHint.visibility = View.GONE
                scanningHint.setText(R.string.hint_scanning)
                resultPanel.visibility = View.GONE
                statusText.setText(R.string.status_ready)
            }
            ScanState.SCANNING -> {
                readyHint.visibility = View.GONE
                scanningHint.visibility = View.VISIBLE
                scanningHint.setText(R.string.hint_scanning)
                resultPanel.visibility = View.GONE
                statusText.setText(R.string.status_scanning)
            }
            ScanState.WAITING -> {
                readyHint.visibility = View.GONE
                scanningHint.visibility = View.VISIBLE
                resultPanel.visibility = View.GONE
                scanningHint.text = getString(R.string.hint_waiting_ai)
                statusText.setText(R.string.status_waiting_ai)
            }
            ScanState.RESULT -> {
                readyHint.visibility = View.GONE
                scanningHint.visibility = View.GONE
                resultPanel.visibility = View.VISIBLE
                statusText.setText(R.string.status_result)
            }
            ScanState.CONNECTING -> {
                readyHint.visibility = View.GONE
                scanningHint.visibility = View.GONE
                resultPanel.visibility = View.GONE
                statusText.text = "连接中..."
            }
        }
    }
}
