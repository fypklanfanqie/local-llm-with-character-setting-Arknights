package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.llm.profile.ResidencyPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 模型驻留控制器（Task 14）。
 *
 * 统一管理「何时延迟释放已加载模型」：后台/切云/模型变更后按 [ResidencyPolicy.keepAliveMs] 宽限，
 * 前台返回在宽限内取消释放；trim 低档/热紧急立即释放；生成期间绝不释放（native handle 活跃）。
 * 释放走 [releaseAll]（BackendManager 的 deferred-safe 释放：JNI 活跃时延迟到生成结束 finally），
 * 且同一时刻只允许一个后端/模型驻留（释放当前再加载新的）。
 *
 * 纯生命周期状态机，scope/clock 可注入（测试用 runTest 虚拟时间）。
 */
class ModelResidencyController(
    private val releaseAll: suspend () -> Unit,
    private val balancedKeepAliveMs: Long = DEFAULT_BALANCED_KEEP_ALIVE_MS,
    private val maxSpeedKeepAliveMs: Long = DEFAULT_MAX_SPEED_KEEP_ALIVE_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    @Volatile
    private var appForeground: Boolean = true
    @Volatile
    private var generationActive: Boolean = false
    @Volatile
    private var residencyMs: Long = balancedKeepAliveMs

    private var graceJob: Job? = null

    /** 当前是否允许驻留（后台 + 未在生成）。 */
    private fun canReside(): Boolean = !appForeground && !generationActive

    fun onAppForegroundChanged(foreground: Boolean) {
        appForeground = foreground
        if (foreground) cancelRelease() else scheduleRelease()
    }

    fun onGenerationStateChanged(active: Boolean) {
        generationActive = active
        if (active) cancelRelease()
        else if (!appForeground) scheduleRelease()
    }

    fun onProviderChanged(providerStaysLocal: Boolean) {
        // 切云/切 provider 后本地模型不应驻留。
        if (!providerStaysLocal) scheduleRelease()
    }

    fun onModelChanged(policy: ResidencyPolicy) {
        residencyMs = policy.keepAliveMs
        if (canReside()) scheduleRelease()
    }

    /** trim 低档/关键 或 热紧急：立即释放（生成中交由 [releaseAll] 延迟安全释放）。 */
    fun onTrimMemory(immediate: Boolean) {
        if (immediate) releaseNow()
    }

    fun onThermalEmergency() = releaseNow()

    private fun scheduleRelease() {
        if (!canReside()) return
        cancelRelease()
        graceJob = scope.launch {
            delay(residencyMs)
            releaseAll()
        }
    }

    private fun cancelRelease() {
        graceJob?.cancel()
        graceJob = null
    }

    private fun releaseNow() {
        cancelRelease()
        // 生成中由 BackendManager.release 的 deferred-safe 机制处理（JNI 返回后释放）。
        scope.launch { releaseAll() }
    }

    companion object {
        const val DEFAULT_BALANCED_KEEP_ALIVE_MS = 15_000L
        const val DEFAULT_MAX_SPEED_KEEP_ALIVE_MS = 60_000L
    }
}
