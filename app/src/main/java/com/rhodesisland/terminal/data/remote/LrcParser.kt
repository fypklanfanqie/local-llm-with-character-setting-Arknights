package com.rhodesisland.terminal.data.remote

/**
 * 标准 LRC 歌词解析。
 * 形如：[mm:ss.xx] 歌词内容
 * 解析为按时间升序排列的 (timeMs, text) 列表。
 */
data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {

    private val LINE_RE = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(raw: String?): List<LrcLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<LrcLine>()
        for (line in raw.lineSequence()) {
            // 一行可能带多个时间戳，如 [00:01.00][00:05.00]同一句歌词
            val matches = LINE_RE.findAll(line).toList()
            if (matches.isEmpty()) continue
            // 文本取最后一个时间戳之后的内容，按 match 位置切片，
            // 避免重复时间戳时 substringAfter 命中首个而残留后面的 [mm:ss] 标签
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isBlank()) continue
            for (match in matches) {
                val min = match.groupValues[1].toLongOrNull() ?: 0
                val sec = match.groupValues[2].toLongOrNull() ?: 0
                val fracStr = match.groupValues[3]
                val frac = if (fracStr.isNotEmpty()) {
                    // 兼容 .xx 与 .xxx
                    fracStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0
                } else 0
                val timeMs = (min * 60 + sec) * 1000 + frac
                lines.add(LrcLine(timeMs, text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /** 根据当前播放进度（ms）返回应高亮的歌词行索引 */
    fun currentIndex(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        for (i in lines.indices.reversed()) {
            if (lines[i].timeMs <= positionMs) return i
        }
        return 0
    }
}
