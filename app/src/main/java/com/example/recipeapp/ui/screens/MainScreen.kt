package com.example.recipeapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.recipeapp.MainViewModel
import com.example.recipeapp.R
import com.example.recipeapp.audio.AudioRecorder
import com.example.recipeapp.chat.ChatManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Composable
fun MainScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val audioRecorder = remember { AudioRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var wasRecordingCancelled by remember { mutableStateOf(false) }
    var wasSwipeCancelled by remember { mutableStateOf(false) }
    var wasTimerCompleted by remember { mutableStateOf(false) }
    var showCancelAnimation by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }
    var showShortPressHint by remember { mutableStateOf(false) }
    var hintDismissJob by remember { mutableStateOf<Job?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var showChatDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Наблюдение за состоянием ViewModel
    val isSendingText by viewModel.isSendingText.collectAsState()
    val response by viewModel.response.collectAsState()
    val dailyRecipe by viewModel.dailyRecipe.collectAsState()

    // Lottie анимации
    val cancelAnimationSpec = remember { LottieCompositionSpec.RawRes(R.raw.delete) }
    val cancelAnimationComposition by rememberLottieComposition(spec = cancelAnimationSpec)
    val cancelAnimationProgress by animateLottieCompositionAsState(
        composition = cancelAnimationComposition,
        iterations = 1,
        isPlaying = showCancelAnimation,
        speed = 1.5f,
        restartOnPlay = true
    )

    val sendingAnimationSpec = remember { LottieCompositionSpec.RawRes(R.raw.cooking) }
    val sendingAnimationComposition by rememberLottieComposition(spec = sendingAnimationSpec)
    val sendingAnimationProgress by animateLottieCompositionAsState(
        composition = sendingAnimationComposition,
        iterations = Int.MAX_VALUE,
        isPlaying = isSending || isSendingText,
        speed = 1f
    )

    // Сообщения для запросов
    val statusMessages = listOf(
        "Отправляем сообщение...",
        "Изучаем контент...",
        "Готовим ответ...",
        "Считаем калории...",
        "Подбираем слова...",
        "Уточняем детали...",
        "Добавляем изюминку...",
        "Проверяем пропорции...",
        "Доводим до вкуса...",
        "Улучшаем подачу...",
        "Проверяем точность...",
        "Шлифуем ответ...",
        "Делаем умный вид...",
        "Сочиняем этот текст...",
        "Ищем отговорки..."
    )

    val currentStatus = remember { mutableStateOf("") }

    // Обновление статусных сообщений для всех запросов
    LaunchedEffect(isSending, isSendingText) {
        if (isSending || isSendingText) {
            statusMessages.forEach { message ->
                currentStatus.value = message
                delay(2000)
            }
        }
    }

    // Управление диалогом для текстовых запросов
    LaunchedEffect(isSendingText) {
        if (isSendingText) {
            showDialog = true
        } else {
            if (!isRecording && !isSending) {
                showDialog = false
            }
        }
    }

    // Обработка ответа сервера
    LaunchedEffect(response) {
        response?.let { result ->
            if (result is String && !result.startsWith("Ошибка")) {
                val encodedResponse = Uri.encode(result)
                Log.d("MainScreen", "Navigating to VoiceRecipeScreen with response: $result")
                navController.navigate("voice_recipe?response=$encodedResponse&inputType=text") {
                    popUpTo("main") { inclusive = false }
                }
            } else if (result is String) {
                Log.e("MainScreen", "Text query error: $result")
            }
            viewModel.clearResponse()
        }
    }

    // Анимация таймера
    val progress = remember { Animatable(0f) }

    // Обработчик завершения записи и отправки
    fun stopRecordingAndSend(isTimerTriggered: Boolean = false) {
        if (!wasRecordingCancelled) {
            val file = audioRecorder.stopRecording()
            if (file != null) {
                showDialog = true
                isSending = true
                showShortPressHint = false
                isRecording = false
                if (isTimerTriggered) {
                    wasTimerCompleted = true
                }

                coroutineScope.launch {
                    audioRecorder.sendAudioToServer(
                        audioFile = file,
                        onSuccess = { response ->
                            isSending = false
                            if (!isSendingText) {
                                showDialog = false
                            }
                            val encodedResponse = Uri.encode(response)
                            navController.navigate("voice_recipe?response=$encodedResponse&inputType=voice") {
                                popUpTo("main") { inclusive = false }
                            }
                        },
                        onError = { error ->
                            Log.e("MainScreen", "Upload failed: $error")
                            isSending = false
                            if (!isSendingText) {
                                showDialog = false
                            }
                            showShortPressHint = false
                        }
                    )
                }
            } else {
                isRecording = false
                showDialog = false
                showShortPressHint = false
            }
        } else {
            isRecording = false
        }
    }

    // Обработчик отмены записи
    fun cancelRecording() {
        wasRecordingCancelled = true
        wasSwipeCancelled = true
        audioRecorder.cancelRecording()
        isRecording = false
        showCancelAnimation = true
        showDialog = true
        showShortPressHint = false

        coroutineScope.launch {
            delay(800)
            showDialog = false
            showCancelAnimation = false
        }
    }

    // Запуск/остановка анимации таймера
    LaunchedEffect(isRecording) {
        if (isRecording) {
            try {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 10000,
                        easing = LinearEasing
                    )
                )
                if (isRecording && !wasRecordingCancelled) {
                    stopRecordingAndSend(true)
                }
            } catch (e: CancellationException) {
                // Анимация была отменена
            }
        } else {
            progress.snapTo(0f)
        }
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
            audioRecorder.cancelRecording()
            hintDismissJob?.cancel()
            longPressJob?.cancel()
        }
    }

    // Сбрасываем подсказку при изменении ключевых состояний
    LaunchedEffect(isRecording, isSending, showDialog) {
        if (!isRecording && !isSending && !showDialog) {
            showShortPressHint = false
            wasRecordingCancelled = false
            wasSwipeCancelled = false
            wasTimerCompleted = false
        }
    }

    // Парсим название и intro рецепта дня
    val (dailyRecipeTitle, dailyRecipeIntro) = remember(dailyRecipe) {
        val current = dailyRecipe
        when {
            current == null -> Pair("Загружаем...", "Рецепт загружается...")
            current.startsWith("Ошибка") -> Pair("Ошибка загрузки", "Ошибка загрузки рецепта")
            else -> {
                try {
                    // Парсим внешний JSON
                    val outerJson = Json.decodeFromString<Map<String, String>>(current)
                    val recipeJson = outerJson["recipe"] ?: return@remember Pair("Нет данных", "Нет данных")

                    // Очистка от ```json
                    val cleanJson = if (recipeJson.startsWith("```json\n") && recipeJson.endsWith("\n```")) {
                        recipeJson.substring(8, recipeJson.length - 4).trim()
                    } else {
                        recipeJson
                    }

                    // Парсим внутренний JSON
                    val recipeData = Json.decodeFromString<RecipeData>(cleanJson)
                    Pair(
                        recipeData.title ?: "Без названия",
                        recipeData.intro?.takeUnless { it.isNullOrBlank() } ?: "Описание не получено"
                    )
                } catch (e: Exception) {
                    Log.e("MainScreen", "Error parsing daily recipe: ${e.message}")
                    Pair("Ошибка парсинга", "Ошибка парсинга")
                }
            }
        }
    }

    // Диалог чата
    if (showChatDialog) {
        ChatManager(
            viewModel = viewModel,
            onDismiss = { showChatDialog = false }
        )
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
            // Блюдо дня от шефа (упрощённая версия)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                onClick = {
                    val current = dailyRecipe
                    when {
                        current == null -> {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Рецепт загружается...")
                            }
                        }
                        current.startsWith("Ошибка") -> {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Ошибка загрузки рецепта")
                            }
                        }
                        current.isNotBlank() -> {
                            val encodedResponse = Uri.encode(current)
                            navController.navigate("daily_recipe?response=$encodedResponse") {
                                popUpTo("main") { inclusive = false }
                            }
                        }
                        else -> {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Рецепт недоступен")
                            }
                        }
                    }
                },
                color = Color(0xFFFFF1D0),
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.chef),
                        contentDescription = "Шеф",
                        modifier = Modifier.size(70.dp),
                        tint = Color.Unspecified
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Блюдо дня от шефа",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = dailyRecipeTitle,
                            fontSize = 18.sp
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showShortPressHint) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(16.dp)
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
                    Dialog(
                        onDismissRequest = { /* Запрет закрытия диалога */ },
                        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
                    ) {
                        // Для состояния записи или отмены записи - круглый диалог
                        if (isRecording || showCancelAnimation) {
                            Box(
                                modifier = Modifier
                                    .size(200.dp)
                                    .drawBehind {
                                        if (isRecording) {
                                            val strokeWidth = 10.dp.toPx()
                                            drawArc(
                                                color = Color(0xFF79CCBF),
                                                startAngle = -90f,
                                                sweepAngle = progress.value * 360f,
                                                useCenter = false,
                                                style = Stroke(
                                                    width = strokeWidth,
                                                    cap = StrokeCap.Round
                                                )
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .size(180.dp),
                                    shape = CircleShape,
                                    color = Color.White,
                                    shadowElevation = 8.dp
                                ) {}
                                when {
                                    showCancelAnimation -> {
                                        LottieAnimation(
                                            composition = cancelAnimationComposition,
                                            progress = { cancelAnimationProgress },
                                            modifier = Modifier.size(150.dp)
                                        )
                                    }
                                    else -> {
                                        Icon(
                                            painter = painterResource(id = R.drawable.rec),
                                            contentDescription = "Запись",
                                            modifier = Modifier.size(100.dp),
                                            tint = Color.Unspecified
                                        )
                                    }
                                }
                            }
                        }
                        // Для состояния отправки - прямоугольный диалог
                        else if (isSending || isSendingText) {
                            Surface(
                                // ФИКСИРОВАННАЯ ШИРИНА (70% экрана) - РЕГУЛИРОВАТЬ ЗДЕСЬ
                                modifier = Modifier
                                    .fillMaxWidth(0.8f),
                                color = Color(0xFFFFF1D0),
                                shape = RoundedCornerShape(16.dp),
                                shadowElevation = 4.dp
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    // РАЗМЕР АНИМАЦИИ - РЕГУЛИРОВАТЬ ЗДЕСЬ
                                    LottieAnimation(
                                        composition = sendingAnimationComposition,
                                        progress = { sendingAnimationProgress },
                                        modifier = Modifier.size(100.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = currentStatus.value,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(80.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .align(Alignment.BottomCenter),
                        color = Color(0xFFE8C988),
                        tonalElevation = 8.dp,
                        shape = RoundedCornerShape(36.dp)
                    ) {
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
                                            onClick = {
                                                showChatDialog = true
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.chat),
                                        contentDescription = "Написать",
                                        modifier = Modifier.size(42.dp),
                                        tint = Color.Unspecified
                                    )
                                }

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
                                                onPress = { offset ->
                                                    isPressed = true
                                                    hintDismissJob?.cancel()
                                                    showShortPressHint = false
                                                    wasRecordingCancelled = false
                                                    wasSwipeCancelled = false
                                                    wasTimerCompleted = false
                                                    longPressJob?.cancel()

                                                    longPressJob = coroutineScope.launch {
                                                        delay(500)
                                                        if (!isRecording) {
                                                            if (ContextCompat.checkSelfPermission(
                                                                    context,
                                                                    Manifest.permission.RECORD_AUDIO
                                                                ) == PackageManager.PERMISSION_GRANTED
                                                            ) {
                                                                showDialog = true
                                                                isRecording = true
                                                                showShortPressHint = false
                                                                audioRecorder.startRecording(
                                                                    onSuccess = { file -> },
                                                                    onError = { error ->
                                                                        isRecording = false
                                                                        showDialog = false
                                                                    }
                                                                )
                                                            } else {
                                                                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                            }
                                                        }
                                                    }

                                                    tryAwaitRelease()
                                                    isPressed = false
                                                    longPressJob?.cancel()

                                                    if (!isRecording && !wasSwipeCancelled && !wasTimerCompleted) {
                                                        audioRecorder.vibrate(50)
                                                        showShortPressHint = true
                                                        hintDismissJob?.cancel()
                                                        hintDismissJob = coroutineScope.launch {
                                                            delay(1000)
                                                            showShortPressHint = false
                                                        }
                                                    } else if (isRecording) {
                                                        stopRecordingAndSend()
                                                    }
                                                }
                                            )
                                        }
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    if (isRecording) {
                                                        val position = event.changes.firstOrNull()?.position
                                                        if (position != null &&
                                                            (position.x < 0 || position.y < 0 ||
                                                                    position.x > size.width || position.y > size.height)) {
                                                            longPressJob?.cancel()
                                                            cancelRecording()
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
                }

                val cameraInteractionSource = remember { MutableInteractionSource() }
                val isCameraPressed by cameraInteractionSource.collectIsPressedAsState()

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-6).dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(75.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE8C988))
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

        // Затемнённый фон с Lottie-анимацией для текстовых запросов
        if (isSendingText) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                LottieAnimation(
                    composition = sendingAnimationComposition,
                    progress = { sendingAnimationProgress },
                    modifier = Modifier.size(100.dp)
                )
            }
        }

        // Snackbar для уведомлений
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}