package com.rhodesisland.terminal.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.ui.glass.GlassChip
import com.rhodesisland.terminal.ui.glass.GlassListRow
import com.rhodesisland.terminal.ui.glass.GlassTextField
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.guide.GUIDE_CATEGORIES
import com.rhodesisland.terminal.ui.guide.GUIDE_RECOMMENDED_QUERIES
import com.rhodesisland.terminal.ui.guide.GuideBlock
import com.rhodesisland.terminal.ui.guide.GuideBlockType
import com.rhodesisland.terminal.ui.guide.GuideCategory
import com.rhodesisland.terminal.ui.guide.GuideLevel
import com.rhodesisland.terminal.ui.guide.GuideTopic
import com.rhodesisland.terminal.ui.guide.JiahaoQuizPage
import com.rhodesisland.terminal.ui.guide.searchGuideTopics
import com.rhodesisland.terminal.ui.guide.guideCategoryOf
import com.rhodesisland.terminal.ui.guide.guideTopicOf
import com.rhodesisland.terminal.ui.guide.topicsOfCategory
import com.rhodesisland.terminal.ui.theme.GlassShapes
import kotlinx.coroutines.launch

/**
 * 使用指南（改版）：玻璃全屏面板内的多页状态机。
 *
 * 页面流：首次进入强制水平选择（LevelSelect）→ 首页（搜索 + 分类网格 + 嘉豪入口）→
 * 分类 → 话题详情（正文按当前阅读水平渲染，顶部 chips 可随时切换等级）。
 * 「我是嘉豪」入口进入 ui/guide/GuideQuiz 的十题认证考试，全程零持久化。
 */
