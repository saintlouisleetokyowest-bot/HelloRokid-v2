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
import com.example.hellorokid.shared.data.BusinessCard
import kotlinx.coroutines.launch

/**
 * Rokid 眼镜端：拍照并通过 BLE 发送给手机
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RokidGlassMain"
    }

    private enum class ScanState {
        READY, SCANNING, RESULT, CONNECTING
    }

    private lateinit var readyHint: TextView
    private lateinit var scanningHint: TextView
    private lateinit var resultPanel: View
    private lateinit var statusText: TextView
    private lateinit var scrollView: ScrollView

    private lateinit var personNameText: TextView
    private lateinit var personTitleText: TextView
    private lateinit var companyNameText: TextView

    private var currentState = ScanState.READY
    private val handler = Handler(Looper.getMainLooper())

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
        renderState(ScanState.READY)
    }

    private fun initViews() {
        readyHint = findViewById(R.id.readyHint)
        scanningHint = findViewById(R.id.scanningHint)
        resultPanel = findViewById(R.id.resultPanel)
        statusText = findViewById(R.id.statusText)
        scrollView = findViewById(R.id.scrollView)

        personNameText = findViewById(R.id.personNameText)
        personTitleText = findViewById(R.id.personTitleText)
        companyNameText = findViewById(R.id.companyNameText)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "Key pressed: $keyCode")

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                when (currentState) {
                    ScanState.READY -> checkPermissionsAndScan()
                    ScanState.SCANNING -> {}
                    ScanState.RESULT -> renderState(ScanState.READY)
                    ScanState.CONNECTING -> {}
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

        lifecycleScope.launch {
            try {
                // TODO: 调用 RokidCameraManager 拍照，通过 BLE 发送到手机端
                showToast("扫描成功！（测试数据）")
                val testCard = BusinessCard(
                    name = "张三",
                    title = "CEO",
                    company = "未来科技",
                    phone = "13800138000",
                    email = "zhangsan@example.com"
                )
                updateResultUI(testCard)
                renderState(ScanState.RESULT)
            } catch (e: Exception) {
                Log.e(TAG, "Camera failed", e)
                showToast("扫描失败: ${e.message}")
                renderState(ScanState.READY)
            }
        }
    }

    private fun updateResultUI(card: BusinessCard) {
        personNameText.text = "• ${card.name}"
        personTitleText.text = "• ${card.title}"
        companyNameText.text = "• ${card.company}"
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
                resultPanel.visibility = View.GONE
                statusText.setText(R.string.status_ready)
            }
            ScanState.SCANNING -> {
                readyHint.visibility = View.GONE
                scanningHint.visibility = View.VISIBLE
                resultPanel.visibility = View.GONE
                statusText.setText(R.string.status_scanning)
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
