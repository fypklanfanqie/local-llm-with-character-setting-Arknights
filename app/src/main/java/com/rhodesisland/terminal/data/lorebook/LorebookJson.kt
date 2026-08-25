package com.rhodesisland.terminal.data.lorebook

import com.rhodesisland.terminal.data.model.Lorebook
import com.rhodesisland.terminal.data.model.LorebookEntry
import com.rhodesisland.terminal.data.model.LorebookInsertPosition
import com.rhodesisland.terminal.data.model.LorebookSecondaryLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * SillyTavern 世界书 JSON 解析与导出（移植自大众版）。
 *
 * 兼容三种形态（手动遍历 JsonElement，比定义多套 DTO 更抗格式漂移）：
 * 1. ST 世界书导出标准形态 `{"entries": {"0": {...}}}`（字符串键 map）；
 * 2. 社区变体 `{"entries": [...]}`（数组）；
 * 3. 角色卡 V2 内嵌 `data.character_book.entries[]`（蛇形字段 keys/secondary_keys/
 *    insertion_order/enabled，[LorebookSecondaryLogic] 等价信息在 extensions 内），
 *    书名取 `character_book.name`。
 *
 * 导出为反向映射的 ST 兼容 entries map，可直接被酒馆再导入。
 */
object LorebookJson {

    /** 单次导入条目软上限：超出截断并在 [ParseResult.Ok.warning] 提示（防超大文件写爆 DataStore）。 */
    const val MAX_IMPORT_ENTRIES = 5000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed interface ParseResult {
        /** [name] 为 null 时由调用方回退文件名；[warning] 非空表示有损导入（如超限截断）。 */
        data class Ok(val name: String?, val entries: List<LorebookEntry>, val warning: String?) : ParseResult
        data class Fail(val message: String) : ParseResult
    }

    fun parseSillyTavern(text: String): ParseResult {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { return ParseResult.Fail("不是有效的 JSON 文件") }

        // 书名：顶层 name 或 V2 character_book.name
        val v2Book = (root["data"] as? JsonObject)?.get("character_book") as? JsonObject
        val name = root.str("name") ?: v2Book?.str("name")

        // 条目集合：优先顶层 entries（map/array 均可），其次 V2 character_book.entries
        val rawEntries: List<JsonElement> = when (val el = root["entries"] ?: v2Book?.get("entries")) {
            is JsonObject -> el.values.toList()
            is JsonArray -> el
            else -> return ParseResult.Fail("未找到 entries 字段，不是世界书或角色卡 JSON")
        }
        if (rawEntries.isEmpty()) return ParseResult.Fail("世界书没有任何条目")

        var truncated = false
        val entries = rawEntries.asSequence()
            .filterIsInstance<JsonObject>()
            .mapIndexedNotNull { idx, o -> parseEntry(o, idx) }
            .toList()
            .let { if (it.size > MAX_IMPORT_ENTRIES) { truncated = true; it.take(MAX_IMPORT_ENTRIES) } else it }

        if (entries.isEmpty()) return ParseResult.Fail("没有可识别的条目（content 均为空）")
        val warning = when {
            truncated -> "条目数超过上限 $MAX_IMPORT_ENTRIES，已截断导入前 $MAX_IMPORT_ENTRIES 条"
            else -> null
        }
        return ParseResult.Ok(name, entries, warning)
    }

