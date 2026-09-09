package com.rhodesisland.terminal.i18n

/**
 * 英文词典：key = 中文原文（界面里 `t("...")` 的原样字符串），value = 英文译文。
 *
 * 维护约定：
 * - **key 必须与界面代码里的中文一字不差**（含标点、空格、省略号），否则查不到就回退中文；
 * - 带占位符的模板用 `{0}` / `{1}`，与中文模板下标一致；
 * - 不改写中文原文的语气（本应用是明日方舟同人终端，术语要跨批次一致）；
 * - 按批次追加分区，重复 key 由 `L10nDictionaryTest` 拦截。
 */
internal val EnEntries: List<Pair<String, String>> = listOf(

    // ===== 批次 0：语言设置 =====
    "语言" to "Language",
    "跟随系统" to "System default",
    "界面语言（AI 回复语言不受影响）" to "Interface language (AI replies are unaffected)",
    "当前：{0}" to "Current: {0}",

)

internal val EnStrings: Map<String, String> = EnEntries.toMap()
