package com.example.recipeapp

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.recipeapp.ui.screens.CameraScreen
import com.example.recipeapp.ui.screens.MainScreen
import com.example.recipeapp.ui.screens.PreviewScreen
import com.example.recipeapp.ui.theme.RecipeAppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecipeAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RecipeApp()
                }
            }
        }
    }
}

@Composable
fun RecipeApp() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    NavHost(navController = navController, startDestination = "main") {
        composable(
            route = "main?recipe={recipe}",
            arguments = listOf(
                navArgument("recipe") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val recipe = backStackEntry.arguments?.getString("recipe")?.let { Uri.decode(it) }
            MainScreen(
                navController = navController,
                recipe = recipe
            )
        }
        composable(
            route = "camera",
            enterTransition = { slideInVertically(initialOffsetY = { it }) },
            exitTransition = { slideOutVertically(targetOffsetY = { it }) }
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
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.fillMaxSize()
    )
}