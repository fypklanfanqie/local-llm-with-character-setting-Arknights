package com.rhodesisland.terminal.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃日志采集（对齐大众版 CrashReporter 的落盘格式）。
 *
 * 安装全局 [Thread.setDefaultUncaughtExceptionHandler]：任何进程（主进程 / `:mnn_probe`）的
 * Java 未捕获异常都会把「堆栈 + 设备指纹」写入 `filesDir/crash/crash_<时间戳>.log`，供设置页
 * 「崩溃日志」入口查看 / 复制 / 分享。用户无需 adb，闪退后从 设置 → 崩溃日志 复制发给开发者
 * 即可定位真因（OPPO/鸿蒙启动闪退排查）。
 *
 * 原生崩溃（SIGSEGV 等，Java handler 拦不住）不在本类覆盖范围，需靠真机 logcat。
 */
object CrashCapture {

    private const val TAG = "CrashCapture"
    private const val DIR_NAME = "crash"

    @Volatile
    private var installed = false
    private var crashDir: File? = null

    /**
     * 安装全局崩溃 handler。应在 Application.onCreate 最开头调用（含 `:mnn_probe` 进程），
     * 确保任何 Java 未捕获异常都先落盘再交给原 handler（原 handler 仍负责终止进程）。
     * 幂等：重复调用直接返回。
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        crashDir = File(context.filesDir, DIR_NAME)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(thread, throwable) }
                .onFailure { Log.w(TAG, "写崩溃日志失败: ${it.message}") }
            prev?.uncaughtException(thread, throwable)
        }
        installed = true
        Log.i(TAG, "CrashCapture installed (dir=${crashDir?.absolutePath})")
    }

    /** 崩溃日志目录（不存在时返回目录对象，读取方自行判空）。 */
    fun crashLogDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 手动记录一条事件日志（非崩溃，如启动初始化异常兜底），与崩溃日志同目录。 */
    fun logEvent(context: Context, tag: String, message: String) {
        runCatching {
            val dir = crashDir ?: File(context.filesDir, DIR_NAME).also { crashDir = it }
            dir.mkdirs()
            File(dir, "event_${timestamp()}.log").writeText(buildLogHeader() + "\n[$tag] $message\n")
        }.onFailure { Log.w(TAG, "记录事件日志失败: ${it.message}") }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val dir = crashDir ?: return
        dir.mkdirs()
        val file = File(dir, "crash_${timestamp()}.log")
        val sw = StringWriter()
        sw.append(buildLogHeader())
        sw.appendLine("崩溃线程: ${thread.name} (id=${thread.id})")
        sw.appendLine("进程: ${processName()}")
        sw.appendLine("---- 堆栈 ----")
        sw.append(Log.getStackTraceString(throwable))
        file.writeText(sw.toString())
        Log.e(TAG, "崩溃日志已写入 ${file.absolutePath}")
    }

    private fun buildLogHeader(): String = buildString {
        appendLine("===== 崩溃日志 =====")
        appendLine("时间: ${timestamp()}")
        appendLine("厂商: ${Build.MANUFACTURER}")
        appendLine("品牌: ${Build.BRAND}")
        appendLine("型号: ${Build.MODEL}")
        appendLine("设备: ${Build.DEVICE}")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        appendLine("系统指纹: ${Build.FINGERPRINT}")
        appendLine("版本增量: ${Build.VERSION.INCREMENTAL}")
        appendLine("进程: ${processName()}")
    }

    private fun processName(): String = runCatching {
        Process.myProcessName() ?: "unknown"
    }.getOrDefault("unknown")

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
