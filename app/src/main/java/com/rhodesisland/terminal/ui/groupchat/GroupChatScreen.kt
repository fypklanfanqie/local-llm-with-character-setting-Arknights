package com.rhodesisland.terminal.ui.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.ui.applySystemBarIcons
import com.rhodesisland.terminal.ui.chat.ChatAvatar
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.glass.liquidGlass
import com.rhodesisland.terminal.ui.navigation.ClampedImeBottomPadding
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.ui.theme.LocalDarkTheme

/**
 * 群聊界面（仅云端可用）。
 *
 * 独立于单角色 [com.rhodesisland.terminal.ui.chat.ChatScreen]：群聊 = 多人同群会话，
 * 用户发消息后一名成员（round-robin）回复；后台 Worker 负责空闲自动互聊/提问。
 * 顶栏 + 成员条 + 消息列表 + 纯文本输入框。
 */
@Composable
fun GroupChatScreen(
    container: AppContainer,
    bottomBarHeight: Dp = 0.dp,
    groupId: Long,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as com.rhodesisland.terminal.RhodesApp
    val viewModel: GroupChatViewModel = viewModel(
        factory = viewModelFactory { initializer { GroupChatViewModel(app, container, groupId) } }
    )
    val state by viewModel.uiState.collectAsState()
    val scheme = MaterialTheme.colorScheme

    // 输入「@」时自动弹出提人弹窗
    var showAtPicker by remember { mutableStateOf(false) }
    // 群信息编辑弹窗（群名/封面）
    var showInfo by remember { mutableStateOf(false) }

    // 群聊页深色画面：系统栏图标保持白色；Worker 通知抑制依赖本标记。
    applySystemBarIcons(light = true)
    DisposableEffect(Unit) {
        GroupScreenTracker.isVisible = true
        onDispose { GroupScreenTracker.isVisible = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .statusBarsPadding()
            .padding(top = 20.dp, bottom = bottomBarHeight)
            // 键盘弹出时输入栏随 IME 上浮（与单聊 ChatScreen 同款钳制，避免输入栏被键盘挡住）
            .then(ClampedImeBottomPadding(WindowInsets.ime, PaddingValues(bottom = bottomBarHeight))),
    ) {
        GroupChatTopBar(
            name = state.groupName,
            coverPath = state.groupCoverPath,
            memberCount = state.members.size,
            enabled = state.groupEnabled,
            isCloud = state.isCloud,
            onBack = onBack,
            onEdit = { showInfo = true },
        )

        if (state.members.isNotEmpty()) {
            MemberStrip(state.members, state.memberImages)
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.showWelcome) {
                GroupWelcomeHint()
            } else {
                GroupChatMessageList(
                    state = state,
                    onDelete = { viewModel.deleteMessage(it.databaseId) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        state.errorMessage?.let { error ->
            Text(
                error,
                color = scheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // 本地 AI 下群聊不可用：给出明确指引，而不是只把按钮置灰
        if (!state.isCloud) {
            Text(
                "群聊仅云端 AI 可用：请到聊天页顶栏把「本地」切换为「云端」后使用",
                color = scheme.tertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        GroupChatInputBar(
            text = state.inputText,
            isStreaming = state.isStreaming,
            enabled = state.isCloud && state.members.isNotEmpty(),
            onTextChange = { new ->
                val before = state.inputText.count { it == '@' }
                val after = new.count { it == '@' }
                if (after > before) {
                    if (state.members.isEmpty()) {
                        viewModel.notifyError("请先到「设置 → 群聊」选择群成员")
                    } else {
                        showAtPicker = true
                    }
                }
                viewModel.updateInputText(new)
            },
            onSend = { viewModel.sendMessage() },
        )
    }

    if (showAtPicker && state.members.isNotEmpty()) {
        GroupAtPicker(
            members = state.members,
            images = state.memberImages,
            onPick = { name ->
                showAtPicker = false
                viewModel.applyAtMention(name)
            },
            onDismiss = { showAtPicker = false },
        )
    }

    // 群信息编辑（群名/封面/删除）
    if (showInfo) {
        GroupInfoDialog(
            group = Conversation(
                id = state.conversationId ?: groupId,
                characterId = com.rhodesisland.terminal.data.repository.GroupChatRepository.GROUP_CHARACTER_ID,
                title = state.groupName,
                createdAt = 0L,
                updatedAt = 0L,
                isGroup = true,
                memberIds = state.memberIds,
                coverImagePath = state.groupCoverPath.ifBlank { null },
            ),
            container = container,
            onDismiss = { showInfo = false },
            onSaved = { viewModel.refreshGroup() },
            onDeleted = { onBack() },
        )
    }
}

@Composable
private fun GroupChatTopBar(
    name: String,
    coverPath: String,
    memberCount: Int,
    enabled: Boolean,
    isCloud: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .liquidGlass(GlassShapes.cardSmall, tint = if (LocalDarkTheme.current) Color(0xFF181A22) else Color.White, blurRadius = 16.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).frostedGlass(CircleShape, borderWidth = 1.dp, blurRadius = 16.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        // 群封面（未设置时用「群」图标占位）
        if (coverPath.isNotBlank()) {
            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))) {
                AsyncImage(
                    model = coverPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Groups, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        // 群名称/提示：点击进入群信息（改名/换封面）
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(GlassShapes.cardSmall)
                .clickable(onClick = onEdit)
                .padding(vertical = 2.dp),
        ) {
            Text(
                name.ifBlank { "群聊" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    !isCloud -> "仅云端 AI 可用"
                    !enabled -> "未开启自动聊天（到设置开启）"
                    memberCount == 0 -> "尚未选择成员"
                    else -> "$memberCount 名成员 · 空闲时自动聊天"
                },
                color = scheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MemberStrip(members: List<Character>, images: Map<String, String>) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        members.forEach { m ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ChatAvatar(imageUrl = images[m.id] ?: "", name = m.name, size = 34.dp)
                Spacer(Modifier.height(3.dp))
                Text(m.name, color = scheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GroupWelcomeHint() {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("罗德岛干员群聊", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = scheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text(
                "在这里和多名干员一起聊天；空闲时他们也会自己聊起来并主动找你。",
                color = scheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GroupChatInputBar(
    text: String,
    isStreaming: Boolean,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val inputTint = if (dark) Color(0xFF181A22).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.92f)
    val chipTint = if (dark) Color(0xFF181A22).copy(alpha = 0.70f) else Color.White.copy(alpha = 0.70f)
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .liquidGlass(
                RoundedCornerShape(26.dp),
                tint = inputTint,
                blurRadius = 16.dp,
                fillBrush = Brush.linearGradient(listOf(inputTint, chipTint)),
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.5.sp, lineHeight = 20.sp),
            singleLine = false,
            maxLines = 4,
            cursorBrush = SolidColor(scheme.primary),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text("发消息到群聊…", color = scheme.onSurfaceVariant, fontSize = 14.5.sp)
                }
                inner()
            },
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .shadow(10.dp, CircleShape, clip = false,
                    ambientColor = Color(0xFF6E4DFF).copy(alpha = 0.20f),
                    spotColor = Color(0xFF7C5CFF).copy(alpha = 0.45f))
                .clip(CircleShape)
                .background(if (enabled && !isStreaming) scheme.primary else scheme.primary.copy(alpha = 0.4f))
                .border(1.dp, scheme.primary.copy(alpha = 0.3f), CircleShape)
                .clickable(enabled = enabled && !isStreaming, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            if (isStreaming) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = scheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "发送",
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}