package com.rhodesisland.terminal.data.model

import kotlinx.serialization.Serializable

/**
 * 世界书（Lorebook / World Info）数据模型（移植自大众版实现，逻辑保持一致）。
 *
 * 一本 [Lorebook] 由若干关键词触发的条目组成；书按 [LorebookScopeType] 作用域路由
 * （全局 / 多选角色私聊 / 多选群聊），覆盖内置 + 自定义角色。持久化于 SettingsStore 键
 * `lorebooks` / `lorebook_config`（JSON 列表，仿 custom_characters / worldviews 模式）。
 *
 * SillyTavern 兼容：字段与 ST 世界书条目一一对应（见 data/lorebook/LorebookJson），
 * 支持直接导入社区现成 JSON。
 */

/** 次级关键词逻辑（对应 ST selectiveLogic 0-3）。 */
@Serializable
enum class LorebookSecondaryLogic {
    /** 主命中且任一次级在 */
    AND_ANY,

    /** 主命中但次级非全在 */
    NOT_ALL,

    /** 主命中且次级全不在 */
    NOT_ANY,

    /** 主命中且次级全在 */
    AND_ALL,
}

/** 条目注入位置。同位置组内按 order 升序排（大者靠下靠后、离对话近、影响强）。 */
@Serializable
enum class LorebookInsertPosition {
    /** 角色定义之前（最弱） */
    BEFORE_CHAR,

    /** 角色定义之后 */
    AFTER_CHAR,

    /** 按深度插入聊天记录（距末尾 [LorebookEntry.depth] 条处，最强可变） */
    AT_DEPTH,
}

/**
 * 单个世界书条目。
 *
 * id 约定 `"lbe-" + System.currentTimeMillis()`；title 对应 ST comment（备注名）。
 * [scanDepthOverride] 为 null 时用全局扫描深度（对应 ST scan_depth:null）；≤0 同样视为继承全局。
 */
@Serializable
data class LorebookEntry(
    val id: String,
    val title: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val logic: LorebookSecondaryLogic = LorebookSecondaryLogic.AND_ANY,
    val content: String = "",
    /** 常驻条目（蓝灯）：无需关键词，每次必注入。 */
    val constant: Boolean = false,
    val enabled: Boolean = true,
    val position: LorebookInsertPosition = LorebookInsertPosition.BEFORE_CHAR,
    /** 仅 AT_DEPTH 有效：插到倒数第 N 条消息上方。 */
    val depth: Int = 4,
    /** 插入顺序：数值越大越靠下、影响越强；预算装配时高 order 优先保留。 */
    val order: Int = 100,
    /** 触发概率 1-100（100 = 必触发）。 */
    val probability: Int = 100,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
    val scanDepthOverride: Int? = null,
    /** 本条 content 不参与下一轮递归扫描。 */
    val preventRecursion: Boolean = false,
    /** 本条不能被递归轮激活（仅首轮直连可触发）。 */
    val excludeRecursion: Boolean = false,
)

/**
 * 世界书作用域：决定本书在哪些聊天里参与匹配（书可绑定多个目标）。
 */
@Serializable
enum class LorebookScopeType {
    /** 全局：所有个人聊天与群聊都生效 */
    ALL,

    /** 仅绑定的角色个人聊天生效（scopeIds = 角色 id 列表，可多个） */
    CHARACTER,

    /** 仅绑定的群聊生效（scopeIds = 群会话 id 字符串列表，可多个） */
    GROUP,
}

/**
 * 一本世界书。id 约定 `"lb-" + System.currentTimeMillis()`。
 *
 * [scopeType]/[scopeIds] 是本 app 特有的路由概念（SillyTavern 文件格式不含绑定信息，
 * 导入的书一律落 ALL）；导出时也不写出。
 */
@Serializable
data class Lorebook(
    val id: String,
    val name: String,
    /** 书级开关：关闭后整本书不参与匹配。 */
    val enabled: Boolean = true,
    val entries: List<LorebookEntry> = emptyList(),
    val scopeType: LorebookScopeType = LorebookScopeType.ALL,
    val scopeIds: List<String> = emptyList(),
)

/**
 * 全局参数（设置分区管理）。
 *
 * [budgetCapTokens] 默认必须非 0：本地默认 context 仅 4096，无限预算的长注入链会触发
 * Planner 的 SYSTEM_PROMPT_TOO_LARGE 直接报错；1024 为安全余量。≤0 表示不限。
 */
@Serializable
data class LorebookGlobalConfig(
    val masterEnabled: Boolean = true,
    /** 扫描最近 N 条消息找关键词（默认跟 SillyTavern）。 */
    val scanDepth: Int = 2,
    /** 递归扫描：已激活条目的 content 可再触发其他条目（最多 3 轮）。 */
    val recursiveScanning: Boolean = false,
    val budgetCapTokens: Int = 1024,
)

/** 世界书作用域过滤：当前聊天上下文（角色 id 或群 id）→ 本书是否参与匹配。 */
fun Lorebook.matchesScope(characterId: String?, groupConversationId: String?): Boolean = when (scopeType) {
    LorebookScopeType.ALL -> true
    LorebookScopeType.CHARACTER -> characterId != null && characterId in scopeIds
    LorebookScopeType.GROUP -> groupConversationId != null && groupConversationId in scopeIds
}

/**
 * 世界书查询目标类型（SettingsRepository.activeLorebooksFor 的路由参数）：
 * 与 [LorebookScopeType] 的 CHARACTER/GROUP 一一对应；ALL 书恒参与。
 * 与 WorldviewTargetType 同构（世界观与世界书共用「全局/角色私聊/群聊」三段路由语义）。
 */
@Serializable
enum class LorebookTargetType {
    /** 角色个人聊天目标（targetId = 角色 id）。 */
    CHARACTER,

    /** 群聊目标（targetId = 群会话 id 字符串）。 */
    GROUP,
}
