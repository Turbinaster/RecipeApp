package com.example.recipeapp

import android.content.Context
import android.content.SharedPreferences

object CacheManager {
    private const val PREFS_NAME = "recipe_cache"
    private const val KEY_RECIPE = "daily_recipe"
    private const val KEY_TIMESTAMP = "last_fetch_timestamp"
    private const val FETCH_INTERVAL_HOURS = 2 * 60 * 60 * 1000 // 12 часов в миллисекундах

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveDailyRecipe(context: Context, recipe: String) {
        getPrefs(context).edit().apply {
            putString(KEY_RECIPE, recipe)
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
    }

    fun getDailyRecipe(context: Context): String? {
        return getPrefs(context).getString(KEY_RECIPE, null)
    }

    fun shouldFetchNewRecipe(context: Context): Boolean {
        val lastFetch = getPrefs(context).getLong(KEY_TIMESTAMP, 0)
        return lastFetch == 0L || (System.currentTimeMillis() - lastFetch) >= FETCH_INTERVAL_HOURS
    }
}