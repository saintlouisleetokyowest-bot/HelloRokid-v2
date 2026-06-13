package com.example.hellorokid.glass.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.example.hellorokid.shared.data.BusinessCard
import com.example.hellorokid.shared.data.BusinessCardJson
import com.example.hellorokid.shared.protocol.BleImageTransfer
import com.example.hellorokid.shared.protocol.BleProtocol
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 眼镜端 BLE Peripheral：广播 + GATT Server，向已连接的手机发送 JPEG 图片。
 */
class GlassBleServer(private val context: Context) {

    companion object {
        private const val TAG = "GlassBleServer"
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PACING_MIN_MS = 12L
        private const val PACING_MAX_MS = 30L
        private const val PACING_STEP_DOWN_MS = 1L
        private const val PACING_STEP_UP_MS = 5L
        private const val PACING_SUCCESS_STREAK = 8
    }

    interface Listener {
        fun onAdvertisingStarted()
        fun onAdvertisingFailed(errorCode: Int)
        fun onClientConnected()
        fun onClientDisconnected()
        fun onCardResultReceived(card: BusinessCard)
        fun onCardResultError(message: String)
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var imageCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var resultCharacteristic: BluetoothGattCharacteristic? = null
    private val resultReceiver = BleImageTransfer.ImageReceiver()
    private var connectedDevice: BluetoothDevice? = null
    private var listener: Listener? = null

    private val isSending = AtomicBoolean(false)
    private var imageNotifyEnabled = false
    @Volatile
    private var negotiatedMtu = 23

