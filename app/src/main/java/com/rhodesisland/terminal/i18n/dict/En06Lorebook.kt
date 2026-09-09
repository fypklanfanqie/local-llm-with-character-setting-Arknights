package com.rhodesisland.terminal.i18n

/**
 * 英文词典 —— 批次 06Lorebook：世界书 + 玻璃组件
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 英文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val En06LorebookEntries: List<Pair<String, String>> = listOf(

    // ===== 玻璃组件（CollapsibleSection）=====
    "展开{0}" to "Expand {0}",
    "收起{0}" to "Collapse {0}",

    // ===== 世界书详情页 · 顶栏 / 底部操作 =====
    "导出失败，请重试" to "Export failed. Try again",
    "{0} 个条目 · 点击条目编辑详情" to "{0} entries · Tap an entry to edit its details",
    "修改生效范围" to "Change scope",
    "添加条目" to "Add entry",
    "导出本书" to "Export this lorebook",
    "世界书不存在或已被删除" to "The lorebook does not exist or has been deleted",
    "加载中…" to "Loading…",

    // ===== 世界书详情页 · 条目摘要 =====
    " · 顺序 {0}" to " · Order {0}",
    " · 常驻" to " · Constant",
    " · 已停用" to " · Disabled",
    "未命名条目" to "Unnamed entry",
    "角色设定前" to "Before character persona",
    "角色设定后" to "After character persona",
    "@深度{0}" to "@Depth {0}",

    // ===== 世界书详情页 · 生效范围摘要 =====
    "全局：所有角色聊天与群聊都生效" to "Global: applies to all character chats and group chats",
    "未绑定角色（点此选择）" to "No characters bound (tap to select)",
    "已删除角色" to "Deleted character",
    "仅角色：{0}" to "Characters only: {0}",
    "未绑定群聊（点此选择）" to "No group chats bound (tap to select)",
    "已删除群聊" to "Deleted group chat",
    "仅群聊：{0}" to "Group chats only: {0}",

    // ===== 世界书详情页 · 生效范围弹窗 =====
    "生效范围" to "Scope",
    "指定角色" to "Specific characters",
    "指定群聊" to "Specific group chats",
    "搜索" to "Search",
    "搜索角色名、代号或 ID" to "Search by character name, codename, or ID",
    "搜索群聊名称或 ID" to "Search by group chat name or ID",
    "所有角色聊天与群聊都会应用本书。" to "This lorebook applies to all character chats and group chats.",
    "已删除角色（ID: {0}）" to "Deleted character (ID: {0})",
    "没有找到匹配的角色" to "No matching character found",
    "已删除群聊（ID: {0}）" to "Deleted group chat (ID: {0})",
    "没有找到匹配的群聊" to "No matching group chat found",

    // ===== 世界书详情页 · 重命名弹窗 =====
    "重命名世界书" to "Rename lorebook",
    "世界书名称" to "Lorebook name",

    // ===== 条目编辑页 · 顶栏 / 空态 =====
    "编辑条目" to "Edit entry",
    "世界书或条目不存在，可能已被删除" to "The lorebook or entry does not exist and may have been deleted",
    "删除此条目" to "Delete this entry",

    // ===== 条目编辑页 · 表单 =====
    "备注名" to "Note name",
    "如：青云宗（留空时用首个关键词当标题）" to "e.g. Qingyun Sect (when blank, the first keyword is used as the title)",
    "主关键词（顿号/逗号分隔，命中任一即触发）" to "Primary keywords (separated by 、 or commas; any match triggers)",
    "如：青云宗、玄真子、大师姐" to "e.g. Qingyun Sect, Xuanzhen Zi, Eldest Senior Sister",
    "条目内容" to "Entry content",
    "命中后注入给 AI 的背景设定正文…" to "Background text injected into the AI once triggered…",
    "次级关键词（可选，配合逻辑做二次筛选）" to "Secondary keywords (optional; combined with the logic for a second filter)",
    "留空则不做次级判定" to "Leave blank to skip the secondary check",
    "次级逻辑" to "Secondary logic",
    "任一在" to "Any present",
    "全在" to "All present",
    "非全在" to "Not all present",
    "全不在" to "None present",
    "设定前" to "Before persona",
    "设定后" to "After persona",
    "@深度" to "@Depth",
    "插入深度（插到倒数第几条消息上方）" to "Insert depth (inserted above the Nth message from the end)",
    "插入顺序（越大越靠下、影响越强）" to "Insert order (higher = later and more influential)",
    "触发概率 %（100 = 必触发）" to "Trigger probability % (100 = always triggers)",
    "常驻条目（蓝灯）" to "Constant entry (blue light)",
    "无需关键词，每次对话都注入" to "Injected in every conversation with no keyword needed",
    "非常驻条目至少需要一个主关键词" to "A non-constant entry needs at least one primary keyword",

    // ===== 条目编辑页 · 高级选项 =====
    "高级" to "Advanced",
    "区分大小写" to "Case sensitive",
    "主要针对英文关键词" to "Mainly for English keywords",
    "匹配整个单词" to "Match whole words",
    "英文全词匹配；中文关键词自动按子串处理" to "English matches whole words; Chinese keywords are handled as substrings",
    "防止递归" to "Prevent recursion",
    "本条内容不再触发其他条目" to "This entry's content will not trigger other entries",
    "排除递归" to "Exclude recursion",
    "本条只能由对话文本直接触发，不被其他条目连锁激活" to "This entry can only be triggered directly by the conversation text, not chained from other entries",
    "扫描深度覆盖（留空 = 用全局设置）" to "Scan depth override (blank = use the global setting)",

    // ===== 条目编辑页 · 保存 / 删除 =====
    "创建条目" to "Create entry",
    "删除条目" to "Delete entry",
    "确定删除「{0}」？该操作不可恢复。" to "Delete \"{0}\"? This cannot be undone.",
)
