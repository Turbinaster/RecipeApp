package com.example.recipeapp.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.recipeapp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    // Состояния для жестов
    var isPressed by remember { mutableStateOf(false) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var startTime by remember { mutableStateOf(0L) }

    // Состояния для подсказки
    var showShortPressHint by remember { mutableStateOf(false) }
    var hintDismissJob by remember { mutableStateOf<Job?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }

    // Вибратор
    val vibrator = remember {
        ContextCompat.getSystemService(context, Vibrator::class.java)
    }

    // Запрос разрешения
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Log.d("MainScreen", "RECORD_AUDIO permission denied")
        }
    }

    // Освобождаем ресурсы
    DisposableEffect(Unit) {
        onDispose {
            mediaRecorder?.release()
            hintDismissJob?.cancel()
            longPressJob?.cancel()
        }
    }

    // Сбрасываем подсказку при изменении ключевых состоян
    LaunchedEffect(isRecording, isSending, showDialog) {
        if (!isRecording && !isSending && !showDialog) {
            showShortPressHint = false
        }
    }

    // Функция для вибрации
    fun vibrate(duration: Long = 50) {
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7DCA8))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Основное содержимое экрана
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                // Подсказка при коротком нажатии
                if (showShortPressHint) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                text = "Удерживайте для записи",
                                color = Color.White,
                                modifier = Modifier.padding(16.dp),
                                fontSize = 16.sp
                            )
                        }
                    }
                }

                if (showDialog) {
                    BasicAlertDialog(
                        onDismissRequest = { /* Запрет закрытия диалога */ },
                        modifier = Modifier
                            .background(Color.White)
                            .padding(16.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.rec),
                                contentDescription = "Запись",
                                modifier = Modifier.size(128.dp),
                                tint = Color.Unspecified
                            )
                            Text(
                                text = "Говорите...",
                                fontSize = 20.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                if (isSending) {
                    CircularProgressIndicator()
                }
            }

            // Нижняя панель с обновленным дизайном
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp) // Отступ от нижнего края экрана
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp) // Отступы по бокам
                        .height(80.dp)
                ) {
                    // Фон панели с закругленными углами со всех сторон
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .align(Alignment.BottomCenter),
                        color = Color(0xFFFFF1D0),
                        tonalElevation = 8.dp,
                        shape = RoundedCornerShape(36.dp) // Закругление всех углов
                    ) {
                        // Контейнер для боковых кнопок
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp)
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Кнопка "Написать"
                                val writeInteractionSource = remember { MutableInteractionSource() }
                                val isWritePressed by writeInteractionSource.collectIsPressedAsState()

                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .shadow(
                                            elevation = if (isWritePressed) 0.dp else 8.dp,
                                            shape = CircleShape,
                                            clip = true
                                        )
                                        .clip(CircleShape)
                                        .background(if (isWritePressed) Color(0xFFe4e3e9) else Color.White)
                                        .clickable(
                                            interactionSource = writeInteractionSource,
                                            indication = null,
                                            onClick = { Log.d("MainScreen", "Write button clicked") }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.write),
                                        contentDescription = "Написать",
                                        modifier = Modifier.size(42.dp),
                                        tint = Color.Unspecified
                                    )
                                }

                                // Пустой спейсер для выравнивания под кнопку камеры
                                Spacer(modifier = Modifier.size(72.dp))

                                // Кнопка "Микрофон"
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .shadow(
                                            elevation = if (isPressed) 0.dp else 8.dp,
                                            shape = CircleShape,
                                            clip = true
                                        )
                                        .clip(CircleShape)
                                        .background(if (isPressed) Color(0xFFe4e3e9) else Color.White)
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onPress = { offset: Offset ->
                                                    isPressed = true
                                                    longPressJob?.cancel()
                                                    hintDismissJob?.cancel()
                                                    showShortPressHint = false

                                                    // Запускаем таймер для долгого нажатия
                                                    longPressJob = coroutineScope.launch {
                                                        delay(500)

                                                        if (isPressed) {
                                                            if (ContextCompat.checkSelfPermission(
                                                                    context,
                                                                    Manifest.permission.RECORD_AUDIO
                                                                ) == PackageManager.PERMISSION_GRANTED
                                                            ) {
                                                                Log.d("MainScreen", "Long press confirmed")
                                                                showDialog = true
                                                                isRecording = true
                                                                showShortPressHint = false

                                                                // Вибрация при начале записи
                                                                vibrate(50)

                                                                val fileName = "audio_${System.currentTimeMillis()}.m4a"
                                                                val file = File(
                                                                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                                                                    fileName
                                                                )
                                                                audioFile = file
                                                                startTime = System.currentTimeMillis()

                                                                try {
                                                                    MediaRecorder().apply {
                                                                        setAudioSource(MediaRecorder.AudioSource.MIC)
                                                                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                                                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                                                        setAudioEncodingBitRate(96000)
                                                                        setAudioSamplingRate(44100)
                                                                        setOutputFile(file.absolutePath)
                                                                        prepare()
                                                                        start()
                                                                        mediaRecorder = this
                                                                    }
                                                                } catch (e: IOException) {
                                                                    Log.e("MainScreen", "Recording failed: ${e.message}")
                                                                    isRecording = false
                                                                    showDialog = false
                                                                    mediaRecorder?.release()
                                                                    mediaRecorder = null
                                                                }

                                                                try {
                                                                    awaitRelease()
                                                                    isPressed = false

                                                                    if (isRecording) {
                                                                        try {
                                                                            mediaRecorder?.stop()
                                                                            mediaRecorder?.release()
                                                                            mediaRecorder = null

                                                                            showDialog = false
                                                                            isSending = true
                                                                            showShortPressHint = false

                                                                            coroutineScope.launch {
                                                                                try {
                                                                                    Log.d("MainScreen", "Sending audio to server")
                                                                                    val client = OkHttpClient.Builder()
                                                                                        .connectTimeout(30, TimeUnit.SECONDS)
                                                                                        .readTimeout(30, TimeUnit.SECONDS)
                                                                                        .writeTimeout(30, TimeUnit.SECONDS)
                                                                                        .build()
                                                                                    val requestBody = MultipartBody.Builder()
                                                                                        .setType(MultipartBody.FORM)
                                                                                        .addFormDataPart(
                                                                                            "audio",
                                                                                            audioFile!!.name,
                                                                                            audioFile!!.asRequestBody("audio/mp4".toMediaTypeOrNull())
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
                                                                                        Log.d("MainScreen", "Server response received")
                                                                                        val responseBody = response.body?.string()
                                                                                        val encodedResponse = responseBody?.let { Uri.encode(it) }
                                                                                        navController.navigate("voice_recipe?response=$encodedResponse") {
                                                                                            popUpTo("main") { inclusive = false }
                                                                                        }
                                                                                    } else {
                                                                                        Log.e("MainScreen", "Server error: ${response.code}")
                                                                                        isSending = false
                                                                                        showShortPressHint = false
                                                                                    }
                                                                                } catch (e: Exception) {
                                                                                    Log.e("MainScreen", "Upload failed: ${e.message}")
                                                                                    isSending = false
                                                                                    showShortPressHint = false
                                                                                } finally {
                                                                                    isRecording = false
                                                                                }
                                                                            }
                                                                        } catch (e: Exception) {
                                                                            Log.e("MainScreen", "Stop failed: ${e.message}")
                                                                            mediaRecorder?.release()
                                                                            mediaRecorder = null
                                                                            audioFile?.delete()
                                                                            isSending = false
                                                                            isRecording = false
                                                                            showDialog = false
                                                                            showShortPressHint = false
                                                                        }
                                                                    }
                                                                } catch (e: Exception) {
                                                                    isPressed = false
                                                                }
                                                            } else {
                                                                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                            }
                                                        }
                                                    }
                                                },
                                                onTap = {
                                                    // Вибрация при коротком нажатии
                                                    vibrate(50)

                                                    // Показ сообщения
                                                    showShortPressHint = true
                                                    hintDismissJob?.cancel()
                                                    hintDismissJob = coroutineScope.launch {
                                                        delay(2000)
                                                        showShortPressHint = false
                                                    }
                                                }
                                            )
                                        }
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent()

                                                    if (event.changes.any { it.pressed }) {
                                                        val position = event.changes.first().position

                                                        if (position.x < 0 || position.y < 0 ||
                                                            position.x > size.width || position.y > size.height) {

                                                            longPressJob?.cancel()

                                                            if (isRecording) {
                                                                try {
                                                                    mediaRecorder?.stop()
                                                                    vibrate(100)
                                                                } catch (e: Exception) {
                                                                } finally {
                                                                    mediaRecorder?.release()
                                                                    mediaRecorder = null
                                                                    audioFile?.delete()
                                                                    audioFile = null
                                                                    isRecording = false
                                                                    showDialog = false
                                                                    showShortPressHint = false
                                                                }
                                                            }

                                                            isPressed = false
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.microphone),
                                        contentDescription = "Микрофон",
                                        modifier = Modifier.size(42.dp),
                                        tint = Color.Unspecified
                                    )
                                }
                            }
                        }
                    }

                    // Кнопка камеры
                    val cameraInteractionSource = remember { MutableInteractionSource() }
                    val isCameraPressed by cameraInteractionSource.collectIsPressedAsState()

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = (-6).dp)
                    ) {
                        // Ободок для кнопки камеры
                        Box(
                            modifier = Modifier
                                .size(75.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFF1D0))
                                .padding(4.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isCameraPressed) Color(0xFFe4e3e9) else Color.White,
                                shadowElevation = if (isCameraPressed) 0.dp else 8.dp,
                                tonalElevation = if (isCameraPressed) 0.dp else 8.dp,
                                modifier = Modifier
                                    .size(68.dp)
                                    .clickable(
                                        interactionSource = cameraInteractionSource,
                                        indication = null,
                                        onClick = {
                                            Log.d("MainScreen", "Camera button clicked")
                                            navController.navigate("camera")
                                        }
                                    )
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.camera),
                                        contentDescription = "Сфотографировать блюдо",
                                        modifier = Modifier.size(50.dp),
                                        tint = Color.Unspecified
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}