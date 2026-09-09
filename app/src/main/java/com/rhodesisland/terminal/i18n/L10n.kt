package com.rhodesisland.terminal.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * 界面语言。
 *
 * - [SYSTEM] 跟随系统语言（在根 Composable 处解析成具体语言，见 [L10n.resolve]）
 * - [ZH] 简体中文（= 词典的 key 语言，中文原文即词条 key）
 * - [EN] English
 * - [JA] 日本語
 *
 * [key] 是持久化到 DataStore 的值（`app_language`），与 [ThemeMode] 的 `.name` 存法不同：
 * 这里用稳定小写字符串，将来改名/加语言不会让老用户的设置失效。
 */
enum class AppLanguage(val key: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en"),
    JA("ja"),
    ;

    companion object {
        /** 默认值：跟随系统。 */
        val DEFAULT = SYSTEM

        /** 解析 DataStore 值；未知/空值一律回退 [DEFAULT]（永不抛异常）。 */
        fun fromKey(key: String?): AppLanguage = entries.find { it.key == key } ?: DEFAULT
    }
}

/**
 * 中央词典查表（纯函数，Worker / ViewModel 可直接用）。
 *
 * 设计要点：
 * - **中文原文即 key**：写界面时把中文原文包进 `t("...")`，en/ja 两份词典按中文原文查表；
 * - **缺词回退中文**：查不到就返回中文原文，永不抛异常、永不显示空白，因此可以渐进覆盖；
 * - **界面语言与 AI 输出语言解耦**：LLM 提示词、角色人设、用户自建内容一律不进词典，
 *   换界面语言不会改变 AI 的回复语言。
 *
 * UI 层请用 `t()` / `tf()`（Composable 重载，自动读 [LocalAppLanguage]）；
 * 非 Composable 环境（Worker、ViewModel、通知构建）用 `L10n.t(lang, zh)`，
 * 语言取 `SettingsRepository.getResolvedAppLanguageNow()`。
 */
object L10n {

    /**
     * 查表：已解析语言（不含 SYSTEM）+ 中文原文 → 译文。
     * [AppLanguage.ZH] 与 [AppLanguage.SYSTEM]（未解析的兜底）直接返回中文原文。
     */
    fun t(lang: AppLanguage, zh: String): String = when (lang) {
        AppLanguage.EN -> EnStrings[zh] ?: zh
        AppLanguage.JA -> JaStrings[zh] ?: zh
        AppLanguage.ZH, AppLanguage.SYSTEM -> zh
    }

    /**
     * 带占位符的查表：中文模板用 `{0}` / `{1}`，词典值沿用同一下标。
     *
     * 例：`tf("已选择 {0} 个角色", count)`，en 词条 `"已选择 {0} 个角色" to "{0} characters selected"`。
     * 词典缺词时对中文模板做同样的替换，结果仍是可读的中文，不会漏出 `{0}`。
     */
    fun format(lang: AppLanguage, zh: String, vararg args: Any?): String {
        val template = t(lang, zh)
        if (args.isEmpty()) return template
        var out = template
        args.forEachIndexed { index, arg ->
            out = out.replace("{$index}", arg?.toString().orEmpty())
        }
        return out
    }

    /**
     * 解析生效语言：显式选定的语言原样返回；[AppLanguage.SYSTEM] 按系统语言匹配
     * zh / ja / en，其余系统语言（含空值）回退英文。
     *
     * [systemLanguageTag] 传 BCP-47 语言标签（`"zh"`、`"zh-Hans-CN"`、`"en-US"`…），
     * 只取前两段的主语言码比较。
     */
    fun resolve(language: AppLanguage, systemLanguageTag: String?): AppLanguage = when (language) {
        AppLanguage.ZH, AppLanguage.EN, AppLanguage.JA -> language
        AppLanguage.SYSTEM -> when (systemLanguageTag?.lowercase(Locale.ROOT)?.substringBefore('-')) {
            "zh" -> AppLanguage.ZH
            "ja" -> AppLanguage.JA
            else -> AppLanguage.EN
        }
    }

    /** 词典是否已收录该中文原文（覆盖度脚本 / 单测用）。 */
    fun has(lang: AppLanguage, zh: String): Boolean = when (lang) {
        AppLanguage.EN -> EnStrings.containsKey(zh)
        AppLanguage.JA -> JaStrings.containsKey(zh)
        AppLanguage.ZH, AppLanguage.SYSTEM -> true
    }

    /** 已收录词条数（进度度量）。 */
    fun size(lang: AppLanguage): Int = when (lang) {
        AppLanguage.EN -> EnStrings.size
        AppLanguage.JA -> JaStrings.size
        AppLanguage.ZH, AppLanguage.SYSTEM -> 0
    }
}

/**
 * 当前生效语言（**必须是已解析语言**，不含 SYSTEM：根 Composable 用 [L10n.resolve] 解析后注入）。
 *
 * 用 static 版本：语言切换频率极低，值变了整棵子树重组正是我们要的「切换即时生效」，
 * 不需要细粒度读取追踪。
 */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ZH }

/**
 * 运行期语言缓存：给**非 Composable、也非 suspend** 的场景用（通知构建、前台服务、纯格式化函数）。
 *
 * 由 [com.rhodesisland.terminal.RhodesApp] 启动时的 collector 持续写入（设置 + 系统语言解析后的
 * 生效语言）；collector 未跑起来时默认中文——最坏情况是文案回退中文，不会崩、不会空白。
 *
 * 能用 `t()`（Composable）或 `L10n.t(lang, zh)`（有 suspend 上下文）时**优先用它们**，
 * 这个缓存是最后的便利入口。
 */
object L10nRuntime {

    @Volatile
    var language: AppLanguage = AppLanguage.ZH
        private set

    /** 由启动期 collector 调用：把「设置 + 系统语言」解析成生效语言缓存下来。 */
    fun update(setting: AppLanguage, systemLanguageTag: String?) {
        language = L10n.resolve(setting, systemLanguageTag)
    }

    /** 查表（读 [language] 缓存）。 */
    fun t(zh: String): String = L10n.t(language, zh)

    /** 带占位符的查表，见 [L10n.format]。 */
    fun format(zh: String, vararg args: Any?): String = L10n.format(language, zh, *args)
}

/**
 * Composable 查表：读 [LocalAppLanguage] 的当前语言。
 *
 * 语言变化时因 [LocalAppLanguage] 是 static local，调用方所在子树整体重组，文案立即更新
 * （不重建 Activity，也不丢页面状态）。
 */
@Composable
fun t(zh: String): String {
    val lang = LocalAppLanguage.current
    return remember(lang, zh) { L10n.t(lang, zh) }
}

/**
 * Composable 查表（带占位符），见 [L10n.format]。
 *
 * 注意 Kotlin 中文标识符坑：`"${count}个角色"` 这种中文紧贴变量的写法会把「个角色」当成
 * 变量名的一部分，务必写 `"{0}个角色"` 配合本函数（或用 `"${count}个角色"`）。
 */
@Composable
fun tf(zh: String, vararg args: Any?): String {
    val lang = LocalAppLanguage.current
    val argsKey = args.toList()
    return remember(lang, zh, argsKey) { L10n.format(lang, zh, *args) }
}
