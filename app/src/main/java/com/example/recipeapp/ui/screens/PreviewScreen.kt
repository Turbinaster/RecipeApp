package com.example.recipeapp.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.recipeapp.network.NetworkService
import kotlinx.coroutines.launch
import java.io.File

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
    val bitmapState = remember { mutableStateOf<Bitmap?>(null) }
    val tempFileState = remember { mutableStateOf<File?>(null) }
    val networkService = remember { NetworkService() }

    // Загружаем изображение из файла
    LaunchedEffect(photoUri) {
        try {
            val uri = Uri.parse(photoUri)
            val file = File(uri.path!!)
            if (!file.exists()) {
                Log.e("PreviewScreen", "Файл не существует: ${uri.path}")
                scope.launch {
                    snackbarHostState.showSnackbar("Ошибка: файл не существует")
                }
                onError("Файл не существует")
                return@LaunchedEffect
            }

            // Декодируем изображение в Bitmap
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null) {
                Log.e("PreviewScreen", "Не удалось декодировать изображение: ${uri.path}")
                scope.launch {
                    snackbarHostState.showSnackbar("Ошибка: не удалось декодировать изображение")
                }
                onError("Не удалось декодировать изображение")
                return@LaunchedEffect
            }
            bitmapState.value = bitmap
            Log.d("PreviewScreen", "Изображение успешно загружено в Bitmap")

            // Сохраняем файл для отправки
            tempFileState.value = file
            Log.d("PreviewScreen", "Файл готов для отправки: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("PreviewScreen", "Ошибка загрузки изображения: ${e.message}", e)
            scope.launch {
                snackbarHostState.showSnackbar("Ошибка загрузки изображения: ${e.message}")
            }
            onError("Ошибка загрузки изображения: ${e.message}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7DCA8)),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Отображаем изображение или состояние ошибки/загрузки
        when (val bitmap = bitmapState.value) {
            null -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(16.dp)
                )
            }
            else -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Сфотографированное блюдо",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }

        TextField(
            value = caption.value,
            onValueChange = { caption.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = {
                Text(
                    text = "Здесь вы можете добавить описание к фото, чтобы уточнить свой вопрос.",
                    style = TextStyle(color = Color(0xFF999999))
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFFFFF1D0),
                unfocusedContainerColor = Color(0xFFFFF1D0),
                focusedIndicatorColor = Color(0xFF79CCBF),
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
                            val tempFile = tempFileState.value
                            if (tempFile == null || !tempFile.exists()) {
                                Log.e("PreviewScreen", "Временный файл недоступен")
                                scope.launch {
                                    snackbarHostState.showSnackbar("Ошибка: временный файл недоступен")
                                }
                                onError("Временный файл недоступен")
                                return@launch
                            }

                            networkService.uploadImage(
                                imageFile = tempFile,
                                caption = caption.value.takeIf { it.isNotBlank() },
                                onSuccess = { response ->
                                    Log.d("PreviewScreen", "Файл успешно отправлен")
                                    val encodedRecipe = Uri.encode(response)
                                    val encodedUri = Uri.encode(photoUri)
                                    navController.navigate("recipe?recipe=$encodedRecipe&photoUri=$encodedUri") {
                                        popUpTo("main") { inclusive = false }
                                    }
                                },
                                onError = { error ->
                                    Log.e("PreviewScreen", "Ошибка отправки: $error")
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Ошибка отправки: $error")
                                    }
                                    onError(error)
                                }
                            )
                        } catch (e: Exception) {
                            val errorMsg = "Неожиданная ошибка: ${e.message}"
                            Log.e("PreviewScreen", errorMsg, e)
                            scope.launch {
                                snackbarHostState.showSnackbar(errorMsg)
                            }
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