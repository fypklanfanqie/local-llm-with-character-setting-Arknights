package com.rhodesisland.terminal.llm.template

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [ThinkingTemplateCapabilityResolver] 单元测试（Task 2）。
 *
 * 覆盖：`enable_thinking` 分支 → SUPPORTED；无思考标记完整模板 → UNSUPPORTED；
 * 空/缺失/畸形/仅名称引用 → UNKNOWN；`<think>` 无 `enable_thinking`（R1 风格）→ UNKNOWN；
 * tokenizer 含 `<think>` token → UNKNOWN；缓存命中（同路径同 mtime 不重读）与 mtime 失效。
 */
class ThinkingTemplateCapabilityResolverTest {

    private lateinit var modelDir: File

    @Before
    fun setUp() {
        modelDir = createTempDir()
    }

    @After
    fun tearDown() {
        modelDir.deleteRecursively()
    }

    private fun write(name: String, content: String) {
        File(modelDir, name).writeText(content)
    }

    /** 计数解析器：覆写 readText 统计实际读盘次数（验证缓存）。 */
    private class CountingResolver : ThinkingTemplateCapabilityResolver() {
        var reads = 0
        override fun readText(file: File): String? {
            reads++
            return super.readText(file)
        }
    }

    @Test
    fun configWithEnableThinkingBranch_isSupported() {
        write("config.json", QWEN_JINJA)
        assertEquals(ThinkingTemplateCapability.SUPPORTED, ThinkingTemplateCapabilityResolver().resolve(modelDir))
    }

    @Test
    fun llmConfigJsonWithEnableThinkingBranch_isSupported() {
        write("llm_config.json", QWEN_JINJA)
        assertEquals(ThinkingTemplateCapability.SUPPORTED, ThinkingTemplateCapabilityResolver().resolve(modelDir))
    }

    @Test
    fun templateFileWithoutConfig_isScannedAndSupported() {
        // 无 config.json（无缓存键），但模板文件含 enable_thinking 分支仍应检出。
        write("chat_template.jinja", QWEN_JINJA)
        assertEquals(ThinkingTemplateCapability.SUPPORTED, ThinkingTemplateCapabilityResolver().resolve(modelDir))
    }

    @Test
    fun completeTemplateWithoutThinkingMarkers_isUnsupported() {
        write("config.json", LLAMA_JINJA)
        assertEquals(ThinkingTemplateCapability.UNSUPPORTED, ThinkingTemplateCapabilityResolver().resolve(modelDir))
    }

    @Test
    fun emptyModelDir_isUnknown() {
        assertEquals(ThinkingTemplateCapability.UNKNOWN, ThinkingTemplateCapabilityResolver().resolve(modelDir))
    }

    @Test
    fun emptyConfigFile_isUnknown() {
        write("config.json", "")
        assertEquals(ThinkingTemplateCapability.UNKNOWN, ThinkingTemplateCapabilityResolver().resolve(modelDir))
    }

    @Test
    fun malformedConfigText_isUnknown() {
        write("config.json", "这不是 JSON 也 { 没有 [ Jinja 语法] }")
        assertEquals(ThinkingTemplateCapability.UNKNOWN, ThinkingTemplateCapabilityResolver().resolve(modelDir))
    }

    @Test
    fun namedTemplateReferenceOnly_isUnknown() {
        // 模板仅以名称引用（内容不可见）：信息不足，绝不默认 SUPPORTED。
        write("config.json", """{"llm_model":"llm.mnn","chat_template":"qwen3"}""")
        assertEquals(ThinkingTemplateCapability.UNKNOWN, ThinkingTemplateCapabilityResolver().resolve(modelDir))
    }

