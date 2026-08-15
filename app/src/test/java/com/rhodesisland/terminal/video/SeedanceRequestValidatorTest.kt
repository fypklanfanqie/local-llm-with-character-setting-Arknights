package com.rhodesisland.terminal.video

import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seedance 请求校验参数矩阵测试（Task 1）。
 *
 * 覆盖：时长边界 3/4/15/16、标准/Fast 分辨率矩阵、必填字段空值、
 * generateAudio 恒真、全部比例的 apiValue、默认值、模型 ID 契约与错误文案。
 */
class SeedanceRequestValidatorTest {

    companion object {
        private const val CHARACTER_PATH = "characters/alice.png"
        private const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
        private const val TEST_API_KEY = "test-seedance-key"
    }

    private fun config(
        variant: SeedanceModelVariant = SeedanceModelVariant.STANDARD,
        resolution: SeedanceResolution = SeedanceResolution.P720,
        ratio: SeedanceRatio = SeedanceRatio.PORTRAIT,
        durationSeconds: Int = 5,
        baseUrl: String = DEFAULT_BASE_URL,
        apiKey: String = TEST_API_KEY,
    ): SeedanceConfig = SeedanceConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        variant = variant,
        resolution = resolution,
        ratio = ratio,
        durationSeconds = durationSeconds,
    )

    private fun assertInvalid(result: SeedanceValidationResult) {
        assertTrue("期望校验失败，实际 $result", result is SeedanceValidationResult.Invalid)
        val message = (result as SeedanceValidationResult.Invalid).message
        assertTrue("失败原因不得为空", message.isNotBlank())
    }

    // ---- 时长边界矩阵 ----

    @Test
    fun durationBelowFourSecondsIsInvalid() {
        assertInvalid(validateSeedanceRequest(config(durationSeconds = 3), CHARACTER_PATH))
    }

    @Test
    fun durationAtFourSecondsIsValid() {
        assertEquals(SeedanceValidationResult.Valid,
            validateSeedanceRequest(config(durationSeconds = 4), CHARACTER_PATH))
    }

    @Test
    fun durationAtFifteenSecondsIsValid() {
        assertEquals(SeedanceValidationResult.Valid,
            validateSeedanceRequest(config(durationSeconds = 15), CHARACTER_PATH))
    }

    @Test
    fun durationAboveFifteenSecondsIsInvalid() {
        assertInvalid(validateSeedanceRequest(config(durationSeconds = 16), CHARACTER_PATH))
    }

    // ---- 分辨率矩阵 ----

    @Test
    fun standardVariantAcceptsAllResolutions() {
        for (resolution in SeedanceResolution.entries) {
            assertEquals("标准模型应支持 $resolution", SeedanceValidationResult.Valid,
                validateSeedanceRequest(config(resolution = resolution), CHARACTER_PATH))
        }
    }

    @Test
    fun fastVariantAcceptsOnly480pAnd720p() {
        val fast = config(variant = SeedanceModelVariant.FAST)
        assertEquals(SeedanceValidationResult.Valid,
            validateSeedanceRequest(fast.copy(resolution = SeedanceResolution.P480), CHARACTER_PATH))
        assertEquals(SeedanceValidationResult.Valid,
            validateSeedanceRequest(fast.copy(resolution = SeedanceResolution.P720), CHARACTER_PATH))
        assertInvalid(validateSeedanceRequest(fast.copy(resolution = SeedanceResolution.P1080), CHARACTER_PATH))
        assertInvalid(validateSeedanceRequest(fast.copy(resolution = SeedanceResolution.P4K), CHARACTER_PATH))
    }

    // ---- 必填字段 ----

    @Test
    fun blankCharacterImagePathIsInvalid() {
        assertInvalid(validateSeedanceRequest(config(), ""))
        assertInvalid(validateSeedanceRequest(config(), "   "))
    }

    @Test
    fun blankApiKeyIsInvalid() {
        assertInvalid(validateSeedanceRequest(config(apiKey = ""), CHARACTER_PATH))
        assertInvalid(validateSeedanceRequest(config(apiKey = "   "), CHARACTER_PATH))
    }

    @Test
    fun blankBaseUrlIsInvalid() {
        assertInvalid(validateSeedanceRequest(config(baseUrl = ""), CHARACTER_PATH))
        assertInvalid(validateSeedanceRequest(config(baseUrl = "  "), CHARACTER_PATH))
    }

    // ---- 固定音频与比例 ----

    @Test
    fun generateAudioIsAlwaysEnabled() {
        assertTrue(SeedanceConfig().generateAudio)
        assertTrue(config(durationSeconds = 15).copy(watermark = true).generateAudio)
    }

    @Test
    fun everyRatioExposesExpectedApiValue() {
        assertEquals("9:16", SeedanceRatio.PORTRAIT.apiValue)
        assertEquals("16:9", SeedanceRatio.LANDSCAPE.apiValue)
        assertEquals("1:1", SeedanceRatio.SQUARE.apiValue)
        assertEquals("3:4", SeedanceRatio.PORTRAIT_CLASSIC.apiValue)
        assertEquals("4:3", SeedanceRatio.LANDSCAPE_CLASSIC.apiValue)
        assertEquals("21:9", SeedanceRatio.ULTRAWIDE.apiValue)
        assertEquals("adaptive", SeedanceRatio.ADAPTIVE.apiValue)
    }

    // ---- 默认值与模型 ID 契约 ----

    @Test
    fun configDefaultsMatchContract() {
        val config = SeedanceConfig()
        assertEquals(DEFAULT_BASE_URL, config.baseUrl)
        assertEquals("", config.apiKey)
        assertEquals("kwvideo-v2-ref", config.relayModelId)
        assertEquals(SeedanceModelVariant.STANDARD, config.variant)
        assertEquals(SeedanceResolution.P720, config.resolution)
        assertEquals(SeedanceRatio.PORTRAIT, config.ratio)
        assertEquals(5, config.durationSeconds)
        assertFalse(config.watermark)
        assertEquals(null, config.backgroundImagePath)
        assertEquals("", config.sceneDescription)
    }

    @Test
    fun modelVariantIdsMatchOfficialContract() {
        assertEquals("doubao-seedance-2-0-260128", SeedanceModelVariant.STANDARD.modelId)
        assertEquals("doubao-seedance-2-0-fast-260128", SeedanceModelVariant.FAST.modelId)
    }

    // ---- 中转站媒体协议 ----

    @Test
    fun mediaRelay_blankModelId_isInvalid() {
        val media = config(baseUrl = "https://api.lk888.ai/v1/media/generate").copy(relayModelId = "   ")
        assertInvalid(validateSeedanceRequest(media, CHARACTER_PATH))
    }

    @Test
    fun mediaRelay_configuredModelId_isValid() {
        val media = config(baseUrl = "https://api.lk888.ai/v1/media/generate").copy(relayModelId = "kwvideo-v2-ref")
        assertEquals(SeedanceValidationResult.Valid, validateSeedanceRequest(media, CHARACTER_PATH))
    }

    @Test
    fun arkProtocol_blankRelayModelId_stillValid() {
        // 官方方舟不使用 relayModelId，留空不影响校验。
        val ark = config().copy(relayModelId = "")
        assertEquals(SeedanceValidationResult.Valid, validateSeedanceRequest(ark, CHARACTER_PATH))
    }

    // ---- 组合场景与错误文案 ----

    @Test
    fun fullyPopulatedConfigIsValid() {
        val populated = config(
            variant = SeedanceModelVariant.FAST,
            resolution = SeedanceResolution.P720,
            ratio = SeedanceRatio.ADAPTIVE,
            durationSeconds = 15,
        ).copy(watermark = true, backgroundImagePath = "scene/beach.png", sceneDescription = "海边日落")

        assertEquals(SeedanceValidationResult.Valid,
            validateSeedanceRequest(populated, CHARACTER_PATH))
    }

    @Test
    fun invalidMessagesAreUserFacingChinese() {
        val cases = listOf(
            validateSeedanceRequest(config(baseUrl = ""), CHARACTER_PATH) to "必须配置 Seedance 服务地址",
            validateSeedanceRequest(config(apiKey = ""), CHARACTER_PATH) to "必须配置 Seedance API Key",
            validateSeedanceRequest(config(durationSeconds = 3), CHARACTER_PATH) to "视频时长必须在 4-15 秒之间",
            validateSeedanceRequest(
                config(variant = SeedanceModelVariant.FAST, resolution = SeedanceResolution.P1080),
                CHARACTER_PATH,
            ) to "该模型仅支持 P480、P720 分辨率",
            validateSeedanceRequest(config(), "") to "必须提供角色立绘图片",
        )
        for ((result, expected) in cases) {
            assertEquals(expected, (result as SeedanceValidationResult.Invalid).message)
        }
    }
}
