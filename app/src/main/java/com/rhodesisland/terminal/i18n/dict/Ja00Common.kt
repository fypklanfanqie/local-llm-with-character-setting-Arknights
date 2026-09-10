package com.rhodesisland.terminal.i18n

/**
 * 日文词典 —— 批次 00Common：公共（按钮/状态/通用词）
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 日文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val Ja00CommonEntries: List<Pair<String, String>> = listOf(

    // 言語設定（批次 0）
    "语言" to "言語",
    "跟随系统" to "システムに従う",
    "界面语言（AI 回复语言会同步切换）" to "表示言語（AI の返答言語も切り替わります）",
    "当前：{0}" to "現在：{0}",

)
