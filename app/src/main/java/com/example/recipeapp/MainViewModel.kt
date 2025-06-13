package com.example.recipeapp

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeapp.network.NetworkService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val networkService = NetworkService()

    private val _isSendingText = MutableStateFlow(false)
    val isSendingText: StateFlow<Boolean> = _isSendingText

    private val _response = MutableStateFlow<String?>(null)
    val response: StateFlow<String?> = _response

    private val _dailyRecipe = MutableStateFlow<String?>(null)
    val dailyRecipe: StateFlow<String?> = _dailyRecipe

    init {
        getDailyRecipe()
    }

    fun getDailyRecipe() {
        viewModelScope.launch {
            try {
                // Проверяем, нужно ли загружать новый рецепт
                val shouldFetch = CacheManager.shouldFetchNewRecipe(getApplication())
                val cachedRecipe = CacheManager.getDailyRecipe(getApplication())

                // Показываем кэшированный рецепт, если есть
                cachedRecipe?.let {
                    _dailyRecipe.value = it
                }

                if (shouldFetch) {
                    networkService.uploadText(
                        text = "",
                        onSuccess = { response ->
                            Log.d("MainViewModel", "Daily recipe response: $response")
                            _dailyRecipe.value = response
                            // Сохраняем в кэш
                            CacheManager.saveDailyRecipe(getApplication(), response)
                        },
                        onError = { error ->
                            Log.e("MainViewModel", "Daily recipe fetch failed: $error")
                            // Если кэша нет, показываем ошибку
                            if (cachedRecipe == null) {
                                _dailyRecipe.value = "Ошибка: $error"
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error getting daily recipe", e)
                // Используем кэш при ошибках
                val cachedRecipe = CacheManager.getDailyRecipe(getApplication())
                if (cachedRecipe == null) {
                    _dailyRecipe.value = "Ошибка загрузки"
                }
            }
        }
    }

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

    fun clearResponse() {
        _response.value = null
    }

    fun clearDailyRecipe() {
        _dailyRecipe.value = null
    }
}