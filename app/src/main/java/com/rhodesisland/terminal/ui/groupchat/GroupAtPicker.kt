package com.rhodesisland.terminal.ui.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.i18n.t
import com.rhodesisland.terminal.i18n.tf
import com.rhodesisland.terminal.ui.chat.ChatAvatar
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.settings.characterMatchesQuery
import com.rhodesisland.terminal.ui.theme.GlassShapes

/**
 * @ 提人弹窗：用户在群聊输入框输入「@」时自动弹出，选择要 @ 的群成员。
 * 列表用**固定高度 LazyColumn**（AlertDialog + verticalScroll 在无界约束下不滚动的问题不再重演）。
 * 群成员上限 10 人本来不需要搜索，但输入区在底部、弹窗容易被软键盘顶起，
 * 有关键词时直接筛出目标更快；判定口径与其它角色选择器保持一致。
 */
@Composable
fun GroupAtPicker(
    members: List<Character>,
    images: Map<String, String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var search by remember { mutableStateOf("") }
    val q = search.trim()
    val filtered = remember(members, q) {
        if (q.isEmpty()) members else members.filter { characterMatchesQuery(it, q) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .frostedGlass(GlassShapes.card, borderWidth = 1.dp, blurRadius = 20.dp)
                .padding(16.dp),
        ) {
            Text(
                t("选择要 @ 的成员"),
                color = scheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    textStyle = TextStyle(color = scheme.onSurface, fontSize = 12.sp),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    decorationBox = { inner ->
                        if (search.isEmpty()) {
                            Text(t("搜索角色名 / 代号…"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        inner()
                    },
                )
                if (search.isNotEmpty()) {
                    Text(
                        t("清空"),
                        color = scheme.error,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { search = "" }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            if (search.isNotEmpty()) {
                Text(
                    tf("找到 {0} 个角色", filtered.size),
                    color = scheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            tf("未找到「{0}」相关的干员", q),
                            color = scheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(filtered, key = { it.id }) { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(m.name) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(imageUrl = images[m.id] ?: "", name = m.name, size = 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(m.name, color = scheme.onSurface, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(t("取消"), color = scheme.onSurfaceVariant, fontSize = 13.sp) }
            }
        }
    }
}