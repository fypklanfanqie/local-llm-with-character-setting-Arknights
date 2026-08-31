package com.rhodesisland.terminal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MomentImageExtractor] 契约测试：生图中转站回复的图片与文案提取。
 */
class MomentImageExtractorTest {

    @Test
    fun extract_markdownImage() {
        val refs = MomentImageExtractor.extract("这是图 ![img](https://x.com/a.png) 完毕")
        assertEquals(1, refs.size)
        assertEquals("https://x.com/a.png", refs[0].url)
    }

    @Test
    fun extract_bareUrl() {
        val refs = MomentImageExtractor.extract("生成完成：https://cdn.example.com/pic/123.jpg")
        assertEquals(1, refs.size)
        assertEquals("https://cdn.example.com/pic/123.jpg", refs[0].url)
    }

    @Test
    fun extract_jsonUrlField() {
        val raw = """{"url": "https://img.example.com/out/abc.png", "revised_prompt": "x"}"""
        val refs = MomentImageExtractor.extract(raw)
        assertEquals(1, refs.size)
        assertEquals("https://img.example.com/out/abc.png", refs[0].url)
    }

    @Test
    fun extract_jsonB64Field() {
        val b64 = "iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg=="
        val refs = MomentImageExtractor.extract("""{"b64_json": "$b64"}""")
        assertEquals(1, refs.size)
        assertEquals(b64, refs[0].base64)
    }

    @Test
    fun extract_dataUri() {
        val b64 = "iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg=="
        val refs = MomentImageExtractor.extract("结果：data:image/png;base64,$b64 请查收")
        assertEquals(1, refs.size)
        assertEquals(b64, refs[0].base64)
    }

    @Test
    fun extract_fencedJson() {
        val raw = "```json\n{\"url\": \"https://a.example.com/x.jpg\"}\n```"
        val refs = MomentImageExtractor.extract(raw)
        assertEquals(1, refs.size)
        assertEquals("https://a.example.com/x.jpg", refs[0].url)
    }

    @Test
    fun extract_deduplicates() {
        val refs = MomentImageExtractor.extract(
            "![a](https://x.com/a.png) 又是 https://x.com/a.png",
        )
        assertEquals(1, refs.size)
    }

    @Test
    fun extract_emptyReturnsEmpty() {
        assertTrue(MomentImageExtractor.extract("").isEmpty())
        assertTrue(MomentImageExtractor.extract("纯文字回复没有任何图片").isEmpty())
    }

    @Test
    fun extractCaption_fromJson() {
        val raw = """{"caption": "今天天气不错\n出去转转", "imagePrompt": "sunny park"}"""
        assertEquals("今天天气不错\n出去转转", MomentImageExtractor.extractCaption(raw))
    }

    @Test
    fun extractCaption_stripsMarkdownImage() {
        assertEquals("配图如下", MomentImageExtractor.extractCaption("配图如下 ![x](https://a.com/b.png)"))
    }

    @Test
    fun extractCaption_plainTextPassthrough() {
        assertEquals("就直接说话", MomentImageExtractor.extractCaption("就直接说话"))
    }
}
