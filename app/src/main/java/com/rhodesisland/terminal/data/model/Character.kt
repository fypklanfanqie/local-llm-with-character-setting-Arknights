package com.rhodesisland.terminal.data.model

import kotlinx.serialization.Serializable

/**
 * 角色人设定义。
 */
@Serializable
data class Character(
    val id: String,
    val name: String,
    val code: String,
    val role: String,
    val race: String,
    val ttsEnabled: Boolean = false,
    val image: String = "",
    val voiceFile: String = "",
    val voiceLines: VoiceLines? = null,
    /** 刷卡时「随机问好」用的问候语池；为空时回退到通用问候池。 */
    val greetings: List<String> = emptyList(),
    /** 干员技能名（游戏内技能1/2/3，仅名称；全量干员由 ExtraCharacters 生成）。 */
    val skills: List<String> = emptyList(),
    /** 干员天赋名（游戏内天赋1/2，仅名称）。 */
    val talents: List<String> = emptyList(),
    val systemPrompt: String,
    val isCustom: Boolean = false,
) {
    val watermarkName: String
        get() = when (id) {
            "amiya" -> "AMIYA"
            "eyjafjalla" -> "EYJAFJALLA"
            "goldenglow" -> "GOLDENGLOW"
            "mudrock" -> "MUDROCK"
            "la-pluma" -> "LA PLUMA"
            "logos" -> "LOGOS"
            "honeyberry" -> "HONEYBERRY"
            "haruka" -> "HARUKA"
            "wisdel" -> "WIS'ADEL"
            "zuole" -> "ZUO LE"
            "magallan" -> "MAGALLAN"
            "shu" -> "SHU"
            "surtr" -> "SURTR"
            "xinoge" -> "CANTABILE"
            "lin" -> "LIN"
            "lappland" -> "LAPPLAND"
            "executor" -> "EXECUTOR"
            "mon3tr" -> "Mon3tr"
            "xingyuan" -> "ASTGENNE"
            "texas" -> "TEXAS"
            else -> id.uppercase()
        }
}

@Serializable
data class VoiceLines(
    val jp: String = "",
    val cn: String = "",
)
