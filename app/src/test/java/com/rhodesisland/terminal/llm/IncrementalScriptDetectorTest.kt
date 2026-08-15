package com.rhodesisland.terminal.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IncrementalScriptDetector 单元测试（Task 4 Step 5）。
 *
 * 覆盖：跨 delta 边界的标记、最早标记、CJK 姓名、半角冒号不匹配、
 * 有界缓冲（不重扫旧文本）、空输入/空姓名列表边界。
 */
class IncrementalScriptDetectorTest {

    private val names = listOf("小明", "小红", "苏菲亚")

    @Test
    fun noMarkerReturnsNullAndConsumesAll() {
        val d = IncrementalScriptDetector(names)
        assertNull(d.append("今天天气不错").cutAbsoluteIndex)
        // 消费计数：bufferStartIndex + buffer.length == 已喂入总字符数
        assertEquals(6, d.bufferStartIndex + d.buffer.length)
    }

    @Test
    fun singleChunkMarkerReportsAbsoluteIndex() {
        val d = IncrementalScriptDetector(names)
        val r = d.append("你好，小明：我们去玩吧")
        assertEquals(3, r.cutAbsoluteIndex)  // 小=3
    }

    @Test
    fun markerCrossingDeltaBoundaryDetected() {
        val d = IncrementalScriptDetector(names)
        assertNull(d.append("你好，小"))   // "小明：" 尚未闭合（缺冒号）
        assertNull(d.append("明"))          // 仍无冒号
        val r = d.append("：走")
        assertEquals(3, r.cutAbsoluteIndex)  // 小=3，跨三个 delta 检测到
    }

    @Test
    fun markerCompletingExactlyAtBoundary() {
        val d = IncrementalScriptDetector(names)
        assertNull(d.append("你好，小"))
        val r = d.append("明：走")
        assertEquals(3, r.cutAbsoluteIndex)
    }

    @Test
    fun earliestMarkerWinsAmongSeveral() {
        val d = IncrementalScriptDetector(names)
        val r = d.append("小红：喂 小明：来")
        assertEquals(0, r.cutAbsoluteIndex)  // 小红： 最早（0），小明： 在 5
    }

    @Test
    fun maxLengthCjkNameAcrossManyChunks() {
        val d = IncrementalScriptDetector(names)  // maxNameLen=3（苏菲亚），keep=4
        assertNull(d.append("甲乙丙丁"))
        assertNull(d.append("苏菲"))
        val r = d.append("亚：走了")
        // 绝对流：甲乙丙丁苏菲亚：走了 -> 苏菲亚： 起始于 4
        assertEquals(4, r.cutAbsoluteIndex)
    }

    @Test
    fun halfWidthColonDoesNotMatch() {
        val d = IncrementalScriptDetector(listOf("小明", "时间"))
        assertNull(d.append("小明: 现在10:30了").cutAbsoluteIndex)
    }

    @Test
    fun bufferStaysBoundedAndStartIndexAdvances() {
        val d = IncrementalScriptDetector(names)  // keep = 3+1 = 4
        var fed = 0
        for (chunk in listOf("甲乙", "丙丁", "戊己", "庚辛", "壬癸")) {
            assertNull(d.append(chunk).cutAbsoluteIndex)
            fed += chunk.length
            assertEquals(fed, d.bufferStartIndex + d.buffer.length)
            assert(d.buffer.length <= 4) { "buffer 应不超过 maxNameLen+1，实际 ${d.buffer.length}" }
        }
        // 已丢弃的旧文本（甲乙）不在 buffer 内：不重扫旧文本
        assertEquals("庚辛壬癸", d.buffer)
        assertEquals(6, d.bufferStartIndex)
    }

    @Test
    fun emptyDeltaIsNoop() {
        val d = IncrementalScriptDetector(names)
        assertNull(d.append("你好").cutAbsoluteIndex)
        assertNull(d.append("").cutAbsoluteIndex)
        assertEquals(2, d.bufferStartIndex + d.buffer.length)
    }

    @Test
    fun emptyNamesNeverDetect() {
        val d = IncrementalScriptDetector(emptyList())
        assertNull(d.append("任何文本：都没有角色名").cutAbsoluteIndex)
    }

    @Test
    fun singleCharNamesMatch() {
        val d = IncrementalScriptDetector(listOf("羽"))
        val r = d.append("我是羽：好的")
        assertEquals(2, r.cutAbsoluteIndex)  // 我=0 是=1 羽=2
    }
}
