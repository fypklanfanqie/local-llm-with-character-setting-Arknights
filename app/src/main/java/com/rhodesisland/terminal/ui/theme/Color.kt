package com.rhodesisland.terminal.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * PRTS 终端深色主题色板
 * 完整迁移自小程序 app.wxss CSS 变量
 */
object PrtsColors {
    // 背景
    val BgPrimary = Color(0xFF0A0A0F)
    val BgSecondary = Color(0xFF0E0E16)
    val BgTertiary = Color(0xFF15151F)
    val BgCard = Color(0xFF181825)
    val BgHover = Color(0xFF1E1E2E)
    val BgInput = Color(0xFF12121C)

    // 金色
    val Gold = Color(0xFFC9A87C)
    val GoldBright = Color(0xFFD4B88C)
    val GoldDim = Color(0xFF8A7355)
    val GoldGlow = Color(0x26C9A87C)

    // 文字
    val TextPrimary = Color(0xFFE8E4E0)
    val TextSecondary = Color(0xFF9A9690)
    val TextDim = Color(0xFF5A5650)

    // 强调
    val AccentBlue = Color(0xFF5B8CBD)
    val AccentBlueDim = Color(0xFF3A5A7A)
    val Danger = Color(0xFF8B4545)
    val DangerBright = Color(0xFFB55A5A)
    val Success = Color(0xFF5A8B5A)

    // 边框
    val Border = Color(0xFF252535)
    val BorderLight = Color(0xFF333345)
    val AcrylicBorder = Color(0x2EC9A87C)

    // 警戒色
    val WarnYellow = Color(0xFFD4B04A)
    val WarnOrange = Color(0xFFC9783E)
    val AlertRed = Color(0xFFA83838)

    // 毛玻璃
    val AcrylicBg = Color(0xB80E0E16)

    // 代码块（VS Code Dark+）
    val CodeBg = Color(0xFF1E1E1E)
    val CodeHeaderBg = Color(0xFF252526)

    // 公式块
    val ScienceBg = Color(0xFF1A2A2A)
}
