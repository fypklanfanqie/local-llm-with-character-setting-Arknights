package com.rhodesisland.terminal.perfmon

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import com.rhodesisland.terminal.provider.local.LocalChatProvider
import java.io.File
import java.io.RandomAccessFile

/**
 * 性能指标采集器
 *
 * 采集 6 类硬件指标供浮窗展示：CPU 占用、CPU 大核频率、GPU 占用、NPU 占用、温度、内存。
 * Token 速率由推理流式回调实时更新（[updateTokenRate]），不依赖 500ms 刷新周期。
 *
 * 适配说明：本仓库为 MNN 推理（CPU/OpenCL GPU/QNN NPU）。
 *  - CPU 占用：优先 `/proc/stat` 差值（系统级；root / 部分机型可读）；Android 10+ SELinux
 *    禁止 untrusted_app 读 `/proc/stat` 时，回退 [Process.getElapsedCpuTime] 进程 CPU 利用率
 *    （按核数归一化到 0-100%，永远可读、无需权限）——本地推理负载即进程 CPU，正是本场景最相关指标；
 *  - GPU/NPU 占用：按 sysfs 读取真实值（CPU 推理时通常为 0/低）。非 root 设备 sysfs 不可读，
 *    此时返回 `-1f` 哨兵表示「本设备不可用」，由浮窗显示 N/A（诚实而非伪造 0）；
 *  - [activeBackend] 为当前 MNN 后端映射（CPU/GPU/NPU，浮窗据此高亮对应行）；
 *  - 大核频率优先用 [LocalChatProvider.getBigCoreFreqGHz]（已封装 native 读取 + 异常保护），
 *    返回 0 时回退 sysfs `scaling_cur_freq`；
 *  - 温度直接读 `/sys/class/thermal/`（文件读取，不依赖本工程 android.jar 缺失的
 *    ThermalManager 系统服务，与现有 ThermalMonitor 的 PowerManager 方案互补）。
 *
 * 所有文件读取均有 try-catch，并提供多路 fallback，避免任何采集失败导致浮窗崩溃。
 *
 * @param context 应用上下文（用于 ActivityManager / BatteryManager）
 * @param localChatProvider 本地 Provider，提供 native 频率读取与热状态文案
 */
