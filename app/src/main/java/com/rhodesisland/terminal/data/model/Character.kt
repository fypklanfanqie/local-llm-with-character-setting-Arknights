package com.rhodesisland.terminal.data.model

import kotlinx.serialization.Serializable

/**
 * 干员角色定义
 * 对应小程序 utils/characters.js 的 CHARACTERS
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
