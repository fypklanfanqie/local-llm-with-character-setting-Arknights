package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.Lorebook
import com.rhodesisland.terminal.data.model.LorebookEntry
import com.rhodesisland.terminal.data.model.LorebookGlobalConfig
import com.rhodesisland.terminal.data.model.LorebookInsertPosition
import com.rhodesisland.terminal.data.model.LorebookSecondaryLogic
import kotlin.random.Random

/**
 * 世界书激活结果（缓存友好布局，移植自大众版）。
 *
 * [staticHead]：**纯静态**段——由 constant（常驻/蓝灯）条目拼成，内容只随条目编辑而变，
 * 与本轮对话无关。调用方把它拼进 system，保证 system 逐字节稳定、KV 前缀缓存可复用。
 *
 * [tailInjection]：**动态**段——关键词命中的全部条目（每轮不同）。绝不进 system：
 * 云端作为深度 system 消息插到消息列表尾部，本地并入最新 user 消息。这样长头部
 * （人设+世界观+静态世界书）每轮逐字节一致 → 本地 anchor 稳定 / 云端前缀缓存命中，
 * 只有短尾部参与重算。
 *
 * [estimatedTokens] 为两段合计估算 token（复用 [PromptWindowPlanner.estimateTextTokens]）。
 */
data class LorebookActivation(
    val staticHead: String,
    val tailInjection: String,
    val activatedCount: Int,
    val estimatedTokens: Int,
) {
    val isEmpty: Boolean get() = staticHead.isEmpty() && tailInjection.isEmpty()
}

/**
 * 世界书匹配引擎（纯 Kotlin，JVM 可测；移植自大众版，逻辑一致）。
 *
 * 激活流程：constant 直过 → 主关键词命中 → 次级关键词按逻辑判定 → 概率掷点 →
 * 可选递归轮（已激活 content 并入扫描文本再扫，最多 [MAX_RECURSION_ROUNDS] 轮，尊重
 * preventRecursion / excludeRecursion）→ 预算装配（constant > 高 order > 直接 > 递归；
 * 超限从「递归低 order」起丢，constant 最高 order 永远最后丢）→ 分静态头/动态尾两段拼装。
 *
 * 关键词默认子串忽略大小写；matchWholeWords 用 \b 词界正则，含 CJK 字符的关键词退化为子串
 * （中文无词界，Java 正则 \b 基于 ASCII \w 判定会误判）。
 *
 * 缓存策略：条目 position 仅作为**排序语义**参与组内 order 排布（设定前=更靠上/弱，
 * 设定后与 @深度=更靠下/强），不再产生独立注入通道——所有动态命中统一走尾部注入，
 * 保证 system 头部逐字节稳定、本地 anchor 与云端前缀缓存全程复用。
 */
object LorebookEngine {

    private const val MAX_RECURSION_ROUNDS = 3
    private const val RANK_CONSTANT = 0
    private const val RANK_DIRECT = 1
    private const val RANK_RECURSIVE = 2

    /** 静态头包裹语（constant 条目段）；动态尾用 [TAIL_HEAD]，两者措辞区分便于模型理解时效性。 */
    private const val STATIC_HEAD = "\n【世界背景设定】以下为常驻世界观，请在对话中始终遵循：\n"

    /** 动态尾包裹语（关键词命中段）。 */
    private const val TAIL_HEAD = "【相关设定】本轮对话涉及以下背景，请自然融入回应：\n"