    /** 自适应发包间隔：稳定后降至 12ms，丢包重试时回升 */
    private var currentPacingMs = PACING_MAX_MS
    private var notifySuccessStreak = 0
    private val notifyMaxRetries = 8

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "BLE advertising started")
            listener?.onAdvertisingStarted()
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
            listener?.onAdvertisingFailed(errorCode)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Client connected: ${device.address}")
                    connectedDevice = device
                    listener?.onClientConnected()
                    updateStatus("CONNECTED")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Client disconnected: ${device.address}")
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                        imageNotifyEnabled = false
                        negotiatedMtu = 23
                        resultReceiver.reset()
                    }
                    listener?.onClientDisconnected()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enabled = value?.let {
                    it.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        it.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                } ?: false
                val charUuid = descriptor.characteristic?.uuid?.toString() ?: ""
                if (charUuid.equals(BleProtocol.CHARACTERISTIC_IMAGE_UUID, ignoreCase = true)) {
                    imageNotifyEnabled = enabled
                    Log.i(TAG, "Image notifications ${if (enabled) "enabled" else "disabled"}")
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            negotiatedMtu = mtu
            val payload = effectivePayloadSize()
            Log.i(TAG, "MTU negotiated: $mtu, chunkPayload=$payload")
            if (mtu < 100) {
                Log.w(TAG, "MTU is low ($mtu); image transfer will be slow")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (!characteristic.uuid.toString()
                    .equals(BleProtocol.CHARACTERISTIC_RESULT_UUID, ignoreCase = true)
            ) {
                return
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (value == null || value.isEmpty()) return

            try {
                val payload = resultReceiver.onPacket(value) ?: return
                val json = String(payload, StandardCharsets.UTF_8)
                Log.i(TAG, "Result JSON received: ${json.length} chars")
                BusinessCardJson.parseError(json)?.let { error ->
                    listener?.onCardResultError(error)
                    return
                }
                listener?.onCardResultReceived(BusinessCardJson.fromJson(json))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse result JSON", e)
                listener?.onCardResultError("结果解析失败: ${e.message}")
            }
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun isConnected(): Boolean = connectedDevice != null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val btAdapter = adapter ?: run {
            Log.e(TAG, "Bluetooth adapter unavailable")
            return false
        }
        if (!btAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            return false
        }

        try {
            btAdapter.name = "${BleProtocol.DEVICE_NAME_PREFIX}-${btAdapter.address.takeLast(4)}"
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot set adapter name", e)
        }

        if (!setupGattServer()) return false
        return startAdvertising(btAdapter)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopAdvertising permission error", e)
        }
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server", e)
        }
        gattServer = null
        connectedDevice = null
    }

    @SuppressLint("MissingPermission")
    suspend fun sendJpeg(jpegBytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        val device = connectedDevice ?: return@withContext Result.failure(
            IllegalStateException("手机未连接，请先在手机端点击「连接眼镜」")
        )
        if (!isSending.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("正在发送上一张图片"))
        }

        try {
            if (!imageNotifyEnabled) {
                return@withContext Result.failure(
                    IllegalStateException("手机未订阅图片通知，请重新连接")
                )
            }
            if (jpegBytes.size < 1000) {
                return@withContext Result.failure(IllegalStateException("图片数据过小"))
            }

            val payloadSize = effectivePayloadSize()
            resetTransferPacing()
            val transferStartMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "Sending JPEG: ${jpegBytes.size} bytes, mtu=$negotiatedMtu, payload=$payloadSize")

            var chunkCount = 0
            withTimeout(120_000L) {
                // 传图期间不发 status 通知，避免与 image 特征值抢 BLE 队列
                sendPacketWithPacing(device, BleImageTransfer.encodeStart(jpegBytes.size))

                var offset = 0
                var index = 0
                while (offset < jpegBytes.size) {
                    val length = minOf(payloadSize, jpegBytes.size - offset)
                    val packet = BleImageTransfer.encodeChunk(index, jpegBytes, offset, length)
                    sendPacketWithPacing(device, packet)
                    offset += length
                    index++
                    chunkCount++
                }

                sendPacketWithPacing(device, BleImageTransfer.encodeEnd())
            }

            val elapsedMs = SystemClock.elapsedRealtime() - transferStartMs
            Log.i(
                TAG,
                "Image sent: $chunkCount chunks in ${elapsedMs}ms, finalPacing=${currentPacingMs}ms"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "sendJpeg failed", e)
            Result.failure(e)
        } finally {
            isSending.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer(): Boolean {
        val server = bluetoothManager.openGattServer(context, gattServerCallback) ?: run {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }
        gattServer = server

        val imageChar = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.CHARACTERISTIC_IMAGE_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        imageChar.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        val statusChar = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.CHARACTERISTIC_STATUS_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        statusChar.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        val resultChar = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.CHARACTERISTIC_RESULT_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val service = BluetoothGattService(
            UUID.fromString(BleProtocol.SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(imageChar)
        service.addCharacteristic(statusChar)
        service.addCharacteristic(resultChar)

        imageCharacteristic = imageChar
        statusCharacteristic = statusChar
        resultCharacteristic = resultChar

        val added = server.addService(service)
        if (!added) {
            Log.e(TAG, "Failed to add GATT service")
            return false
        }
        updateStatus("READY")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(btAdapter: BluetoothAdapter): Boolean {
        val advertiser = btAdapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertiser unavailable")
            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(UUID.fromString(BleProtocol.SERVICE_UUID)))
            .build()

        return try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "startAdvertising permission error", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendPacket(device: BluetoothDevice, data: ByteArray): Int {
        val characteristic = imageCharacteristic
            ?: throw IllegalStateException("Image characteristic not ready")
        repeat(notifyMaxRetries) { attempt ->
            characteristic.value = data
            val ok = gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
            if (ok) return attempt
            Log.w(TAG, "notifyCharacteristicChanged failed, retry ${attempt + 1}/$notifyMaxRetries")
            delay(15L * (attempt + 1))
        }
        throw IllegalStateException("notifyCharacteristicChanged failed after $notifyMaxRetries retries")
    }

    private fun resetTransferPacing() {
        currentPacingMs = PACING_MAX_MS
        notifySuccessStreak = 0
    }

    private fun updatePacingAfterPacket(notifyRetries: Int) {
        if (notifyRetries > 0) {
            notifySuccessStreak = 0
            currentPacingMs = minOf(
                PACING_MAX_MS,
                currentPacingMs + PACING_STEP_UP_MS * notifyRetries
            )
            Log.d(TAG, "Pacing increased to ${currentPacingMs}ms after $notifyRetries retries")
            return
        }
        notifySuccessStreak++
        if (notifySuccessStreak >= PACING_SUCCESS_STREAK) {
            notifySuccessStreak = 0
            val before = currentPacingMs
            currentPacingMs = maxOf(PACING_MIN_MS, currentPacingMs - PACING_STEP_DOWN_MS)
            if (currentPacingMs != before) {
                Log.d(TAG, "Pacing decreased to ${currentPacingMs}ms")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateStatus(status: String) {
        val device = connectedDevice ?: return
        val characteristic = statusCharacteristic ?: return
        characteristic.value = status.toByteArray()
        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
    }

    /** ATT 头 3 字节 + CHUNK 头 3 字节 */
    private fun effectivePayloadSize(): Int {
        return minOf(BleImageTransfer.MAX_PAYLOAD_SIZE, negotiatedMtu - 6).coerceAtLeast(1)
    }

    /** 自适应节奏发包：稳定后加快，重试失败时放慢 */
    private suspend fun sendPacketWithPacing(device: BluetoothDevice, data: ByteArray) {
        val notifyRetries = sendPacket(device, data)
        updatePacingAfterPacket(notifyRetries)
        delay(currentPacingMs)
    }
}
