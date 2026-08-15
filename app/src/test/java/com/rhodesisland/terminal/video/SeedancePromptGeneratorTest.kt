package com.rhodesisland.terminal.video

import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Seedance 结构化视频提示词生成器测试（Task 4）。
 *
 * 用假 [SeedancePromptLlm]（零 HTTP）验证生成器自身契约：
 *  - 出站消息绝不含图片/base64；
 *  - `<think>` 被剥离后再解析；
 *  - 单一可见角色（系统指令强制，且不额外引入第二人）；
 *  - 可选场景：空白时省略、有值时透传；
 *  - technical / finalPrompt 均含模型型号/分辨率/画幅/时长/音频；
 *  - 围栏 JSON 与裸 JSON 均可解析；
 *  - 畸形 JSON 抛类型化异常且不重试（只调用一次）。
 */
class SeedancePromptGeneratorTest {

    /** 假 LLM：记录每次请求并返回脚本内容，便于断言调用次数与出站消息。 */
    private class FakeLlm(private val script: String) : SeedancePromptLlm {
        val calls = mutableListOf<SeedancePromptRequest>()

        override suspend fun complete(request: SeedancePromptRequest): String {
            calls += request
            return script
        }
    }

    private fun apiConfig() = ApiConfig(
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "test-key",
        model = "deepseek-chat",
    )

    private fun input(
        variant: SeedanceModelVariant = SeedanceModelVariant.STANDARD,
        resolution: SeedanceResolution = SeedanceResolution.P720,
        ratio: SeedanceRatio = SeedanceRatio.PORTRAIT,
        durationSeconds: Int = 5,
        sceneDescription: String = "海边日落",
        recentContext: String = "",
        hasBackgroundReference: Boolean = false,
    ) = SeedancePromptInput(
        characterName = "小明",
        characterRole = "邻家少年",
        characterSystemPrompt = "你是小明，一个温和的邻家少年。",
        userText = "你好呀，今天天气不错。",
        assistantText = "（小明抬头看向远方，微笑着说）是啊，适合散步。",
        sceneDescription = sceneDescription,
        hasBackgroundReference = hasBackgroundReference,
        recentContext = recentContext,
        variant = variant,
        resolution = resolution,
        ratio = ratio,
        durationSeconds = durationSeconds,
    )

    private fun fullDocumentJson(): String = """
        {
          "subject": "小明，一个身穿白衬衫的邻家少年，面容温和，短发被海风轻轻吹起",
          "appearance": "黑色短发，棕色眼睛，白色衬衫配浅蓝色牛仔裤，脚穿帆布鞋，气质温和内敛，笑起来眼睛弯弯",
          "action": "他缓步走向海边，停下脚步抬头望向远方，随后转头对镜头露出微笑，轻轻挥手示意",
          "environment": "黄昏时分的海边，金色夕阳悬在海平面上，浪花轻轻拍打沙滩，远处几只海鸥缓缓飞过",
          "camera": "中景起幅，镜头缓慢推近至近景，跟随他转头的动作轻微横移",
          "lighting": "暖金色夕阳逆光，皮肤边缘泛起柔和的轮廓光，整体色调温暖明亮",
          "audio": "轻柔的海浪声、海鸥鸣叫与他的脚步声，语气轻快地说着话",
          "continuity": "白衬衫、黑色短发与微笑的神态全程保持一致",
          "technical": "占位",
          "finalPrompt": "占位"
        }
    """.trimIndent()

    private fun singleUserMessage(fake: FakeLlm): String =
        (fake.calls.single().messages[1].content as JsonPrimitive).content

    // ---- 出站消息不含图片/base64 ----

