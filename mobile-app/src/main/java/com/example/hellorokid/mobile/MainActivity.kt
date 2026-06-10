package com.example.hellorokid.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 手机端：接收眼镜端的图片，调用后端 API 分析，存储数据
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MobileMain"
    }

    private lateinit var statusText: TextView
    private lateinit var scanBtn: Button
    private lateinit var exportVCardBtn: Button
    private lateinit var exportCsvBtn: Button
    private lateinit var progressBar: ProgressBar

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startBleScan()
        } else {
            showToast("需要蓝牙权限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        scanBtn = findViewById(R.id.scanBtn)
        exportVCardBtn = findViewById(R.id.exportVCardBtn)
        exportCsvBtn = findViewById(R.id.exportCsvBtn)
        progressBar = findViewById(R.id.progressBar)

        scanBtn.setOnClickListener { checkPermissionsAndScan() }
        exportVCardBtn.setOnClickListener { exportToVCard() }
        exportCsvBtn.setOnClickListener { exportToCsv() }
    }

    private fun checkPermissionsAndScan() {
        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (neededPermissions.isEmpty()) {
            startBleScan()
        } else {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    private fun startBleScan() {
        statusText.text = "正在扫描 Rokid Glass..."
        showToast("开始扫描（模拟）")

        // TODO: 启动 BLE Central 扫描，使用 shared 模块中的 BleProtocol 常量
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            statusText.text = "已连接到 Rokid Glass"
            showToast("已连接！")
        }
    }

    private fun exportToVCard() {
        showToast("导出 vCard（功能待实现）")
    }

    private fun exportToCsv() {
        showToast("导出 CSV（功能待实现）")
    }

    private fun showToast(message: String) {
        Log.d(TAG, "Toast: $message")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
