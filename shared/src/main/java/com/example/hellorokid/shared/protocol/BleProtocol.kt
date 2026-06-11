package com.example.hellorokid.shared.protocol

/**
 * BLE 通信协议常量，眼镜端（Peripheral）与手机端（Central）共用。
 */
object BleProtocol {
    const val SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_IMAGE_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_STATUS_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
    /** 手机 → 眼镜：AI 解析结果（JSON 分片写入） */
    const val CHARACTERISTIC_RESULT_UUID = "0000ff03-0000-1000-8000-00805f9b34fb"

    const val DEVICE_NAME_PREFIX = "RokidCard"
    const val MAX_CHUNK_SIZE = 512
    const val IMAGE_FORMAT_JPEG = "image/jpeg"
}
