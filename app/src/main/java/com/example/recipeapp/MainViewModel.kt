package com.example.recipeapp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeapp.network.NetworkService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val networkService = NetworkService()

    private val _isSendingText = MutableStateFlow(false)
    val isSendingText: StateFlow<Boolean> = _isSendingText

    private val _response = MutableStateFlow<String?>(null)
    val response: StateFlow<String?> = _response

    private val _dailyRecipe = MutableStateFlow<String?>(null)
    val dailyRecipe: StateFlow<String?> = _dailyRecipe

    fun sendTextQuery(text: String) {
        _isSendingText.value = true
        viewModelScope.launch {
            networkService.uploadText(
                text = text,
                onSuccess = { response ->
                    Log.d("MainViewModel", "Server response: $response")
                    _response.value = response
                    _isSendingText.value = false
                },
                onError = { error ->
                    Log.e("MainViewModel", "Text upload failed: $error")
                    _response.value = "Ошибка: $error"
                    _isSendingText.value = false
                }
            )
        }
    }

    fun getDailyRecipe() {
        viewModelScope.launch {
            networkService.uploadText(
                text = "", // Пустой текст означает запрос на рецепт дня
                onSuccess = { response ->
                    Log.d("MainViewModel", "Daily recipe response: $response")
                    _dailyRecipe.value = response
                },
                onError = { error ->
                    Log.e("MainViewModel", "Daily recipe fetch failed: $error")
                    _dailyRecipe.value = "Ошибка: $error"
                }
            )
        }
    }

    fun clearResponse() {
        _response.value = null
    }

    fun clearDailyRecipe() {
        _dailyRecipe.value = null
    }
}