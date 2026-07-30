package com.rhodesisland.terminal.llm

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.rhodesisland.terminal.service.InferenceForegroundService

/**
 * 推理期间「保活提权」句柄。
 *
 * 由 [com.rhodesisland.terminal.llm.backend.BackendManager.generate] 在生成（含模型加载 prefill）
 * 前后调用 [begin]/[end]，启动 [InferenceForegroundService]（前台服务 + WakeLock），防止国产 ROM
 * 在用户切后台时冻结/杀死进程导致数分钟的 prefill 中断。
 *
 * 仿 [CpuBoostController] 模式：
 * - **begin 内部吞掉一切异常**（Android 12+ 从后台启动前台服务会抛
 *   `BackgroundServiceStartNotAllowedException`），绝不向上抛--本地生成始终源自前台「发送」点击，
 *   进入 generate 时 App 在前台，正常不会触发；触发时降级为无 FG 保护，生成照常进行。
 * - **引用计数**：若上层并发调用两次 begin（极少，聊天通常串行），只启动一次服务，两次 end 后才停止，
 *   避免第二个生成被第一个的 end 误停。
 * - **end 幂等**：未启动时 no-op。
 */
class InferenceSessionController(private val appContext: Context) {

    private val lock = Any()
    private var refCount = 0
    private var running = false

    /**
     * 启动推理保活前台服务。[backendLabel] 仅用于通知展示当前后端（CPU/GPU/NPU）。
     * 已在前台时计数 +1 直接返回（复用）。启动失败（后台受限等）置 running=false 以便下次重试。
     */
    fun begin(backendLabel: String) {
        synchronized(lock) {
            refCount++
            if (running) return
            running = true // 占位，防止并发重复启动
        }
        runCatching {
            val intent = Intent(appContext, InferenceForegroundService::class.java).apply {
                putExtra(InferenceForegroundService.EXTRA_BACKEND_LABEL, backendLabel)
            }
            ContextCompat.startForegroundService(appContext, intent)
            Log.i(TAG, "inference FG service started ($backendLabel)")
        }.onFailure {
            synchronized(lock) { running = false }
            Log.w(TAG, "startForegroundService failed (降级无 FG 保护): ${it.message}")
        }
    }

    /** 结束本次保活：引用计数归零才真正停止服务。幂等。 */
    fun end() {
        val shouldStop: Boolean
        synchronized(lock) {
            if (refCount > 0) refCount--
            shouldStop = refCount == 0 && running
            if (shouldStop) running = false
        }
        if (!shouldStop) return
        runCatching {
            appContext.stopService(Intent(appContext, InferenceForegroundService::class.java))
        }.onFailure { Log.w(TAG, "stopService failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "InferenceSession"
    }
}
