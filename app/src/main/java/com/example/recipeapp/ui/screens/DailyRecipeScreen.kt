package com.example.recipeapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.serialization.json.Json
import android.util.Log
import kotlinx.serialization.decodeFromString

@Composable
fun DailyRecipeScreen(navController: NavController, response: String?) {
    var recipeInfo by remember { mutableStateOf<RecipeData?>(null) }

    // Динамические отступы
    val density = LocalDensity.current
    val topPadding = 8.dp
    val topPaddingPx = with(density) { topPadding.toPx() }
    val bottomPadding = 16.dp
    val bottomPaddingPx = with(density) { bottomPadding.toPx() }
    var dynamicTopPaddingPx by remember { mutableStateOf(topPaddingPx) }
    var dynamicBottomPaddingPx by remember { mutableStateOf(bottomPaddingPx) }

    // Получаем высоту статусбара и навигационной панели
    val statusBarHeightPx = with(density) { WindowInsets.statusBars.getTop(this).toFloat() }
    val navBarHeightPx = with(density) { WindowInsets.navigationBars.getBottom(this).toFloat() }

    // NestedScrollConnection для управления отступами
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val newTopPadding = dynamicTopPaddingPx + delta
                dynamicTopPaddingPx = newTopPadding.coerceIn(0f, topPaddingPx)
                val newBottomPadding = dynamicBottomPaddingPx - delta
                dynamicBottomPaddingPx = newBottomPadding.coerceIn(0f, bottomPaddingPx)
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return Velocity.Zero
            }
        }
    }

    // Парсим JSON-данные
    LaunchedEffect(response) {
        if (!response.isNullOrEmpty()) {
            try {
                Log.d("DailyRecipeScreen", "Full response: $response")
                // Парсим ответ как объект с ключом "recipe"
                val jsonResponse = Json.decodeFromString<Map<String, String>>(response)
                var recipeJson = jsonResponse["recipe"]

                if (!recipeJson.isNullOrEmpty()) {
                    // Удаляем обёртку ```json, если она есть
                    if (recipeJson.startsWith("```json\n") && recipeJson.endsWith("\n```")) {
                        recipeJson = recipeJson.substring(8, recipeJson.length - 4).trim()
                        Log.d("DailyRecipeScreen", "Cleaned JSON: $recipeJson")
                    }
                    recipeInfo = Json.decodeFromString<RecipeData>(recipeJson)
                    Log.d("DailyRecipeScreen", "Parsed recipe: $recipeInfo")
                } else {
                    Log.e("DailyRecipeScreen", "Recipe JSON is empty")
                }
            } catch (e: Exception) {
                Log.e("DailyRecipeScreen", "Failed to parse response: ${e.message}")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7DCA8))
            .nestedScroll(nestedScrollConnection)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = with(density) { dynamicTopPaddingPx.toDp() },
                    bottom = with(density) { dynamicBottomPaddingPx.toDp() }
                ),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Text(
                text = recipeInfo?.title ?: "Название рецепта",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth()
            ) {
                val blockWidth = with(LocalDensity.current) {
                    (maxWidth / 4) - 8.dp
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NutritionalField(
                        label = "Белки",
                        value = recipeInfo?.proteins?.toString() ?: "n/a",
                        width = blockWidth
                    )
                    NutritionalField(
                        label = "Жиры",
                        value = recipeInfo?.fats?.toString() ?: "n/a",
                        width = blockWidth
                    )
                    NutritionalField(
                        label = "Углеводы",
                        value = recipeInfo?.carbs?.toString() ?: "n/a",
                        width = blockWidth
                    )
                    NutritionalField(
                        label = "Ккал",
                        value = recipeInfo?.calories?.toString() ?: "n/a",
                        width = blockWidth
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF1D0), shape = RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = recipeInfo?.intro?.takeUnless { it.isNullOrBlank() } ?: "Описание не получено",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Ингредиенты",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                val ingredientsText = recipeInfo?.ingredients?.takeUnless { it.isNullOrBlank() } ?: "Ингредиенты не получены"
                if (ingredientsText != "Ингредиенты не получены") {
                    val ingredientLines = ingredientsText.split("\n")
                    ingredientLines.forEachIndexed { index, line ->
                        Text(
                            text = line,
                            modifier = Modifier
                                .fillMaxWidth(),
                            lineHeight = 24.sp
                        )
                    }
                } else {
                    Text(
                        text = ingredientsText,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Рецепт",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = recipeInfo?.recipe?.replace("\\n", "\n")?.takeUnless { it.isNullOrBlank() } ?: "Рецепт не получен",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}