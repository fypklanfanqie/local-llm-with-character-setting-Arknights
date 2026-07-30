package com.rhodesisland.terminal.llm.backend

import android.util.Log
import com.rhodesisland.terminal.data.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * MNN 后端 JNI 桥接
 *
 * 对应 C 层 libmnn_jni.so（由 cpp/mnn_jni.cpp 编译，移植自 MNN `llm_session` + `llm_mnn_jni`）：
 *   Java_com_rhodesisland_terminal_llm_backend_MnnBridge_nativeCreate
 *   Java_com_rhodesisland_terminal_llm_backend_MnnBridge_nativeGenerateStream
 *   Java_com_rhodesisland_terminal_llm_backend_MnnBridge_nativeStop
 *   Java_com_rhodesisland_terminal_llm_backend_MnnBridge_nativeRelease
 *   Java_com_rhodesisland_terminal_llm_backend_MnnBridge_nativeGetMetrics
 *
 * 与 [com.rhodesisland.terminal.llm.CpuSysBridge] 共用同一套静态回调流式机制：C 层每生成一个 token，按 UTF-8
 * 字符边界切分后通过 JNI 回调本类静态 [nativeCallback]，再由 [onToken] 分发到 Kotlin；
 * 中断用静态 [abort] 标志，C 层每轮轮询 [shouldAbort]。
 *
 * 模型格式：MNN `.mnn` 目录（config.json + llm.mnn + llm.mnn.weight + tokenizer.txt），
 * [nativeCreate] 传入目录里的 `config.json` 路径，由 MNN `Llm::createLLM` 加载。聊天模板
 * 由 MNN 按各模型自带的 `llm_config.json`/tokenizer 应用（Qwen=ChatML，Llama/Gemma/Phi 各异），
 * 故本后端接收**消息列表**（[nativeGenerateStream] 的 messagesJson）而非预格式化的 ChatML 串。
 *
 * 后端选择：MNN `set_config` 的 `backend_type` 字段--`"cpu"` / `"opencl"`（GPU）/ `"qnn"`（NPU）。
 * `"qnn"` 需 libMNN.so 含 QNN 后端构建 + 运行时加载 libQnnHtp*.so（见 QnnModule），否则 [nativeCreate] 失败。
 *
 * 库加载顺序：c++_shared -> libMNN.so -> libmnn_jni.so。libMNN.so 缺失时 [nativeAvailable]=false，
 * MNN 后端整体不可用、[BackendManager] 回退 llama.cpp（CPU/Vulkan）。
 */
class MnnBridge {

    companion object {
        private const val TAG = "MnnBridge"

        /** token 回调（由 C 层 JNI 调用，转发给当前 generateStream 调用方） */
        @Volatile
        @JvmStatic
        internal var onToken: ((String) -> Unit)? = null

        /** 中断标志（C 层每轮检查，true 则提前结束） */
        @Volatile
        @JvmStatic
        internal var abort: Boolean = false

        /** 依赖顺序加载：c++_shared -> MNN 引擎 -> 本工程 JNI 包装 libmnn_jni.so */
        private val LIBS = arrayOf(
            "c++_shared",
            "MNN",       // MNN 引擎（libMNN.so，含 LLM + OpenCL + ARM82 + transformer-fuse）
            "mnn_jni",   // 本工程 JNI 包装（含 MnnBridge_* 符号）
        )

        @Volatile
        private var bridgeLoaded = false

        @Volatile
        private var mnnLibLoaded = false

        init {
            for (lib in LIBS) {
                try {
                    System.loadLibrary(lib)
                    when (lib) {
                        "mnn_jni" -> bridgeLoaded = true
                        "MNN" -> mnnLibLoaded = true
                    }
                    Log.i(TAG, "✓ $lib loaded")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "✗ $lib FAILED: ${e.message}")
                }
            }
            Log.i(TAG, "bridgeLoaded=$bridgeLoaded mnnLibLoaded=$mnnLibLoaded")
        }

        /** JNI 包装库是否可用（native 调用的前提） */
        val nativeAvailable: Boolean
            get() = bridgeLoaded && mnnLibLoaded

        /** libMNN.so 是否加载成功 */
        val mnnAvailable: Boolean
            get() = mnnLibLoaded

        /** C 层通过 JNI 调用此方法推送一个 token（按 UTF-8 字符边界切分后的字节） */
        @JvmStatic
        fun nativeCallback(bytes: ByteArray) {
            onToken?.invoke(String(bytes, Charsets.UTF_8))
        }

