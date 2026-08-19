package com.rhodesisland.terminal.affinity

import android.content.Context
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.Character
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SpecialEventScript(
    val characterId: String,
    val threshold: Int,
    val title: String,
    val scene: String,
    val opening: String,
    val systemPrompt: String,
    val memorySummary: String,
    val toneTags: List<String>,
    val contentVersion: Int = 1,
)

/**
 * 内置特殊事件目录。正式资源由 assets/content/special_events.json 提供；缺少条目时使用
 * 基于干员既有人设的离线保底场景，保证升级用户不会因内容文件损坏而无法进入已解锁事件。
 */
class SpecialEventCatalog(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val scripts: Map<String, SpecialEventScript> by lazy {
        runCatching {
            context.assets.open("content/special_events.json").bufferedReader().use { reader ->
                json.decodeFromString<List<SpecialEventScript>>(reader.readText())
                    .associateBy { keyOf(it.characterId, it.threshold) }
            }
        }.getOrDefault(emptyMap())
    }

    fun eventFor(characterId: String, threshold: Int): SpecialEventScript {
        return scripts[keyOf(characterId, threshold)] ?: fallbackFor(
            Characters.ALL[characterId] ?: Character(
                id = characterId,
                name = "干员",
                code = characterId,
                role = "罗德岛干员",
                race = "",
                systemPrompt = "以罗德岛干员身份与博士自然交谈。",
            ),
            threshold,
        )
    }

    fun hasCompleteOfficialCoverage(): Boolean =
        Characters.ALL.keys.all { id -> AFFINITY_EVENT_THRESHOLDS.all { threshold -> scripts.containsKey(keyOf(id, threshold)) } }

    fun missingOfficialKeys(): List<String> = Characters.ALL.keys.flatMap { id ->
        AFFINITY_EVENT_THRESHOLDS.filter { threshold -> !scripts.containsKey(keyOf(id, threshold)) }
            .map { threshold -> keyOf(id, threshold) }
    }

    private fun fallbackFor(character: Character, threshold: Int): SpecialEventScript {
        val stage = when (threshold) {
            50 -> "第一次把值班之外的时间留给彼此"
            100 -> "在任务间隙共同处理一件只属于你们的难题"
            150 -> "面对角色不愿轻易说出的旧事与选择"
            else -> "在罗德岛漫长航程中确认彼此会同行的约定"
        }
        val scene = "罗德岛舰内，${character.role.ifBlank { "干员休整区" }}附近。${stage}。"
        return SpecialEventScript(
            characterId = character.id,
            threshold = threshold,
            title = "${character.name} · ${threshold}好感邂逅",
            scene = scene,
            opening = "博士，能占用您一点时间吗？这件事……我想只和您谈谈。",
            systemPrompt = character.systemPrompt + "\n\n【好感邂逅场景】\n$scene\n请由你主动开启对话，围绕这一场景与博士自然交流。保持角色人设，避免提及系统、好感度或游戏机制。",
            memorySummary = stage,
            toneTags = listOf("关系进展", "独处", character.role.ifBlank { "罗德岛" }),
        )
    }

    companion object {
        fun keyOf(characterId: String, threshold: Int): String = "$characterId#$threshold"
    }
}
