package com.example.recipeapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            onError("Разрешение на использование камеры не предоставлено")
            navController.navigate("main")
        }
    }

    LaunchedEffect(Unit) {
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> {}
            else -> launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .setTargetResolution(Size(512, 512))
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } catch (e: Exception) {
                    Log.e("CameraScreen", "Ошибка привязки камеры: ${e.message}", e)
                    onError("Ошибка камеры: ${e.message}")
                }

                previewView
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        Button(
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
            modifier = Modifier.padding(16.dp)
        ) {
            Text("Сделать фото")
        }
    }
}