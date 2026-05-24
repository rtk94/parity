package com.rknepp.parity.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.rknepp.parity.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object RetrofitFactory {

    private val jsonMediaType = "application/json".toMediaType()

    fun create(
        baseUrl: String,
        authInterceptor: AuthInterceptor? = null,
        authenticator: TokenAuthenticator? = null,
    ): Retrofit {
        val client = baseClientBuilder()
            .apply {
                if (authInterceptor != null) addInterceptor(authInterceptor)
                if (authenticator != null) authenticator(authenticator)
            }
            .build()
        return retrofit(baseUrl, client)
    }

    fun bareClient(): OkHttpClient = baseClientBuilder().build()

    fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(apiJson.asConverterFactory(jsonMediaType))
            .build()
    }

    private fun baseClientBuilder(): OkHttpClient.Builder {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
    }
}
