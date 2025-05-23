package com.example.recipeapp.ui.screens

import androidx.compose.foundation.background // Добавлен импорт для background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun MainScreen(
    navController: NavController,
    recipe: String? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7DCA8))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!recipe.isNullOrEmpty()) {
                Text(
                    text = recipe,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            } else {
                Text(
                    text = "Рецепт не получен",
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
        Button(
            onClick = { navController.navigate("camera") },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp)
        ) {
            Text("Сфотографировать блюдо")
        }
    }
}