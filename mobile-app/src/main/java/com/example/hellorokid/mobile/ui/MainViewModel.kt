package com.example.hellorokid.mobile.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.hellorokid.mobile.api.BackendApiService
import com.example.hellorokid.mobile.ble.PhoneBleClient
import com.example.hellorokid.mobile.data.BusinessCardEntity
import com.example.hellorokid.mobile.data.CardRepository
import com.example.hellorokid.mobile.export.CardExportHelper
import com.example.hellorokid.mobile.export.ImageSaveHelper
import com.example.hellorokid.shared.data.BusinessCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val application: Application,
    private val repository: CardRepository,
    private val bleClient: PhoneBleClient,
    private val backendApi: BackendApiService = BackendApiService()
) : ViewModel() {

    val cards: StateFlow<List<BusinessCardEntity>> = repository.cards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cardCount: StateFlow<Int> = repository.cardCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        bleClient.setListener(object : PhoneBleClient.Listener {
            override fun onScanning() {
                _connectionState.value = ConnectionState.SCANNING
            }

            override fun onConnected(deviceName: String) {
                _connectionState.value = ConnectionState.CONNECTED
                _message.value = "已连接到 $deviceName，等待扫描名片"
            }

            override fun onDisconnected() {
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            override fun onImageReceiving() {
                _connectionState.value = ConnectionState.RECEIVING
                _message.value = "正在接收眼镜传来的图片…"
            }

            override fun onImageReceived(bitmap: Bitmap, jpegBytes: ByteArray) {
                onBleImageReceived(bitmap, jpegBytes)
            }

            override fun onImageReceiveTimeout() {
                if (_connectionState.value == ConnectionState.RECEIVING) {
                    _connectionState.value = ConnectionState.CONNECTED
                    _message.value = "接收图片超时，请重新拍照或断开重连"
                }
            }

            override fun onError(message: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _message.value = message
            }
        })
    }

    private fun onBleImageReceived(bitmap: Bitmap, jpegBytes: ByteArray) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.RECEIVING
            Log.i("MainViewModel", "Received JPEG: ${jpegBytes.size} bytes, ${bitmap.width}x${bitmap.height}")

            val saveResult = ImageSaveHelper.saveJpegToGallery(application, jpegBytes)
            saveResult.fold(
                onSuccess = { path ->
                    _message.value = "图片已保存到相册（$path，${jpegBytes.size / 1024}KB 灰度），正在 AI 分析…"
                },
                onFailure = { error ->
                    _message.value = "相册保存失败: ${error.message}，仍尝试 AI 分析…"
                }
            )

            analyzeJpeg(jpegBytes)
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun startBleScan() {
        if (_connectionState.value == ConnectionState.SCANNING) return
        bleClient.startScan()
    }

    fun disconnect() {
        bleClient.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _message.value = "已断开与眼镜的连接"
    }

    private fun analyzeJpeg(jpegBytes: ByteArray) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.ANALYZING
            val result = backendApi.analyzeBusinessCard(jpegBytes)
            result.fold(
                onSuccess = { card ->
                    repository.insert(card)
                    syncResultToGlasses(card)
                },
                onFailure = { error ->
                    _connectionState.value = ConnectionState.CONNECTED
                    val msg = error.message ?: "未知错误"
                    _message.value = "AI 分析失败: $msg（原图已在相册中）"
                    viewModelScope.launch { bleClient.sendResultError(msg) }
                }
            )
        }
    }

    private suspend fun syncResultToGlasses(card: BusinessCard) {
        val sendResult = bleClient.sendBusinessCardResult(card)
        _connectionState.value = ConnectionState.CONNECTED
        val name = card.name.ifBlank { "新名片" }
        _message.value = sendResult.fold(
            onSuccess = { "已保存 $name，已同步到眼镜" },
            onFailure = { "已保存 $name（同步眼镜失败: ${it.message}）" }
        )
    }

    fun analyzeImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.ANALYZING
            val result = backendApi.analyzeBusinessCard(bitmap)
            result.fold(
                onSuccess = { card ->
                    repository.insert(card)
                    syncResultToGlasses(card)
                },
                onFailure = { error ->
                    _connectionState.value = ConnectionState.CONNECTED
                    val msg = error.message ?: "未知错误"
                    _message.value = "AI 分析失败: $msg（图片已在相册中）"
                    viewModelScope.launch { bleClient.sendResultError(msg) }
                }
            )
        }
    }

    fun exportVCard(onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val all = repository.getAll()
                if (all.isEmpty()) {
                    onResult(Result.failure(IllegalStateException("暂无名片可导出")))
                    return@launch
                }
                onResult(Result.success(CardExportHelper.buildVCardContent(all)))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    fun exportCsv(onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val all = repository.getAll()
                if (all.isEmpty()) {
                    onResult(Result.failure(IllegalStateException("暂无名片可导出")))
                    return@launch
                }
                onResult(Result.success(CardExportHelper.buildCsvContent(all)))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    override fun onCleared() {
        bleClient.setListener(null)
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val repository: CardRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                val bleClient = (application as com.example.hellorokid.mobile.HelloRokidApplication).bleClient
                return MainViewModel(application, repository, bleClient) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
