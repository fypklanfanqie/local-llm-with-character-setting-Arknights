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
    val systemPrompt: String,
    val isCustom: Boolean = false,
) {
    val watermarkName: String
        get() = name
}

@Serializable
data class VoiceLines(
    val jp: String = "",
    val cn: String = "",
)
