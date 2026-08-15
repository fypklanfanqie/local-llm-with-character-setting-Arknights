package com.rhodesisland.terminal.llm.metrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * nativeGenerateStream 返回的紧凑版本化 `GenerationSummary` JSON 的 Kotlin 模型（Task 4 Step 1；v2 见 Task 1）。
 *
 * Wire 格式（native `mnn_jni.cpp` 产出，Kotlin 侧 [parse] 严格校验）：
 * ```json
 * {"v":2,"completionReason":"EOS","promptTokens":123,"generatedTokens":45,
 *  "prefillUs":1234567,"decodeUs":890123,"reuseKv":1,"callbackCount":12,"callbackBytes":456,
 *  "firstDeltaUs":890,"errorStage":null,"errorMessage":null,
 *  "decodeStepTokens":1,"thinkingConfigAccepted":true,
 *  "reasoningEndUs":null,"firstBodyDeltaUs":null,"errorCode":null}
 * ```
 *
 * v2（Task 1）新增字段全部可空/默认：v1 摘要缺这些字段时解析自动填充默认值（向后兼容），
 * 旧 native（v1）与 Kotlin v2 模型可继续互通。
 *
 * 严格性：[parse] 对版本（!= [VERSION] 且 != [V1_VERSION]）、未知 completionReason / errorStage 字符串、
 * 非法 JSON 一律拒绝（返回 null，调用方按 [CompletionReason.BACKEND_FAILURE] 处理）；同版本新增字段宽容
 * （`ignoreUnknownKeys`），避免字段追加即契约破裂。
 */
@Serializable
data class NativeGenerationSummary(
    /** Wire 协议版本，必须为 [VERSION]（v2）或 [V1_VERSION]（v1 向后兼容），否则拒收。 */
    @SerialName("v") val version: Int,
    /** 完成原因字符串，取值 [CompletionReason] 枚举名（native 侧 best-effort；Kotlin 侧有更高优先级推导）。 */
    val completionReason: String,
    /** 本轮 prefill 的 prompt token 数（远小于完整历史 = 前缀复用生效）。 */
    val promptTokens: Int,
    /** 本轮生成的 token 数。 */
    val generatedTokens: Int,
    val prefillUs: Long,
    val decodeUs: Long,
    /** 是否复用了 KV 前缀缓存：1=是 / 0=否 / -1=取不到。 */
    val reuseKv: Int,
    /** 流式回调次数（StreamBatcher 实际 flush 次数）。 */
    val callbackCount: Int,
    /** 流式回调累计 UTF-8 字节数。 */
    val callbackBytes: Long,
    /** 首 delta（首个可见字符回调）相对生成起点的时延（us）；未产生可见输出为 null。 */
    val firstDeltaUs: Long? = null,
    /** 出错阶段（[InferenceStage] 枚举名）；无错误为 null。 */
    val errorStage: String? = null,
    val errorMessage: String? = null,
    // ---- v2（Task 1）：全部可空/默认，v1 摘要缺省时取默认值 ----
    /** 实际生效的 decode 步长（native clamp 到 1..4）；v1 摘要默认 1（等价 v1 逐 token 行为）。 */
    val decodeStepTokens: Int = 1,
    /** `set_config(enable_thinking)` 是否被 native 接受（true=生效；false=回退模型默认）；未知为 null。 */
    val thinkingConfigAccepted: Boolean? = null,
    /** 检测到 `</think>` 的时刻（us，相对生成起点）；无思考段为 null。 */
    val reasoningEndUs: Long? = null,
    /** 首个非思考正文回调的时刻（us，相对生成起点）；无正文为 null。 */
    val firstBodyDeltaUs: Long? = null,
    /** 错误码字符串（如 `PREFILL_EXCEPTION` / `DECODE_EXCEPTION` / `LOAD_*`）；无错误为 null。 */
    val errorCode: String? = null,
) {
    /** [reuseKv] 原始值 → 语义值：1=true、0=false、其余（-1 取不到）=null。 */
    val kvReuse: Boolean?
        get() = when (reuseKv) {
            1 -> true
            0 -> false
            else -> null
        }

    /**
     * 把摘要转为 nativeGetMetrics 同构的指标数组
     * `[tps, prefillUs, decodeUs, promptLen, genLen, reuseKv]`（Task 4 Step 3）。
     *
     * tps 由摘要的 decode 耗时与生成 token 数推算（decode_us>0 才有意义），避免二次 native 调用；
     * reuseKv 语义映射与 [nativeGetMetrics] 一致（1→1、0→0、-1→0，下游按 !=0 判复用）。
     */
    fun toMetricsArray(): FloatArray = floatArrayOf(
        if (decodeUs > 0L) generatedTokens * 1_000_000f / decodeUs else 0f,
        prefillUs.toFloat(),
        decodeUs.toFloat(),
        promptTokens.toFloat(),
        generatedTokens.toFloat(),
        reuseKv.toFloat(),
    )

    companion object {
        /** 当前 wire 协议版本（v2，Task 1）；native 与 Kotlin 必须一致。 */
        const val VERSION = 2

        /** v1 wire 协议版本（向后兼容：v1 摘要缺 v2 字段时用默认值填充）。 */
        const val V1_VERSION = 1

        /** 测试与 MnnBackend 复用（同模块 test 源集可访问）。 */
        internal val summaryJson = Json { ignoreUnknownKeys = true }

        /**
         * 严格解析 native 返回的摘要 JSON。
         * @return 校验通过的对象；版本不符 / 未知 reason / 未知 stage / 非法 JSON 返回 null。
         */
        fun parse(json: String): NativeGenerationSummary? {
            return try {
                val raw = summaryJson.decodeFromString<NativeGenerationSummary>(json)
                if (raw.version != VERSION && raw.version != V1_VERSION) return null
                if (CompletionReason.entries.none { it.name == raw.completionReason }) return null
                if (raw.errorStage != null &&
                    InferenceStage.entries.none { it.name == raw.errorStage }) return null
                raw
            } catch (e: Exception) {
                null
            }
        }
    }
}