    /**
     * [books] 建议由调用方先按作用域过滤（data.model.matchesScope）再传入；
     * 引擎仍防御性复查 masterEnabled/enabled/content 非空。
     * [scanMessages] 为最近消息列表（建议 takeLast(50)，含本次用户输入）；条目级
     * [LorebookEntry.scanDepthOverride] 在引擎内按各自窗口截取尾部判定。
     * [random] 注入以便概率单测固定 seed。
     */
    fun activate(
        books: List<Lorebook>,
        config: LorebookGlobalConfig,
        scanMessages: List<ChatMessage>,
        random: Random = Random.Default,
    ): LorebookActivation {
        if (!config.masterEnabled) return empty()
        val candidates = books.asSequence()
            .filter { it.enabled }
            .flatMap { it.entries.asSequence() }
            .filter { it.enabled && it.content.isNotBlank() }
            .toList()
        if (candidates.isEmpty()) return empty()

        val messageTexts = scanMessages.map { it.content }
        fun windowText(n: Int): String =
            if (n <= 0 || messageTexts.isEmpty()) ""
            else messageTexts.takeLast(minOf(n, messageTexts.size)).joinToString("\n")
        val baseWindow = windowText(config.scanDepth)

        class Hit(val entry: LorebookEntry, val rank: Int)
        val hits = LinkedHashMap<String, Hit>()

        // 轮次一：常驻直过 + 关键词直连命中
        for (e in candidates) {
            when {
                e.constant -> hits[e.id] = Hit(e, RANK_CONSTANT)
                e.keys.isEmpty() -> Unit // 空 keys 且非 constant 永不触发（ST 行为）
                else -> {
                    val win = e.scanDepthOverride?.takeIf { it > 0 }?.let { windowText(it) } ?: baseWindow
                    if (win.isNotEmpty() && matchesEntry(e, win) && probabilityPass(e, random)) {
                        hits[e.id] = Hit(e, RANK_DIRECT)
                    }
                }
            }
        }

        // 递归轮：世界书激活世界书。excludeRecursion 条目仅首轮可命中；preventRecursion 不外溢扫描文本。
        if (config.recursiveScanning && hits.isNotEmpty()) {
            var roundText = hits.values.filter { !it.entry.preventRecursion }
                .joinToString("\n") { it.entry.content }
            repeat(MAX_RECURSION_ROUNDS) {
                if (roundText.isBlank()) return@repeat
                var grew = false
                val nextText = StringBuilder(roundText)
                for (e in candidates) {
                    if (hits.containsKey(e.id) || e.excludeRecursion || e.constant || e.keys.isEmpty()) continue
                    if (matchesEntry(e, roundText) && probabilityPass(e, random)) {
                        hits[e.id] = Hit(e, RANK_RECURSIVE)
                        grew = true
                        if (!e.preventRecursion) nextText.append('\n').append(e.content)
                    }
                }
                if (!grew) return@repeat
                roundText = nextText.toString()
            }
        }

        if (hits.isEmpty()) {
            // 无动态命中但可能有 constant 条目 → 只出静态头
            val staticOnly = candidates.filter { it.constant }
                .sortedBy { it.order }
                .joinToString("") { formatBlock(it) }
            if (staticOnly.isEmpty()) return empty()
            val head = STATIC_HEAD + staticOnly
            return LorebookActivation(head, "", 0, PromptWindowPlanner.estimateTextTokens(head))
        }

        // 预算装配：rank 升序（constant 最先保）+ order 降序（高 order 先保）。cap<=0 不限。
        // 注意 constant 也计入预算——极端情况下超预算时 constant 低 order 先丢，最高 order 最后丢。
        val cap = config.budgetCapTokens
        val ranked = hits.values.sortedWith(compareBy({ it.rank }, { -it.entry.order }))
        var used = 0
        val kept = ArrayList<Pair<Int, LorebookEntry>>(ranked.size) // rank to entry
        for (hit in ranked) {
            val cost = PromptWindowPlanner.estimateTextTokens(formatBlock(hit.entry))
            if (cap > 0 && used + cost > cap) break
            kept.add(hit.rank to hit.entry)
            used += cost
        }
        if (kept.isEmpty()) return empty()

        val staticHead = kept.filter { it.first == RANK_CONSTANT }
            .map { it.second }
            .let { assembleStatic(it) }
        // 动态尾：position 语义转为排序权重（BEFORE_CHAR 视为更靠前/弱，其余靠后/强），
        // 组内 order 升序；块间以 position 权重稳定排序保证同轮内确定性。
        val tailBlocks = kept.filter { it.first != RANK_CONSTANT }
            .sortedWith(
                compareBy(
                    { it.second.position.sortWeight() },
                    { it.second.order },
                ),
            )
            .joinToString("") { formatBlock(it.second) }
        val tailInjection = if (tailBlocks.isEmpty()) "" else TAIL_HEAD + tailBlocks

        val totalText = staticHead + tailInjection
        return LorebookActivation(
            staticHead = staticHead,
            tailInjection = tailInjection,
            activatedCount = kept.size,
            estimatedTokens = PromptWindowPlanner.estimateTextTokens(totalText),
        )
    }

