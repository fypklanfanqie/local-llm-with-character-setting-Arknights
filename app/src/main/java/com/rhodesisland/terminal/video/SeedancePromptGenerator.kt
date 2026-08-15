package com.rhodesisland.terminal.video

import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import com.rhodesisland.terminal.data.remote.DirectLlmClient
import com.rhodesisland.terminal.util.MarkdownParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Seedance 结构化视频提示词文档。
 *
 * 全部为字符串字段：前八项为导演式分镜描述（来自 LLM 结构化输出），
 * [technical] 与 [finalPrompt] 由生成器依据输入技术参数确定性覆盖，
 * 保证最终文档含版本 / 分辨率 / 画幅 / 时长 / 音频开启。
 */
@Serializable
data class SeedancePromptDocument(
    val subject: String = "",
    /** 角色外貌与服装的详细描述（发色/发型/瞳色/服装/配饰/气质，≥60 字）。 */
    val appearance: String = "",
    val action: String = "",
    val environment: String = "",
    val camera: String = "",
    val lighting: String = "",
    val audio: String = "",
    val continuity: String = "",
    val technical: String = "",
    val finalPrompt: String = "",
)

/**
 * Seedance 视频提示词生成输入（来自角色与对话快照 + 前情对话 + 生成参数）。
 */
data class SeedancePromptInput(
    val characterName: String,
    val characterRole: String,
    val characterSystemPrompt: String,
    val userText: String,
    val assistantText: String,
    val sceneDescription: String,
    /**
     * 是否提供了背景参考图（第 2 张参考图）。true 时提示词须明确
     * 「第 1 张参考图 = 角色 / 第 2 张参考图 = 背景」，避免视频模型混淆两张图。
     */
    val hasBackgroundReference: Boolean = false,
    /** 前情对话（已格式化文本，可为空）；帮助 LLM 理解本次对话的来龙去脉。 */
    val recentContext: String = "",
    val variant: SeedanceModelVariant,
    val resolution: SeedanceResolution,
    val ratio: SeedanceRatio,
    val durationSeconds: Int,
)

/**
 * 提示词生成 LLM 一次性调用请求。
 */
data class SeedancePromptRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<ChatMessageDto>,
    val jsonMode: Boolean,
)

/**
 * 提示词生成 LLM 抽象（可注入假实现，JVM 测试零 HTTP）。
 */
interface SeedancePromptLlm {
    suspend fun complete(request: SeedancePromptRequest): String
}

/**
 * 生产实现：包装 [DirectLlmClient]，请求结构化 JSON 输出。
 */
class DirectLlmSeedancePromptLlm(
    private val client: DirectLlmClient,
) : SeedancePromptLlm {
    override suspend fun complete(request: SeedancePromptRequest): String =
        client.chatOnceStructured(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            model = request.model,
            messages = request.messages,
            responseFormatJson = request.jsonMode,
        )
}

/**
 * 结构化提示词解析失败（模型输出无法解析为合法 JSON 文档，或内容过短不合格）。
 *
 * 解析失败即抛出本异常，绝不重试/二次调用、绝不拼凑伪造 JSON。
 */
class SeedancePromptParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Seedance 结构化视频提示词生成器。
 *
 * 流程：固定中文系统指令（视频直接演绎本次角色回复 / 单一可见角色 / 外貌与服装详述且与参考人设图一致 /
 * 环境优先采用场景补充 / 电影感运动 / 原生声音 / 严格 JSON）+ 单条纯文本用户消息（角色信息 + 前情对话 +
 * 本次对话 + 场景补充，绝不携带图片或 base64）→ 一次性调用 [SeedancePromptLlm] → 严格解析 JSON
 * （容忍 ```json 围栏、裸 JSON、<think> 与首个 { 之前的引导语）→ 分镜描述合计过短即抛
 * [SeedancePromptParseException]（避免简陋短语式提示词直接入参视频模型）。
 */
class SeedancePromptGenerator(
    private val llm: SeedancePromptLlm,
) {
    suspend fun generate(apiConfig: ApiConfig, input: SeedancePromptInput): SeedancePromptDocument {
        val messages = listOf(
            ChatMessageDto("system", JsonPrimitive(SYSTEM_PROMPT)),
            ChatMessageDto("user", JsonPrimitive(buildUserMessage(input))),
        )
        val raw = llm.complete(
            SeedancePromptRequest(
                baseUrl = apiConfig.baseUrl,
                apiKey = apiConfig.apiKey,
                model = apiConfig.model,
                messages = messages,
                jsonMode = true,
            )
        )
        return parseDocument(raw, input)
    }

    private fun parseDocument(raw: String, input: SeedancePromptInput): SeedancePromptDocument {
        val candidate = extractJsonCandidate(MarkdownParser.stripThink(raw))
        val doc = try {
            json.decodeFromString(SeedancePromptDocument.serializer(), candidate)
        } catch (e: Exception) {
            throw SeedancePromptParseException("结构化提示词解析失败：模型输出不是合法 JSON", e)
        }
        // 拒绝空/占位 JSON：没有主体/动作/环境描述不得视为成功提示词。
        if (doc.subject.isBlank() && doc.action.isBlank() && doc.environment.isBlank()) {
            throw SeedancePromptParseException("结构化提示词缺少主体/动作/环境描述")
        }
        val technical = buildTechnical(input)
        val finalDoc = doc.copy(
            technical = technical,
            finalPrompt = buildFinalPrompt(doc, technical, input.hasBackgroundReference),
        )
        // 质量红线：分镜描述合计过短（短语式敷衍输出）直接判定失败，由用户重试。
        val description = finalDoc.descriptionParts().joinToString("；")
        if (description.length < MIN_DESCRIPTION_LENGTH) {
            throw SeedancePromptParseException(
                "模型返回的提示词过短（${description.length} 字，需 ≥$MIN_DESCRIPTION_LENGTH 字），请重试"
            )
        }
        return finalDoc
    }

    /** 分镜描述字段（按最终提示词中的顺序）。 */
    private fun SeedancePromptDocument.descriptionParts(): List<String> = listOf(
        subject, appearance, action, environment, camera, lighting, audio, continuity,
    ).filter { it.isNotBlank() }

    /** 提取 JSON 候选文本：去 ```json 围栏、截取首个 { 到最后一个 }（仅做必然安全的引导语裁剪）。 */
    private fun extractJsonCandidate(text: String): String {
        var t = text.trim()
        val jsonFence = t.indexOf("```json")
        if (jsonFence >= 0) {
            t = t.substring(jsonFence + "```json".length)
            val close = t.indexOf("```")
            if (close >= 0) t = t.substring(0, close)
        } else {
            val generic = t.indexOf("```")
            if (generic >= 0) {
                val close = t.indexOf("```", generic + 3)
                t = if (close >= 0) t.substring(generic + 3, close) else t.substring(generic + 3)
            }
        }
        t = t.trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw SeedancePromptParseException("模型输出中未找到 JSON 对象")
        }
        return t.substring(start, end + 1)
    }

    /** 技术参数（确定性覆盖，保证含版本/分辨率/画幅/时长/音频）。 */
    private fun buildTechnical(input: SeedancePromptInput): String =
        "版本：${variantLabel(input.variant)}；分辨率：${resolutionLabel(input.resolution)}；" +
            "画幅：${input.ratio.apiValue}；时长：${input.durationSeconds}秒；音频：开启"

    /** 最终成片提示词 = 参考图角色映射 + 分镜描述 + 技术参数。 */
    private fun buildFinalPrompt(
        doc: SeedancePromptDocument,
        technical: String,
        hasBackgroundReference: Boolean,
    ): String {
        val description = doc.descriptionParts().joinToString("；")
        return listOf(buildReferenceDirective(hasBackgroundReference), description, technical)
            .filter { it.isNotBlank() }
            .joinToString("。")
    }

    /**
     * 参考图角色映射指令：直接写入最终提示词（与参考图一起提交给视频模型），
     * 明确「第 1 张 = 角色 / 第 2 张 = 背景」，与请求体中图片顺序一致。
     */
    private fun buildReferenceDirective(hasBackgroundReference: Boolean): String =
        if (hasBackgroundReference) {
            "角色形象以第 1 张参考图为准，背景场景以第 2 张参考图为准"
        } else {
            "角色形象以参考图为准"
        }

    /** 单条用户消息：角色信息 + 前情对话 + 本次对话 + 参考图角色映射 + 场景补充，纯文本拼接，绝不携带图片/base64。 */
    private fun buildUserMessage(input: SeedancePromptInput): String = buildString {
        appendLine("【角色信息】")
        appendLine("角色名称：${input.characterName}")
        appendLine("角色身份：${input.characterRole}")
        appendLine("角色设定：${input.characterSystemPrompt.trim().take(800)}")
        if (input.recentContext.isNotBlank()) {
            appendLine()
            appendLine("【前情对话】（用于理解本次对话的来龙去脉，视频只演绎「本次对话」）")
            append(input.recentContext.trim().take(1500))
            appendLine()
        }
        appendLine()
        appendLine("【本次对话】（视频要演绎的就是下面这条「角色回复」）")
        appendLine("用户发言：${input.userText.trim().take(500)}")
        appendLine("角色回复：${input.assistantText.trim().take(1000)}")
        appendLine()
        appendLine("【参考图】（生成视频时会按顺序附带，请据此理解画面）")
        appendLine("第 1 张参考图 = 角色形象图：角色的外貌、服装、发型、配饰必须以它为准，appearance 与它完全一致。")
        if (input.hasBackgroundReference) {
            appendLine("第 2 张参考图 = 背景场景图：environment 必须以它为准，画面背景、地点、时间、天气、氛围与它一致，不得凭空改换。")
        }
        if (input.sceneDescription.isNotBlank()) {
            if (input.hasBackgroundReference) {
                appendLine("场景补充（文字，仅作环境细节参考，不得与背景参考图冲突）：${input.sceneDescription.trim().take(300)}")
            } else {
                appendLine("场景补充（文字，environment 以此为依据）：${input.sceneDescription.trim().take(300)}")
            }
        }
        appendLine()
        appendLine("【视频时长】（动作、镜头与音效的编排数量必须与总时长严格匹配）")
        appendLine("本次视频总时长为 ${input.durationSeconds} 秒。${durationGuidance(input.durationSeconds)}")
        appendLine()
        append("请生成结构化视频提示词 JSON。")
    }

    /**
     * 按时长给出动作/镜头/音效的编排密度指引：视频越短越精简，所有编排都须能在给定秒数内自然完成。
     * 与系统指令「时长适配」保持一致；档位对应模型支持区间 4-15 秒。
     */
    private fun durationGuidance(seconds: Int): String = when {
        seconds <= 5 ->
            "4-5 秒的短片只能是一个连贯瞬间：动作写成单个连续动作（可含一句台词与伴随的小动作），" +
                "不允许分多阶段展开、不允许出现多个场景或多次转场；镜头一镜到底或仅一次轻微运镜；" +
                "音效只保留与当前动作直接相关的一两样。"
        seconds <= 9 ->
            "6-9 秒的中短片是一个完整动作过程：最多两个自然阶段（开场到收尾），镜头允许一次景别变化，运镜简洁。"
        else ->
            "10-15 秒的长片可以写成完整的两到三阶段运动（开场、发展、收尾），允许更丰富的运镜层次与音效编排。"
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** 分镜描述合计的最短字数：低于此值视为敷衍输出，判失败。 */
        private const val MIN_DESCRIPTION_LENGTH = 100

        /**
         * 固定中文系统指令：视频直接演绎本次角色回复、单一可见角色、外貌/服装详述且与参考人设图一致、
         * 环境优先采用场景补充、电影感连贯运动、原生声音、严格 JSON、每字段写满拒绝空洞词。
         */
        private const val SYSTEM_PROMPT = """你是资深 Seedance 视频分镜导演。根据角色设定、前情对话与本次对话，生成一段可直接用于视频生成的详细中文分镜提示词。

硬性要求：
1. 本次视频必须直接演绎「角色回复」这句话：画面就是角色正在说出这句话的瞬间——动作、手势、神态、口型与情绪都要与这句话的内容逐句对应；回复中提到的事物、场景、事件必须真实出现在画面里（例如回复提到“下雨”，画面就要下雨；提到“递来一杯茶”，就要有递茶的动作）。禁止编造与本次对话无关的剧情。
2. 全片只有一个可见角色，即当前角色本人；不得出现第二人、路人或其他人物（仅允许环境中的非人物元素）。角色外貌、服装、发型、配饰在全片保持完全一致（身份与服饰连续性），并且必须与参考人设图（第 1 张参考图 = 角色形象图）完全一致。
3. 外貌与服装必须写足细节：从「角色设定」中提炼发色、发型、瞳色、服装款式与颜色、配饰、体型、气质等，写成至少 60 字的完整描述填入 appearance 字段；设定未提及的细节可合理补全，但不得与设定矛盾，也不得因此引入第二个人。
4. 环境（environment）按参考图角色映射确定：若提供了第 2 张参考图（背景场景图），必须严格以它为准，地点/时间/天气/氛围与之完全一致，不得凭空改换；没有第 2 张参考图时，优先严格采用「场景补充」（文字），仍无则根据对话内容推断具体地点/时间/天气/氛围。
5. 动作要有电影感与连贯运动感（cinematic motion），写成完整连贯的运动过程并按需分阶段描述（是否分阶段、分几阶段遵循第 9 条「时长适配」）；镜头语言明确（景别、运动方向）；光影与色调具体。
6. 视频必须包含原生声音与音效（native audio）：环境音、动作音效，以及角色说话时的语气与口型状态；音频描述需与画面动作一致。
7. 严格只输出一个 JSON 对象，不要输出任何解释或多余文字，不要输出思考过程。
8. 参考图角色映射（重要）：本次生成会按顺序附带参考图——第 1 张参考图固定为角色形象图（人物外貌/服装/发型/配饰的唯一依据），第 2 张参考图（若有）固定为背景场景图（环境/地点/时间/天气/氛围的唯一依据）。appearance 必须与第 1 张参考图一致，environment 必须与第 2 张参考图一致，绝不混淆两张图的用途。
9. 时长适配（重要）：动作、镜头与音效的编排数量必须与视频总时长严格匹配，视频越短越精简。具体秒数与该时长下的编排要求见用户消息「视频时长」小节：4-5 秒短片只能是单个连续瞬间，不得分多阶段展开；10-15 秒长片才可写成完整多阶段运动。禁止在短时长视频中堆砌多个动作阶段或多个场景。

JSON 字段（均为字符串，使用中文）：
- subject：画面主体（只能是当前这一个角色）
- appearance：角色外貌与服装的详细描述（至少 60 字）
- action：角色的动作与运动全过程（分阶段，如开场→发展→收尾）
- environment：环境与场景（具体到地点/时间/天气/氛围）
- camera：镜头与运镜（含景别与运动方向）
- lighting：光线与色调
- audio：原生声音、音效与角色说话状态
- continuity：身份与服饰连续性说明
- technical：技术参数（版本/分辨率/画幅/时长/音频，留空即可，由系统补全）
- finalPrompt：整合以上所有要素的最终成片提示词（留空即可，由系统补全）

质量红线：
- 除 technical 与 finalPrompt 外，每个字段必须写具体、写满（30 字以上，appearance 至少 60 字）；禁止空洞形容词（如“美丽”“好看”“帅气”）与短语拼接；
- finalPrompt 与 technical 可以留空，其余字段不得为空。"""
    }
}

/** 模型档位的中文标签（提示词技术参数用，与具体服务商无关）。 */
internal fun variantLabel(variant: SeedanceModelVariant): String = when (variant) {
    SeedanceModelVariant.STANDARD -> "标准版"
    SeedanceModelVariant.FAST -> "快速版"
}

/** 分辨率的中文标签（提示词技术参数用）。 */
internal fun resolutionLabel(resolution: SeedanceResolution): String = when (resolution) {
    SeedanceResolution.P480 -> "480p"
    SeedanceResolution.P720 -> "720p"
    SeedanceResolution.P1080 -> "1080p"
    SeedanceResolution.P4K -> "4K"
}
