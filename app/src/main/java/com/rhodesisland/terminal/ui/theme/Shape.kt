package com.rhodesisland.terminal.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 玻璃圆角：类 iOS 大圆角，统一阶。
 * 连续圆角（squircle）在 API 31+ 由 RoundedCornerShape 自动近似为 continuous；低版本退化为普通圆角。
 */
val IrisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 玻璃组件用形状。 */
object GlassShapes {
    val card: Shape = RoundedCornerShape(24.dp)
    val cardSmall: Shape = RoundedCornerShape(18.dp)
    val large: Shape = RoundedCornerShape(22.dp)
    val pill: Shape = RoundedCornerShape(50)
    val sheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val button: Shape = RoundedCornerShape(16.dp)
    val chip: Shape = RoundedCornerShape(50)
    val bar: Shape = RoundedCornerShape(28.dp)
    val none: Shape = RectangleShape
}
