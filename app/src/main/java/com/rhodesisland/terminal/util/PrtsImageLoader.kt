package com.rhodesisland.terminal.util

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * PRTS 立绘加载专用 OkHttp：浏览器 UA + Referer + 内存 Cookie。
 *
 * media.prts.wiki 有反热链保护：对未带 cookie 的请求返回一个 `document.location.reload()` 的
 * HTML 挑战页（HTTP 200，Coil 无法解码成图片）。挑战响应会下发 `sec` Cookie，带此 Cookie 的
 * 后续请求返回真实图片。因此这里统一：
 *  - 加浏览器 UA / Referer / Accept 头；
 *  - 用内存 CookieJar 持久化 `sec` cookie；
 *  - 启动时 [prewarm] 请求一次触发挑战、存入 cookie，此后所有立绘加载均正常。
 */
object PrtsImageLoader {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    private const val REFERER = "https://prts.wiki/"

    /** 预热目标：任意一张真实存在的 PRTS 立绘（阿米娅精二），用于触发挑战页下发 cookie。 */
    private const val WARM_URL = "https://media.prts.wiki/3/3f/%E7%AB%8B%E7%BB%98_%E9%98%BF%E7%B1%B3%E5%A8%85_2.png"

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", REFERER)
                    .header("Accept", "image/avif,image/webp,image/png,image/*,*/*;q=0.8")
                    .build()
                chain.proceed(req)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 预热：请求让反热链挑战页下发 sec cookie（结果非图片也无妨，关键是 cookie 进 jar）。带重试，幂等。 */
    fun prewarm() {
        runCatching {
            val warmUrl = WARM_URL.toHttpUrl()
            repeat(3) {
                okHttpClient.newCall(Request.Builder().url(WARM_URL).build())
                    .execute().use { it.body?.close() }
                // 已拿到 media.prts.wiki 的 cookie => 挑战已通过，后续图片请求都正常
                if (okHttpClient.cookieJar.loadForRequest(warmUrl).isNotEmpty()) return
            }
        }
    }

    /** 进程内 Cookie 存储：media.prts.wiki 域下的一次挑战 cookie 可复用于全域图片请求。 */
    private class InMemoryCookieJar : CookieJar {
        private val cache = mutableMapOf<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) cache[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cache[url.host] ?: emptyList()
    }
}
