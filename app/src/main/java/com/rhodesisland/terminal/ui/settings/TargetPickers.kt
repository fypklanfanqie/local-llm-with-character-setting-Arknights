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
import com.rhodesisland.terminal.i18n.t
import com.rhodesisland.terminal.i18n.tf
import com.rhodesisland.terminal.ui.theme.fieldPlaceholderColor
import com.rhodesisland.terminal.ui.theme.fieldTextColor

/**
 * 目标选择列表（带搜索框），供世界观/世界书绑定目标选择。
 * 原 [WorldviewSection] 内 private 实现，提升为 internal 共享；
 * 多选版供世界书多目标绑定使用。
 */

/**
 * 角色搜索命中判定：名称 / 代号 / id / **角色定位行**。
 *
 * 为什么带上 [Character.role]：384 位干员里按名字滚动找人不现实，
 * 用户更常按「术师」「医疗」「狙击」这类定位词筛人；定位行本身也展示不出来，
 * 不参与匹配的话这个词就完全搜索不到。
 *
 * 单独抽成函数是为了四个选择器（世界观/世界书/群聊/朋友圈）判定口径完全一致。
 */
internal fun characterMatchesQuery(char: Character, q: String): Boolean =
    char.name.contains(q, true) ||
        char.code.contains(q, true) ||
        char.id.contains(q, true) ||
        char.role.contains(q, true)

/**
 * 选择器通用搜索框：玻璃风容器 + 放大镜 + 命中数 + 清空按钮。
 *
 * 为什么在文件内抽一个：角色单选/多选、群聊单选/多选四处原本各抄一份搜索框，
 * 加「命中数」时四份要同步改；集中成一个后视觉与交互只有一处定义。
 * [hitTemplate] 让角色列表与群聊列表各自说清「找到了几个什么」。
 */
@Composable
private fun PickerSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    hitTemplate: String,
    hitCount: Int,
) {
    val scheme = MaterialTheme.colorScheme
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
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                textStyle = TextStyle(color = fieldTextColor(), fontSize = 12.sp),
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                decorationBox = { inner ->
                    if (value.text.isEmpty()) {
                        Text(placeholder, color = fieldPlaceholderColor(), fontSize = 12.sp)
                    }
                    inner()
                },
            )
            // 有关键词才出现「清空」：没输入时多一个按钮只是噪音
            if (value.text.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    t("清空"),
                    color = scheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onValueChange(TextFieldValue("")) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        // 命中数：过滤后立刻知道「还剩几个」，避免以为角色不见了
        if (value.text.isNotEmpty()) {
            Text(
                tf(hitTemplate, hitCount),
                color = scheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
        }
    }
}

/** 角色单选列表：按名称/代号/id/定位过滤；内置与自定义统一列出（自定义带徽标）。 */
@Composable
internal fun CharacterPickerList(
    characters: List<Character>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var search by remember { mutableStateOf(TextFieldValue("")) }
    Column {
        val q = search.text.trim()
        // 选中集合由调用方持有，过滤只影响「显示哪些行」，不影响已选中的角色
        val filtered = remember(characters, q) {
            if (q.isEmpty()) characters else characters.filter { characterMatchesQuery(it, q) }
        }
        PickerSearchField(
            value = search,
            onValueChange = { search = it },
            placeholder = t("搜索角色名 / 代号…"),
            hitTemplate = "找到 {0} 个角色",
            hitCount = filtered.size,
        )
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
                    badge = if (char.isCustom) t("自定义") else null,
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
    Column {
        var search by remember { mutableStateOf(TextFieldValue("")) }
        val q = search.text.trim()
        // 已勾选但被搜索过滤掉的角色仍在 selectedIds 里 —— 过滤只影响列表显示
        val filtered = remember(characters, q) {
            if (q.isEmpty()) characters else characters.filter { characterMatchesQuery(it, q) }
        }
        PickerSearchField(
            value = search,
            onValueChange = { search = it },
            placeholder = t("搜索角色名 / 代号…"),
            hitTemplate = "找到 {0} 个角色",
            hitCount = filtered.size,
        )
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
                    badge = if (char.isCustom) t("自定义") else null,
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
    var search by remember { mutableStateOf(TextFieldValue("")) }
    Column {
        val q = search.text.trim()
        val filtered = remember(groups, q) {
            if (q.isEmpty()) groups
            else groups.filter { it.title.contains(q, true) }
        }
        PickerSearchField(
            value = search,
            onValueChange = { search = it },
            placeholder = t("搜索群聊名称…"),
            hitTemplate = "找到 {0} 个群聊",
            hitCount = filtered.size,
        )
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
    var search by remember { mutableStateOf(TextFieldValue("")) }
    Column {
        val q = search.text.trim()
        val filtered = remember(groups, q) {
            if (q.isEmpty()) groups
            else groups.filter { it.title.contains(q, true) }
        }
        PickerSearchField(
            value = search,
            onValueChange = { search = it },
            placeholder = t("搜索群聊名称…"),
            hitTemplate = "找到 {0} 个群聊",
            hitCount = filtered.size,
        )
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
            if (group.title.isBlank()) t("群聊") else group.title,
            color = if (selected) scheme.primary else scheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(tf("{0} 人", group.memberIds.size), color = scheme.onSurfaceVariant, fontSize = 9.sp)
    }
}

/** 群聊列表空态提示。 */
@Composable
private fun EmptyGroupsHint(hasAny: Boolean) {
    Text(
        if (hasAny) t("未找到匹配的群聊") else t("尚无群聊，请先在通讯页创建"),
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
