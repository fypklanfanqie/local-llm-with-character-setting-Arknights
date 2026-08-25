package com.rhodesisland.terminal.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.ui.theme.fieldPlaceholderColor
import com.rhodesisland.terminal.ui.theme.fieldTextColor

/**
 * 目标选择列表（带搜索框），供世界观/世界书绑定目标选择。
 * 原 [WorldviewSection] 内 private 实现，提升为 internal 共享；
 * 多选版供世界书多目标绑定使用。
 */

/** 角色单选列表：按名称/代号/id 过滤；内置与自定义统一列出（自定义带徽标）。 */
@Composable
internal fun CharacterPickerList(
    characters: List<Character>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var search by remember { mutableStateOf(TextFieldValue("")) }
    Column {
        // 搜索框（复用输入框 token 色）
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
            androidx.compose.foundation.text.BasicTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                textStyle = TextStyle(color = fieldTextColor(), fontSize = 12.sp),
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                decorationBox = { inner ->
                    if (search.text.isEmpty()) {
                        Text("搜索角色名 / 代号…", color = fieldPlaceholderColor(), fontSize = 12.sp)
                    }
                    inner()
                },
            )
        }
        val q = search.text.trim()
        val filtered = remember(characters, q) {
            if (q.isEmpty()) characters
            else characters.filter { it.name.contains(q, true) || it.code.contains(q, true) || it.id.contains(q, true) }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filtered, key = { it.id }) { char ->
                val selected = char.id == selectedId
                PickerRow(
                    label = char.name,
                    selected = selected,
                    badge = if (char.isCustom) "自定义" else null,
                    onClick = { onSelect(char.id) },
                )
            }
        }
    }
}

/** 角色**多选**列表：点击切换勾选；[selectedIds] 中的行高亮（世界书多目标绑定用）。 */
@Composable
internal fun CharacterMultiPickerList(
    characters: List<Character>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var search by remember { mutableStateOf(TextFieldValue("")) }
    Column {
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
            androidx.compose.foundation.text.BasicTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                textStyle = TextStyle(color = fieldTextColor(), fontSize = 12.sp),
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                decorationBox = { inner ->
                    if (search.text.isEmpty()) {
                        Text("搜索角色名 / 代号…", color = fieldPlaceholderColor(), fontSize = 12.sp)
                    }
                    inner()
                },
            )
        }
        val q = search.text.trim()
        val filtered = remember(characters, q) {
            if (q.isEmpty()) characters
            else characters.filter { it.name.contains(q, true) || it.code.contains(q, true) || it.id.contains(q, true) }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filtered, key = { it.id }) { char ->
                PickerRow(
                    label = char.name,
                    selected = char.id in selectedIds,
                    badge = if (char.isCustom) "自定义" else null,
                    onClick = { onToggle(char.id) },
                )
            }
        }
    }
}

/** 群聊单选列表（带搜索框）。 */
@Composable
internal fun GroupPickerList(
    groups: List<Conversation>,
    selectedId: String,
    onSelect: (Conversation) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var search by remember { mutableStateOf(TextFieldValue("")) }
    Column {
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
            androidx.compose.foundation.text.BasicTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                textStyle = TextStyle(color = fieldTextColor(), fontSize = 12.sp),
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                decorationBox = { inner ->
                    if (search.text.isEmpty()) {
                        Text("搜索群聊名称…", color = fieldPlaceholderColor(), fontSize = 12.sp)
                    }
                    inner()
                },
            )
        }
        val q = search.text.trim()
        val filtered = remember(groups, q) {
            if (q.isEmpty()) groups
            else groups.filter { it.title.contains(q, true) }
        }
        if (filtered.isEmpty()) {
            EmptyGroupsHint(groups.isNotEmpty())
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filtered, key = { it.id }) { group ->
                GroupRow(group = group, selected = group.id.toString() == selectedId, onClick = { onSelect(group) })
            }
        }
    }
}

/** 群聊**多选**列表（带搜索框）：点击切换勾选（世界书多目标绑定用）。 */
@Composable
internal fun GroupMultiPickerList(
    groups: List<Conversation>,
    selectedIds: Set<String>,
    onToggle: (Conversation) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var search by remember { mutableStateOf(TextFieldValue("")) }
    Column {
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
            androidx.compose.foundation.text.BasicTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                textStyle = TextStyle(color = fieldTextColor(), fontSize = 12.sp),
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                decorationBox = { inner ->
                    if (search.text.isEmpty()) {
                        Text("搜索群聊名称…", color = fieldPlaceholderColor(), fontSize = 12.sp)
                    }
                    inner()
                },
            )
        }
        val q = search.text.trim()
        val filtered = remember(groups, q) {
            if (q.isEmpty()) groups
            else groups.filter { it.title.contains(q, true) }
        }
        if (filtered.isEmpty()) {
            EmptyGroupsHint(groups.isNotEmpty())
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filtered, key = { it.id }) { group ->
                GroupRow(group = group, selected = group.id.toString() in selectedIds, onClick = { onToggle(group) })
            }
        }
    }
}

/** 选择器通用行：主文本 + 可选徽标 + 选中高亮。 */
@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit, badge: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) scheme.primary else scheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        badge?.let {
            Text(it, color = scheme.tertiary, fontSize = 9.sp)
        }
    }
}

/** 群聊选择器行：标题 + 人数。 */
@Composable
private fun GroupRow(group: Conversation, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.title.ifBlank { "群聊" },
            color = if (selected) scheme.primary else scheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text("${group.memberIds.size} 人", color = scheme.onSurfaceVariant, fontSize = 9.sp)
    }
}

/** 群聊列表空态提示。 */
@Composable
private fun EmptyGroupsHint(hasAny: Boolean) {
    Text(
        if (hasAny) "未找到匹配的群聊" else "尚无群聊，请先在通讯页创建",
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
