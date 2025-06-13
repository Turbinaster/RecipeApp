package com.example.recipeapp

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.recipeapp.ui.screens.CameraScreen
import com.example.recipeapp.ui.screens.DailyRecipeScreen
import com.example.recipeapp.ui.screens.MainScreen
import com.example.recipeapp.ui.screens.PreviewScreen
import com.example.recipeapp.ui.screens.RecipeScreen
import com.example.recipeapp.ui.screens.VoiceRecipeScreen
import com.example.recipeapp.ui.theme.RecipeAppTheme
import kotlinx.coroutines.launch
import android.util.Log

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            installSplashScreen()
        } else {
            setContentView(R.layout.activity_splash)
            Handler(Looper.getMainLooper()).postDelayed({
                setContent {
                    RecipeAppTheme {
                        RecipeApp(application = application)
                    }
                }
            }, 1000)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setContent {
                RecipeAppTheme {
                    RecipeApp(application = application)
                }
            }
        }
    }
}

@Composable
fun RecipeApp(application: Application) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(application))

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(navController = navController, viewModel = viewModel)
        }
        composable(
            route = "camera",
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(300)
                )
            }
        ) {
            CameraScreen(
                navController = navController,
                onError = { error ->
                    scope.launch {
                        snackbarHostState.showSnackbar(error)
                    }
                },
                scope = scope
            )
        }
        composable("preview/{photoUri}") { backStackEntry ->
            val photoUri = backStackEntry.arguments?.getString("photoUri")?.let { Uri.decode(it) } ?: ""
            Log.d("MainActivity", "Navigating to PreviewScreen with photoUri: $photoUri")
            PreviewScreen(
                navController = navController,
                photoUri = photoUri,
                onError = { error ->
                    scope.launch {
                        snackbarHostState.showSnackbar(error)
                    }
                }
            )
        }
        composable(
            route = "recipe?recipe={recipe}&photoUri={photoUri}",
            arguments = listOf(
                navArgument("recipe") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("photoUri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(300)
                )
            }
        ) { backStackEntry ->
            val recipe = backStackEntry.arguments?.getString("recipe")?.let { Uri.decode(it) }
            val photoUri = backStackEntry.arguments?.getString("photoUri")?.let { Uri.decode(it) }
            RecipeScreen(
                navController = navController,
                recipe = recipe,
                photoUri = photoUri
            )
        }
        composable(
            route = "voice_recipe?response={response}&inputType={inputType}",
            arguments = listOf(
                navArgument("response") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("inputType") {
                    type = NavType.StringType
                    defaultValue = "voice"
                }
            ),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(300)
                )
            }
        ) { backStackEntry ->
            val response = backStackEntry.arguments?.getString("response")?.let { Uri.decode(it) }
            val inputType = backStackEntry.arguments?.getString("inputType") ?: "voice"
            VoiceRecipeScreen(
                navController = navController,
                response = response,
                inputType = inputType
            )
        }
        composable(
            route = "daily_recipe?response={response}",
            arguments = listOf(
                navArgument("response") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(300)
                )
            }
        ) { backStackEntry ->
            val response = backStackEntry.arguments?.getString("response")?.let { Uri.decode(it) }
            DailyRecipeScreen(
                navController = navController,
                response = response
            )
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.fillMaxSize()
    )
}

class MainViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}