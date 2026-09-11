package com.rhodesisland.terminal.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.repository.NovelRepository
import com.rhodesisland.terminal.i18n.t
import com.rhodesisland.terminal.i18n.tf
import com.rhodesisland.terminal.ui.glass.GlassSheet
import com.rhodesisland.terminal.ui.glass.GlassTextField

/**
 * 小说「角色阵容」管理控件（主角 / 配角 + 随时增删角色）。
 *
 * 为什么单独成文件：这套控件被两处复用（写作页的阵容弹窗、对白编辑器的阵容入口），
 * 且内部状态是「草稿 + 保存」两段式——放在 NovelScreens.kt 里会和屏幕布局混在一起。
 *
 * 状态设计：**定位表不单独存**，而是由弹窗打开时的现状 + 本次草稿的「主角 key」推导
 * （见 resolveRoles）。单一事实来源，避免删角色后定位表里残留幽灵 key。
 */

/** 阵容草稿：本次编辑中的成员 / NPC / 主角 key。 */
private data class CastDraft(
    val memberIds: List<String> = emptyList(),
    val npcs: List<NovelRepository.CustomNpc> = emptyList(),
    val protagonistKey: String? = null,
)

/**
 * 阵容管理弹窗（底部抽屉）。
 *
 * 用来在写作过程中随时改主角/配角、增删应用角色与自定义 NPC；
 * 保存走 [NovelRepository.updateStoryCast] 整包写库，下一次 AI 续写即用新阵容。
 */
