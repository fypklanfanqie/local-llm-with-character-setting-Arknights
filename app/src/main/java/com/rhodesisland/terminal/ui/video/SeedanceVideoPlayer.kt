package com.rhodesisland.terminal.ui.video

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/** 内联播放器表面 testTag（instrumentation 断言「同一时刻仅一个活动表面」）。 */
const val SEEDANCE_INLINE_PLAYER_TAG = "seedance_inline_player"

/** 全屏播放器表面 testTag。 */
const val SEEDANCE_FULLSCREEN_PLAYER_TAG = "seedance_fullscreen_player"

/**
 * Compose 桥接的 Media3 [PlayerView]（Task 8）。
 *
 * [player] 为 null 时渲染空占位（不创建任何 View/表面），保证任意时刻只有一个
 * [PlayerView] 挂载同一个 [Player]。内联卡片与全屏预览通过传入的 player 是否非空切换：
 * 全屏开启时内联表面让出、全屏表面接管，播放进度无缝延续——同一时刻至多一个活动表面。
 *
 * 用法（AndroidView 桥接模式与 [com.rhodesisland.terminal.ui.chat.MathView] 一致）：
 * 工厂创建 [PlayerView] 并挂载 [player]，离开组合（如 LazyColumn 滚出视口）时解绑。
 */
@Composable
fun SeedanceVideoPlayer(
    player: Player?,
    modifier: Modifier = Modifier,
    showControls: Boolean = false,
    testTag: String? = null,
) {
    if (player != null) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = showControls
                    setPlayer(player)
                }
            },
            update = { it.setPlayer(player) },
            onRelease = { it.player = null },
            modifier = if (testTag != null) modifier.testTag(testTag) else modifier,
        )
    } else {
        Box(modifier = modifier)
    }
}