    @Test
    fun outgoingMessages_containNoImageOrBase64() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input())

        val all = fake.calls.single().messages.joinToString("\n") { msg ->
            when (val c = msg.content) {
                is JsonPrimitive -> c.content
                else -> c.toString()
            }
        }
        assertFalse("出站消息不应出现图片标记", all.contains("image_url", ignoreCase = true))
        assertFalse("出站消息不应出现 base64", all.contains("base64", ignoreCase = true))
        assertFalse("出站消息不应出现 data:image", all.contains("data:image", ignoreCase = true))
        assertFalse("出站消息不应出现 base64 数据头", all.contains("iVBOR", ignoreCase = true))
    }

    // ---- think 剥离 ----

    @Test
    fun thinkTagIsStrippedBeforeParsing() = runBlocking {
        val fake = FakeLlm("<think>我先分析一下人物与环境</think>\n" + fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input())
        assertEquals("小明", doc.subject.substringBefore("，"))
    }

    // ---- 单一可见角色 ----

    @Test
    fun onlySingleCharacterIsReferenced() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input())

        val messages = fake.calls.single().messages
        val system = (messages[0].content as JsonPrimitive).content
        val user = (messages[1].content as JsonPrimitive).content
        assertTrue("系统指令应强制单一可见角色", system.contains("只有一个可见角色"))
        assertTrue("系统指令应禁止第二人", system.contains("第二人"))
        assertTrue("用户消息应提及当前角色", user.contains("小明"))
        assertFalse("用户消息不得额外引入其他角色名", user.contains("小红"))
    }

    // ---- 可选场景 ----

    @Test
    fun blankSceneIsOmittedFromUserMessage() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input(sceneDescription = ""))
        assertFalse("空白场景不应出现「场景补充」", singleUserMessage(fake).contains("场景补充"))
    }

    @Test
    fun sceneDescriptionIsPassedThrough() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input(sceneDescription = "雨夜街头"))
        assertTrue("非空场景应透传", singleUserMessage(fake).contains("雨夜街头"))
    }

    // ---- 参考图角色映射 ----

    @Test
    fun backgroundReferenceMapsImagesToRoles() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input(hasBackgroundReference = true))

        val user = singleUserMessage(fake)
        assertTrue("用户消息应写明第 1 张参考图 = 角色", user.contains("第 1 张参考图 = 角色形象图"))
        assertTrue("用户消息应写明第 2 张参考图 = 背景", user.contains("第 2 张参考图 = 背景场景图"))
        assertTrue("最终提示词应映射第 1 张为角色", doc.finalPrompt.contains("角色形象以第 1 张参考图为准"))
        assertTrue("最终提示词应映射第 2 张为背景", doc.finalPrompt.contains("背景场景以第 2 张参考图为准"))
    }

    @Test
    fun noBackgroundReferenceOmitsBackgroundMapping() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input(hasBackgroundReference = false))

        val user = singleUserMessage(fake)
        assertTrue("用户消息仍应写明第 1 张参考图 = 角色", user.contains("第 1 张参考图 = 角色形象图"))
        assertFalse("无背景参考图时不应出现第 2 张参考图映射", user.contains("第 2 张参考图"))
        assertTrue("最终提示词应以单图角色映射", doc.finalPrompt.contains("角色形象以参考图为准"))
        assertFalse("无背景参考图时最终提示词不应出现第 2 张", doc.finalPrompt.contains("第 2 张参考图"))
    }

    // ---- technical / finalPrompt 技术参数 ----

    @Test
    fun technicalAndFinalPromptEncodeTechnicalConstraints() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(
            apiConfig(),
            input(
                variant = SeedanceModelVariant.FAST,
                resolution = SeedanceResolution.P720,
                ratio = SeedanceRatio.LANDSCAPE,
                durationSeconds = 8,
            ),
        )
        val bits = listOf("快速版", "720p", "16:9", "8秒", "音频：开启")
        for (bit in bits) {
            assertTrue("technical 应包含「$bit」", doc.technical.contains(bit))
            assertTrue("finalPrompt 应包含「$bit」", doc.finalPrompt.contains(bit))
        }
    }

    // ---- 外貌字段与前情对话 ----

    @Test
    fun appearanceIsIncludedInFinalPrompt() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input())
        assertTrue("finalPrompt 应包含外貌描述", doc.finalPrompt.contains("白色衬衫"))
        assertTrue("appearance 字段应保留", doc.appearance.contains("黑色短发"))
    }

    @Test
    fun recentContextIsPassedThroughToUserMessage() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        val context = "用户：昨天我们聊了什么？\n角色：聊了夏天的海边。"
        SeedancePromptGenerator(fake).generate(apiConfig(), input(recentContext = context))
        assertTrue("前情对话应透传给 LLM", singleUserMessage(fake).contains(context))
    }

    @Test
    fun blankRecentContextOmitsSection() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input(recentContext = ""))
        assertFalse("空白前情不应出现该小节", singleUserMessage(fake).contains("前情对话"))
    }

    // ---- 系统指令：对话驱动 ----

    @Test
    fun systemPromptRequiresDialogueDrivenVideo() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input())
        val system = (fake.calls.single().messages[0].content as JsonPrimitive).content
        assertTrue("系统指令应要求演绎角色回复", system.contains("角色回复"))
        assertTrue("系统指令应要求与回复逐句对应", system.contains("逐句对应"))
        assertTrue("系统指令应要求外貌与参考人设图一致", system.contains("参考人设图"))
        assertTrue("系统指令应要求 environment 以场景补充为依据", system.contains("场景补充"))
    }

    // ---- 时长适配：动作/镜头/音效数量与总时长匹配 ----

    @Test
    fun userMessageCarriesDurationAndScalingGuidance() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input(durationSeconds = 5))
        val user = singleUserMessage(fake)
        assertTrue("用户消息应写明视频总时长", user.contains("视频总时长为 5 秒"))
        assertTrue("用户消息应含时长适配小节", user.contains("视频时长"))
    }

    @Test
    fun shortDurationForbidsMultiStageAction() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input(durationSeconds = 5))
        val user = singleUserMessage(fake)
        assertTrue("5 秒短片应写为单个连续动作", user.contains("单个连续动作"))
        assertTrue("5 秒短片不得分多阶段展开", user.contains("不允许分多阶段展开"))
    }

    @Test
    fun mediumDurationAllowsTwoStages() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input(durationSeconds = 8))
        assertTrue("6-9 秒应允许最多两个自然阶段", singleUserMessage(fake).contains("最多两个自然阶段"))
    }

    @Test
    fun longDurationAllowsMultiStageAction() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input(durationSeconds = 12))
        assertTrue("10-15 秒应允许多阶段运动", singleUserMessage(fake).contains("两到三阶段运动"))
    }

    @Test
    fun systemPromptRequiresDurationMatching() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input())
        val system = (fake.calls.single().messages[0].content as JsonPrimitive).content
        assertTrue("系统指令应要求动作编排与时长匹配", system.contains("时长适配"))
        assertTrue("系统指令应要求短视频更精简", system.contains("视频越短越精简"))
    }

    // ---- 质量红线：过短拒绝 ----

    @Test
    fun tooShortDescriptionIsRejected() = runBlocking {
        val fake = FakeLlm(
            """{"subject":"小明","action":"笑","environment":"海边","technical":"x","finalPrompt":"x"}"""
        )
        val generator = SeedancePromptGenerator(fake)
        try {
            generator.generate(apiConfig(), input())
            fail("过短提示词应抛出 SeedancePromptParseException")
        } catch (e: SeedancePromptParseException) {
            assertTrue("失败原因应提示过短", e.message.orEmpty().contains("过短"))
        }
        assertEquals(1, fake.calls.size)
    }

    // ---- JSON 解析形态 ----

    @Test
    fun fencedJsonIsParsed() = runBlocking {
        val fake = FakeLlm("```json\n" + fullDocumentJson() + "\n```")
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input())
        assertEquals("小明", doc.subject.substringBefore("，"))
    }

    @Test
    fun bareJsonIsParsed() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input())
        assertTrue("裸 JSON 应解析出主体", doc.subject.contains("小明"))
    }

    @Test
    fun leadingProseBeforeJsonIsTrimmed() = runBlocking {
        val fake = FakeLlm("好的，以下是你的视频提示词：\n" + fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input())
        assertTrue(doc.subject.contains("小明"))
    }

    // ---- 畸形 JSON：类型化失败且不重试 ----

    @Test
    fun malformedJsonThrowsTypedFailureWithoutRetry() = runBlocking {
        val fake = FakeLlm("好的，这是你的视频提示词：这不是 JSON")
        val generator = SeedancePromptGenerator(fake)
        try {
            generator.generate(apiConfig(), input())
            fail("解析失败应抛出 SeedancePromptParseException")
        } catch (e: SeedancePromptParseException) {
            // 预期
        }
        assertEquals("解析失败不应重试（只调用一次）", 1, fake.calls.size)
    }

    @Test
    fun emptyJsonObjectThrowsInsteadOfSucceeding() = runBlocking {
        val fake = FakeLlm("{}")
        val generator = SeedancePromptGenerator(fake)
        try {
            generator.generate(apiConfig(), input())
            fail("空 JSON 不应被当作成功提示词")
        } catch (e: SeedancePromptParseException) {
            // 预期
        }
        assertEquals(1, fake.calls.size)
    }
}
