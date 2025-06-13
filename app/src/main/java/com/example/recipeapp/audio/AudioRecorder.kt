package com.example.recipeapp.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.recipeapp.network.NetworkService
import java.io.File
import java.io.IOException

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var startTime: Long = 0L
    private val networkService = NetworkService()

    fun startRecording(
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val fileName = "audio_${System.currentTimeMillis()}.m4a"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), fileName)
        audioFile = file

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
            startTime = System.currentTimeMillis()
            vibrate(50)
            onSuccess(file)
        } catch (e: IOException) {
            Log.e("AudioRecorder", "Recording failed: ${e.message}")
            onError("Ошибка записи: ${e.message}")
        }
    }

    fun stopRecording(): File? {
        return try {
            mediaRecorder?.stop()
            vibrate(100)
            mediaRecorder?.release()
            mediaRecorder = null
            audioFile
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Stop failed: ${e.message}")
            null
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    fun cancelRecording() {
        val wasRecording = mediaRecorder != null
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            // Игнорируем ошибки при отмене
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
            audioFile?.delete()
            audioFile = null
            if (wasRecording) {
                vibrate(100)
            }
        }
    }

    suspend fun sendAudioToServer(
        audioFile: File,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        networkService.uploadAudio(
            audioFile = audioFile,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    fun vibrate(duration: Long) {
        val vibratorService = ContextCompat.getSystemService(context, Vibrator::class.java)
        if (vibratorService?.hasVibrator() == true) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibratorService.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibratorService.vibrate(duration)
            }
        }
    }
}