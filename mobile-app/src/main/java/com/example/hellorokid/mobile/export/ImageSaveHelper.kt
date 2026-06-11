package com.example.hellorokid.mobile.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将眼镜 BLE 传来的图片保存到系统相册（Pictures/RokidCard）。
 */
object ImageSaveHelper {

    private const val ALBUM_DIR = "Pictures/RokidCard"

    fun saveJpegToGallery(context: Context, jpegBytes: ByteArray): Result<String> {
        return runCatching {
            val fileName = timestampFileName("rokid_scan", "jpg")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_DIR)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入相册")

            resolver.openOutputStream(uri)?.use { it.write(jpegBytes) }
                ?: throw IllegalStateException("无法打开输出流")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            "$ALBUM_DIR/$fileName"
        }
    }

    fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Result<String> {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return saveJpegToGallery(context, stream.toByteArray())
    }

    private fun timestampFileName(prefix: String, extension: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "${prefix}_$stamp.$extension"
    }
}
