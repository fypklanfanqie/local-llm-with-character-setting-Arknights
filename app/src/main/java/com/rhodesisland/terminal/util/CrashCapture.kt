package com.rhodesisland.terminal.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 全局崩溃捕获：把未捕获的 Java/Kotlin 异常堆栈落盘，供事后定位。
 *
 * OPPO(ColorOS) / 鸿蒙(HarmonyOS) 用户反馈「点图标就闪退」「使用中闪退」，但无崩溃平台/堆栈，
 * 无法定位根因。本类把「闪退」变成可读的 txt：`getExternalFilesDir(null)/crash_logs/crash_<时间戳>.txt`，
 * 用户用文件管理器 / USB 导出即可，开发者据堆栈修根因。
 *
 * 局限：`Thread.setDefaultUncaughtExceptionHandler` 只能捕获 Java/Kotlin 异常；native 崩溃
 * （SIGSEGV，如 .so 内 / OpenCL / AGSL shader）不经过它，堆栈日志为空时须靠真机 `adb logcat`
 * 定位。因此在 [RhodesApp.onCreate] 顶部 install（含 :mnn_probe 等所有进程），不阻塞默认行为
 * （仍终止应用）。
 */
object CrashCapture {

    private const val TAG = "CrashCapture"
    private const val DIR_NAME = "crash_logs"
    private const val FILE_PREFIX = "crash_"

    @Volatile
    private var installed = false

    /** 安装全局未捕获异常处理器。重复调用幂等；必须先于任何可能抛异常的代码路径。 */
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            val appContext = context.applicationContext
            val default = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                writeCrash(appContext, thread, throwable)
                default?.uncaughtException(thread, throwable)
            }
        }
    }

    /** 最近一次崩溃日志文本（无则 null）。SettingsScreen「查看最近崩溃日志」用。 */
    fun latestCrashText(context: Context): String? {
        val dir = crashDir(context) ?: return null
        val latest = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) }
            ?.maxByOrNull { it.lastModified() } ?: return null
        return runCatching { latest.readText() }.getOrNull()
    }

    /** 崩溃日志目录（外部 files 优先，内部 files 兜底）。 */
    private fun crashDir(context: Context): File? = runCatching {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        dir
    }.getOrNull()

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context) ?: return
        val file = File(dir, "$FILE_PREFIX${System.currentTimeMillis()}.txt")
        runCatching {
            file.writeText(buildString {
                appendLine("time=${System.currentTimeMillis()}")
                appendLine("thread=${thread.name}")
                appendLine(Log.getStackTraceString(throwable))
                appendLine("--- device ---")
                appendLine("brand=${Build.BRAND}")
                appendLine("manufacturer=${Build.MANUFACTURER}")
                appendLine("model=${Build.MODEL}")
                appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("abi=${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine("--- caused by ---")
                appendLine(Log.getStackTraceString(throwable.cause ?: throwable))
            })
        }.onFailure { Log.w(TAG, "写崩溃日志失败: ${it.message}") }
        // 保留最近 20 份，避免长期堆积占空间。
        runCatching {
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_CRASH_FILES)
                ?.forEach { it.delete() }
        }
    }

    private const val MAX_CRASH_FILES = 20
}
