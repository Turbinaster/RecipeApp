package com.example.recipeapp.ui.screens

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Composable
fun PreviewScreen(
    navController: NavController,
    photoUri: String,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isSending = remember { mutableStateOf(false) }
    val caption = remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7DCA8)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = rememberAsyncImagePainter(model = Uri.parse(photoUri)),
            contentDescription = "Сфотографированное блюдо",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(8.dp),
            contentScale = ContentScale.Fit
        )
        TextField(
            value = caption.value,
            onValueChange = { caption.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = {
                Text(
                    text = "Вы можете добавить свой комментарий",
                    style = TextStyle(color = Color(0xFF999999)) // Бледный серый цвет
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color(0xFF999999),
                unfocusedIndicatorColor = Color(0xFF999999)
            ),
            singleLine = false,
            maxLines = 3
        )
        Button(
            onClick = {
                if (!isSending.value) {
                    isSending.value = true
                    scope.launch {
                        try {
                            val filePath = Uri.parse(photoUri).path
                            if (filePath == null) {
                                Log.e("PreviewScreen", "Ошибка: путь к файлу null")
                                snackbarHostState.showSnackbar("Ошибка: путь к файлу не найден")
                                onError("Путь к файлу не найден")
                                return@launch
                            }
                            val file = File(filePath)
                            if (!file.exists()) {
                                Log.e("PreviewScreen", "Ошибка: файл не найден по пути $filePath")
                                snackbarHostState.showSnackbar("Ошибка: файл не найден")
                                onError("Файл не найден")
                                return@launch
                            }
                            Log.d("PreviewScreen", "Отправка файла: ${file.name}, путь: $filePath")
                            val client = OkHttpClient.Builder()
                                .connectTimeout(30, TimeUnit.SECONDS)
                                .readTimeout(30, TimeUnit.SECONDS)
                                .writeTimeout(30, TimeUnit.SECONDS)
                                .build()
                            val requestBodyBuilder = MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart(
                                    "image",
                                    file.name,
                                    file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                                )
                            if (caption.value.isNotBlank()) {
                                requestBodyBuilder.addFormDataPart("caption", caption.value)
                            }
                            val requestBody = requestBodyBuilder.build()

                            val serverUrl = "https://recipe.glubina.org/upload"
                            Log.d("PreviewScreen", "Отправка запроса на $serverUrl, caption: ${caption.value}")
                            val request = Request.Builder()
                                .url(serverUrl)
                                .post(requestBody)
                                .build()

                            val response = withContext(Dispatchers.IO) {
                                client.newCall(request).execute()
                            }
                            Log.d("PreviewScreen", "Код ответа: ${response.code}")
                            val responseBody = response.body?.string()
                            Log.d("PreviewScreen", "Тело ответа: $responseBody")

                            if (response.isSuccessful) {
                                Log.d("PreviewScreen", "Файл успешно отправлен")
                                val encodedRecipe = responseBody?.let { Uri.encode(it) } ?: ""
                                navController.navigate("main?recipe=$encodedRecipe") {
                                    popUpTo("main") { inclusive = true }
                                }
                            } else {
                                val errorMsg = "Ошибка отправки: код ${response.code}, ответ: $responseBody"
                                Log.e("PreviewScreen", errorMsg)
                                snackbarHostState.showSnackbar(errorMsg)
                                onError(errorMsg)
                            }
                        } catch (e: IOException) {
                            val errorMsg = "Ошибка сети: ${e.javaClass.simpleName} - ${e.message ?: "неизвестная ошибка"}"
                            Log.e("PreviewScreen", errorMsg, e)
                            snackbarHostState.showSnackbar(errorMsg)
                            onError(errorMsg)
                        } catch (e: Exception) {
                            val errorMsg = "Неожиданная ошибка: ${e.javaClass.simpleName} - ${e.message ?: "неизвестная ошибка"}"
                            Log.e("PreviewScreen", errorMsg, e)
                            snackbarHostState.showSnackbar(errorMsg)
                            onError(errorMsg)
                        } finally {
                            isSending.value = false
                        }
                    }
                }
            },
            modifier = Modifier.padding(8.dp),
            enabled = !isSending.value
        ) {
            if (isSending.value) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            } else {
                Text("Отправить")
            }
        }
        SnackbarHost(hostState = snackbarHostState)
    }
}