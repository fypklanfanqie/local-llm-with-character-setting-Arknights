package com.rhodesisland.terminal.i18n

/**
 * 英文词典 —— 批次 00Common：公共（按钮/状态/通用词）
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 英文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val En00CommonEntries: List<Pair<String, String>> = listOf(

    // 语言设置（批次 0）
    "语言" to "Language",
    "跟随系统" to "System default",
    "界面语言（AI 回复语言不受影响）" to "Interface language (AI replies are unaffected)",
    "当前：{0}" to "Current: {0}",

)