    @Test
    fun thinkTagWithoutEnableThinkingBranch_isUnknown() {
        // DeepSeek-R1 风格：模板无条件输出 <think>（无 enable_thinking 分支）——含思考标记，
        // 不符合 UNSUPPORTED 的「明确无思考相关标记」，也不符合 SUPPORTED → UNKNOWN。
        write("config.json", R1_JINJA)
        assertEquals(ThinkingTemplateCapability.UNKNOWN, ThinkingTemplateCapabilityResolver().resolve(modelDir))
    }

    @Test
    fun tokenizerWithThinkTokens_blocksUnsupportedVerdict() {
        // 配置模板无思考标记（可判 UNSUPPORTED），但 tokenizer 词汇表含 <think>：思考相关标记
        // 存在 → 保守判 UNKNOWN，避免「模板不支持」误判。
        write("config.json", LLAMA_JINJA)
        write("tokenizer.txt", "<think> 1\n</think> 2\nthinking 3\n")
        assertEquals(ThinkingTemplateCapability.UNKNOWN, ThinkingTemplateCapabilityResolver().resolve(modelDir))
    }

    @Test
    fun cacheHit_samePathSameMtime_doesNotReread() {
        write("config.json", QWEN_JINJA)
        File(modelDir, "config.json").setLastModified(1_000_000_000_000L)
        val resolver = CountingResolver()
        assertEquals(ThinkingTemplateCapability.SUPPORTED, resolver.resolve(modelDir))
        assertEquals(1, resolver.reads)
        // 同路径同 mtime：命中缓存，不重读盘。
        assertEquals(ThinkingTemplateCapability.SUPPORTED, resolver.resolve(modelDir))
        assertEquals(1, resolver.reads)
    }

    @Test
    fun cacheInvalidated_whenConfigMtimeChanges() {
        write("config.json", QWEN_JINJA)
        File(modelDir, "config.json").setLastModified(1_000_000_000_000L)
        val resolver = CountingResolver()
        assertEquals(ThinkingTemplateCapability.SUPPORTED, resolver.resolve(modelDir))
        // 模板被替换（去掉 enable_thinking 分支）且 mtime 推进：缓存失效，重读并得出新结论。
        write("config.json", LLAMA_JINJA)
        File(modelDir, "config.json").setLastModified(2_000_000_000_000L)
        assertEquals(ThinkingTemplateCapability.UNSUPPORTED, resolver.resolve(modelDir))
        assertEquals(2, resolver.reads)
    }

    companion object {
        /** Qwen3 风格模板：含 `{%- if enable_thinking %}` 分支（SUPPORTED 判据）。 */
        private val QWEN_JINJA = """
            {%- if messages[0]['role'] == 'system' %}
            {{ messages[0]['content'] }}
            {%- endif %}
            {%- for message in messages %}
            {%- if message['role'] == 'user' %}
            <|im_start|>user
            {{ message['content'] }}
            <|im_end|>
            {%- elif message['role'] == 'assistant' %}
            <|im_start|>assistant
            {%- if enable_thinking %}
            <think>
            {%- endif %}
            {{ message['content'] }}
            {%- if not enable_thinking %}
            <|im_end|>
            {%- endif %}
            {%- endif %}
            {%- endfor %}
        """.trimIndent()

        /** Llama 风格模板：完整 Jinja，无任何思考相关标记（UNSUPPORTED 判据）。 */
        private val LLAMA_JINJA = """
            {%- for message in messages %}
            {%- if message['role'] == 'user' %}
            <|start_header_id|>user<|end_header_id|>
            {{ message['content'] }}
            <|eot_id|>
            {%- elif message['role'] == 'assistant' %}
            <|start_header_id|>assistant<|end_header_id|>
            {{ message['content'] }}
            <|eot_id|>
            {%- endif %}
            {%- endfor %}
        """.trimIndent()

        /** DeepSeek-R1 风格模板：无条件输出 <think>，无 enable_thinking 分支。 */
        private val R1_JINJA = """
            {%- if add_generation_prompt is defined %}
            <think>
            {%- endif %}
            {{ message['content'] }}
        """.trimIndent()
    }
}
