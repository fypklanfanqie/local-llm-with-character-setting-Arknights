package com.chatbyyourside.llm.backend

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.rhodesisland.terminal.llm.backend.OpenClProbeResult
import com.rhodesisland.terminal.llm.backend.OpenClProbeRunner
import kotlinx.serialization.json.Json
import java.io.File
/**
 * OpenCL 执行探测服务（Task 10）。
 *
 * 运行于独立进程 `:mnn_probe`：崩溃（驱动 SIGSEGV 等）不会波及主进程。onStartCommand 中调用
 * [nativeProbe]（backend_probe_jni.cpp，动态加载 libOpenCL.so + 极简 kernel 校验），把
 * [OpenClProbeResult] JSON 写入 cache 目录结果文件，随后 stopSelf 并结束自身进程。
 * 主进程 [OpenClProbeRunner] 负责 pending journal、绑定启动、轮询与超时/死亡判定。
 *
 * **跨进程通道（文件，非 SharedPreferences）**：SharedPreferences 的 MODE_MULTI_PROCESS 在
 * Android N+ 被忽略，同一实例的 getString 走进程内内存缓存——若主进程首轮轮询（~100ms）时
 * 探测尚未完成写结果（成功路径含 dlopen + kernel，约 120ms），读到 null 后被缓存、之后
 * 永远读不到，导致 15s 超时误判探测失败。改用文件读写：每次读盘、无缓存，跨进程可靠。
 */
class OpenClProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val raw = try {
            nativeProbe()
        } catch (t: Throwable) {
            Log.e(TAG, "nativeProbe 异常: ${t.message}")
            "{\"success\":false,\"failureCode\":\"KERNEL_EXECUTION\"}"
        }
        val result = runCatching { json.decodeFromString<OpenClProbeResult>(raw) }
            .getOrElse { OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_KERNEL_EXECUTION) }
        try {
            // 文件通道：写结果文件 + 清 pending 标记（主进程按结果文件存在与否轮询，无缓存）。
            val resultFile = File(cacheDir, OpenClProbeRunner.RESULT_FILE)
            resultFile.writeText(json.encodeToString(result))
            File(cacheDir, OpenClProbeRunner.PENDING_FILE).delete()
        } catch (t: Throwable) {
            Log.w(TAG, "写结果失败: ${t.message}")
        }
        Log.i(TAG, "probe finished success=${result.success} failure=${result.failureCode}")
        stopSelf(startId)
        // 探测进程自终止：避免残留空进程，也便于主进程按 Binder 死亡判定失败。
        Process.killProcess(Process.myPid())
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "OpenClProbeService"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        init {
            runCatching { System.loadLibrary("backend_probe") }
                .onFailure { Log.e(TAG, "System.loadLibrary(backend_probe) 失败: ${it.message}") }
        }

        /** backend_probe_jni.cpp 的入口：动态加载 OpenCL 并运行极简 kernel，返回结果 JSON。 */
        @JvmStatic
        external fun nativeProbe(): String
    }
}
