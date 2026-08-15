package com.rhodesisland.terminal.llm.template

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 模型聊天模板的深度思考能力（Task 2）。
 *
 * 用于区分「模板含 `enable_thinking` 分支（开关应生效）」「模板无任何思考分支（开关必然无效）」
 * 与「信息不足（无法定位模板/解析失败）」。**绝不默认 SUPPORTED**：拿不到模板文本时按 UNKNOWN 处理，
 * 避免把「开关未生效」误记成「模板可支持」。
 *
 * 范围约束：本解析器只判定 `enable_thinking` **开关分支**。SUPPORTED 不能作为「原生思考 token
 * 预算 / reasoning effort」的证据——预算/effort 能力必须由
 * [com.rhodesisland.terminal.llm.thinking.NativeThinkingBudgetCapabilityResolver] 的完整认证证据判定，
 * 不能由本结果推断。
 */
enum class ThinkingTemplateCapability {
    /** 模板文本含 `enable_thinking` 分支：开关运行时经 jinja context 注入应能生效。 */
    SUPPORTED,
    /** 模板结构清晰且无任何思考相关标记：模型模板不含思考分支，开关必然被忽略。 */
    UNSUPPORTED,
    /** 无法定位模板 / 解析失败 / 信息不足（如模板仅以名称引用、内容不可见）。 */
    UNKNOWN,
}

/**
 * 模板能力解析器（Task 2）：纯关键字分支启发式，不做完整 Jinja 解析。
 *
 * 解析范围：模型目录下的 `config.json`、`llm_config.json`，以及目录内可定位的模板/分词文本文件
 * （文件名含 template 或以 .jinja/.j2/.tpl 结尾，另含 tokenizer.txt）；逐源扫描
 * `enable_thinking`、`<think>`、`</think>`、`thinking` 相关分支。
 *
 * 判定规则：
 * - 任一源文本含 `enable_thinking` → SUPPORTED（分支存在，开关可被模板消费）。
 * - 存在「模板结构清晰」证据（含 Jinja 语法的模板文本，或按文件名定位的模板文件）
 *   且所有源均无思考标记 → UNSUPPORTED（模板明确没有思考分支）。
 * - 其余（无配置/模板、读盘失败、畸形内容、仅有名称引用、含 `<think>` 等标记但无
 *   `enable_thinking` 分支——如 DeepSeek-R1 无条件思考模板）→ UNKNOWN（信息不足，不猜测）。
 *
 * 缓存：按「模型 config 文件路径 + mtime」做进程内缓存，避免每轮生成重复读盘；
 * config.json 优先、llm_config.json 次之；无任一配置文件时不缓存（直接扫描模板文件）。
 * 并发安全：ConcurrentHashMap，读-算-写竞态最多重复计算一次，结果幂等无害。
 */
class ThinkingTemplateCapabilityResolver {

    /** 缓存：键 = `<config 绝对路径>@<mtime>`。 */
    private val cache = ConcurrentHashMap<String, ThinkingTemplateCapability>()

    /**
     * 解析模型目录的思考模板能力。
     * @param modelDir `.mnn` 模型目录（config.json + llm.mnn + 权重 + tokenizer 的所在目录）。
     * @return [ThinkingTemplateCapability.SUPPORTED] / [UNSUPPORTED] / [UNKNOWN]。
     */
    fun resolve(modelDir: File): ThinkingTemplateCapability {
        val primary = primaryConfigFile(modelDir)
        if (primary != null) {
            val key = cacheKey(primary)
            cache[key]?.let { return it }
        }
        val capability = compute(modelDir, primary)
        if (primary != null) {
            cache[cacheKey(primary)] = capability
            // mtime 变化会产生新键、旧键永不命中；达到上限整体清空，防无界增长。
            if (cache.size > MAX_CACHE_ENTRIES) cache.clear()
        }
        return capability
    }

