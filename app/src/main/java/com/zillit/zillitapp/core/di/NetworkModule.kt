package com.zillit.zillitapp.core.di

import com.zillit.zillitapp.BuildConfig
import com.zillit.zillitapp.core.network.ChatGptTokenProvider
import com.zillit.zillitapp.core.network.UnconfiguredChatGptTokenProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
        // encodeDefaults stays false so null fields drop out of `moduledata`,
        // reproducing v2's Gson behaviour. Do not turn this on.
        encodeDefaults = false
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false

        engine {
            // Default platform trust. v2 installed a preconfigured OkHttpClient whose
            // X509TrustManager accepted every certificate and whose hostnameVerifier
            // always returned true — and it was applied unconditionally, so release
            // builds shipped with TLS validation disabled. That is deliberately not
            // carried over; certificate validation stays on in every flavour.
            preconfigured = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        install(ContentNegotiation) { json(json) }

        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_SECONDS * 1000
            socketTimeoutMillis = READ_TIMEOUT_SECONDS * 1000
        }

        // No Ktor Logging plugin: ApiLogger prints the full exchange (URL, request body,
        // response body) from a single place and also persists it. Installing both meant
        // two interleaved, half-complete accounts of the same call in Logcat.
    }

    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val REQUEST_TIMEOUT_MILLIS = 60_000L
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindsModule {

    @Binds
    @Singleton
    abstract fun bindChatGptTokenProvider(
        impl: UnconfiguredChatGptTokenProvider,
    ): ChatGptTokenProvider
}
