package com.example.hellorokid.mobile.api

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.hellorokid.mobile.BuildConfig
import com.example.hellorokid.mobile.locale.AppLocaleManager
import com.example.hellorokid.shared.data.BusinessCard
import com.example.hellorokid.shared.image.ImagePostProcessor
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
class BackendApiService(context: Context) {

    companion object {
        private const val TAG = "BackendApiService"
    }

    private val appContext = context.applicationContext
    private val backendUrl = BuildConfig.BACKEND_URL.trimEnd('/')

    private val extractClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val enrichClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun apiLocale(): String {
        val tag = AppLocaleManager.getLanguageTag(appContext)
        return AppLocaleManager.toApiLocale(tag)
    }

    /** 快速提取联系信息（仅 OCR，不含情报字段）。 */
    suspend fun extractBusinessCard(jpegBytes: ByteArray): Result<BusinessCard> = withContext(Dispatchers.IO) {
        try {
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            postExtract(base64Image)
        } catch (e: Exception) {
            Log.e(TAG, "Extract failed", e)
            Result.failure(e)
        }
    }

    suspend fun extractBusinessCard(bitmap: Bitmap, enhance: Boolean = true): Result<BusinessCard> =
        withContext(Dispatchers.IO) {
            try {
                val source = if (enhance) ImagePostProcessor.enhanceForOcr(bitmap) else bitmap
                val created = enhance && source !== bitmap
                try {
                    postExtract(bitmapToBase64(source))
                } finally {
                    if (created) {
                        source.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extract failed", e)
                Result.failure(e)
            }
        }

    /** 根据联系信息补充公司情报（Cloudsway + Gemini）。 */
    suspend fun enrichBusinessCard(contact: BusinessCard): Result<BusinessCard> = withContext(Dispatchers.IO) {
        try {
            postEnrich(contact)
        } catch (e: Exception) {
            Log.e(TAG, "Enrich failed", e)
            Result.failure(e)
        }
    }

    /** 兼容旧接口：一次性完整分析。 */
    suspend fun analyzeBusinessCard(jpegBytes: ByteArray): Result<BusinessCard> = withContext(Dispatchers.IO) {
        try {
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            postAnalyze(base64Image)
        } catch (e: Exception) {
            Log.e(TAG, "Backend analysis failed", e)
            Result.failure(e)
        }
    }

    suspend fun analyzeBusinessCard(bitmap: Bitmap): Result<BusinessCard> = withContext(Dispatchers.IO) {
        try {
            val enhanced = ImagePostProcessor.enhanceForOcr(bitmap)
            val base64Image = bitmapToBase64(enhanced)
            if (enhanced !== bitmap) {
                enhanced.recycle()
            }
            postAnalyze(base64Image)
        } catch (e: Exception) {
            Log.e(TAG, "Backend analysis failed", e)
            Result.failure(e)
        }
    }

    private fun postExtract(base64Image: String): Result<BusinessCard> {
        val requestBody = JSONObject()
            .put("image", base64Image)
            .put("uiLocale", apiLocale())
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$backendUrl/api/extract")
            .post(requestBody)
            .build()

        return executeRequest(extractClient, request) { body ->
            parseBusinessCard(body)
        }
    }

    private fun postEnrich(contact: BusinessCard): Result<BusinessCard> {
        val requestBody = contactToJson(contact)
            .put("outputLocale", apiLocale())
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$backendUrl/api/enrich")
            .post(requestBody)
            .build()

        return executeRequest(enrichClient, request) { body ->
            parseBusinessCard(body)
        }
    }

    private fun postAnalyze(base64Image: String): Result<BusinessCard> {
        val requestBody = JSONObject()
            .put("image", base64Image)
            .put("uiLocale", apiLocale())
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$backendUrl/api/analyze")
            .post(requestBody)
            .build()

        return executeRequest(enrichClient, request) { body ->
            parseBusinessCard(body)
        }
    }

    private fun executeRequest(
        client: OkHttpClient,
        request: Request,
        parser: (String) -> BusinessCard
    ): Result<BusinessCard> {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            throw Exception("Backend error ${response.code}: $errorBody")
        }
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        return Result.success(parser(responseBody))
    }

    private fun contactToJson(contact: BusinessCard): JSONObject {
        return JSONObject()
            .put("name", contact.name)
            .put("title", contact.title)
            .put("department", contact.department)
            .put("company", contact.company)
            .put("phone", contact.phone)
            .put("mobile", contact.mobile)
            .put("fax", contact.fax)
            .put("email", contact.email)
            .put("address", contact.address)
            .put("website", contact.website)
            .put("sourceLanguage", contact.sourceLanguage)
            .put("nameReading", contact.nameReading)
            .put("companyNameEn", contact.companyNameEn)
            .put("titleLocalized", contact.titleLocalized)
            .put("departmentLocalized", contact.departmentLocalized)
            .put("addressEn", contact.addressEn)
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int = 92): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseBusinessCard(jsonString: String): BusinessCard {
        val json = JSONObject(jsonString)
        return BusinessCard(
            name = json.optString("name", ""),
            title = json.optString("title", ""),
            department = json.optString("department", ""),
            company = json.optString("company", ""),
            phone = json.optString("phone", ""),
            mobile = json.optString("mobile", ""),
            fax = json.optString("fax", ""),
            email = json.optString("email", ""),
            address = json.optString("address", ""),
            website = json.optString("website", ""),
            sourceLanguage = json.optString("sourceLanguage", ""),
            nameReading = json.optString("nameReading", ""),
            companyNameEn = json.optString("companyNameEn", ""),
            titleLocalized = json.optString("titleLocalized", ""),
            departmentLocalized = json.optString("departmentLocalized", ""),
            addressEn = json.optString("addressEn", ""),
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
