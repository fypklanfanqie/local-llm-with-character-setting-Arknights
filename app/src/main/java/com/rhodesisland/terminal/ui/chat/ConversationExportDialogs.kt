package com.rhodesisland.terminal.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.data.model.Conversation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun exportDialogConversationTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))

@Composable
fun ConversationExportSelectionDialog(
    conversations: List<Conversation>,
    activeConversationId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        title = { Text("选择要导出的对话", color = scheme.onSurface) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(conversations, key = { it.id }) { conversation ->
                    Surface(
                        color = if (conversation.id == activeConversationId) scheme.primary.copy(alpha = 0.14f) else scheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(conversation.id) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(conversation.title.ifBlank { "新对话" }, color = scheme.onSurface, fontSize = 14.sp)
                            Text(
                                exportDialogConversationTime(conversation.updatedAt) + if (conversation.id == activeConversationId) " · 当前对话" else "",
                                color = scheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant) } },
    )
}

@Composable
fun ConversationExportFormatDialog(
    onText: () -> Unit,
    onImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        title = { Text("选择导出格式", color = scheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onText, modifier = Modifier.fillMaxWidth()) { Text("TXT（完整记录）") }
                OutlinedButton(onClick = onImage, modifier = Modifier.fillMaxWidth()) { Text("图片（PNG）") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant) } },
    )
}

@Composable
fun ConversationExportImageModeDialog(
    onPaged: () -> Unit,
    onLong: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        title = { Text("选择图片导出方式", color = scheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPaged, modifier = Modifier.fillMaxWidth()) { Text("自动分页多张图") }
                OutlinedButton(onClick = onLong, modifier = Modifier.fillMaxWidth()) { Text("一张超长图") }
                Text("超长会话建议使用分页模式，避免生成失败。", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant) } },
    )
}
