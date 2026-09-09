package com.rhodesisland.terminal.work

import android.content.Context
import com.rhodesisland.terminal.i18n.L10nRuntime
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.MomentImageGenConfig
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import com.rhodesisland.terminal.data.remote.DirectLlmClient
import com.rhodesisland.terminal.data.remote.MomentImageGenClient
import com.rhodesisland.terminal.data.repository.MomentRepository
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.llm.MomentPromptBuilder
import com.rhodesisland.terminal.util.MomentImageExtractor
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random

/**
 * 朋友圈生成协调器：角色发一条朋友圈的完整链路（UI 手动触发与后台 Worker 共用）。
 *
 * 1. 取该角色活跃会话最近若干条聊天（衔接正在聊的话题；无会话则空）；
 * 2. 云端对话 LLM（主 ApiConfig）生成 `{caption, imagePrompt}` JSON；
 * 3. 生图 API（用户自有 MomentImageGenConfig，OpenAI 聊天格式 + 立绘参考图）生成图片落盘；
 *    未配置/失败 → 降级纯文字帖（caption 仍发）。
 * 4. 落库 moment_post。
 *
 * 仅云端可用；调用方负责前置门控（Provider/配置检查）。
 */
class MomentGenerationCoordinator(
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatRepository: com.rhodesisland.terminal.data.repository.ChatRepository,
    private val conversationRepository: com.rhodesisland.terminal.data.repository.ConversationRepository,
    private val characterRepository: com.rhodesisland.terminal.data.repository.CharacterRepository,
    private val momentRepository: MomentRepository,
    private val directLlmClient: DirectLlmClient,
    private val imageGenClient: MomentImageGenClient,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val thinkRegex = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    data class GeneratedPost(
        val postId: Long,
        val caption: String,
        val images: List<String>,
        val degradedToTextOnly: Boolean,
    )

    /**
     * 生成并落库一条角色朋友圈。
     * @param imageCount 目标图片数（0 = 纯文字；≥1 时生图失败仍降级纯文字发 caption）
     * @throws Exception 主 LLM 未配置/生成失败（此时不落库，由调用方决定重试/提示）
     */
    suspend fun generateAndPost(
        characterId: String,
        imageCount: Int,
    ): GeneratedPost {
        val character = characterRepository.getNow(characterId)
            ?: throw IllegalStateException(L10nRuntime.t("角色不存在"))
        val apiConfig = settings.getApiConfigNow()
        if (apiConfig.apiKey.isBlank()) throw IllegalStateException(L10nRuntime.t("请先在设置中配置云端 AI API"))

        // 有的时候随机 @ 一个人（其他角色或用户）；其余帖子不 @。内容一律纯日常、不提博士。
        val mentionTarget = rollMentionTarget(character.name)
        val captionPrompt = buildCaptionPrompt(characterId, character.name, character.systemPrompt, imageCount, mentionTarget)
        val raw = withTimeout(AppConfig.Moment.GENERATE_TIMEOUT_MS) {
            directLlmClient.chatOnce(
                baseUrl = apiConfig.baseUrl,
                apiKey = apiConfig.apiKey,
                model = apiConfig.model,
                messages = listOf(
                    ChatMessageDto(role = "system", content = JsonPrimitive(captionPrompt)),
                    ChatMessageDto(role = "user", content = JsonPrimitive(MomentPromptBuilder.buildPostUserMessage(character.name, "", imageCount, mentionTarget))),
                ),
                onUsage = { usage ->
                    usage?.let { settings.recordTokenUsage(characterId, it.promptTokens, it.completionTokens, it.cachedTokens) }
                },
            )
        }
        val parsed = parseCaptionResponse(thinkRegex.replace(raw, "").trim())
        val caption = parsed.caption.ifBlank {
            MomentImageExtractor.extractCaption(raw).ifBlank {
                throw IllegalStateException(L10nRuntime.t("生成的朋友圈文案为空"))
            }
        }

        // 生图：配置齐全才尝试；失败降级纯文字
        var images: List<String> = emptyList()
        var degraded = false
        val imageGenConfig = settings.getMomentImageGenConfigNow()
        val imageGenEnabled = settings.getMomentImageGenEnabledNow()
        if (imageCount > 0 && imageGenEnabled && imageGenConfig.isConfigured && parsed.imagePrompt.isNotBlank()) {
            images = try {
                imageGenClient.generateAndSave(
                    config = imageGenConfig,
                    imagePrompt = parsed.imagePrompt,
                    referenceImagePath = resolveReferenceImage(characterId, character),
                    count = imageCount,
                )
            } catch (e: Exception) {
                degraded = true
                android.util.Log.w("MomentGen", "生图失败，降级纯文字发圈", e)
                emptyList()
            }
        } else if (imageCount > 0 && imageGenEnabled && !imageGenConfig.isConfigured) {
            degraded = true
        }
        // 开关关闭 = 有意纯文字发圈，不算降级

        val postId = momentRepository.addCharacterPost(
            characterId = characterId,
            content = caption,
            images = images,
            imagePrompt = parsed.imagePrompt.ifBlank { null },
        )
        return GeneratedPost(postId, caption, images, degraded)
    }

    /**
     * 生成发帖者对用户评论的回复正文（不落库；落库由调用方调 [MomentRepository.addCharacterComment]）。
     * @throws Exception 生成失败（调用方提示后可重试）
     */
    suspend fun generateReply(
        characterId: String,
        postCaption: String,
        commentContent: String,
        isCharacterPost: Boolean,
    ): String {
        val character = characterRepository.getNow(characterId)
            ?: throw IllegalStateException(L10nRuntime.t("角色不存在"))
        val apiConfig = settings.getApiConfigNow()
        if (apiConfig.apiKey.isBlank()) throw IllegalStateException(L10nRuntime.t("请先在设置中配置云端 AI API"))
        val prompt = if (isCharacterPost) {
            MomentPromptBuilder.buildReplyPrompt(postCaption, commentContent)
        } else {
            MomentPromptBuilder.buildReplyPromptForUserPost(postCaption, hasImages = false, commentContent)
        }
        val system = buildString {
            append(character.systemPrompt)
            append(settings.getUserProfileNow().toDirectiveText())
            append(com.rhodesisland.terminal.llm.OutputLanguage.ZH_DIRECTIVE)
        }
        val raw = withTimeout(AppConfig.Moment.GENERATE_TIMEOUT_MS) {
            directLlmClient.chatOnce(
                baseUrl = apiConfig.baseUrl,
                apiKey = apiConfig.apiKey,
                model = apiConfig.model,
                messages = listOf(
                    ChatMessageDto(role = "system", content = JsonPrimitive(system)),
                    ChatMessageDto(role = "user", content = JsonPrimitive(prompt)),
                ),
                onUsage = { usage ->
                    usage?.let { settings.recordTokenUsage(characterId, it.promptTokens, it.completionTokens, it.cachedTokens) }
                },
            )
        }
        return thinkRegex.replace(raw, "").trim().removeSurrounding("\"").take(AppConfig.Moment.CAPTION_MAX_CHARS)
    }

    /**
     * 生成「角色评论用户朋友圈」的正文（用户发圈后随机互动角色评论；不落库，由调用方落库）。
     * @throws Exception 生成失败（调用方静默降级，不影响发圈本身）
     */
    suspend fun generatePostComment(
        characterId: String,
        postCaption: String,
        hasImages: Boolean,
    ): String {
        val character = characterRepository.getNow(characterId)
            ?: throw IllegalStateException(L10nRuntime.t("角色不存在"))
        val apiConfig = settings.getApiConfigNow()
        if (apiConfig.apiKey.isBlank()) throw IllegalStateException(L10nRuntime.t("请先在设置中配置云端 AI API"))
        val profile = settings.getUserProfileNow()
        val system = buildString {
            append(character.systemPrompt)
            append(profile.toDirectiveText())
            append(com.rhodesisland.terminal.llm.OutputLanguage.ZH_DIRECTIVE)
        }
        val prompt = MomentPromptBuilder.buildUserPostCommentPrompt(
            userDisplayName = profile.displayOrMe,
            postCaption = postCaption,
            hasImages = hasImages,
        )
        val raw = withTimeout(AppConfig.Moment.GENERATE_TIMEOUT_MS) {
            directLlmClient.chatOnce(
                baseUrl = apiConfig.baseUrl,
                apiKey = apiConfig.apiKey,
                model = apiConfig.model,
                messages = listOf(
                    ChatMessageDto(role = "system", content = JsonPrimitive(system)),
                    ChatMessageDto(role = "user", content = JsonPrimitive(prompt)),
                ),
                onUsage = { usage ->
                    usage?.let { settings.recordTokenUsage(characterId, it.promptTokens, it.completionTokens, it.cachedTokens) }
                },
            )
        }
        return thinkRegex.replace(raw, "").trim().removeSurrounding("\"").take(AppConfig.Moment.CAPTION_MAX_CHARS)
    }

    /** caption 响应解析：严格 JSON 优先，失败退 [MomentImageExtractor.extractCaption]。 */
    private fun parseCaptionResponse(raw: String): CaptionParsed {
        val cleaned = raw.trim().removeSurrounding("```").trim()
        val jsonText = if (cleaned.startsWith("```")) cleaned.removePrefix("```").substringAfter("\n").removeSuffix("```").trim() else cleaned
        val candidate = if (jsonText.startsWith("{")) jsonText else {
            // 从混合文本里抠第一个 {...} 块
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start in 0 until end) raw.substring(start, end + 1) else null
        }
        if (candidate != null) {
            runCatching {
                val obj = json.parseToJsonElement(candidate).jsonObject
                val caption = (obj["caption"] as? JsonPrimitive)?.content.orEmpty()
                val imagePrompt = (obj["imagePrompt"] as? JsonPrimitive)?.content
                    ?.takeIf { it.isNotBlank() && it != "null" && !it.equals("\"\"", true) }
                    .orEmpty()
                if (caption.isNotBlank()) return CaptionParsed(caption, imagePrompt)
            }
        }
        return CaptionParsed(MomentImageExtractor.extractCaption(raw), "")
    }

    private data class CaptionParsed(val caption: String, val imagePrompt: String)

    /** 生图参考图：角色立绘（自定义=file:// 本地；内置=assets 解析后的 URL）。 */
    private suspend fun resolveReferenceImage(characterId: String, character: com.rhodesisland.terminal.data.model.Character): String? {
        if (character.isCustom && character.image.isNotBlank()) return character.image
        return AssetPathsHolder.pictureOf(characterId)
    }

    /** 组装发圈 caption 的 system 提示词：人设 + 日常基调（不提博士）+ 本条 @ 规则。 */
    private suspend fun buildCaptionPrompt(
        characterId: String,
        characterName: String,
        systemPrompt: String,
        imageCount: Int,
        mentionTarget: String?,
    ): String = buildString {
        append(systemPrompt)
        append("\n\n[任务] 你要发一条朋友圈（微信 Moments）。输出严格 JSON：{\"caption\": \"...\", \"imagePrompt\": \"...\"}。") // l10n:ignore 非界面文案（提示词/比较值/崩溃日志/拼接片段）
        if (imageCount > 0) {
            append("imagePrompt 是英文生图提示词，描述你要配图的画面（明日方舟游戏美术风格：动画插画、赛璐璐上色，不要写实照片）。") // l10n:ignore 非界面文案（提示词/比较值/崩溃日志/拼接片段）
        } else {
            append("本次不带图，imagePrompt 填空字符串。") // l10n:ignore 非界面文案（提示词/比较值/崩溃日志/拼接片段）
        }
        append("caption 贴合人设，第一人称，1~3 句，不含话题标签。") // l10n:ignore 非界面文案（提示词/比较值/崩溃日志/拼接片段）
        append(MomentPromptBuilder.buildPostSystemDirective(mentionTarget))
        append("\n[备注] 角色名：$characterName") // l10n:ignore 非界面文案（提示词/比较值/崩溃日志/拼接片段）
        append(com.rhodesisland.terminal.llm.OutputLanguage.ZH_DIRECTIVE)
    }

    /**
     * 掷点决定本条朋友圈是否 @ 一个人：以 [AppConfig.Moment.MENTION_PROBABILITY_PERCENT] 的概率
     * 从「其他角色 + 用户（昵称，未设置则『博士』）」里均匀随机选一个；否则返回 null（本条不 @）。
     */
    private suspend fun rollMentionTarget(posterName: String): String? {
        if (Random.nextInt(100) >= AppConfig.Moment.MENTION_PROBABILITY_PERCENT) return null
        val candidates = characterRepository.getAllNamesNow(excludeName = posterName) +
            settings.getUserProfileNow().displayName.trim().ifBlank { L10nRuntime.t("博士") }
        return candidates.distinct().randomOrNull()
    }
}

/** AssetPaths 的静态访问壳（保持协调器可构造性；asset URL 由 AssetRepository 语义解析）。 */
private object AssetPathsHolder {
    fun pictureOf(characterId: String): String? =
        com.rhodesisland.terminal.config.AssetPaths.PICTURES[characterId]?.let { "file:///android_asset/$it" }
}
