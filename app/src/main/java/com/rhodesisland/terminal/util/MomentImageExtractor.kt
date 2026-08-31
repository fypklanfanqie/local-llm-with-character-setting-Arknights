package com.rhodesisland.terminal.util

/**
 * 从生图模型回复中提取图片引用。纯字符串处理，JVM 可测。
 *
 * 生图中转站（OpenAI 聊天格式出图）的回复形态各异，按优先级提取：
 * 1. JSON 字段 `url` / `b64_json`（可能整体是 JSON 或夹在 markdown 代码围栏里）
 * 2. markdown 图片 `![alt](url)`
 * 3. 裸 URL（http/https，截断于空白或反引号）
 * 4. data URI（data:image/...;base64,xxxx）
 *
 * 返回「图片引用」列表（URL 或 base64 裸串，去重、按出现顺序）；下载/解码由调用方负责。
 */
object MomentImageExtractor {

    /** 提取结果：url = 远程地址；base64 = 裸 base64（不含 data: 前缀）。 */
    data class ImageRef(val url: String? = null, val base64: String? = null)

    private val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val markdownImageRegex = Regex("!\\[[^\\]]*\\]\\(([^)\\s]+)[^)]*\\)")
    private val bareUrlRegex = Regex("https?://[^\\s`\"'<>\\)\\]]+")
    private val dataUriRegex = Regex("data:image/[a-zA-Z0-9.+-]+;base64,([A-Za-z0-9+/=]+)")
    private val jsonUrlKey = Regex("\"(?:url|image_url|imageUrl)\"\\s*:\\s*\"([^\"]+)\"")
    private val jsonB64Key = Regex("\"(?:b64_json|b64|base64|image_base64)\"\\s*:\\s*\"([A-Za-z0-9+/=]{64,})\"")

    /** 从原始回复提取图片引用；找不到任何图片返回空列表。 */
    fun extract(raw: String): List<ImageRef> {
        if (raw.isBlank()) return emptyList()
        val refs = mutableListOf<ImageRef>()
        val seen = mutableSetOf<String>()

        fun addUrl(u: String) {
            val clean = u.trim().trimEnd('.', ',', '。', '，', ')', ']', '}', '"', '\'', '`')
            if (clean.length < 8) return
            if (seen.add("u:$clean")) refs += ImageRef(url = clean)
        }

        fun addB64(b: String) {
            if (b.length < 64) return
            if (seen.add("b:${b.take(64)}")) refs += ImageRef(base64 = b)
        }

        // 1) data URI（含 base64 内联图）
        dataUriRegex.findAll(raw).forEach { addB64(it.groupValues[1]) }

        // 2) JSON 键（先剥代码围栏，也直接扫原文——有的模型不包围栏）
        val candidateTexts = buildList {
            fenceRegex.findAll(raw).forEach { add(it.groupValues[1]) }
            add(raw)
        }
        candidateTexts.forEach { text ->
            jsonUrlKey.findAll(text).forEach { m ->
                val v = m.groupValues[1]
                if (v.startsWith("data:")) {
                    dataUriRegex.find(v)?.let { addB64(it.groupValues[1]) }
                } else {
                    addUrl(v)
                }
            }
            jsonB64Key.findAll(text).forEach { addB64(it.groupValues[1]) }
        }

        // 3) markdown 图片
        markdownImageRegex.findAll(raw).forEach { m ->
            val u = m.groupValues[1]
            if (u.startsWith("data:")) dataUriRegex.find(u)?.let { addB64(it.groupValues[1]) } else addUrl(u)
        }

        // 4) 裸 URL（排除已提取的；也排除明显是文字里的普通链接——无法区分，一律接受，
        //    下载失败时由调用方按「提取失败」降级纯文字）
        bareUrlRegex.findAll(raw).forEach { addUrl(it.value) }

        return refs
    }

    /**
     * 从回复提取文案（caption）：剥代码围栏、剥 JSON 结构取 caption/text/content 字段、
     * 剥 markdown 图片语法与 data URI。生图模型常把「文案+图」混在一条回复里。
     * 结果 trim 后仍可能为空，由调用方兜底。
     */
    fun extractCaption(raw: String): String {
        var text = raw.trim()
        // 整体是 JSON（或围栏包 JSON）：取 caption/text/content/description 字段
        val fenced = fenceRegex.find(text)?.groupValues?.get(1)?.trim()
        if (fenced != null && fenced.startsWith("{")) text = fenced
        if (text.startsWith("{")) {
            val jsonField = Regex("\"(?:caption|text|content|description|post)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            jsonField.find(text)?.let { m ->
                val unescaped = m.groupValues[1]
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                if (unescaped.isNotBlank()) return unescaped.trim().take(CAPTION_LIMIT)
            }
        }
        // 非 JSON：剥围栏标记、markdown 图片、data URI
        text = text.replace(fenceRegex, "$1")
        text = text.replace(markdownImageRegex, "")
        text = text.replace(dataUriRegex, "")
        // 剥 JSON 标记行（{"caption": ...} 残余）
        text = text.replace(jsonUrlKey, "").replace(jsonB64Key, "")
        return text.trim().take(CAPTION_LIMIT)
    }

    /** 文案长度上限（与 AppConfig.Moment.CAPTION_MAX_CHARS 一致；纯函数避免依赖 config）。 */
    const val CAPTION_LIMIT = 500
}