@Composable
fun GuideDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val setupDone by container.settingsRepository.guideSetupDone.collectAsState(initial = false)
    val levelRaw by container.settingsRepository.guideLevel.collectAsState(initial = "")
    val level = GuideLevel.fromStorageKey(levelRaw) ?: GuideLevel.BEGINNER

    // 页面栈仅单层回退需求：Topic→Category→Home 由 categoryId/topicId 反查，无需显式栈。
    // 初始页不能写死在 remember 初值里：DataStore 异步加载（initial=false）时老用户会被误困在
    // 水平选择页；改为「setupDone 落地为 true 且仍停在 LevelSelect 则放行到 Home」。真实选择
    // 会同步把页面切走（selectLevel 后立即 page=Home），故此处不会与选择动作竞争。
    var page by remember { mutableStateOf<GuidePage>(GuidePage.LevelSelect) }
    var searchQuery by remember { mutableStateOf("") }
    LaunchedEffect(setupDone) {
        if (setupDone && page == GuidePage.LevelSelect) page = GuidePage.Home
    }

    // Quiz 结果 overlay 显示期间吞掉返回键（空实现），防止 onDismissRequest 把失败弹窗整个关掉。
    var quizOverlayShowing by remember { mutableStateOf(false) }

    // ---- BackHandler（互斥完备）----
    when {
        // 失败/成功 overlay 在屏：吞键，逼用户点按钮。
        page is GuidePage.Quiz && quizOverlayShowing -> BackHandler(enabled = true) { /* 吞掉 */ }
        // 考试中未出结果：允许放弃考试回首页。
        page is GuidePage.Quiz -> BackHandler(enabled = true) { page = GuidePage.Home }
        // 水平选择页没有上一页：返回即关闭指南。
        page == GuidePage.LevelSelect -> BackHandler(enabled = true) { onDismiss() }
        // Topic 回分类；Category 回首页；Home 不启用（走 onDismissRequest 关弹窗）。
        else -> BackHandler(enabled = page != GuidePage.Home) {
            page = when (val p = page) {
                is GuidePage.Topic -> GuidePage.Category(p.topicCategoryId())
                is GuidePage.Category -> GuidePage.Home
                else -> GuidePage.Home
            }
        }
    }

    fun selectLevel(selected: GuideLevel) {
        scope.launch {
            container.settingsRepository.setGuideLevel(selected.storageKey)
            container.settingsRepository.setGuideSetupDone(true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            color = Color.Transparent,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(GlassShapes.sheet)
                    .frostedGlass(
                        GlassShapes.sheet,
                        tint = scheme.surfaceContainerHigh.copy(alpha = 0.95f),
                        shadowElevation = 0.dp,
                    ),
            ) {
                // ===== 顶部标题栏（Quiz 自绘头部，不复用全局头部——失败 overlay 时不得有关闭钮）=====
                if (page !is GuidePage.Quiz) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (page != GuidePage.Home && page != GuidePage.LevelSelect) {
                            IconButton(
                                onClick = {
                                    page = when (val p = page) {
                                        is GuidePage.Topic -> GuidePage.Category(p.topicCategoryId())
                                        is GuidePage.Category -> GuidePage.Home
                                        else -> GuidePage.Home
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = scheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            "使用指南",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = scheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = scheme.outline.copy(alpha = 0.5f))
                }

                // ===== 页面主体 =====
                Box(modifier = Modifier.weight(1f)) {
                    when (val p = page) {
                        GuidePage.Home -> GuideHomePage(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onOpenCategory = { page = GuidePage.Category(it) },
                            onOpenTopic = { page = GuidePage.Topic(it) },
                            onOpenQuiz = { searchQuery = ""; page = GuidePage.Quiz },
                        )
                        GuidePage.LevelSelect -> GuideLevelSelectPage(
                            onSelect = { selected ->
                                selectLevel(selected)
                                page = GuidePage.Home
                            },
                            onOpenQuiz = { page = GuidePage.Quiz },
                        )
                        is GuidePage.Category -> GuideCategoryPage(
                            category = guideCategoryOf(p.categoryId),
                            onOpenTopic = { page = GuidePage.Topic(it) },
                        )
                        is GuidePage.Topic -> GuideTopicPage(
                            topic = guideTopicOf(p.id),
                            currentLevel = level,
                            onSelectLevel = { selected ->
                                selectLevel(selected)  // 话题页切等级立即持久化（重选入口）
                            },
                        )
                        GuidePage.Quiz -> JiahaoQuizPage(
                            onPassHome = { quizOverlayShowing = false; page = GuidePage.Home },
                            onFailExit = { /* exitApplication 在 Quiz 内部调用；此回调仅兜底 */ },
                            onOverlayShowingChanged = { quizOverlayShowing = it },
                        )
                    }
                }

                if (page !is GuidePage.Quiz) {
                    HorizontalDivider(color = scheme.outline.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                        ) {
                            Text("关闭指南", color = scheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// 页面路由
// =====================================================================

private sealed interface GuidePage {
    /** 首页：搜索框 + 推荐联想 chips + 分类网格 + 底部嘉豪入口。 */
    data object Home : GuidePage

    /** 首次进入的水平选择页（guide_setup_done=false 时的初始页）。 */
    data object LevelSelect : GuidePage

    /** 分类页：该分类下的话题列表。 */
    data class Category(val categoryId: String) : GuidePage

    /** 话题详情页。 */
    data class Topic(val id: String) : GuidePage

    /** 嘉豪认证考试（结果 overlay 状态由 Quiz 内部承载并回调宿主）。 */
    data object Quiz : GuidePage
}

/** 话题反查所属分类（BackHandler 用）；悬空话题防御性归首页。 */
private fun GuidePage.Topic.topicCategoryId(): String =
    guideTopicOf(id)?.categoryId ?: ""

// =====================================================================
// 首页：搜索 + 推荐 chips + 分类网格 + 嘉豪入口
// =====================================================================

@Composable
private fun GuideHomePage(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenTopic: (String) -> Unit,
    onOpenQuiz: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val hits = remember(query) { searchGuideTopics(query) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 搜索框（本项目的 GlassTextField 无 leading 槽，放大镜图标外置在 Row 头部）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).padding(end = 8.dp),
            )
            GlassTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "搜索功能（如：朗读 / 世界书 / 免费）",
                modifier = Modifier.weight(1f),
                trailing = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "清除搜索", tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                },
            )
        }

        if (query.isNotBlank()) {
            // ---- 联想结果列表 ----
            val catOf = remember { GUIDE_CATEGORIES.associateBy { it.id } }
            if (hits.isEmpty()) {
                Text(
                    "没有匹配的功能，换个关键词试试（如「语音」「群聊」「模型」）",
                    color = scheme.onSurfaceVariant, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            } else {
                hits.forEach { hit ->
                    GlassListRow(
                        title = "${hit.topic.emoji} ${hit.topic.title}",
                        subtitle = "${catOf[hit.topic.categoryId]?.title.orEmpty()} · 命中：${hit.matchedIn}",
                        onClick = {
                            onQueryChange("")
                            onOpenTopic(hit.topic.id)
                        },
                        showDivider = false,
                    )
                }
            }
        } else {
            // ---- 推荐联想词 ----
            Text("热门搜索", color = scheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GUIDE_RECOMMENDED_QUERIES.forEach { keyword ->
                    GlassChip(label = keyword, selected = false, onClick = { onQueryChange(keyword) })
                }
            }

            Spacer(Modifier.height(2.dp))
            Text("功能分类", color = scheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

            // ---- 分类按钮网格（每行 2 个；勿用 LazyVerticalGrid 嵌 verticalScroll）----
            GUIDE_CATEGORIES.chunked(2).forEach { rowCategories ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowCategories.forEach { category ->
                        GuideCategoryCard(category = category, onClick = { onOpenCategory(category.id) }, modifier = Modifier.weight(1f))
                    }
                    // 单数行补一个占位保持左右等宽
                    if (rowCategories.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }

            // ---- 嘉豪入口（低调一行，无任何解释）----
            Spacer(Modifier.height(4.dp))
            GlassListRow(
                title = "我是嘉豪",
                onClick = onOpenQuiz,
                showDivider = false,
            )
        }
    }
}

@Composable
private fun GuideCategoryCard(category: GuideCategory, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(GlassShapes.cardSmall)
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(category.emoji, fontSize = 22.sp)
        Text(category.title, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            category.subtitle,
            color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

// =====================================================================
// 水平选择页（首次进入）
// =====================================================================

@Composable
private fun GuideLevelSelectPage(
    onSelect: (GuideLevel) -> Unit,
    onOpenQuiz: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("开始之前", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = scheme.onSurface)
        Text("你平时用 AI 聊天应用吗？选择最符合你的说明方式，之后随时可以更改。", color = scheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)

        Spacer(Modifier.height(4.dp))

        GuideLevelCard(
            emoji = "🧭",
            title = "我是小白",
            desc = "用大白话讲解每一步，几乎不出现专业词。",
            highlight = true,
            onClick = { onSelect(GuideLevel.BEGINNER) },
        )
        GuideLevelCard(
            emoji = "🚀",
            title = "我有 AI 聊天经验，很熟",
            desc = "直接上术语与参数，讲清实现与配置细节。",
            highlight = false,
            onClick = { onSelect(GuideLevel.EXPERIENCED) },
        )

        Spacer(Modifier.height(8.dp))

        // 嘉豪入口：刻意低调，无副标题、无解释；点击零提示直进考试。
        Surface(
            shape = GlassShapes.cardSmall,
            color = scheme.surfaceContainerHigh.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "我是嘉豪",
                color = scheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenQuiz)
                    .padding(vertical = 18.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GuideLevelCard(
    emoji: String,
    title: String,
    desc: String,
    highlight: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlassShapes.cardSmall)
            .background(if (highlight) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(emoji, fontSize = 26.sp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = scheme.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = scheme.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        Text("›", color = scheme.onSurfaceVariant, fontSize = 20.sp)
    }
}

// =====================================================================
// 分类页
// =====================================================================

@Composable
private fun GuideCategoryPage(category: GuideCategory?, onOpenTopic: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (category == null) {
        Text("分类不存在", color = scheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        return
    }
    val topics = remember(category.id) { topicsOfCategory(category.id) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${category.emoji} ${category.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)
        Text(category.subtitle, color = scheme.onSurfaceVariant, fontSize = 11.sp)

        topics.forEach { topic ->
            GuideListCard(
                emoji = topic.emoji,
                title = topic.title,
                subtitle = firstLineOf(topic),
                onClick = { onOpenTopic(topic.id) },
            )
        }
    }
}

/** 话题摘要：列表预览统一用小白版首段（短且友好）。 */
private fun firstLineOf(topic: GuideTopic): String {
    val block = topic.beginner.firstOrNull() ?: return ""
    return block.text.take(40) + if (block.text.length > 40) "…" else ""
}

@Composable
private fun GuideListCard(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlassShapes.cardSmall)
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(emoji, fontSize = 20.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Text("›", color = scheme.onSurfaceVariant, fontSize = 18.sp)
    }
}

// =====================================================================
// 话题详情页（正文按当前等级渲染 + 顶部等级切换）
// =====================================================================

@Composable
private fun GuideTopicPage(
    topic: GuideTopic?,
    currentLevel: GuideLevel,
    onSelectLevel: (GuideLevel) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (topic == null) {
        Text("话题不存在", color = scheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        return
    }
    val blocks = remember(topic.id, currentLevel) {
        if (currentLevel == GuideLevel.EXPERIENCED) topic.experienced else topic.beginner
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${topic.emoji} ${topic.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)

        // 等级切换 chips：点击立即持久化（满足"随时重新选择水平"）。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GuideLevel.values().forEach { lv ->
                GlassChip(label = lv.chipLabel, selected = lv == currentLevel, onClick = { onSelectLevel(lv) })
            }
        }

        HorizontalDivider(color = scheme.outline.copy(alpha = 0.35f))

        blocks.forEach { block -> GuideBlockView(block) }

        if (currentLevel == GuideLevel.BEGINNER) {
            Spacer(Modifier.height(4.dp))
            Text(
                "💡 觉得太啰嗦？点上方「🚀 熟练版」切换为专业讲解。",
                color = scheme.onSurfaceVariant, fontSize = 11.sp,
            )
        }
    }
}

// =====================================================================
// 内容块渲染器
// =====================================================================

@Composable
private fun GuideBlockView(block: GuideBlock) {
    val scheme = MaterialTheme.colorScheme
    when (block.type) {
        GuideBlockType.PARAGRAPH -> Text(
            block.text, color = scheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
        )
        GuideBlockType.STEP_TITLE -> Text(
            block.text, color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp),
        )
        GuideBlockType.STEP_TEXT -> Text(
            block.text, color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
        GuideBlockType.TIP -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("•", color = scheme.primary, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = scheme.primary, fontWeight = FontWeight.Bold)) { append("${block.title}：") }
                    withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append(block.text) }
                },
                fontSize = 11.sp, lineHeight = 16.sp,
            )
        }
        GuideBlockType.WARN -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFB8860B).copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("⚠️", fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
            Text(block.text, color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
