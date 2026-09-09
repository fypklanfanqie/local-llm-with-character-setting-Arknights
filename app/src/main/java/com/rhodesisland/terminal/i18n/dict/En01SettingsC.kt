package com.rhodesisland.terminal.i18n

/**
 *  —— 批次 01SettingsC：设置页世界书/世界观/指南弹窗/选择器
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val En01SettingsCEntries: List<Pair<String, String>> = listOf(
    // ===== 世界书 =====
    "世界书" to "Lorebook",
    "按关键词触发的背景设定库：对话提到关键词时自动注入对应设定。" to "A background-setting library triggered by keywords: matching entries are injected automatically when the conversation mentions their keywords.",
    "支持导入 SillyTavern 世界书 JSON 文件，可按作用域绑定角色私聊与群聊。" to "Supports importing SillyTavern lorebook JSON files, and can bind to character chats and group chats by scope.",
    "世界书总开关已关闭，所有条目均不会注入。" to "The lorebook master switch is off; no entries will be injected.",
    "还没有世界书，点下方「新建」或「导入 .json」开始。" to "No lorebooks yet. Tap \"New\" or \"Import .json\" below to start.",
    "未命名世界书" to "Unnamed lorebook",
    "{0} 条 · {1} 启用" to "{0} entries · {1} enabled",
    "已停用" to "Disabled",
    "新建世界书" to "New lorebook",
    "如：修仙世界" to "e.g. Cultivation world",
    "创建" to "Create",
    "新建" to "New",
    "导入 .json" to "Import .json",
    "删除世界书" to "Delete lorebook",
    "确定删除「{0}」及其全部 {1} 个条目？该操作不可恢复。" to "Delete \"{0}\" and all {1} of its entries? This cannot be undone.",
    "世界书导入" to "Lorebook import",
    "读取文件失败" to "Failed to read the file",
    "导入失败：{0}" to "Import failed: {0}",
    "导入的世界书" to "Imported lorebook",
    "已导入「{0}」共 {1} 条" to "Imported \"{0}\" with {1} entries",

    // ===== 世界书 · 全局参数 =====
    "扫描深度" to "Scan depth",
    "扫描最近 {0} 条消息中的关键词" to "Scan the last {0} messages for keywords",
    "扫描最近多少条消息中的关键词（1-20）" to "How many recent messages to scan for keywords (1-20)",
    "Token 预算上限" to "Token budget cap",
    "单次注入不超过约 {0} tokens" to "No more than about {0} tokens per injection",
    "单次注入的 token 上限（0 表示不限）" to "Token cap per injection (0 = unlimited)",
    "不限" to "Unlimited",
    "递归扫描" to "Recursive scan",
    "已激活条目的内容可再触发其他条目" to "Content of activated entries can trigger other entries",

    // ===== 世界书 · 作用域摘要 =====
    "全局" to "Global",
    "未绑定角色" to "No characters bound",
    "{0} 个角色" to "{0} characters",
    "未绑定群聊" to "No group chats bound",
    "{0} 个群聊" to "{0} group chats",

    // ===== 自定义世界观 =====
    "自定义世界观" to "Custom worldview",
    "未添加" to "None added",
    "{0} 条已生效" to "{0} active",
    "世界观是一段注入对话提示词的自定义设定（如「故事发生在末日废土」）。" to "A worldview is a custom setting injected into the conversation prompt (for example, \"the story takes place in a post-apocalyptic wasteland\").",
    "每条世界观绑定一个应用对象——某个角色的私聊或某个群聊；同一对象重复保存将替换旧设定。" to "Each worldview binds to one target — a character chat or a group chat; saving again for the same target replaces the old setting.",
    "新建世界观" to "New worldview",
    "编辑世界观" to "Edit worldview",
    "删除世界观" to "Delete worldview",
    "确定删除「{0}」？该对象将恢复为无自定义世界观。" to "Delete \"{0}\"? This target will revert to no custom worldview.",
    "未命名世界观" to "Unnamed worldview",
    "替换已有世界观" to "Replace existing worldview",
    "该对象已有世界观「{0}」，保存后将替换它。" to "This target already has the worldview \"{0}\"; saving will replace it.",
    "替换" to "Replace",
    "角色 {0}（已不存在）" to "Character {0} (no longer exists)",
    "群聊（已删除）" to "Group chat (deleted)",
    "名称" to "Name",
    "如：末日废土设定" to "e.g. Post-apocalyptic wasteland",
    "世界观内容（注入提示词）" to "Worldview content (injected into the prompt)",
    "描述这个世界观的规则、背景、氛围…" to "Describe this worldview's rules, background, and mood…",
    "应用到" to "Applies to",
    "私聊角色" to "Character chat",
    "名称、内容与应用对象均为必填项" to "Name, content, and target are all required",
    "保存" to "Save",

    // ===== 目标选择器 =====
    "搜索角色名 / 代号…" to "Search character name / codename…",
    "搜索群聊名称…" to "Search group chat name…",
    "{0} 人" to "{0} members",
    "未找到匹配的群聊" to "No matching group chat found",
    "尚无群聊，请先在通讯页创建" to "No group chats yet. Create one on the Chats page first.",
)
