package com.rhodesisland.terminal.data.model

/**
 * Seedance 视频生成模型型号。
 *
 * 支持中国火山方舟 Seedance 2.0 系列；`modelId` 直接作为创建任务请求的 `model` 字段值：
 *  - [STANDARD]：Seedance 2.0 标准模型，支持全部分辨率；
 *  - [FAST]：Seedance 2.0 快速模型，仅支持 480p/720p。
 *
 * 持久化用 [storageKey]（= modelId）；[fromStorageKey] 还原时对未知/空值保守回落 [DEFAULT]。
 */
enum class SeedanceModelVariant(val modelId: String) {
    STANDARD("doubao-seedance-2-0-260128"),
    FAST("doubao-seedance-2-0-fast-260128");

    /** 该模型支持的最短视频时长（秒）；各模型均从 4 秒起。 */
    val minDurationSeconds: Int get() = 4

    /** 该模型支持的最长视频时长（秒）；2.0 系列统一为 15 秒。 */
    val maxDurationSeconds: Int get() = 15

    /** 该模型支持的分辨率档位：Fast 仅 480p/720p；标准支持全部。 */
    val supportedResolutions: Set<SeedanceResolution> get() =
        if (this == FAST) setOf(SeedanceResolution.P480, SeedanceResolution.P720)
        else SeedanceResolution.entries.toSet()

    /** Room 持久化键 = modelId（请求原值，稳定且唯一）。 */
    val storageKey: String get() = modelId

    companion object {
        val DEFAULT: SeedanceModelVariant = STANDARD

        /** 从存储键还原；未知/空值保守回落 [DEFAULT]（SeedanceConfig 默认档位）。 */
        fun fromStorageKey(value: String?): SeedanceModelVariant =
            entries.firstOrNull { it.modelId == value } ?: DEFAULT
    }
}

/**
 * 视频分辨率。标准模型支持全部档位，Fast 模型仅支持 [P480]/[P720]。
 *
 * 持久化用 [storageKey]（= 枚举名，如 "P720"）；[fromStorageKey] 对未知/空值保守回落 [DEFAULT]。
 */
enum class SeedanceResolution {
    P480, P720, P1080, P4K;

    /** Room 持久化键 = 枚举名（P480/P720/P1080/P4K）。 */
    val storageKey: String get() = name

    companion object {
        val DEFAULT: SeedanceResolution = P720

        /** 从存储键还原；未知/空值保守回落 [DEFAULT]（SeedanceConfig 默认档位）。 */
        fun fromStorageKey(value: String?): SeedanceResolution =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/**
 * 视频画幅比例，`apiValue` 为创建任务请求中使用的字符串。
 *
 * 持久化用 [storageKey]（= apiValue）；[fromStorageKey] 对未知/空值保守回落 [DEFAULT]。
 */
enum class SeedanceRatio(val apiValue: String) {
    /** 竖屏 9:16（默认）。 */
    PORTRAIT("9:16"),
    /** 横屏 16:9。 */
    LANDSCAPE("16:9"),
    /** 方形 1:1。 */
    SQUARE("1:1"),
    /** 经典竖屏 3:4。 */
    PORTRAIT_CLASSIC("3:4"),
    /** 经典横屏 4:3。 */
    LANDSCAPE_CLASSIC("4:3"),
    /** 超宽屏 21:9。 */
    ULTRAWIDE("21:9"),
    /** 由模型自适应画幅。 */
    ADAPTIVE("adaptive");

    /** Room 持久化键 = apiValue（"9:16" 等请求原值）。 */
    val storageKey: String get() = apiValue

    companion object {
        val DEFAULT: SeedanceRatio = PORTRAIT

        /** 从存储键还原；未知/空值保守回落 [DEFAULT]（SeedanceConfig 默认档位）。 */
        fun fromStorageKey(value: String?): SeedanceRatio =
            entries.firstOrNull { it.apiValue == value } ?: DEFAULT
    }
}

/**
 * Seedance 视频生成配置（DataStore 聚合持久化，Task 3 接入设置页）。
 *
 * - [generateAudio] 固定为 true，不可配置；
 * - 时长为 4–15 秒固定整数，默认 5 秒；
 * - 方舟基地址来自用户配置，默认中国官方地址；API Key 不得硬编码，也不进入 Room/日志；
 * - 人物图由角色立绘提供（调用方单独传入），背景图与场景描述可选。
 */
data class SeedanceConfig(
    val baseUrl: String = "https://ark.cn-beijing.volces.com/api/v3",
    val apiKey: String = "",
    /**
     * 中转站媒体协议（POST /v1/media/generate）使用的 `model` 字段值。
     * 官方方舟路径不使用本字段（模型由 [variant] 决定）。默认值对齐 dm1124/灵科中转站的
     * Seedance 2.0 参考生视频模型（kwvideo-v2-ref）。
     */
    val relayModelId: String = "kwvideo-v2-ref",
    val variant: SeedanceModelVariant = SeedanceModelVariant.STANDARD,
    val resolution: SeedanceResolution = SeedanceResolution.P720,
    val ratio: SeedanceRatio = SeedanceRatio.PORTRAIT,
    val durationSeconds: Int = 5,
    val watermark: Boolean = false,
    val backgroundImagePath: String? = null,
    val sceneDescription: String = "",
) {
    /** 固定开启视频语音（Seedance 2.0 生成音频不可关闭）。 */
    val generateAudio: Boolean get() = true
}
