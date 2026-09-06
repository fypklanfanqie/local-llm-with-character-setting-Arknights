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
     * ——caption 是要发的朋友圈文字（第一人称、口语化、纯日常分享，不围绕博士展开）；
     * imagePrompt 是给生图模型的英文提示词（描述一张该角色会发的照片）。
     *
     * [mentionTarget] = 本条要 @ 的人（其他角色名或用户昵称；null = 本条不 @，由调用方随机掷点决定）。
     */
    fun buildPostUserMessage(
        characterName: String,
        recentChat: String,
        imageCount: Int,
        mentionTarget: String? = null,
    ): String = buildString {
        append("你现在是 $characterName 本人，要发一条朋友圈。")
        if (recentChat.isNotBlank()) {
            append("\n\n你们最近的聊天：\n$recentChat")
        }
        append("\n\n要求：")
        append("\n1. 先输出严格 JSON（不要代码围栏、不要多余解释）：{\"caption\": \"朋友圈正文\", \"imagePrompt\": \"英文照片描述\"}")
        append("\n2. caption 是朋友圈正文：第一人称、口语化、符合你的人设与心情的日常分享（今天做的事、心情、见闻、吃喝、吐槽等），1~3 句话，不要话题标签，不要出现「朋友圈」三个字或任何元叙述；不要提「博士」，不要写任何和博士有关的事（@ 好友写出名字除外）。")
        if (mentionTarget.isNullOrBlank()) {
            append("\n3. 这条不要 @ 任何人。")
        } else {
            append("\n3. 在正文里自然地 @ 一次「@$mentionTarget」：@ 后紧跟名字（不要加空格），全条只 @ 这一个人。")
        }
        if (imageCount > 0) {
            append("\n4. imagePrompt 是给生图模型的英文生图提示词：描述一张适合配这条朋友圈的照片（场景/光线/构图，写实照片风格），不要出现人物面部特写以外的奇怪元素，不要文字水印。")
        } else {
            append("\n4. imagePrompt 填空字符串。")
        }
        append("\n5. caption 不超过 ${AppConfig.Moment.CAPTION_MAX_CHARS} 字。")
    }

    /**
     * 发圈 system 区追加的「日常基调 + @ 规则」指令块（纯函数，JVM 可测）。
     * 基调：纯日常、与博士无关；@ 的唯一例外是写出好友名字。
     * [mentionTarget] 非空时附加本条 @ 指令（与 [buildPostUserMessage] 的 user 区约束一致）。
     */
    fun buildPostSystemDirective(mentionTarget: String?): String = buildString {
        append("\n\n[朋友圈基调] 这是你自己的日常分享（今天做的事、心情、见闻、吃喝、吐槽等），与博士无关：不要提「博士」，不要写任何和博士有关的事；唯一例外是 @ 好友时写出 TA 的名字。")
        if (!mentionTarget.isNullOrBlank()) {
            append("\n[@ 好友] 本条朋友圈要 @ 一个人：$mentionTarget。写法：直接在正文里写「@$mentionTarget」（@ 后紧跟名字），自然融入句子，全条只 @ 这一个。")
        }
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
