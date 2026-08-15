package com.rhodesisland.terminal.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * 进程内存采样器（Task 15/16）。
 *
 * 只在**阶段边界**采样（准入前 / 生成结束等），不逐 token 采样——getProcessMemoryInfo 本身
 * 有数十毫秒级开销，逐 token 采样会拖慢推理。读取本进程 total PSS（KB），换算字节。
 *
 * PSS 口径说明：total PSS 已包含 MNN 权重 mmap 常驻页、native 堆、OpenCL 驱动分配与 managed heap，
 * 是「进程总足迹」的权威近似；用于准入校准与 benchmark/promotion 的内存门禁。
 */
object ProcessMemorySampler {

    private const val TAG = "ProcessMemorySampler"

    /** 当前进程 PSS（字节）；失败/无权限返回 null（调用方按未知处理，不阻断推理）。 */
    fun currentProcessPssBytes(context: Context): Long? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return try {
            val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull() ?: return null
            if (info.totalPss > 0L) info.totalPss * 1024L else null
        } catch (e: Exception) {
            // 采样是旁路数据：任何失败只记日志，绝不影响推理主路径。
            Log.w(TAG, "进程 PSS 采样失败（忽略）: ${e.message}")
            null
        }
    }
}
