package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.config.AppConfig

/**
 * 朋友圈提示词构建（纯函数，JVM 可测）。
 *
 * 两条链路：
 * 1. [buildPostSystem] + [buildPostUserMessage]：让对话 LLM 生成「文案 + 生图提示词」的 JSON。
 *    system 稳定区 = 人设 + 世界观等（调用方拼接后传入）；user 消息含最近聊天摘要与图片数要求。
 * 2. [buildReplyPrompt]：用户评论后让发帖角色以人设回复（单条 user 消息，走 chatOnce）。
 */
object MomentPromptBuilder {

    /**
     * 发圈任务的 user 指令。要求模型输出严格 JSON：`{"caption": "...", "imagePrompt": "..."}`
     * ——caption 是要发的朋友圈文字（第一人称、口语化、贴近人设与近期聊天）；
     * imagePrompt 是给生图模型的英文提示词（描述一张该角色会发的照片）。
     */
    fun buildPostUserMessage(
        characterName: String,
        recentChat: String,
        imageCount: Int,
    ): String = buildString {
        append("你现在是 $characterName 本人，要发一条朋友圈。")
        if (recentChat.isNotBlank()) {
            append("\n\n你们最近的聊天：\n$recentChat")
        }
        append("\n\n要求：")
        append("\n1. 先输出严格 JSON（不要代码围栏、不要多余解释）：{\"caption\": \"朋友圈正文\", \"imagePrompt\": \"英文照片描述\"}")
        append("\n2. caption 是朋友圈正文：第一人称、口语化、符合你的人设与心情，1~3 句话，不要话题标签，不要 @ 任何人，不要出现「朋友圈」三个字或任何元叙述。")
        if (imageCount > 0) {
            append("\n3. imagePrompt 是给生图模型的英文生图提示词：描述一张适合配这条朋友圈的照片（场景/光线/构图，写实照片风格），不要出现人物面部特写以外的奇怪元素，不要文字水印。")
        } else {
            append("\n3. imagePrompt 填空字符串。")
        }
        append("\n4. caption 不超过 ${AppConfig.Moment.CAPTION_MAX_CHARS} 字。")
    }

    /**
     * 生图请求的 user 文本（与参考图一起发给生图模型）。
     * 参考图 = 角色立绘（data URL），指令要求「以参考图为同一人物」生成场景照片。
     */
    fun buildImageGenUserMessage(imagePrompt: String, imageCount: Int): String = buildString {
        append("请以参考图中的人物为同一角色（保持发型、服装风格与气质一致），生成 $imageCount 张写实现实照片：")
        append(imagePrompt)
        append("\n直接输出图片，不要文字说明。")
    }

    /**
     * 评论回复任务的单条 user 提示词。发帖角色看到用户评论后必回（第一人称，贴合人设）。
     */
    fun buildReplyPrompt(
        postCaption: String,
        commentContent: String,
    ): String = buildString {
        append("你发了一条朋友圈：\"$postCaption\"")
        append("\n有位好友评论了：\"$commentContent\"")
        append("\n请以你的身份直接回复这条评论（一两句话，口语化，符合你的人设与心情）。")
        append("只输出回复正文，不要引号、不要前缀、不要解释。")
    }

    /** 用户手发朋友圈时，评论回复也要有据可依（角色看到用户自己发的帖子）。 */
    fun buildReplyPromptForUserPost(
        postCaption: String,
        hasImages: Boolean,
        commentContent: String,
    ): String = buildString {
        append("好友发了一条朋友圈：\"$postCaption\"${if (hasImages) "（附了几张照片）" else ""}")
        append("\n你评论了：\"$commentContent\"")
        append("\n请以你的身份直接回复这条评论（一两句话，口语化，符合你的人设）。")
        append("只输出回复正文，不要引号、不要前缀、不要解释。")
    }
}
