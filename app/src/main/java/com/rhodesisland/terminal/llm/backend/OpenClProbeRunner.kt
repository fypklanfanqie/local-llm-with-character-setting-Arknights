package com.rhodesisland.terminal.llm.backend

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import com.chatbyyourside.llm.backend.OpenClProbeService

/**
 * 主进程 OpenCL 探测协调器（Task 10 Step 3）。
 *
 * 流程：写 pending journal -> 启动 `:mnn_probe` 进程的 [OpenClProbeService] -> 轮询结果
 * （cache 目录结果文件）-> 超时/进程死亡/畸形结果均视为失败。
 * 探测可经 [launchProbe]/[readResult]/[clock] 注入（测试用 fake probe 覆盖成功/普通失败/超时/死亡）。
 *
 * **跨进程通道（文件，非 SharedPreferences）**：见 [OpenClProbeService] 的通道说明——同一
 * SharedPreferences 实例的 getString 走进程内缓存，首轮读到 null 后不再重读盘，成功路径
 * （约 120ms）晚于首轮轮询（~100ms）时必然超时误判。文件通道每次读盘、无缓存。
 */
class OpenClProbeRunner(
    private val launchProbe: () -> Unit,
    private val readResult: () -> OpenClProbeResult?,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 执行一次探测，返回终态结果。超时返回 [OpenClProbeResult.FAILURE_TIMEOUT]；
     * 结果畸形（无法解析）返回 [OpenClProbeResult.FAILURE_PROCESS_DEATH]（服务死亡未写结果）。
     */
    suspend fun runProbe(): OpenClProbeResult {
        launchProbe()
        val start = clock()
        while (clock() - start < PROBE_TIMEOUT_MS) {
            delay(POLL_MS)
            val raw = readResult() ?: continue
            return raw
        }
        return OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_TIMEOUT)
    }

    companion object {
        /** 探测结果文件（cache 目录；:mnn_probe 进程写、主进程轮询读）。 */
        const val RESULT_FILE = "opencl_probe_result.json"
        /** pending 标记文件（探测进行中；结果写入后由服务删除）。 */
        const val PENDING_FILE = "opencl_probe_pending"
        // 15s：覆盖 :mnn_probe 隔离进程冷启动 + 驱动初始化（首次 dlopen libOpenCL.so 可能较慢）。
        // 原 5s 在部分设备冷启动时超时 -> probe 失败 -> 24h COOLDOWN -> OpenCL 不入链 -> GPU 不可用。
        const val PROBE_TIMEOUT_MS = 15000L
        private const val POLL_MS = 100L

        /** 真实实现：写 journal -> startService(:mnn_probe)。 */
        fun real(context: Context): OpenClProbeRunner {
            val cacheDir = context.cacheDir
            val json = Json { ignoreUnknownKeys = true }
            return OpenClProbeRunner(
                launchProbe = {
                    // 清旧结果 + 写 pending 标记，再启动探测进程。
                    File(cacheDir, RESULT_FILE).delete()
                    File(cacheDir, PENDING_FILE).writeText("1")
                    context.startService(Intent(context, OpenClProbeService::class.java))
                },
                readResult = {
                    val file = File(cacheDir, RESULT_FILE)
                    if (!file.exists()) return@OpenClProbeRunner null
                    val raw = runCatching { file.readText() }.getOrNull() ?: return@OpenClProbeRunner null
                    runCatching { json.decodeFromString<OpenClProbeResult>(raw) }
                        .getOrElse { OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_PROCESS_DEATH) }
                },
            )
        }
    }
}
