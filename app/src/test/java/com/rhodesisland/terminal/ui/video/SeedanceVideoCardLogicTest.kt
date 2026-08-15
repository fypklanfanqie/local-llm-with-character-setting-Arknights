package com.rhodesisland.terminal.ui.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SeedanceVideoCard] 纯逻辑契约测试（Task 10，纯 JVM，不触碰 Compose/Android）。
 *
 * 覆盖活动内联视频判定使用的路径归一化 [normalizeVideoPathForCompare]：
 * 统一分隔符、折叠多余斜杠、去除尾部斜杠，使控制器侧 `File.absolutePath` 与 Room 持久化
 * 的 `localVideoPath` 在等价路径表示下可比；不解析符号链接、不做文件系统 I/O。
 */
class SeedanceVideoCardLogicTest {

    @Test
    fun sameAbsolutePathIsUnchanged() {
        assertEquals(
            "/data/user/0/pkg/files/seedance/tasks/uuid-1/video.mp4",
            normalizeVideoPathForCompare("/data/user/0/pkg/files/seedance/tasks/uuid-1/video.mp4"),
        )
    }

    @Test
    fun windowsBackslashesAreNormalizedToSlash() {
        assertEquals(
            "/data/user/0/pkg/files/seedance/tasks/uuid-1/video.mp4",
            normalizeVideoPathForCompare("\\data\\user\\0\\pkg\\files\\seedance\\tasks\\uuid-1\\video.mp4"),
        )
    }

    @Test
    fun duplicateAndTrailingSlashesAreCollapsed() {
        assertEquals(
            "/data/user/0/pkg/files/video.mp4",
            normalizeVideoPathForCompare("/data//user/0/pkg///files/video.mp4/"),
        )
    }

    @Test
    fun equivalentRepresentationsCompareEqual() {
        val fromController = "/data/user/0/pkg/files/seedance/tasks/uuid-1/video.mp4"
        val fromRoom = "\\data\\user\\0\\pkg\\files\\seedance/tasks//uuid-1/video.mp4"
        assertEquals(
            "等价路径表示必须归一化为相同值",
            normalizeVideoPathForCompare(fromController),
            normalizeVideoPathForCompare(fromRoom),
        )
    }

    @Test
    fun differentFilesStillDifferAfterNormalization() {
        assertEquals(false, normalizeVideoPathForCompare("/a/video.mp4") == normalizeVideoPathForCompare("/a/other.mp4"))
    }

    @Test
    fun blankAndRootPathsAreTolerated() {
        assertEquals("", normalizeVideoPathForCompare(""))
        assertEquals("", normalizeVideoPathForCompare("/"))
        assertEquals("", normalizeVideoPathForCompare("///"))
    }
}
