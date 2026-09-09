package com.rhodesisland.terminal.i18n

/**
 * 日文词典：key = 中文原文（界面里 `t("...")` 的原样字符串），value = 日文译文。
 *
 * 维护约定同 [EnEntries]：key 与中文一字不差、占位符下标一致、按批次追加分区。
 */
internal val JaEntries: List<Pair<String, String>> = listOf(

    // ===== 批次 0：言語設定 =====
    "语言" to "言語",
    "跟随系统" to "システムに従う",
    "界面语言（AI 回复语言不受影响）" to "表示言語（AI の返答言語には影響しません）",
    "当前：{0}" to "現在：{0}",

)

internal val JaStrings: Map<String, String> = JaEntries.toMap()
