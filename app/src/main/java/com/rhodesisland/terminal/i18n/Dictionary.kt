package com.rhodesisland.terminal.i18n

/**
 * 词典注册表：各批次词典分文件维护（`i18n/dict/En**.kt` / `Ja**.kt`），在此汇总。
 *
 * 为什么要分文件：全库约 2,200 条界面文案，按模块分批翻译时把词条写在各自的批次文件里，
 * 便于并行推进、逐批 review，也避免所有人改同一个巨型 Map 产生冲突。
 *
 * 合并语义：**先出现的定义胜出**（`listOf` 顺序即优先级）。同一句中文出现在多个批次里
 * 只要译文一致就无害；译文不一致由 [dictionaryConflicts] + 单测拦截。
 */
internal val EnDictionaries: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "00Common" to En00CommonEntries,
    "01SettingsA" to En01SettingsAEntries,
    "01SettingsB" to En01SettingsBEntries,
    "01SettingsC" to En01SettingsCEntries,
    "02Chat" to En02ChatEntries,
    "03Guide" to En03GuideEntries,
    "03GuideB" to En03GuideBEntries,
    "04Video" to En04VideoEntries,
    "05GroupChat" to En05GroupChatEntries,
    "06Lorebook" to En06LorebookEntries,
    "07Moment" to En07MomentEntries,
    "08Novel" to En08NovelEntries,
    "09System" to En09SystemEntries,
    "10Export" to En10ExportEntries,
)

/** 日文词典注册表，顺序与 [EnDictionaries] 一致。 */
internal val JaDictionaries: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "00Common" to Ja00CommonEntries,
    "01SettingsA" to Ja01SettingsAEntries,
    "01SettingsB" to Ja01SettingsBEntries,
    "01SettingsC" to Ja01SettingsCEntries,
    "02Chat" to Ja02ChatEntries,
    "03Guide" to Ja03GuideEntries,
    "03GuideB" to Ja03GuideBEntries,
    "04Video" to Ja04VideoEntries,
    "05GroupChat" to Ja05GroupChatEntries,
    "06Lorebook" to Ja06LorebookEntries,
    "07Moment" to Ja07MomentEntries,
    "08Novel" to Ja08NovelEntries,
    "09System" to Ja09SystemEntries,
    "10Export" to Ja10ExportEntries,
)

/** 合并各批次词典；重复 key 保留最先出现的译文。 */
internal fun mergeDictionaries(
    sources: List<Pair<String, List<Pair<String, String>>>>,
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    sources.forEach { (_, entries) ->
        entries.forEach { (zh, text) -> out.putIfAbsent(zh, text) }
    }
    return out
}

/** 同一句中文在不同批次文件里译文不一致 —— 由单测拦截（术语/语气必须跨模块统一）。 */
internal fun dictionaryConflicts(
    sources: List<Pair<String, List<Pair<String, String>>>>,
): List<String> {
    val seen = HashMap<String, Pair<String, String>>()
    val conflicts = mutableListOf<String>()
    sources.forEach { (name, entries) ->
        entries.forEach { (zh, text) ->
            val prev = seen[zh]
            when {
                prev == null -> seen[zh] = name to text
                prev.second != text -> conflicts += "\"$zh\"：${prev.first}=\"${prev.second}\" / $name=\"$text\""
            }
        }
    }
    return conflicts
}

/** 英文词典总表（L10n 查表用）。 */
internal val EnStrings: Map<String, String> = mergeDictionaries(EnDictionaries)

/** 日文词典总表（L10n 查表用）。 */
internal val JaStrings: Map<String, String> = mergeDictionaries(JaDictionaries)
