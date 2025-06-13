package com.example.recipeapp.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Log
import java.io.File

@Serializable
data class RecipeData(
    val title: String,
    val intro: String,
    val ingredients: String,
    val recipe: String,
    val proteins: Float,
    val fats: Float,
    val carbs: Float,
    val calories: Int
)

@Composable
fun NutritionalField(label: String, value: String, width: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier
            .width(width)
            .background(Color.White, shape = RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color(0xFF999999),
            fontSize = 12.sp,
            style = TextStyle(
                lineHeight = 12.sp
            )
        )
        Text(
            text = try {
                val floatValue = value.toFloatOrNull()
                if (floatValue != null && floatValue == floatValue.toInt().toFloat()) {
                    floatValue.toInt().toString() // Целое число без .0
                } else {
                    String.format("%.1f", floatValue) // Дробное с одним знаком после запятой
                }
            } catch (e: Exception) {
                value // Если не число, отображаем как есть
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

@Composable
fun RecipeScreen(
    navController: NavController,
    recipe: String? = null,
    photoUri: String? = null
) {
    var recipeInfo by remember { mutableStateOf<RecipeData?>(null) }

    // Динамические отступы
    val density = LocalDensity.current
    val topPadding = 16.dp
    val topPaddingPx = with(density) { topPadding.toPx() }
    val bottomPadding = 16.dp
    val bottomPaddingPx = with(density) { bottomPadding.toPx() }
    var dynamicTopPaddingPx by remember { mutableStateOf(topPaddingPx) }
    var dynamicBottomPaddingPx by remember { mutableStateOf(bottomPaddingPx) }

    // Получаем высоту статусбара и навигационной панели
    val statusBarHeightPx = with(density) { WindowInsets.statusBars.getTop(this).toFloat() }
    val navBarHeightPx = with(density) { WindowInsets.navigationBars.getBottom(this).toFloat() }

    // Удаление временного файла при выходе с экрана
    DisposableEffect(Unit) {
        onDispose {
            if (!photoUri.isNullOrEmpty()) {
                val uri = Uri.parse(photoUri)
                val file = File(uri.path!!)
                if (file.exists()) {
                    val deleted = file.delete()
                    Log.d("RecipeScreen", "Временный файл удалён: ${file.absolutePath}, успешно: $deleted")
                }
            }
        }
    }

    // NestedScrollConnection для управления отступами
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                // Верхний отступ (фото)
                val newTopPadding = dynamicTopPaddingPx + delta
                dynamicTopPaddingPx = newTopPadding.coerceIn(0f, topPaddingPx)
                // Нижний отступ (рецепт)
                val newBottomPadding = dynamicBottomPaddingPx - delta
                dynamicBottomPaddingPx = newBottomPadding.coerceIn(0f, bottomPaddingPx)
                return Offset.Zero // Пропускаем скролл в verticalScroll
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return Velocity.Zero // Не обрабатываем fling
            }
        }
    }

    // Парсим JSON-данные
    LaunchedEffect(recipe) {
        if (!recipe.isNullOrEmpty()) {
            try {
                // Проверяем наличие JSON-маркеров
                val jsonStartMarker = "```json\n"
                val jsonEndMarker = "\n```"
                val jsonStartIndex = recipe.indexOf(jsonStartMarker)
                val jsonEndIndex = recipe.indexOf(jsonEndMarker, jsonStartIndex)

                val jsonString = if (jsonStartIndex != -1 && jsonEndIndex != -1) {
                    // JSON с маркерами
                    recipe.substring(
                        jsonStartIndex + jsonStartMarker.length,
                        jsonEndIndex
                    ).trim()
                } else {
                    // JSON без маркеров
                    recipe.trim()
                }

                Log.d("RecipeScreen", "Extracted JSON: $jsonString")
                recipeInfo = Json.decodeFromString<RecipeData>(jsonString)
                Log.d("RecipeScreen", "Recipe data parsed: $recipeInfo")
            } catch (e: Exception) {
                Log.e("RecipeScreen", "Failed to parse recipe data: ${e.message}")
            }
        }
    }

    // Проверяем, нужно ли показывать питательные характеристики
    val shouldShowNutritionalInfo = recipeInfo?.let {
        it.proteins > 0f || it.fats > 0f || it.carbs > 0f || it.calories > 0
    } ?: false

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
            if (!photoUri.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(bottom = 16.dp)
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(model = Uri.parse(photoUri)),
                        contentDescription = "Сфотографированное блюдо",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(topPadding))
            }

            // Recipe title
            Text(
                text = recipeInfo?.title ?: "Название рецепта",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            // Nutritional information fields
            if (shouldShowNutritionalInfo) {
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
            }

            // Intro, Ingredients, Recipe sections
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF1D0), shape = RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                // Intro
                Text(
                    text = recipeInfo?.intro.takeUnless { it.isNullOrBlank() } ?: "Описание не получено",
                    modifier = Modifier.fillMaxWidth()
                )

                // Пустая строка только если есть ingredients или recipe
                if (recipeInfo?.ingredients != "none" || recipeInfo?.recipe != "none") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Ingredients header and list
                if (recipeInfo?.ingredients != "none") {
                    Text(
                        text = "Ингредиенты",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = recipeInfo?.ingredients?.takeUnless { it.isNullOrBlank() } ?: "Ингредиенты не получены",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Пустая строка только если есть recipe
                if (recipeInfo?.ingredients != "none" && recipeInfo?.recipe != "none") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Recipe header and steps
                if (recipeInfo?.recipe != "none") {
                    Text(
                        text = "Рецепт",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = recipeInfo?.recipe?.takeUnless { it.isNullOrBlank() } ?: "Рецепт не получен",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}