    /** 常驻条目静态头：order 升序。内容只随条目编辑变化，不随对话轮次抖动。 */
    private fun assembleStatic(entries: List<LorebookEntry>): String {
        if (entries.isEmpty()) return ""
        return STATIC_HEAD + entries.sortedBy { it.order }.joinToString("") { formatBlock(it) }
    }

    /**
     * 动态尾的落位消息（云端路径用）：调用方插入到消息列表尾部附近。
     * 本地路径不走此函数——直接取 [LorebookActivation.tailInjection] 并入最新 user 消息。
     */
    fun buildTailMessage(activation: LorebookActivation): ChatMessage? =
        if (activation.tailInjection.isEmpty()) null
        else ChatMessage(role = "system", content = activation.tailInjection)

    // ===== 匹配 =====

    /** 主关键词任一命中 + 次级关键词按 logic 判定；次级为空时四种逻辑均直过。 */
    private fun matchesEntry(entry: LorebookEntry, text: String): Boolean {
        if (!matchAny(entry.keys, text, entry.caseSensitive, entry.matchWholeWords)) return false
        if (entry.secondaryKeys.isEmpty()) return true
        val any = matchAny(entry.secondaryKeys, text, entry.caseSensitive, entry.matchWholeWords)
        val all = entry.secondaryKeys.all { keyHit(it, text, entry.caseSensitive, entry.matchWholeWords) }
        return when (entry.logic) {
            LorebookSecondaryLogic.AND_ANY -> any
            LorebookSecondaryLogic.AND_ALL -> all
            LorebookSecondaryLogic.NOT_ALL -> !all
            LorebookSecondaryLogic.NOT_ANY -> !any
        }
    }

    private fun matchAny(keys: List<String>, text: String, caseSensitive: Boolean, wholeWords: Boolean): Boolean =
        keys.any { keyHit(it, text, caseSensitive, wholeWords) }

    private fun keyHit(keyword: String, text: String, caseSensitive: Boolean, wholeWords: Boolean): Boolean {
        val k = keyword.trim()
        if (k.isEmpty()) return false
        // 含 CJK/汉字等非 ASCII 词文字时退化为子串：Java 正则 \b 基于 ASCII \w，中文词界判定不可靠
        if (wholeWords && k.none { it.code >= 0x2E80 }) {
            val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val regex = runCatching { Regex("\\b${Regex.escape(k)}\\b", opts) }.getOrNull()
                ?: return containsKeyword(k, text, caseSensitive)
            return regex.containsMatchIn(text)
        }
        return containsKeyword(k, text, caseSensitive)
    }

    private fun containsKeyword(k: String, text: String, caseSensitive: Boolean): Boolean =
        if (caseSensitive) text.contains(k) else text.contains(k, ignoreCase = true)

    private fun probabilityPass(entry: LorebookEntry, random: Random): Boolean {
        if (entry.probability >= 100) return true
        return random.nextInt(100) < entry.probability.coerceAtLeast(1)
    }

    // ===== 拼装 =====

    /** 动态尾排序权重：设定前(弱/靠上) < 设定后 = @深度(强/靠下)。position 不再产生独立通道。 */
    private fun LorebookInsertPosition.sortWeight(): Int = when (this) {
        LorebookInsertPosition.BEFORE_CHAR -> 0
        LorebookInsertPosition.AFTER_CHAR -> 1
        LorebookInsertPosition.AT_DEPTH -> 2
    }

    /** 单条目注入块，仿【世界观】中文标记风格（WorldviewConfig.buildWorldviewDirective 先例）。 */
    private fun formatBlock(entry: LorebookEntry): String = buildString {
        append('\n')
        val header = when {
            entry.title.isNotBlank() -> entry.title.trim()
            entry.keys.isNotEmpty() -> entry.keys.first().trim()
            else -> "设定"
        }
        append('【').append(header).append("】\n")
        append(entry.content.trim())
        append('\n')
    }

    private fun empty(): LorebookActivation =
        LorebookActivation("", "", 0, 0)

    // ===== 便捷解析层（Resolved / resolve / apply）=====
    //
    // 消费者（GreetingWorker / GroupChatWorker / GroupChatViewModel 等）期望的三段式 API：
    // resolve(books, scanTexts, settings) -> Resolved(stableBeforeChar, stableAfterChar, tailBlock)
    //   —— 蓝灯（constant）条目拆成「角色定义前/后」两段进 system 稳定区；绿灯命中合并为尾块。
    // apply(messages, resolved, allowMidHistorySystem) -> 尾块落位后的消息列表。
    // 底层复用 [activate] 的匹配/递归/预算逻辑，仅重新拼装输出。

