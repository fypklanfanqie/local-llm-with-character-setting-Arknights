package com.rhodesisland.terminal.i18n

/**
 * 英文词典 —— 批次 07Moment：朋友圈/卡片流/导航/角色
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 英文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val En07MomentEntries: List<Pair<String, String>> = listOf(
    // ===== 底部导航 / 卡片流 =====
    "通讯" to "Comms",
    "开始对话" to "Start chat",
    "好感" to "Affinity",
    "语音" to "Voice",
    "删除角色" to "Delete character",
    "确定删除「{0}」？" to "Delete \"{0}\"?",
    // ===== 朋友圈 =====
    "还没有动态\n点右上角相机让角色发一条，或自己发一条" to "No posts yet\nTap the camera in the top right to have a character post, or post one yourself",
    "让角色发朋友圈" to "Have a character post",
    "自己发朋友圈" to "Post it yourself",
    "朋友圈封面" to "Moments cover",
    "更换封面" to "Change cover",
    "我的头像" to "My avatar",
    "赞/评论" to "Like / comment",
    "赞" to "Like",
    "评论" to "Comment",
    "❤ {0} 人觉得很赞" to "❤ {0} people liked this",
    "❤ {0} 觉得很赞" to "❤ {0} liked this",
    "对方正在输入…" to "Typing…",
    "选择角色（云端 AI 生成文案与配图）" to "Select a character (the cloud AI writes the text and generates the images)",
    "配图数量" to "Number of images",
    "无图" to "No image",
    "{0}张" to "{0} images",
    "生成发布" to "Generate & post",
    "发朋友圈" to "Post to Moments",
    "这一刻的想法…" to "What's on your mind…",
    "添加图片（最多 3 张）" to "Add images (up to 3)",
    "已选 {0} 张 · 点击更换" to "{0} selected · tap to change",
    "发表" to "Post",
    "回复生成失败：{0}" to "Failed to generate a reply: {0}",
    "请稍后再试" to "Please try again later",
    "封面图片保存失败" to "Failed to save the cover image",
    // ===== 角色页 =====
    "没有自定义角色可导出" to "No custom characters to export",
    "已复制 {0} 个自定义角色 JSON" to "Copied the JSON for {0} custom characters",
    "搜索干员名称 / 代号…" to "Search operator name / code…",
    "未找到「{0}」相关的干员" to "No operators found for \"{0}\"",
    "确定删除自定义角色「{0}」？将同时删除其立绘，不可恢复。" to "Delete custom character \"{0}\"? Its illustration will be deleted too. This cannot be undone.",
    "已导入 {0} 个自定义角色" to "Imported {0} custom characters",
    "导入失败：JSON 格式错误" to "Import failed: invalid JSON",
    "好感 {0} / 200" to "Affinity {0} / 200",
    "编辑" to "Edit",
    "编辑角色" to "Edit character",
    "新建自定义角色" to "New custom character",
    "名称 *" to "Name *",
    "代号 / 编号" to "Code / ID",
    "职位 / 定位" to "Position / Role",
    "种族" to "Race",
    "立绘（可选）" to "Illustration (optional)",
    "立绘保存失败，请重试" to "Failed to save the illustration. Please try again.",
    "人格设定（System Prompt）*" to "Persona settings (System Prompt) *",
    "名称与人格设定为必填项" to "Name and persona settings are required",
    "立绘预览" to "Illustration preview",
    "移除立绘" to "Remove illustration",
    "点击上传手机本地照片" to "Tap to upload a photo from your phone",
    "导入自定义角色" to "Import custom characters",
    "粘贴导出的角色 JSON：" to "Paste the exported character JSON:",
    "技能：{0}" to "Skills: {0}",
    "天赋：{0}" to "Talents: {0}",
    // ===== 启动画面 =====
    "AI 角色扮演聊天" to "AI roleplay chat",
)
