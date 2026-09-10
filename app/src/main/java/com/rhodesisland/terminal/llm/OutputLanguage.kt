package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.i18n.AppLanguage
import com.rhodesisland.terminal.i18n.L10nRuntime

/**
 * 输出语言约束：注入到所有 system prompt 尾部，修复第三方 API 偶发英文输出/思考。
 *
 * **与界面语言联动**：界面选 English → 角色用英文回答；选 日本語 → 用日文；简体中文 / 未解析 → 中文。
 * 指令本身写成中文（角色人设与提示词体系仍是中文），只把「输出语言」这一项指向目标语言，
 * 因此人设、世界观、世界书等中文设定不需要翻译，AI 也能照常用目标语言说话。
 *
 * 每个语言的指令文本恒定（同语言内逐字节稳定）→ 不破坏云端前缀缓存。
 */
object OutputLanguage {

    /** 中文（默认）。 */
    const val ZH_DIRECTIVE =
        "\n\n[语言要求] 全程使用简体中文输出，人名与既有专有名词可保留原文；如需输出思考过程，思考也必须使用简体中文。" // l10n:ignore 非界面文案（提示词）

    /** 英文界面 → 角色用英文回答。 */
    const val EN_DIRECTIVE =
        "\n\n[语言要求] 全程使用英文（English）输出：角色人设、世界观与世界书仍是中文设定，不要翻译或复述它们，" +
            "但你写出的所有台词、旁白与思考过程都必须是英文；人名与既有专有名词可保留原文。" // l10n:ignore 非界面文案（提示词）

    /** 日文界面 → 角色用日文回答。 */
    const val JA_DIRECTIVE =
        "\n\n[语言要求] 全程使用日文（日本語）输出：角色人设、世界观与世界书仍是中文设定，不要翻译或复述它们，" +
            "但你写出的所有台词、旁白与思考过程都必须是日文；人名与既有专有名词可保留原文。" // l10n:ignore 非界面文案（提示词）

    /** 按**已解析**界面语言取输出语言约束（SYSTEM 视为中文）。 */
    fun directiveFor(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> EN_DIRECTIVE
        AppLanguage.JA -> JA_DIRECTIVE
        AppLanguage.ZH, AppLanguage.SYSTEM -> ZH_DIRECTIVE
    }

    /**
     * 运行期便捷入口：读 [L10nRuntime] 缓存的生效语言。
     * Worker / 通知 / PromptBuilder 等非 Composable、非 suspend 场景统一用它。
     */
    fun current(): String = directiveFor(L10nRuntime.language)

    /**
     * 辅助产物（滚动摘要等非台词生成）的叙述语言简称，与输出语言联动。
     * 摘要会以【前情提要】注回对话上下文：若固定中文，英文/日文对话会被拉回中文，
     * 因此叙述语言必须跟随用户选择的语言。
     */
    fun narrationName(lang: AppLanguage = L10nRuntime.language): String = when (lang) {
        AppLanguage.EN -> "英文（English）"
        AppLanguage.JA -> "日文（日本語）"
        AppLanguage.ZH, AppLanguage.SYSTEM -> "简体中文"
    }
}
