package com.rhodesisland.terminal.i18n

/**
 * 英文词典 —— 批次 05GroupChat：群聊
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 英文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val En05GroupChatEntries: List<Pair<String, String>> = listOf(

    // ===== 群聊列表 / 顶栏 =====
    "新建" to "New",
    "{0} 个群聊" to "{0} group chats",
    "还没有群聊\n点右上角「新建」创建一个吧" to "No group chats yet.\nTap \"New\" in the top right to create one.",
    "暂无消息" to "No messages yet",
    "群信息" to "Group info",

    // ===== 群聊页 =====
    "罗德岛干员群聊" to "Rhodes Island operator group chat",
    "在这里和多名干员一起聊天；空闲时他们也会自己聊起来并主动找你。" to
        "Chat with several operators here; when idle they also talk among themselves and reach out to you.",
    "{0} 名成员 · 空闲时自动聊天" to "{0} members · chats automatically when idle",
    "未开启自动聊天（到设置开启）" to "Auto chat is off (enable it in Settings)",
    "尚未选择成员" to "No members selected yet",
    "发消息到群聊…" to "Message the group…",
    "发送" to "Send",
    "回到底部" to "Back to bottom",
    "选择要 @ 的成员" to "Choose a member to @",

    // ===== 群聊错误提示 =====
    "群聊不存在（可能已被删除）" to "This group chat no longer exists (it may have been deleted)",
    "群信息加载失败：{0}" to "Failed to load group info: {0}",
    "群聊尚未就绪，请稍候再试" to "The group chat isn't ready yet; please try again shortly",
    "请先到「设置 → 群聊」选择群成员" to "Select group members in Settings → Group chat first",
    "本轮回复未能确认发言角色，请稍后重试" to "Couldn't confirm which member was speaking this round; please try again later",
    "本轮没有生成有效回复，请稍后重试" to "No valid reply was generated this round; please try again later",

    // ===== 新建群聊弹窗 =====
    "新建群聊" to "New group chat",
    "＋ 封面" to "+ Cover",
    "群封面（选填）" to "Group cover (optional)",
    "群名称（选填，默认「群聊」）" to "Group name (optional, defaults to \"Group chat\")",
    "选择成员（已选 {0}，2–{1} 人）" to "Select members ({0} selected, 2–{1} people)",
    "搜索角色名 / ID（如 能天使 / exusiai）" to "Search name / ID (e.g. Exusiai)",
    "最多选择 {0} 名成员" to "You can select at most {0} members",
    "至少选择 2 名成员" to "Select at least 2 members",
    "封面保存失败" to "Failed to save the cover",
    "创建中…" to "Creating…",

    // ===== 群信息弹窗 =====
    "群聊信息" to "Group info",
    "群封面" to "Group cover",
    "群名称" to "Group name",
    "删除群聊" to "Delete group chat",
    "保存中…" to "Saving…",
    "确定删除「{0}」？该群的全部消息将被清除。" to
        "Delete \"{0}\"? All messages in this group will be erased.",

)