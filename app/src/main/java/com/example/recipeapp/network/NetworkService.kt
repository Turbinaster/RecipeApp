package com.example.recipeapp.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class NetworkService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun uploadAudio(
        audioFile: File,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            Log.d("NetworkService", "Sending audio to server")
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
                )
                .build()
            val request = Request.Builder()
                .url("https://recipe.glubina.org/upload_audio")
                .post(requestBody)
                .build()
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (response.isSuccessful) {
                Log.d("NetworkService", "Server response received")
                val responseBody = response.body?.string() ?: ""
                onSuccess(responseBody)
            } else {
                Log.e("NetworkService", "Server error: ${response.code}")
                onError("Ошибка сервера: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Upload failed: ${e.message}")
            onError("Ошибка отправки: ${e.message}")
        }
    }

    suspend fun uploadImage(
        imageFile: File,
        caption: String?,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            Log.d("NetworkService", "Sending image to server")
            val requestBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    imageFile.name,
                    imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
            if (!caption.isNullOrBlank()) {
                requestBodyBuilder.addFormDataPart("caption", caption)
            }
            val request = Request.Builder()
                .url("https://recipe.glubina.org/upload")
                .post(requestBodyBuilder.build())
                .build()
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (response.isSuccessful) {
                Log.d("NetworkService", "Server response received")
                val responseBody = response.body?.string() ?: ""
                onSuccess(responseBody)
            } else {
                Log.e("NetworkService", "Server error: ${response.code}")
                onError("Ошибка сервера: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Upload failed: ${e.message}")
            onError("Ошибка отправки: ${e.message}")
        }
    }

    suspend fun uploadText(
        text: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            Log.d("NetworkService", "Sending text to server")
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("text", text)
                .build()

            // Всегда используем правильный URL
            val url = if (text.isEmpty()) {
                "https://recipe.glubina.org/upload_daily_recipe"
            } else {
                "https://recipe.glubina.org/upload_text"
            }

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (response.isSuccessful) {
                Log.d("NetworkService", "Server response received")
                val responseBody = response.body?.string() ?: ""
                onSuccess(responseBody)
            } else {
                Log.e("NetworkService", "Server error: ${response.code}")
                onError("Ошибка сервера: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Upload failed: ${e.message}")
            onError("Ошибка отправки: ${e.message}")
        }
    }
}