    /**
     * 单条目解析。ST 与 V2 字段名并存读取；content 为空的条目直接跳过（无注入价值的脏行）。
     * position 映射：0→BEFORE_CHAR、1→AFTER_CHAR、仅显式 4→AT_DEPTH、其余归 AFTER_CHAR。
     */
    private fun parseEntry(o: JsonObject, index: Int): LorebookEntry? {
        val content = o.str("content")?.trim() ?: return null
        if (content.isEmpty()) return null
        val id = "lbe-${System.currentTimeMillis()}-$index"
        val enabled = if ("disable" in o) !(o.bool("disable") ?: false) else (o.bool("enabled") ?: true)
        val useProbability = o.bool("useProbability") ?: true
        val rawProbability = o.int("probability")
        val ext = o.obj("extensions")
        return LorebookEntry(
            id = id,
            title = o.str("comment") ?: o.str("name") ?: "", // ST comment；V2 条目级 name
            keys = o.strList("key", "keys").orEmpty(),
            secondaryKeys = o.strList("keysecondary", "secondary_keys").orEmpty(),
            logic = when (o.int("selectiveLogic")) {
                1 -> LorebookSecondaryLogic.NOT_ALL
                2 -> LorebookSecondaryLogic.NOT_ANY
                3 -> LorebookSecondaryLogic.AND_ALL
                else -> LorebookSecondaryLogic.AND_ANY
            },
            content = content,
            constant = o.bool("constant") ?: false,
            enabled = enabled,
            position = when (o.int("position")) {
                0 -> LorebookInsertPosition.BEFORE_CHAR
                4 -> LorebookInsertPosition.AT_DEPTH
                // ST 0/1=角色定义前后；2-6(↑AN/↓AN/@D/↑EM/↓EM) 与 V2 缺省均归 AFTER_CHAR
                else -> LorebookInsertPosition.AFTER_CHAR
            },
            depth = o.int("depth") ?: 4,
            order = o.int("order") ?: o.int("insertion_order") ?: 100,
            probability = when {
                !useProbability -> 100
                rawProbability != null -> rawProbability.coerceIn(1, 100)
                else -> 100
            },
            caseSensitive = o.bool("case_sensitive") ?: false,
            matchWholeWords = o.bool("match_whole_words") ?: false,
            scanDepthOverride = o.int("scan_depth")?.takeIf { it > 0 } ?: ext?.int("scan_depth")?.takeIf { it > 0 },
            preventRecursion = o.bool("preventRecursion") ?: ext?.bool("prevent_recursion") ?: false,
            excludeRecursion = o.bool("excludeRecursion") ?: ext?.bool("exclude_recursion") ?: false,
        )
    }

    /** 序列化为 ST 兼容的世界书导出格式（entries 字符串键 map），可直接被酒馆导入。 */
    fun toSillyTavernJson(book: Lorebook): String = buildJsonObject {
        put("name", book.name)
        put("entries", buildJsonObject {
            book.entries.forEachIndexed { i, e ->
                put(i.toString(), buildJsonObject {
                    put("uid", i)
                    put("key", JsonArray(e.keys.map { JsonPrimitive(it) }))
                    put("keysecondary", JsonArray(e.secondaryKeys.map { JsonPrimitive(it) }))
                    put("comment", e.title)
                    put("content", e.content)
                    put("constant", e.constant)
                    put("selective", e.secondaryKeys.isNotEmpty())
                    put("selectiveLogic", when (e.logic) {
                        LorebookSecondaryLogic.AND_ANY -> 0
                        LorebookSecondaryLogic.NOT_ALL -> 1
                        LorebookSecondaryLogic.NOT_ANY -> 2
                        LorebookSecondaryLogic.AND_ALL -> 3
                    })
                    put("addMemo", true)
                    put("order", e.order)
                    put("position", when (e.position) {
                        LorebookInsertPosition.BEFORE_CHAR -> 0
                        LorebookInsertPosition.AFTER_CHAR -> 1
                        LorebookInsertPosition.AT_DEPTH -> 4
                    })
                    put("disable", !e.enabled)
                    put("excludeRecursion", e.excludeRecursion)
                    put("preventRecursion", e.preventRecursion)
                    put("probability", e.probability)
                    put("useProbability", e.probability < 100)
                    put("depth", e.depth)
                    put("scan_depth", e.scanDepthOverride?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("case_sensitive", e.caseSensitive)
                    put("match_whole_words", e.matchWholeWords)
                    put("displayIndex", i)
                })
            }
        })
    }.toString()

    // ===== JsonElement 宽松取值辅助 =====

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    /** 多候选字段名依次尝试数组字符串列表（兼容 ST key / V2 keys）。 */
    private fun JsonObject.strList(vararg names: String): List<String>? {
        for (n in names) {
            val el = this[n] as? JsonArray ?: continue
            return el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .filter { it.isNotBlank() }
                .map { it.trim() }
        }
        return null
    }
}
