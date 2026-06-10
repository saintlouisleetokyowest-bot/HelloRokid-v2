package com.example.hellorokid.mobile.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.hellorokid.mobile.BuildConfig
import com.example.hellorokid.shared.data.BusinessCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 通过后端服务分析名片，API Key 仅保存在服务端。
 */
class BackendApiService {

    companion object {
        private const val TAG = "BackendApiService"
    }

    private val backendUrl = BuildConfig.BACKEND_URL.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeBusinessCard(bitmap: Bitmap): Result<BusinessCard> = withContext(Dispatchers.IO) {
        try {
            val base64Image = bitmapToBase64(bitmap)
            val requestBody = JSONObject()
                .put("image", base64Image)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$backendUrl/api/analyze")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw Exception("Backend error ${response.code}: $errorBody")
            }

            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            Result.success(parseBusinessCard(responseBody))
        } catch (e: Exception) {
            Log.e(TAG, "Backend analysis failed", e)
            Result.failure(e)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseBusinessCard(jsonString: String): BusinessCard {
        val json = JSONObject(jsonString)
        return BusinessCard(
            name = json.optString("name", ""),
            title = json.optString("title", ""),
            company = json.optString("company", ""),
            phone = json.optString("phone", ""),
            email = json.optString("email", ""),
            address = json.optString("address", ""),
            website = json.optString("website", ""),
            industry = json.optString("industry", ""),
            companySize = json.optString("companySize", ""),
            revenue = json.optString("revenue", ""),
            coreBusiness = json.optString("coreBusiness", ""),
            markets = json.optString("markets", ""),
            partners = json.optString("partners", ""),
            opportunities = json.optString("opportunities", ""),
            investmentReadiness = json.optString("investmentReadiness", ""),
            timing = json.optString("timing", "")
        )
    }
}
