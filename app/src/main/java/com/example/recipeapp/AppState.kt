package com.example.recipeapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull // Добавлен импорт
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody // Добавлен импорт
import retrofit2.Retrofit
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import java.util.concurrent.TimeUnit
import android.util.Log

// Интерфейс API
interface ApiService {
    @Multipart
    @POST("upload")
    suspend fun uploadImage(@Part file: MultipartBody.Part): Map<String, Any?> // Изменён тип на Any? для безопасности
}

// Инициализация Retrofit с кастомным OkHttpClient
private val okHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

private val retrofit = Retrofit.Builder()
    .baseUrl("https://recipe.glubina.org/")
    .client(okHttpClient)
    .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
    .build()

// Делаем apiService доступным
private val apiService = retrofit.create(ApiService::class.java)

object UploadResultFlow {
    private val _resultFlow = MutableStateFlow<String?>(null)
    val resultFlow: StateFlow<String?> = _resultFlow

    // Глобальный CoroutineScope для долговечных операций
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun emitResult(result: String) {
        _resultFlow.value = result
    }

    fun clearResult() {
        _resultFlow.value = null
    }

    // Функция для выполнения загрузки в глобальном scope
    fun uploadImage(
        decodedPath: String,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit
    ) {
        scope.launch {
            try {
                emitResult("Пишем рецепт...")
                val file = File(decodedPath)
                if (!file.exists()) {
                    emitResult("Ошибка: Файл не найден")
                    onError("Файл не найден")
                    return@launch
                }
                val requestFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val response = apiService.uploadImage(body)
                Log.d("UploadResultFlow", "Server response: $response")
                val result = response["response"]?.toString() ?: "Ответ от сервера не содержит данных"
                emitResult(result)
                onSuccess(result)
            } catch (e: Exception) {
                Log.e("UploadResultFlow", "Upload error: ${e.message}", e)
                emitResult("Ошибка отправки: ${e.message}")
                onError("Ошибка отправки: ${e.message}")
            }
        }
    }
}