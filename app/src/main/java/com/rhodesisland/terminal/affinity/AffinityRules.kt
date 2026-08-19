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

fun crossedAffinityThresholds(
    previous: Float,
    current: Float,
    alreadyUnlocked: Set<Int>,
): List<Int> = AFFINITY_EVENT_THRESHOLDS.filter { threshold ->
    threshold !in alreadyUnlocked && previous < threshold && current >= threshold
}
