package com.example.recipeapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.recipeapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Импорт необходимых функций для сглаживания
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

@Composable
fun CameraScreen(
    navController: NavController,
    onError: (String) -> Unit,
    scope: CoroutineScope
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setTargetResolution(Size(512, 512))
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            onError("Разрешение на использование камеры не предоставлено")
            navController.navigate("main")
        }
    }

    // Лаунчер для выбора изображения из галереи
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            // Пользователь отменил выбор - ничего не делаем
            return@rememberLauncherForActivityResult
        }

        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("CameraScreen", "Не удалось открыть InputStream для URI: $uri")
                onError("Не удалось открыть изображение")
                return@rememberLauncherForActivityResult
            }

            // Копируем изображение во внутреннее хранилище
            val tempFile = File(context.filesDir, "gallery_image_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { out ->
                inputStream.copyTo(out)
            }
            inputStream.close()

            val savedUri = Uri.fromFile(tempFile)
            val encodedUri = Uri.encode(savedUri.toString())
            Log.d("CameraScreen", "Изображение из галереи сохранено во временный файл: $savedUri")
            scope.launch(Dispatchers.Main) {
                navController.navigate("preview/$encodedUri")
            }
        } catch (e: SecurityException) {
            Log.e("CameraScreen", "Ошибка доступа к URI: ${e.message}", e)
            onError("Ошибка доступа: ${e.message}")
        } catch (e: Exception) {
            Log.e("CameraScreen", "Неожиданная ошибка при копировании изображения: ${e.message}", e)
            onError("Неожиданная ошибка: ${e.message}")
        }
    }

    LaunchedEffect(Unit) {
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> {}
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AndroidView(
            factory = { ctx ->
                val view = PreviewView(ctx)
                previewView = view // Сохраняем PreviewView для доступа к meteringPointFactory
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .setTargetResolution(Size(512, 512))
                    .build()
                    .also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                    // Настраиваем автофокус при инициализации
                    camera?.cameraControl?.startFocusAndMetering(
                        FocusMeteringAction.Builder(
                            view.meteringPointFactory.createPoint(0.5f, 0.5f) // Центр экрана
                        ).build()
                    )
                } catch (e: Exception) {
                    Log.e("CameraScreen", "Ошибка привязки камеры: ${e.message}", e)
                    onError("Ошибка камеры: ${e.message}")
                }

                view
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(Unit) {
                    detectTapGestures { offset: androidx.compose.ui.geometry.Offset ->
                        previewView?.let { view ->
                            camera?.let { cam ->
                                val meteringPoint = view.meteringPointFactory.createPoint(
                                    offset.x / view.width,
                                    offset.y / view.height
                                )
                                try {
                                    cam.cameraControl.startFocusAndMetering(
                                        FocusMeteringAction.Builder(meteringPoint).build()
                                    )
                                } catch (e: Exception) {
                                    Log.e("CameraScreen", "Ошибка фокусировки: ${e.message}", e)
                                }
                            }
                        }
                    }
                }
        )
        Spacer(modifier = Modifier.height(80.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(80.dp)
        ) {
            // Кнопка съёмки фото (по центру)
            IconButton(
                onClick = {
                    val photoFile = File(
                        context.getExternalFilesDir(null),
                        "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                    )
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                    imageCapture.takePicture(
                        outputOptions,
                        executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                val savedUri = Uri.fromFile(photoFile)
                                val encodedUri = Uri.encode(savedUri.toString())
                                Log.d("CameraScreen", "Фото сохранено: $savedUri")
                                scope.launch(Dispatchers.Main) {
                                    navController.navigate("preview/$encodedUri")
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e("CameraScreen", "Ошибка съёмки: ${exception.message}", exception)
                                onError("Ошибка съёмки: ${exception.message}")
                            }
                        }
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.Center)
            ) {
                // ОБНОВЛЕННЫЙ БЛОК ДЛЯ КНОПКИ КАМЕРЫ СО СГЛАЖИВАНИЕМ
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            onDrawWithContent {
                                // Включаем сглаживание
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.drawARGB(0, 0, 0, 0) // Прозрачный фон
                                }
                                drawContent()
                            }
                        }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.camera_button),
                        contentDescription = "Сделать фото",
                        modifier = Modifier.fillMaxSize(),
                        tint = Color.Unspecified // Важно для сохранения оригинальных цветов
                    )
                }
            }

            // Кнопка для открытия галереи (слева от кнопки съёмки)
            IconButton(
                onClick = {
                    galleryLauncher.launch(arrayOf("image/*"))
                },
                modifier = Modifier
                    .size(50.dp)
                    .align(Alignment.Center)
                    .offset(x = (-100).dp) // Расстояние между краями = 80dp
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.gallery),
                    contentDescription = "Выбрать из галереи",
                    modifier = Modifier.size(40.dp),
                    tint = Color.White
                )
            }
        }
    }
}