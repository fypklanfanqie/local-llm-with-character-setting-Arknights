package com.rhodesisland.terminal.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视频导出目标选择策略（Task 8，纯 JVM 逻辑，无 Android 依赖）。
 *
 * 边界：Android 10（API 29）起可用 MediaStore.Video 免权限写入公共相册
 * （[VideoExportTarget.MediaStoreMovies]）；Android 7–9（API 24–28，即 minSdk
 * 实际范围）走 SAF ACTION_CREATE_DOCUMENT 由用户选择保存位置
 * （[VideoExportTarget.CreateDocument]）。23 为历史边界一并覆盖。
 */
class SeedanceExportPolicyTest {

    @Test
    fun sdk23_usesCreateDocument() {
        assertEquals(VideoExportTarget.CreateDocument, exportTargetForSdk(23))
    }

    @Test
    fun sdk24_usesCreateDocument() {
        assertEquals(VideoExportTarget.CreateDocument, exportTargetForSdk(24))
    }

    @Test
    fun sdk28_usesCreateDocument() {
        assertEquals(VideoExportTarget.CreateDocument, exportTargetForSdk(28))
    }

    @Test
    fun sdk29_usesMediaStoreMovies() {
        assertEquals(VideoExportTarget.MediaStoreMovies, exportTargetForSdk(29))
    }

    @Test
    fun sdk34_usesMediaStoreMovies() {
        assertEquals(VideoExportTarget.MediaStoreMovies, exportTargetForSdk(34))
    }
}