        /** C 层轮询此标志决定是否中断 */
        @JvmStatic
        fun shouldAbort(): Boolean = abort

        /**
         * 把消息列表序列化为 MNN JNI 可解析的 JSON：
         * `[{"role":"system","content":"..."},{"role":"user","content":"..."},...]`
         */
        fun toMessagesJson(messages: List<ChatMessage>): String {
            val arr = JSONArray()
            for (msg in messages) {
                val role = when (msg.role) {
                    "system", "user", "assistant" -> msg.role
                    else -> "user"
                }
                arr.put(JSONObject().apply {
                    put("role", role)
                    put("content", msg.content)
                })
            }
            return arr.toString()
        }
    }

    /**
     * 加载 MNN 模型并初始化引擎。
     * @param configPath 模型目录里的 `config.json` 绝对路径
     * @param backendType `"cpu"` / `"opencl"` / `"qnn"`
     * @param threads CPU 线程数（OpenCL 后端 MNN 默认忽略，由其内部调度）
     * @param contextLen 上下文长度（CPU 后端用于钉 `kv_max_length` KV 上限）
     * @param lookahead CPU 模式下是否启用 lookahead n-gram 投机解码（1.5–3×，无需 draft 模型）；
     *        非 cpu 后端忽略。仅在 cpu 后端于 set_config 追加 `speculative_type=lookahead`。
     * @param temperature 采样温度。MNN 采样器在 load() 内一次性构建，故必须在此（load 前）传入才能
     *        保证生效；改值由 [BackendManager] 纳入重载指纹，触发下次重载。
     * @param topP top-p 采样（MNN 配置键为 `topP`，非 top_p）。AppConfig 常量。
     * @param repeatPenalty 重复惩罚（MNN 配置键为 `repetition_penalty`，非 repeat_penalty）。AppConfig 常量。
     * @return 引擎句柄（非 0 成功，0 失败）
     */
    external fun nativeCreate(
        configPath: String,
        backendType: String,
        threads: Int,
        contextLen: Int,
        lookahead: Boolean,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
    ): Long

    /**
     * 流式推理（阻塞，内部逐 token 回调 [nativeCallback]）。
     * @param handle [nativeCreate] 返回的句柄
     * @param messagesJson 消息列表 JSON（[toMessagesJson]），MNN 据此应用模型自带 chat 模板
     * @param maxTokens 最大生成 token 数
     * @param temperature 采样温度
     * @param topP top-p 采样
     * @param repeatPenalty 重复惩罚
     * @param enableThinking 是否启用深度思考。经 set_config 注入 jinja context `enable_thinking`，
     *        控制推理模型（Qwen3/R1）chat 模板是否插入 `<think>` 前缀；运行时生效，无需重载。
     *        false 时模型跳过推理直接作答；true 时生成 reasoning 后再作答。
     *        无 enable_thinking 分支的模板（Llama/Gemma）忽略，无害。
     * @return 完整生成文本的 UTF-8 字节
     */
    external fun nativeGenerateStream(
        handle: Long,
        messagesJson: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        enableThinking: Boolean,
    ): ByteArray

    /** 中断当前生成（下一轮 token 前检测） */
    external fun nativeStop(handle: Long)

    /** 释放引擎（模型/上下文/KV 缓存） */
    external fun nativeRelease(handle: Long)

    /**
     * 性能指标：[tokensPerSecond, prefillUs, decodeUs, promptLen, genLen, reuseKv]。
     * 取自 MNN `LlmContext` 的 prefill_us/decode_us/gen_seq_len；reuseKv 取自 `Llm::reuse_kv()`
     * （1=本轮复用了 KV 前缀/0=否/-1=取不到），用于验证多轮前缀复用是否生效。
     */
    external fun nativeGetMetrics(handle: Long): FloatArray

    /**
     * 最近一次 [nativeCreate] 的加载失败原因（对应 C 层 g_last_load_error）。
     * 在 [nativeCreate] 返回 0 后立即调用，取真实失败原因（如 `Llm::load 异常: ...`、
     * `Llm::load() 失败 (backend=cpu)`，含 CPU 安全配置重试的结果）填入 [MnnBackend.lastErrorMessage]，
     * 再由 [BackendManager] 汇总上报，定位「所有后端均加载失败」的芯片相关根因。
     * 空串表示无错误/上次加载成功。
     */
    external fun nativeGetLastError(): String
}
