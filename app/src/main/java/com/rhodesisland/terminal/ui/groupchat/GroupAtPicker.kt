package com.rhodesisland.terminal.ui.groupchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.ui.chat.ChatAvatar
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.theme.GlassShapes

/**
 * @ 提人弹窗：用户在群聊输入框输入「@」时自动弹出，选择要 @ 的群成员。
 * 列表用**固定高度 LazyColumn**（AlertDialog + verticalScroll 在无界约束下不滚动的问题不再重演）。
 */
@Composable
fun GroupAtPicker(
    members: List<Character>,
    images: Map<String, String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .frostedGlass(GlassShapes.card, borderWidth = 1.dp, blurRadius = 20.dp)
                .padding(16.dp),
        ) {
            Text(
                "选择要 @ 的成员",
                color = scheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                items(members, key = { it.id }) { m ->
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
                TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant, fontSize = 13.sp) }
            }
        }
    }
}