    /**
     * 读取源文件文本（UTF-8，限长防误扫大文件）。internal + open 供测试覆写以统计读盘次数。
     * 读失败（不存在/不可读/超限/异常）返回 null，该源按「信息不足」跳过。
     */
    internal open fun readText(file: File): String? {
        return try {
            if (!file.isFile || file.length() > MAX_SOURCE_BYTES) null else file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** 主配置（缓存键来源）：config.json 优先，其次 llm_config.json；都不是目录返回 null。 */
    private fun primaryConfigFile(modelDir: File): File? {
        if (!modelDir.isDirectory) return null
        val config = File(modelDir, CONFIG_FILE)
        if (config.isFile) return config
        val llmConfig = File(modelDir, LLM_CONFIG_FILE)
        return if (llmConfig.isFile) llmConfig else null
    }

    private fun cacheKey(config: File): String = "${config.absolutePath}@${config.lastModified()}"

    /** 收集全部扫描源：主/次配置 + 按文件名/扩展名定位的模板与分词文本文件（仅直接子文件）。 */
    private fun collectSources(modelDir: File, primary: File?): List<File> {
        if (!modelDir.isDirectory) return emptyList()
        val sources = mutableListOf<File>()
        primary?.let(sources::add)
        File(modelDir, LLM_CONFIG_FILE).takeIf { it.isFile && it != primary }?.let(sources::add)
        modelDir.listFiles()?.asSequence()?.filter { it.isFile }?.forEach { f ->
            if (f == primary || f == File(modelDir, LLM_CONFIG_FILE)) return@forEach
            if (isTemplateCandidate(f.name)) sources.add(f)
        }
        return sources
    }

    /** 模板候选：文件名含 template（chat_template/prompt_template 等）、常见模板扩展名、或 tokenizer.txt。 */
    private fun isTemplateCandidate(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("template") ||
            lower.endsWith(".jinja") || lower.endsWith(".j2") || lower.endsWith(".tpl") ||
            lower == TOKENIZER_FILE
    }

    private fun compute(modelDir: File, primary: File?): ThinkingTemplateCapability {
        val sources = collectSources(modelDir, primary)
        if (sources.isEmpty()) return ThinkingTemplateCapability.UNKNOWN

        var hasTemplateStructure = false
        for (source in sources) {
            val text = readText(source) ?: continue
            // 1) 分支存在即支持：enable_thinking 是唯一「开关应生效」的强证据。
            if (text.contains(ENABLE_THINKING_KEY)) return ThinkingTemplateCapability.SUPPORTED
            // 2) 模板结构证据：Jinja 语句/表达式语法，或文件名即模板文件。
            if (isTemplateLike(text) || isTemplateCandidate(source.name)) hasTemplateStructure = true
            // 3) 思考相关标记（<think>/thinking 等，含 enable_thinking 自身——已在 1 短路）。
            if (hasThinkingMarker(text)) return ThinkingTemplateCapability.UNKNOWN
        }
        // 全部源无任何思考标记：模板结构清晰 → 明确不支持思考开关；结构不清 → 信息不足。
        return if (hasTemplateStructure) ThinkingTemplateCapability.UNSUPPORTED
        else ThinkingTemplateCapability.UNKNOWN
    }

    /** 模板文本证据：含 Jinja 语句 `{%` 或表达式 `{{`（完整模板正文而非名称引用）。 */
    private fun isTemplateLike(text: String): Boolean =
        text.contains("{%") || text.contains("{{")

    /** 思考相关标记：<think> / </think> / thinking（含 enable_thinking 本身，已在主判定短路）。 */
    private fun hasThinkingMarker(text: String): Boolean =
        text.contains(THINK_OPEN_MARKER) || text.contains(THINK_CLOSE_MARKER) || text.contains(THINKING_WORD)

    companion object {
        /** MNN 引擎入口配置文件名（缓存键优先源）。 */
        const val CONFIG_FILE = "config.json"

        /** 备选配置文件名（部分模型包使用）。 */
        const val LLM_CONFIG_FILE = "llm_config.json"

        /** 分词文本文件名（含 <think> 等 token 的词汇表）。 */
        const val TOKENIZER_FILE = "tokenizer.txt"

        /** 模板含分支的关键字：MNN 经 jinja context 注入，Qwen3 等模板以其控制 `<think>` 输出。 */
        const val ENABLE_THINKING_KEY = "enable_thinking"

        const val THINK_OPEN_MARKER = "<think>"
        const val THINK_CLOSE_MARKER = "</think>"
        const val THINKING_WORD = "thinking"

        /** 单源扫描大小上限（字节）：tokenizer.txt 可达数 MB，权重大文件恒二进制不进入候选集。 */
        private const val MAX_SOURCE_BYTES = 8L * 1024 * 1024

        /** 进程内缓存条目上限：mtime 变化产生新键，超过即整体清空，防无界增长。 */
        private const val MAX_CACHE_ENTRIES = 64
    }
}
