package com.example.recipeapp.data

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

data class ApiResponse(val response: String)

interface ApiService {
    @Multipart
    @POST("upload")
    fun uploadImage(@Part file: MultipartBody.Part): Call<ApiResponse>
}