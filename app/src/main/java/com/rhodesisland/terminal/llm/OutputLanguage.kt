package com.rhodesisland.terminal.llm

/**
 * 云端输出语言约束：注入到所有 system prompt 尾部，修复第三方 API 偶发英文输出/思考。
 * 文本恒定 → 不破坏云端前缀缓存。
 */
object OutputLanguage {
    const val ZH_DIRECTIVE =
        "\n\n[语言要求] 全程使用简体中文输出，人名与既有专有名词可保留原文；如需输出思考过程，思考也必须使用简体中文。"
}
