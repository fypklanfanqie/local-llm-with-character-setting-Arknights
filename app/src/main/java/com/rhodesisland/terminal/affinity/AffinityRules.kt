package com.rhodesisland.terminal.affinity

const val MAX_AFFINITY = 200f
const val CHAT_AFFINITY_GAIN = 0.5f
const val VIDEO_AFFINITY_GAIN = 5f
const val DAILY_CHECKIN_LMD = 10_000L
val AFFINITY_EVENT_THRESHOLDS = listOf(50, 100, 150, 200)

fun clampAffinity(value: Float): Float = value.coerceIn(0f, MAX_AFFINITY)

fun affinityGainForGiftPrice(price: Long): Float? = when (price) {
    in 5_000L..9_999L -> 1f
    in 10_000L..14_999L -> 2f
    in 15_000L..20_000L -> 3f
    else -> null
}

fun nextAffinityHint(value: Float): String = AFFINITY_EVENT_THRESHOLDS.firstOrNull { it > value }
    ?.let { "距离下一阶段 $it 好感还差 ${formatAffinity(it - value)}" }
    ?: "已达到最高好感度。"

fun formatAffinity(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value)

fun crossedAffinityThresholds(
    previous: Float,
    current: Float,
    alreadyUnlocked: Set<Int>,
): List<Int> = AFFINITY_EVENT_THRESHOLDS.filter { threshold ->
    threshold !in alreadyUnlocked && previous < threshold && current >= threshold
}
