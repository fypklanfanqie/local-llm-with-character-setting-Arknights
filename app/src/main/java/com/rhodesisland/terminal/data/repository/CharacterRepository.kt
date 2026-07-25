package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.Character
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 角色仓库：合并预设干员（Characters.ALL）与用户自定义角色。
 * 自定义角色持久化于 SettingsStore，可增删改 / 导入导出。
 */
class CharacterRepository(private val settings: SettingsRepository) {

    /** 预设 + 自定义角色（预设顺序在前，自定义追加在后） */
    val characters: Flow<List<Character>> = settings.customCharacters.map { custom ->
        // getOrderedList() 已返回按展示顺序排好的 List<Character>，无需再用 ALL 重新索引
        // （旧写法 mapNotNull { Characters.ALL[it] } 中 it 是 Character，而 ALL 的 key 是 String，
        //  类型不匹配会导致编译错误）
        Characters.getOrderedList() + custom
    }

    suspend fun getNow(id: String): Character? {
        Characters.ALL[id]?.let { return it }
        return settings.customCharacters.first().firstOrNull { it.id == id }
    }

    suspend fun addCustom(character: Character) {
        // 原子读-改-写，避免并发导入/新建时 lost update
        settings.updateCustomCharacters { current ->
            val result = current.toMutableList()
            result.removeAll { it.id == character.id }
            result.add(character.copy(isCustom = true))
            result
        }
    }

    suspend fun removeCustom(id: String) {
        settings.updateCustomCharacters { current -> current.filterNot { it.id == id } }
    }

    suspend fun importCustom(list: List<Character>) {
        settings.updateCustomCharacters { current ->
            val result = current.toMutableList()
            for (c in list) {
                result.removeAll { it.id == c.id }
                result.add(c.copy(isCustom = true))
            }
            result
        }
    }

    suspend fun exportCustom(): List<Character> = settings.customCharacters.first()
}
