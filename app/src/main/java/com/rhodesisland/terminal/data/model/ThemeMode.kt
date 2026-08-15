package com.rhodesisland.terminal.data.model

/**
 * 应用主题模式。
 *
 * - SYSTEM：跟随系统亮/暗设置
 * - LIGHT：强制浅色
 * - DARK：强制深色
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.find { it.name == key } ?: SYSTEM
    }
}
