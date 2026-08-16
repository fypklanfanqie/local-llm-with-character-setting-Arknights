package com.rhodesisland.terminal.ui.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.ui.applySystemBarIcons
import com.rhodesisland.terminal.ui.glass.GlassButton
import com.rhodesisland.terminal.ui.glass.GlassButtonStyle
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.glass.liquidGlass
import com.rhodesisland.terminal.ui.theme.GlassShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 微信式群聊列表：全部已建群（封面 + 名称 + 最新消息预览），可新建群聊、进入群聊、
 * 编辑群名称/封面、删除群聊。
 */
@Composable
fun GroupListScreen(
    container: AppContainer,
    bottomBarHeight: Dp = 0.dp,
    onBack: () -> Unit,
    onOpenGroup: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as com.rhodesisland.terminal.RhodesApp
    val viewModel: GroupListViewModel = viewModel(
        factory = viewModelFactory { initializer { GroupListViewModel(app, container) } }
    )
    val state by viewModel.uiState.collectAsState()
    val scheme = MaterialTheme.colorScheme

    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Conversation?>(null) }

    applySystemBarIcons(light = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 20.dp, bottom = bottomBarHeight),
    ) {
        // 顶栏：返回 + 标题 + 新建
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .liquidGlass(GlassShapes.cardSmall, blurRadius = 16.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .frostedGlass(CircleShape, borderWidth = 1.dp, blurRadius = 16.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("群聊", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                Text("${state.rows.size} 个群聊", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
            GlassButton(
                onClick = { showCreate = true },
                style = GlassButtonStyle.Glass,
                horizontalPadding = 12.dp,
                verticalPadding = 8.dp,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建", color = scheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (state.rows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Groups, contentDescription = null, tint = scheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "还没有群聊\n点右上角「新建」创建一个吧",
                        color = scheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                items(state.rows, key = { it.group.id }) { row ->
                    GroupListRowItem(
                        row = row,
                        onOpen = { onOpenGroup(row.group.id) },
                        onEdit = { editing = row.group },
                    )
                }
            }
        }
    }

    if (showCreate) {
        GroupCreateDialog(
            container = container,
            onDismiss = { showCreate = false },
            onCreated = { id ->
                showCreate = false
                onOpenGroup(id)
            },
        )
    }

    editing?.let { group ->
        GroupInfoDialog(
            group = group,
            container = container,
            onDismiss = { editing = null },
            onSaved = {},
            onDeleted = {},
        )
    }
}

@Composable
private fun GroupListRowItem(
    row: GroupListRow,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .frostedGlass(RoundedCornerShape(16.dp), borderWidth = 1.dp, blurRadius = 16.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 群封面 / 「群」占位
        val cover = row.group.coverImagePath
        if (!cover.isNullOrBlank()) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Groups, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.group.title.ifBlank { "群聊" },
                color = scheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.preview ?: "暂无消息",
                color = scheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (row.previewTime != null) {
                Text(
                    formatGroupTime(row.previewTime),
                    color = scheme.onSurfaceVariant,
                    fontSize = 9.5.sp,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "群信息", tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** 时间戳 -> 「MM-dd HH:mm」；无消息不传。 */
private fun formatGroupTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))