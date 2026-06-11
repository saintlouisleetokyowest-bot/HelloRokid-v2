package com.example.hellorokid.shared.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BLE 图片分片传输编解码，眼镜端发送、手机端接收。
 *
 * 包格式：
 * - START: [0x01][4 bytes totalSize]
 * - CHUNK: [0x02][2 bytes index][payload]
 * - END:   [0x03]
 */
object BleImageTransfer {

    const val TYPE_START: Byte = 0x01
    const val TYPE_CHUNK: Byte = 0x02
    const val TYPE_END: Byte = 0x03

    /** 单包 payload 上限（不含 3 字节 CHUNK 头） */
    const val MAX_PAYLOAD_SIZE = 500

    fun encodeStart(totalSize: Int): ByteArray {
        return ByteBuffer.allocate(5)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_START)
            .putInt(totalSize)
            .array()
    }

    fun encodeChunk(index: Int, payload: ByteArray, offset: Int, length: Int): ByteArray {
        val packet = ByteArray(3 + length)
        packet[0] = TYPE_CHUNK
        packet[1] = ((index shr 8) and 0xFF).toByte()
        packet[2] = (index and 0xFF).toByte()
        System.arraycopy(payload, offset, packet, 3, length)
        return packet
    }

    fun encodeEnd(): ByteArray = byteArrayOf(TYPE_END)

    class ImageReceiver {
        private var expectedSize = -1
        private var nextChunkIndex = 0
        private val buffer = mutableListOf<ByteArray>()

        fun reset() {
            expectedSize = -1
            nextChunkIndex = 0
            buffer.clear()
        }

        /**
         * @return 完整 JPEG 字节数组；传输未完成时返回 null
         */
        fun onPacket(data: ByteArray): ByteArray? {
            if (data.isEmpty()) return null
            return when (data[0]) {
                TYPE_START -> {
                    if (data.size < 5) return null
                    reset()
                    expectedSize = ByteBuffer.wrap(data, 1, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int
                    null
                }
                TYPE_CHUNK -> {
                    if (data.size < 3 || expectedSize < 0) return null
                    val index = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                    if (index != nextChunkIndex) {
                        android.util.Log.w(
                            "BleImageTransfer",
                            "Chunk order mismatch: got $index, expected $nextChunkIndex"
                        )
                        return null
                    }
                    nextChunkIndex++
                    val chunk = ByteArray(data.size - 3)
                    System.arraycopy(data, 3, chunk, 0, chunk.size)
                    buffer.add(chunk)
                    null
                }
                TYPE_END -> {
                    if (expectedSize < 0) return null
                    val totalReceived = buffer.sumOf { it.size }
                    if (totalReceived == 0) {
                        reset()
                        return null
                    }
                    val result = ByteArray(totalReceived)
                    var pos = 0
                    for (chunk in buffer) {
                        System.arraycopy(chunk, 0, result, pos, chunk.size)
                        pos += chunk.size
                    }
                    reset()
                    if (pos != expectedSize) {
                        android.util.Log.w(
                            "BleImageTransfer",
                            "Size mismatch: got $pos, expected $expectedSize (using received bytes)"
                        )
                    }
                    result
                }
                else -> null
            }
        }
    }
}