@Composable
fun CastManagerSheet(
    characters: List<Character>,
    memberIds: List<String>,
    npcs: List<NovelRepository.CustomNpc>,
    castRoles: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (
        memberIds: List<String>,
        npcs: List<NovelRepository.CustomNpc>,
        castRoles: Map<String, String>,
    ) -> Unit,
) {
    /**
     * 定位表 key：应用角色用 id、自定义 NPC 用名字。
     *
     * 与 NovelRepository 的约定一致——名字在小说里就是 NPC 的唯一标识，
     * 换成「给 NPC 编个 id」只会引入一套没有其它读取端的映射。
     */
    fun currentKey(id: String, name: String): String = id.ifBlank { name }

    val initialProtagonist = (memberIds.firstOrNull { castRoles[it] == NovelRepository.ROLE_PROTAGONIST }
        ?: npcs.firstOrNull { castRoles[it.name.trim()] == NovelRepository.ROLE_PROTAGONIST }?.name?.trim())
        ?: memberIds.firstOrNull()
        ?: npcs.firstOrNull()?.name?.trim()

    // 草稿用整体替换（copy-on-write）而非可变集合：改可变集合不会触发重组，点选将毫无反应
    val draftState = remember {
        mutableStateOf(
            CastDraft(
                memberIds = memberIds,
                // 定位统一落到 protagonistKey 上：清掉 NPC 内嵌旧值，避免两个来源打架
                npcs = npcs.map { it.copy(role = null) },
                protagonistKey = initialProtagonist,
            ),
        )
    }
    val draft = draftState.value
    var query by remember { mutableStateOf("") }
    var npcName by remember { mutableStateOf("") }
    var npcPersona by remember { mutableStateOf("") }

    fun isProtagonistOf(id: String, name: String): Boolean =
        draft.protagonistKey == currentKey(id, name)

    /** 草稿 → 定位表：主角一位，其余全部配角（含 NPC）。保存时重算，中间态不会渗进库里。 */
    fun resolveRoles(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        draft.memberIds.forEach { id -> result[id] = NovelRepository.ROLE_SUPPORTING }
        draft.npcs.forEach { npc -> result[npc.name.trim()] = NovelRepository.ROLE_SUPPORTING }
        val key = draft.protagonistKey
        if (key != null && result.containsKey(key)) result[key] = NovelRepository.ROLE_PROTAGONIST
        return result
    }

    /** 主角被删时的继任者：顺位顶上，避免阵容退化成「无主角」而让提示词阵容段整段失效。 */
    fun successorKey(excludingMember: String? = null, excludingNpc: String? = null): String? =
        draft.memberIds.firstOrNull { it != excludingMember }
            ?: draft.npcs.firstOrNull { it.name.trim() != excludingNpc }?.name?.trim()

    fun toggleMember(id: String, name: String) {
        val key = currentKey(id, name)
        draftState.value = if (id in draft.memberIds) {
            draft.copy(
                memberIds = draft.memberIds - id,
                protagonistKey = if (draft.protagonistKey == key) successorKey(excludingMember = id) else draft.protagonistKey,
            )
        } else {
            draft.copy(memberIds = draft.memberIds + id, protagonistKey = draft.protagonistKey ?: key)
        }
    }

    fun toggleNpc(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        draftState.value = if (draft.npcs.any { it.name.trim() == trimmed }) {
            draft.copy(
                npcs = draft.npcs.filterNot { it.name.trim() == trimmed },
                protagonistKey = if (draft.protagonistKey == trimmed) successorKey(excludingNpc = trimmed) else draft.protagonistKey,
            )
        } else {
            draft.copy(
                npcs = draft.npcs + NovelRepository.CustomNpc(name = trimmed, persona = "", role = null),
                protagonistKey = draft.protagonistKey ?: trimmed,
            )
        }
    }

    val shownMembers = filterCharacters(characters, query)
    // 名单里出现「角色已不存在」的 id（角色被删/预设表变化）时界面上无法移除：
    // 打开弹窗时顺带清理，保存即落库——否则这些幽灵 id 会一直占着阵容。
    // 注意只在角色表非空时裁剪：首帧 characters 为空会把整份阵容误清空。
    LaunchedEffect(characters, memberIds) {
        val live = memberIds.filter { id -> characters.any { it.id == id } }
        val current = draftState.value.memberIds
        if (characters.isNotEmpty() && live.size < current.size) {
            // 只做"收缩"：不让迟到的重组把用户本次新加的角色又冲掉
            draftState.value = draftState.value.copy(memberIds = live)
        }
    }

    GlassSheet(onDismissRequest = onDismiss) {
        Text(
            t("角色阵容"),
            color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            t("设定主角与配角，写作中随时可以增删角色；改动会在下一次 AI 续写时生效"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(top = 2.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(420.dp).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Text(
                    t("当前阵容"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (draft.memberIds.isEmpty() && draft.npcs.isEmpty()) {
                item {
                    Text(
                        t("阵容为空，请从下方添加角色"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            draft.memberIds.forEach { id ->
                val name = characters.firstOrNull { it.id == id }?.name ?: return@forEach
                val key = currentKey(id, name)
                item(key = "member_$id") {
                    CastMemberRow(
                        label = name,
                        isProtagonist = isProtagonistOf(id, name),
                        onMarkProtagonist = { draftState.value = draft.copy(protagonistKey = key) },
                        onMarkSupporting = {
                            // 取消主角必须有继任者：否则阵容退化成「无主角」，提示词阵容段整段失效
                            if (isProtagonistOf(id, name)) {
                                draftState.value = draft.copy(protagonistKey = successorKey(excludingMember = id))
                            }
                        },
                        onRemove = { toggleMember(id, name) },
                    )
                }
            }
            draft.npcs.forEachIndexed { index, npc ->
                val key = npc.name.trim()
                item(key = "npc_${index}_$key") {
                    CastMemberRow(
                        label = npc.name,
                        isProtagonist = isProtagonistOf("", npc.name),
                        onMarkProtagonist = { draftState.value = draft.copy(protagonistKey = key) },
                        onMarkSupporting = {
                            if (isProtagonistOf("", npc.name)) {
                                draftState.value = draft.copy(protagonistKey = successorKey(excludingNpc = key))
                            }
                        },
                        onRemove = { toggleNpc(npc.name) },
                    )
                }
            }

            item { CastSectionLabel(t("添加角色")) }
            item {
                GlassTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = t("搜索角色"),
                    singleLine = true,
                )
            }
            items(shownMembers, key = { it.id }) { char ->
                val selected = char.id in draft.memberIds
                val isLead = isProtagonistOf(char.id, char.name)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { toggleMember(char.id, char.name) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = selected, onCheckedChange = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        char.name,
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        Text(
                            if (isLead) t("主角") else t("配角"),
                            color = if (isLead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            item { CastSectionLabel(t("自定义 NPC（可选）")) }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CastDraftField(t("名字"), npcName, Modifier.weight(0.34f)) { npcName = it }
                    CastDraftField(t("设定"), npcPersona, Modifier.weight(0.44f)) { npcPersona = it }
                    TextButton(
                        enabled = npcName.isNotBlank(),
                        onClick = {
                            toggleNpc(npcName)
                            npcName = ""
                            npcPersona = ""
                        },
                    ) { Text(t("添加")) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text(t("取消")) }
            Spacer(Modifier.width(4.dp))
            TextButton(
                // 不因阵容为空而禁用：用户把角色全删掉就是想清空阵容，
                // 续写侧对空阵容本就有兜底（只有旁白可写），这里不该拦住他保存。
                onClick = { onSave(draft.memberIds, draft.npcs, resolveRoles()) },
            ) { Text(t("保存阵容"), fontWeight = FontWeight.Bold) }
        }
    }
}

/** 阵容成员行：名字 + 主角/配角切换 + 移除。 */
@Composable
private fun CastMemberRow(
    label: String,
    isProtagonist: Boolean,
    onMarkProtagonist: () -> Unit,
    onMarkSupporting: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = scheme.onSurface, fontSize = 13.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        RoleChip(t("主角"), isProtagonist, onMarkProtagonist)
        Spacer(Modifier.width(4.dp))
        RoleChip(t("配角"), !isProtagonist, onMarkSupporting)
        IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Filled.Close, contentDescription = t("移除"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun RoleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) scheme.primary.copy(alpha = 0.22f) else scheme.surface.copy(alpha = 0.04f))
            .clickable(enabled = !selected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun CastSectionLabel(label: String) {
    Text(
        label,
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 弹窗内的小输入框：刻意不用 [GlassTextField]。
 *
 * 弹窗窗口内没有毛玻璃背板，frostedGlass 会退化成高透明平涂、文字难读——
 * 与 GlassSheet 注释里「内容型抽屉默认近实底」是同一个原因。
 */
@Composable
private fun CastDraftField(
    placeholder: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inner()
        },
    )
}

/** 按角色名模糊过滤（忽略大小写），384 位角色靠搜索快速定位。 */
private fun filterCharacters(characters: List<Character>, query: String): List<Character> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return characters
    return characters.filter { it.name.contains(keyword, ignoreCase = true) }
}

/** 阵容摘要（如「阿米娅：主角 · 配角 2 人」）：故事页给玩家一眼看到当前阵容。 */
@Composable
fun castSummaryText(
    characters: List<Character>,
    memberIds: List<String>,
    npcs: List<NovelRepository.CustomNpc>,
    castRoles: Map<String, String>,
): String {
    val lead = memberIds.firstOrNull { castRoles[it] == NovelRepository.ROLE_PROTAGONIST }
        ?.let { id -> characters.firstOrNull { it.id == id }?.name }
        ?: npcs.firstOrNull { castRoles[it.name.trim()] == NovelRepository.ROLE_PROTAGONIST }?.name
    val total = memberIds.size + npcs.size
    val supporting = (total - if (lead != null) 1 else 0).coerceAtLeast(0)
    return when {
        total == 0 -> t("阵容为空，请从下方添加角色")
        lead == null -> tf("{0} 名角色", total)
        else -> tf("{0}：主角 · 配角 {1} 人", lead, supporting)
    }
}
