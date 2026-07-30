package com.rhodesisland.terminal.data.remote

import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Retrofit / OkHttp 客户端
 */
object RetrofitClient {

    /** OkHttp 客户端（供 VolcTtsClient + DirectLlmClient 共用） */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .eventListenerFactory(EventListener.Factory { EventListener.NONE })
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    /**
     * 直连对话商流式客户端：readTimeout / callTimeout 设 0（不超时），
     * 适配 SSE 长连接（生成慢时也不被 60s 读超时掐断）。
     */
    val streamingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }
}
