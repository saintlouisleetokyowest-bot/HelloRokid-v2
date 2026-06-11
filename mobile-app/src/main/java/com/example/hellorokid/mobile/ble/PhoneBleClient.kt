package com.example.hellorokid.mobile.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.hellorokid.shared.data.BusinessCard
import com.example.hellorokid.shared.data.BusinessCardJson
import com.example.hellorokid.shared.protocol.BleImageTransfer
import com.example.hellorokid.shared.protocol.BleProtocol
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 手机端 BLE Central：扫描 Rokid 眼镜、连接并接收 JPEG 图片。
 */
class PhoneBleClient(context: Context) {

    companion object {
        private const val TAG = "PhoneBleClient"
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val RECEIVE_TIMEOUT_MS = 45_000L
        private const val REQUESTED_MTU = 517
        private const val RESULT_WRITE_PACING_MS = 20L
    }

    interface Listener {
        fun onScanning()
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onImageReceiving()
        fun onImageReceived(bitmap: Bitmap, jpegBytes: ByteArray)
        fun onImageReceiveTimeout()
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var listener: Listener? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var imageCharacteristic: BluetoothGattCharacteristic? = null
    private var resultCharacteristic: BluetoothGattCharacteristic? = null
    private val imageReceiver = BleImageTransfer.ImageReceiver()

    private var isScanning = false
    private var notificationsEnabled = false
    private val isSendingResult = AtomicBoolean(false)
    @Volatile
    private var negotiatedMtu = 23
    @Volatile
    private var pendingWrite: CompletableDeferred<Int>? = null
    @Volatile
    private var receiveChunkCount = 0

    /** 不在 GATT 回调线程解码 JPEG，否则会阻塞后续通知导致丢包 */
    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BleImageDecode").apply { isDaemon = true }
    }

    private val receiveTimeoutRunnable = Runnable {
        Log.w(TAG, "Image receive timeout, resetting receiver")
        imageReceiver.reset()
        receiveChunkCount = 0
        dispatchOnMain { listener?.onImageReceiveTimeout() }
    }