    /** 世界书解析结果：稳定区前后段 + 动态尾块（均可能为空串）。 */
    data class Resolved(
        val stableBeforeChar: String = "",
        val stableAfterChar: String = "",
        val tailBlock: String = "",
    ) {
        val isEmpty: Boolean get() = stableBeforeChar.isEmpty() && stableAfterChar.isEmpty() && tailBlock.isEmpty()
    }

    /**
     * 解析世界书：[books] 已由调用方按作用域过滤；[scanTexts] 为最近消息文本列表
     * （已剥思考，含本次用户输入）；[settings] 全局参数。
     *
     * 蓝灯条目按 position 拆两段（BEFORE_CHAR → stableBeforeChar，其余 → stableAfterChar，
     * 组内 order 升序，与 [assembleStatic] 一致的稳定性语义）；绿灯/递归命中的全部条目
     * 合并进 tailBlock（预算裁剪沿用 activate 的 constant>高order>直接>递归 顺序）。
     */
    fun resolve(
        books: List<Lorebook>,
        scanTexts: List<String>,
        settings: LorebookGlobalConfig,
        random: Random = Random.Default,
    ): Resolved {
        if (!settings.masterEnabled) return Resolved()
        val scanMessages = scanTexts.map { ChatMessage(role = "user", content = it) }
        val activation = activate(books, settings, scanMessages, random)
        if (activation.isEmpty) return Resolved()

        // 重扫一遍蓝灯条目拼前后段（activate 把蓝灯并进 staticHead 单段，这里需要按 position 分列）。
        val constantEntries = books.asSequence()
            .filter { it.enabled && settings.masterEnabled }
            .flatMap { it.entries.asSequence() }
            .filter { it.enabled && it.constant && it.content.isNotBlank() }
            .toList()
        val before = constantEntries.filter { it.position == LorebookInsertPosition.BEFORE_CHAR }
            .sortedBy { it.order }.joinToString("\n") { it.content.trim() }
        val after = constantEntries.filter { it.position != LorebookInsertPosition.BEFORE_CHAR }
            .sortedBy { it.order }.joinToString("\n") { it.content.trim() }

        // 尾块：activate 的动态段已含包裹头；去掉包裹头只留条目正文（调用方自行加标题），
        // 与测试期望的「【绿灯】\n触发内容」形态一致——直接用 TAIL_HEAD 后的内容。
        val tailBody = activation.tailInjection.removePrefix(TAIL_HEAD)
        return Resolved(
            stableBeforeChar = before,
            stableAfterChar = after,
            tailBlock = tailBody,
        )
    }

    /** [Resolved.tailBlock] 的落位消息（云端路径插尾部 system；空块返回 null）。 */
    fun tailBlockMessageOf(resolved: Resolved): ChatMessage? =
        if (resolved.tailBlock.isBlank()) null else ChatMessage(role = "system", content = AppConfig.Lorebook.REFERENCE_HEADER + "\n" + resolved.tailBlock)

    /**
     * 把 [resolved] 的尾块落到消息列表：
     * - [allowMidHistorySystem]=true（云端）：尾块作为独立 system 消息插到末尾；
     * - false（本地 MNN）：并入最后一条 user 消息头部（本地 PromptWindowPlanner 不接受中段 system 打破配对）。
     * resolved 为空时原样返回 [messages]。
     */
    fun apply(
        messages: List<ChatMessage>,
        resolved: Resolved,
        allowMidHistorySystem: Boolean,
    ): List<ChatMessage> {
        if (resolved.isEmpty || resolved.tailBlock.isBlank()) return messages
        val tailMsg = tailBlockMessageOf(resolved) ?: return messages
        return if (allowMidHistorySystem) {
            messages + tailMsg
        } else {
            val lastUserIdx = messages.indexOfLast { it.role == "user" }
            if (lastUserIdx < 0) return messages + tailMsg
            messages.mapIndexed { idx, m ->
                if (idx == lastUserIdx) {
                    m.copy(content = "${AppConfig.Lorebook.REFERENCE_HEADER}\n${resolved.tailBlock}\n\n" + m.content, modelContent = null)
                } else {
                    m
                }
            }
        }
    }
}