class PerformanceCollector(
    private val context: Context,
    private val localChatProvider: LocalChatProvider,
) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** 上一次 CPU 采样（/proc/stat 累计值），用于计算差值得到瞬时使用率 */
    @Volatile private var prevCpuTotal: Long = 0
    @Volatile private var prevCpuIdle: Long = 0

    /** 进程 CPU 兜底采样（[Process.getElapsedCpuTime] + 墙钟），用于 /proc/stat 不可读时 */
    @Volatile private var prevProcCpuMs: Long = 0L
    @Volatile private var prevProcWallMs: Long = 0L

    /** Token 速率（由推理回调更新），单位 tok/s */
    @Volatile private var tokenRate: Float = 0f
    @Volatile private var lastLog: String = "等待推理..."

    fun updateTokenRate(rate: Float) {
        tokenRate = rate
    }

    fun updateLog(log: String) {
        lastLog = log
    }

    /** 采集全部指标，返回 [RealtimeMetrics] */
    fun collect(): RealtimeMetrics {
        // Token 速率：本地推理进行中时读后端实时快照（onToken 回调算出的 tokens/s，原子读取、零 native 调用），
        // 替代按流式 flush 近似计数（CJK 一 token 多 flush 会偏高）；快照暂未刷新（如生成刚起步 currentTps=0）
        // 时回退 flush 近似值 [tokenRate]。精确 native tps（gen_seq_len/decode_us）仅在生成结束的 finally
        // 受控点写入 InferenceTurnRecord，供基准/健康库消费，不进 500ms 浮窗。非生成态归零（与 UI 终态一致）。
        val activeTps = if (localChatProvider.isGenerating()) {
            val snapshotTps = localChatProvider.getActiveTps()
            if (snapshotTps > 0f) snapshotTps else tokenRate
        } else 0f
        return RealtimeMetrics(
            tokenRate = activeTps,
            cpuUsage = collectCpuUsage(),
            cpuBigCoreFreqGHz = collectBigCoreFreqGHz(),
            gpuUsage = collectGpuUsage(),
            npuUsage = collectNpuUsage(),
            temperatureC = collectTemperature(),
            usedMemoryMB = collectUsedMemoryMB(),
            totalMemoryMB = collectTotalMemoryMB(),
            activeBackend = localChatProvider.getActiveBackend(), // CPU / GPU / NPU（MNN 后端映射）
            lastLog = lastLog,
        )
    }

    // ========== CPU 使用率（/proc/stat 差值 -> 进程 CPU 兜底）==========
    private fun collectCpuUsage(): Float {
        // 主路径：/proc/stat 系统级使用率（root / 部分机型可读）
        val sysUsage = collectCpuUsageFromProcStat()
        if (sysUsage >= 0f) return sysUsage
        // /proc/stat 不可读（Android 10+ 非 root）：回退进程 CPU 利用率（永远可读）
        return collectCpuUsageFromProcess()
    }

    /** @return 0-100 系统级使用率；-1f 表示 /proc/stat 不可读 */
    private fun collectCpuUsageFromProcStat(): Float {
        return try {
            // use{} 保证 readLine 返回 null 或抛异常时也关闭 RandomAccessFile，避免每 tick 泄漏 fd
            val line = RandomAccessFile("/proc/stat", "r").use { it.readLine() } ?: return -1f

            // 格式: cpu user nice system idle iowait irq softirq steal
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 5) return -1f

            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()
            val iowait = if (parts.size > 5) parts[5].toLong() else 0
            val irq = if (parts.size > 6) parts[6].toLong() else 0
            val softirq = if (parts.size > 7) parts[7].toLong() else 0
            val steal = if (parts.size > 8) parts[8].toLong() else 0

            val total = user + nice + system + idle + iowait + irq + softirq + steal
            val totalIdle = idle + iowait

            val totalDiff = total - prevCpuTotal
            val idleDiff = totalIdle - prevCpuIdle

            prevCpuTotal = total
            prevCpuIdle = totalIdle

            if (totalDiff <= 0) 0f
            else ((1f - idleDiff.toFloat() / totalDiff.toFloat()) * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            -1f
        }
    }

    /**
     * 进程 CPU 利用率兜底：[Process.getElapsedCpuTime]（本进程全部线程累计 CPU 时间，ms）
     * 对墙钟差值，按核数归一化到 0-100%。永远可读、无需权限。
     *
     * 语义：本地 LLM 推理负载即进程 CPU，正是本场景最相关指标。多线程满载可接近 100%。
     * 首次采样（prev=0）仅建立基线返回 0，避免 boot-avg 误读。
     */
    private fun collectCpuUsageFromProcess(): Float {
        return try {
            val curCpu = Process.getElapsedCpuTime()          // ms，进程全部线程累计
            val curWall = SystemClock.elapsedRealtime()       // ms，单调墙钟
            if (prevProcWallMs == 0L) {                       // 首次：建立基线
                prevProcCpuMs = curCpu
                prevProcWallMs = curWall
                return 0f
            }
            val deltaCpu = (curCpu - prevProcCpuMs).coerceAtLeast(0L)
            val deltaWall = (curWall - prevProcWallMs).coerceAtLeast(1L)
            prevProcCpuMs = curCpu
            prevProcWallMs = curWall
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            // deltaCpu/deltaWall = 占用核数；按总核数归一化为「占总 CPU 容量百分比」
            ((deltaCpu.toFloat() / deltaWall.toFloat()) * 100f / cores).coerceIn(0f, 100f)
        } catch (e: Exception) {
            0f
        }
    }

    // ========== CPU 大核频率 ==========
    private fun collectBigCoreFreqGHz(): Float {
        return try {
            // 优先用 LocalChatProvider 已封装的 native 读取（内部带异常保护）
            val ghz = localChatProvider.getBigCoreFreqGHz()
            if (ghz > 0) return ghz

            // Fallback: 直接读 sysfs（大核 CPU ID 倒序尝试）
            for (cpuId in intArrayOf(7, 6, 5, 4, 3)) {
                val path = "/sys/devices/system/cpu/cpu$cpuId/cpufreq/scaling_cur_freq"
                val file = File(path)
                if (file.exists()) {
                    val freqKHz = file.readText().trim().toIntOrNull() ?: continue
                    if (freqKHz > 0) return freqKHz / 1_000_000f
                }
            }
            0f
        } catch (e: Exception) {
            0f
        }
    }

    // ========== GPU 使用率（Adreno + Mali + PowerVR/MediaTek 兼容）==========
    // @return 0-100 真实占用；-1f 表示本设备 sysfs 不可读（非 root 常见）
    private fun collectGpuUsage(): Float {
        return try {
            // 方法1: 高通 Adreno GPU - gpubusy
            val gpuBusy = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
            if (gpuBusy.exists()) {
                val content = gpuBusy.readText().trim()
                val parts = content.split(" ").filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    val busy = parts[0].toLongOrNull() ?: 0L
                    val total = parts[1].toLongOrNull() ?: 1L
                    if (total > 0)
                        return (busy.toFloat() / total * 100f).coerceIn(0f, 100f)
                }
            }

            // 方法2: Adreno gpu_load
            val gpuLoad = File("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load")
            if (gpuLoad.exists()) {
                return (gpuLoad.readText().trim().toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
            }

            // 方法3: Mali GPU
            val mali = File("/sys/class/misc/mali0/device/utilisation")
            if (mali.exists()) {
                return (mali.readText().trim().toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
            }

            // 方法4: PowerVR / IMG（部分 MediaTek/Exynos）
            val imgGpu = File("/sys/devices/gpu.0/load")
            if (imgGpu.exists()) {
                // PowerVR 格式常为 "busy cycles@clock"，取首段
                val v = imgGpu.readText().trim().split("[ @]".toRegex()).firstOrNull()?.toFloatOrNull()
                if (v != null) return v.coerceIn(0f, 100f)
            }

            // 方法5: MediaTek GED
            val mtkGed = File("/sys/kernel/ged/hal/gpu_utilization")
            if (mtkGed.exists()) {
                return (mtkGed.readText().trim().toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
            }

            -1f // 所有路径都不可读 -> 本设备不可用
        } catch (e: Exception) {
            -1f
        }
    }

    // ========== NPU 使用率（DSP/HTP/APU 兼容）==========
    // @return 0-100 真实占用；-1f 表示本设备 sysfs 不可读（非 root 常见）
    private fun collectNpuUsage(): Float {
        return try {
            // 方法1: 高通 HTP/DSP 负载
            val htpLoad = File("/sys/class/htp/htp0/load")
            if (htpLoad.exists()) {
                return (htpLoad.readText().trim().toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
            }

            // 方法2: DSP 负载
            val dspLoad = File("/sys/class/dsp/dsp0/load")
            if (dspLoad.exists()) {
                return (dspLoad.readText().trim().toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
            }

            // 方法3: MediaTek APU
            val apuLoad = File("/sys/class/apusys/apu0/load")
            if (apuLoad.exists()) {
                return (apuLoad.readText().trim().toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
            }

            -1f // 所有路径都不可读 -> 本设备不可用
        } catch (e: Exception) {
            -1f
        }
    }

    // ========== 温度（遍历 thermal zones，fallback BatteryManager）==========
    private fun collectTemperature(): Float {
        // /sys/class/thermal/thermal_zone*/temp 按 Linux 内核 ABI 为毫摄氏度（如 45000 = 45℃）。
        // 遍历取相关传感器最大值，并做量纲/越界校验，避免常见失真：
        //  - 占位/无效值（0、255000、负值等）被当成真实温度 -> 显示 0℃ 或 255℃；
        //  - 个别厂商传感器以「度」为单位（raw≈45），一律 /1000 会塌缩成 0.045℃；
        // 仅接受 raw ∈ [1000, 120000]（即 1℃–120℃ 的毫摄氏度读数），其余一律丢弃。
        // 传感器优先级：type 含「cpu」的 CPU 专属传感器（最能反映推理负载）>
        // soc/apc/tsens/skin/x_therm 等广义 SoC/表面温度；都读不到时回退电池温度。
        return try {
            val thermalDir = File("/sys/class/thermal/")
            var maxCpuTemp = 0f   // type 含 cpu 的专属传感器
            var maxSocTemp = 0f   // soc/apc/tsens/skin/x_therm 等广义传感器（含 cpu 专属）

            thermalDir.listFiles()?.forEach { zone ->
                try {
                    val tempFile = File(zone, "temp")
                    if (!tempFile.exists()) return@forEach
                    val raw = tempFile.readText().trim().toFloatOrNull() ?: return@forEach
                    if (raw < 1000f || raw > 120_000f) return@forEach  // 量纲/越界过滤

                    // type 读不到时按空串处理（无法归类则不计入，但不影响 temp 已读到的其它分支）
                    val type = runCatching { File(zone, "type").readText().trim() }
                        .getOrDefault("")

                    val isCpuSpecific = type.contains("cpu", ignoreCase = true)
                    val isSocRelated = isCpuSpecific ||
                        type.contains("soc", ignoreCase = true) ||
                        type.contains("apc", ignoreCase = true) ||
                        type.contains("tsens", ignoreCase = true) ||
                        type.contains("skin", ignoreCase = true) ||
                        type.contains("x_therm", ignoreCase = true)

                    if (isCpuSpecific && raw > maxCpuTemp) maxCpuTemp = raw
                    if (isSocRelated && raw > maxSocTemp) maxSocTemp = raw
                } catch (e: Exception) { }
            }

            val best = when {
                maxCpuTemp > 0f -> maxCpuTemp      // 优先 CPU 专属传感器
                maxSocTemp > 0f -> maxSocTemp      // 回退广义 SoC/表面温度
                else -> return collectBatteryTemperature()
            }
            best / 1000f  // millidegree -> degree
        } catch (e: Exception) {
            collectBatteryTemperature()
        }
    }

    /** Fallback: 通过电池温度估算（读不到 thermal zone 时） */
    private fun collectBatteryTemperature(): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temp > 0) temp / 10f else 0f
        } catch (e: Exception) {
            0f
        }
    }

    // ========== 内存 ==========
    private fun collectUsedMemoryMB(): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
    }

    private fun collectTotalMemoryMB(): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }
}

/**
 * 浮窗单次刷新快照。
 *
 * @param activeBackend 当前激活的推理后端（MNN 后端映射：CPU/GPU/NPU）
 */
data class RealtimeMetrics(
    val tokenRate: Float,
    val cpuUsage: Float,
    val cpuBigCoreFreqGHz: Float,
    val gpuUsage: Float,
    val npuUsage: Float,
    val temperatureC: Float,
    val usedMemoryMB: Long,
    val totalMemoryMB: Long,
    val activeBackend: BackendType,
    val lastLog: String,
)