    private val mtuFallbackRunnable = Runnable {
        val gatt = bluetoothGatt ?: return@Runnable
        if (!notificationsEnabled) {
            Log.w(TAG, "MTU callback timeout, enabling notifications anyway")
            enableImageNotifications(gatt)
        }
    }

    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            stopScan()
            notifyError("扫描超时，请确认眼镜端 App 已打开且蓝牙已开启")
        }
    }

    private fun dispatchOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: ""
            Log.d(TAG, "Found device: name=$name address=${device.address}")

            val matchesPrefix = name.startsWith(BleProtocol.DEVICE_NAME_PREFIX)
            val hasService = result.scanRecord?.serviceUuids?.any {
                it.uuid.toString().equals(BleProtocol.SERVICE_UUID, ignoreCase = true)
            } == true

            if (!matchesPrefix && !hasService) return

            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            isScanning = false
            mainHandler.removeCallbacks(scanTimeoutRunnable)
            notifyError("蓝牙扫描失败 (code=$errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, discovering services")
                    notificationsEnabled = false
                    gatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected")
                    cleanupGatt()
                    dispatchOnMain { listener?.onDisconnected() }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("服务发现失败 (code=$status)")
                disconnect()
                return
            }

            val service = gatt.getService(UUID.fromString(BleProtocol.SERVICE_UUID))
            if (service == null) {
                notifyError("未找到 Rokid BLE 服务")
                disconnect()
                return
            }

            imageCharacteristic = service.getCharacteristic(
                UUID.fromString(BleProtocol.CHARACTERISTIC_IMAGE_UUID)
            )
            if (imageCharacteristic == null) {
                notifyError("未找到图片特征值")
                disconnect()
                return
            }

            resultCharacteristic = service.getCharacteristic(
                UUID.fromString(BleProtocol.CHARACTERISTIC_RESULT_UUID)
            )
            if (resultCharacteristic == null) {
                Log.w(TAG, "Result characteristic not found (older glass app?)")
            }

            mainHandler.removeCallbacks(mtuFallbackRunnable)
            mainHandler.postDelayed(mtuFallbackRunnable, 2_000)
            gatt.requestMtu(REQUESTED_MTU)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed: $mtu (status=$status)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            mainHandler.removeCallbacks(mtuFallbackRunnable)
            enableImageNotifications(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid.toString()
                    .equals(BleProtocol.CHARACTERISTIC_RESULT_UUID, ignoreCase = true)
            ) {
                pendingWrite?.let { deferred ->
                    if (!deferred.isCompleted) {
                        deferred.complete(status)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid.toString()
                    .equals(BleProtocol.CHARACTERISTIC_IMAGE_UUID, ignoreCase = true)
            ) {
                handleImagePacket(value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            if (characteristic.uuid.toString()
                    .equals(BleProtocol.CHARACTERISTIC_IMAGE_UUID, ignoreCase = true)
            ) {
                handleImagePacket(value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                descriptor.uuid == CCCD_UUID &&
                descriptor.characteristic?.uuid.toString()
                    .equals(BleProtocol.CHARACTERISTIC_IMAGE_UUID, ignoreCase = true)
            ) {
                notificationsEnabled = true
                val name = gatt.device.name ?: BleProtocol.DEVICE_NAME_PREFIX
                Log.i(TAG, "Image notifications enabled, connected to $name")
                dispatchOnMain { listener?.onConnected(name) }
            }
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    suspend fun sendBusinessCardResult(card: BusinessCard): Result<Unit> {
        val json = BusinessCardJson.toJson(card)
        return sendResultPayload(json.toByteArray(Charsets.UTF_8))
    }

    suspend fun sendResultError(message: String): Result<Unit> {
        val json = BusinessCardJson.errorJson(message)
        return sendResultPayload(json.toByteArray(Charsets.UTF_8))
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendResultPayload(payload: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        val gatt = bluetoothGatt ?: return@withContext Result.failure(
            IllegalStateException("未连接眼镜")
        )
        val characteristic = resultCharacteristic ?: return@withContext Result.failure(
            IllegalStateException("眼镜端未支持结果回传，请更新 glass-app")
        )
        if (!isSendingResult.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("正在发送上一结果"))
        }

        try {
            val payloadSize = minOf(BleImageTransfer.MAX_PAYLOAD_SIZE, negotiatedMtu - 6).coerceAtLeast(1)
            Log.i(TAG, "Sending result JSON: ${payload.size} bytes, payloadSize=$payloadSize")

            withTimeout(30_000L) {
                writePacket(gatt, characteristic, BleImageTransfer.encodeStart(payload.size))
                var offset = 0
                var index = 0
                while (offset < payload.size) {
                    val length = minOf(payloadSize, payload.size - offset)
                    val packet = BleImageTransfer.encodeChunk(index, payload, offset, length)
                    writePacket(gatt, characteristic, packet)
                    offset += length
                    index++
                }
                writePacket(gatt, characteristic, BleImageTransfer.encodeEnd())
            }
            Log.i(TAG, "Result JSON sent to glasses")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "sendResultPayload failed", e)
            Result.failure(e)
        } finally {
            isSendingResult.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writePacket(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ) {
        val deferred = CompletableDeferred<Int>()
        pendingWrite = deferred
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = data
                gatt.writeCharacteristic(characteristic)
            }
        }
        if (!started) {
            pendingWrite = null
            throw IllegalStateException("writeCharacteristic rejected")
        }

        try {
            withTimeout(3_000L) {
                val status = deferred.await()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    throw IllegalStateException("GATT write failed: $status")
                }
            }
        } finally {
            pendingWrite = null
        }
        delay(RESULT_WRITE_PACING_MS)
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val btAdapter = adapter ?: run {
            notifyError("本机蓝牙不可用")
            return
        }
        if (!btAdapter.isEnabled) {
            notifyError("请先打开手机蓝牙")
            return
        }
        if (isScanning || bluetoothGatt != null) return

        imageReceiver.reset()
        isScanning = true
        dispatchOnMain { listener?.onScanning() }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            // 不使用严格 UUID 过滤，兼容 MIUI / Rokid 广播格式差异
            btAdapter.bluetoothLeScanner.startScan(null, settings, scanCallback)
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
            Log.i(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            isScanning = false
            notifyError("缺少蓝牙权限: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan permission error", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect error", e)
            }
        }
        cleanupGatt()
        dispatchOnMain { listener?.onDisconnected() }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        Log.i(TAG, "Connecting to ${device.address}")
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun enableImageNotifications(gatt: BluetoothGatt) {
        if (notificationsEnabled) return
        val characteristic = imageCharacteristic ?: return
        val enabled = gatt.setCharacteristicNotification(characteristic, true)
        Log.d(TAG, "setCharacteristicNotification: $enabled")
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        } else {
            Log.w(TAG, "CCCD descriptor missing, connecting without notify")
            notificationsEnabled = true
            val name = gatt.device.name ?: BleProtocol.DEVICE_NAME_PREFIX
            dispatchOnMain { listener?.onConnected(name) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleImagePacket(data: ByteArray) {
        try {
            if (data.isNotEmpty()) {
                when (data[0]) {
                    BleImageTransfer.TYPE_START -> {
                        receiveChunkCount = 0
                        mainHandler.removeCallbacks(receiveTimeoutRunnable)
                        mainHandler.postDelayed(receiveTimeoutRunnable, RECEIVE_TIMEOUT_MS)
                        bluetoothGatt?.requestConnectionPriority(CONNECTION_PRIORITY_HIGH)
                        dispatchOnMain { listener?.onImageReceiving() }
                        Log.i(TAG, "Image transfer started")
                    }
                    BleImageTransfer.TYPE_CHUNK -> {
                        receiveChunkCount++
                        if (receiveChunkCount == 1 || receiveChunkCount % 50 == 0) {
                            Log.d(TAG, "Receiving chunk #$receiveChunkCount")
                        }
                    }
                }
            }
            val jpegBytes = imageReceiver.onPacket(data) ?: return

            mainHandler.removeCallbacks(receiveTimeoutRunnable)
            receiveChunkCount = 0
            val copy = jpegBytes.copyOf()
            Log.i(TAG, "Image assembled: ${copy.size} bytes, decoding off GATT thread")

            decodeExecutor.execute {
                try {
                    val bitmap = BitmapFactory.decodeByteArray(copy, 0, copy.size)
                    if (bitmap == null) {
                        imageReceiver.reset()
                        dispatchOnMain { notifyError("收到的图片数据无法解码") }
                        return@execute
                    }
                    Log.i(
                        TAG,
                        "Image decoded: ${copy.size} bytes, ${bitmap.width}x${bitmap.height}"
                    )
                    dispatchOnMain { listener?.onImageReceived(bitmap, copy) }
                } catch (e: Exception) {
                    Log.e(TAG, "Image decode failed", e)
                    imageReceiver.reset()
                    dispatchOnMain { notifyError("图片解码失败: ${e.message}") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleImagePacket failed", e)
            mainHandler.removeCallbacks(receiveTimeoutRunnable)
            imageReceiver.reset()
            receiveChunkCount = 0
            dispatchOnMain { notifyError("接收图片失败: ${e.message}") }
        }
    }

    private fun cleanupGatt() {
        mainHandler.removeCallbacks(mtuFallbackRunnable)
        mainHandler.removeCallbacks(receiveTimeoutRunnable)
        bluetoothGatt = null
        imageCharacteristic = null
        resultCharacteristic = null
        negotiatedMtu = 23
        pendingWrite = null
        notificationsEnabled = false
        imageReceiver.reset()
        receiveChunkCount = 0
    }

    private fun notifyError(message: String) {
        Log.e(TAG, message)
        dispatchOnMain { listener?.onError(message) }
    }
}
