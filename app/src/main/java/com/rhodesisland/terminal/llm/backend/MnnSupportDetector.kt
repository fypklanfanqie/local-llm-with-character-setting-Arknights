package com.rhodesisland.terminal.llm.backend

import android.content.Context
import com.chatbyyourside.llm.backend.MnnBridge

/**
 * MNN 后端支持检测
 *
 * - CPU：libMNN.so 加载成功即支持（[MnnBridge.mnnAvailable]）。
 * - GPU（OpenCL）：[openclAvailable] 先验探测系统 `libOpenCL.so` 是否可加载。锁定量产设备（小米 Adreno
 *   等）的 app 命名空间隔离 vendor OpenCL，dlopen 不可达；MNN 此时静默回退 CPU 但仍带 OpenCL 配置，
 *   该回退运行时产生乱码 logits（decode 全 "FFFFF"）。故探测不可达时直接判 GPU 不可用，AUTO 链走
 *   MNN_CPU。（已移除随包的桩 `libOpenCL.so`：它会让 MNN dlopen 到 0 平台假库而崩在 PipelineModule::load。）
 * - NPU（QNN）：需 libMNN.so 含 QNN 后端构建 + 运行时打包 `libQnnHtp.so`/`libQnnSystem.so`
 *   + 骁龙旗舰（[NpuSupportDetector]）+ **非锁定量产设备**（直接 HTP 需访问 `/dev/fastrpc-cdsp`，
 *   锁定机 SELinux 拒绝 app 访问 CDSP 会原生崩，见 [qnnReady]）。库未打包或锁定机 [qnnReady] 恒 false。
 */
object MnnSupportDetector {

    /** QNN NPU 是否就绪（标准构建，Task 11）：恒 false。
     *
     * 标准构建不含 QNN 运行时（libQnn*.so 已从打包排除），且锁定量产设备 SELinux 拒绝 app 访问
     * `/dev/fastrpc-cdsp`（QNN 直接 HTP 原生崩在 PipelineModule::load，SIGSEGV 不可 catch）。
     * 故标准构建不可选择/自动启用 QNN；legacy `MNN_NPU` 偏好由 resolver 解析为 CPU 并带
     * `QNN_UNAVAILABLE_IN_STANDARD_BUILD` 降级原因。未来实验 flavor 可在注入精确 SoC/运行时/模型矩阵
     * 后覆写（保留 [NpuSupportDetector] 检测结构）。详见 memory `mnn-qnn-htp-selinux-blocked`。
     */
    fun qnnReady(context: Context): Boolean = false

    /** 标准构建 QNN 不可用原因（供 UI/诊断展示）。 */
    const val QNN_STANDARD_BUILD_UNAVAILABLE = "标准构建不含 QNN 运行时，NPU 不可用（QNN 仅实验 flavor 支持）"

    // ===== OpenCL（GPU）可用性 =====
    // MNN 的 OpenCL 后端在运行时 dlopen 系统 `libOpenCL.so`。锁定量产设备（如本机小米 Adreno）的
    // app 命名空间 permitted_paths 不含 /vendor，dlopen 失败 -> MNN 静默回退 CPU 但带 OpenCL 配置
    // （attention_mode=8 / OP_ENCODER_NUMBER_FOR_COMMIT hint），该回退运行时产生乱码 logits（decode
    // 全 "FFFFF"、不触 EOS、跑到 max_tokens）。故在此**先验**探测 libOpenCL.so 是否可加载：不可加载
    // 则直接判 GPU 不可用，[BackendManager] AUTO 链过滤掉 MNN_GPU 走 MNN_CPU（与 MnnLlmChat 默认一致）。
    // 用 System.loadLibrary 探测而非查文件存在：本机 `/system/vendor/lib64/libOpenCL.so` 存在但命名空间
    // 不可达，只有真正 load 一次才能测出可达性。loadLibrary 成功则 lib 已进进程命名空间，MNN 后续 dlopen
    // 亦能复用；失败（UnsatisfiedLinkError）即不可达。结果缓存（loadLibrary 同 lib 二次调用无副作用）。
    @Volatile private var openclChecked = false
    @Volatile private var openclAvail = false
    fun openclAvailable(): Boolean {
        if (openclChecked) return openclAvail
        openclAvail = try {
            System.loadLibrary("OpenCL")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: Throwable) {
            false
        }
        openclChecked = true
        return openclAvail
    }

    /**
     * bootloader 是否锁定（量产机判定）。读 `ro.boot.flash.locked` / `ro.boot.verifiedbootstate`：
     * 任一明确指示锁定（locked=1 或 verifiedbootstate=green/red）即视为锁定。属性读不到（""）
     * 不判锁定（保守放过，交 UI 警告），以免在解锁但限制属性读取的设备上误藏 NPU。
     */
    private fun isBootloaderLocked(): Boolean {
        val locked = readSystemProp("ro.boot.flash.locked")
        val vbs = readSystemProp("ro.boot.verifiedbootstate")
        return locked == "1" || vbs == "green" || vbs == "red"
    }

    /** 反射读 `android.os.SystemProperties.get`（@hide），失败返回 ""。复用 RomDetector 公共实现。 */
    private fun readSystemProp(name: String): String =
        com.rhodesisland.terminal.util.RomDetector.readSystemProp(name) ?: ""
}
