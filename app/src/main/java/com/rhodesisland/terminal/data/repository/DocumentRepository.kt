package com.rhodesisland.terminal.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Base64OutputStream
import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import com.rhodesisland.terminal.data.remote.DirectLlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 文档 / 图片处理仓库（直连对话商，不经代理）。
 *
 * - 图片 base64 / 临时文件等基础工具（沿用旧 OcrRepository）。
 * - [extractDocumentText]：按文件类型提取文字，直连当前配置的对话商模型：
 *     * PDF -> [PdfRenderer] 逐页渲染为图片 -> 多模态模型提取（需多模态模型）。
 *     * 纯文本类 -> 直接 UTF-8 读取（无需 API）。
 *     * 图片文件 -> 多模态模型 OCR（需多模态模型）。
 *     * Office（docx/xlsx/pptx 等）-> Android 无端侧渲染器，抛错引导转 PDF。
 */
class DocumentRepository(
    private val client: DirectLlmClient,
) {
    /** 已知多模态模型关键词 */
    private val multimodalKeywords = listOf(
        "gpt-4o", "gpt-4-turbo", "gpt-4-vision", "gpt-4.1",
        "claude-3", "claude-3.5", "claude-4",
        "gemini-1.5", "gemini-2", "gemini-pro-vision",
        "qwen-vl", "glm-4v", "vision", "multimodal",
        "doubao-vision", "hunyuan-vision",
    )

    fun isMultimodalModel(model: String?): Boolean {
        if (model.isNullOrBlank()) return false
        val m = model.lowercase()
        return multimodalKeywords.any { m.contains(it) }
    }

    /** 读取文件为 base64（流式编码，避免大文件整块读入内存导致 OOM）；超大文件拒绝。 */
    suspend fun readFileAsBase64(path: String): String = withContext(Dispatchers.IO) {
        val f = File(path)
        if (f.length() > MAX_IMAGE_BYTES) throw IOException("文件过大，无法上传")
        val output = ByteArrayOutputStream()
        f.inputStream().use { input ->
            Base64OutputStream(output, Base64.NO_WRAP).use { b64Out ->
                input.copyTo(b64Out, bufferSize = 8192)
            }
        }
        output.toString(Charsets.US_ASCII.name())
    }

    /** 读取 content URI 为 base64（不含 data: 前缀），失败或超大返回 null */
    suspend fun uriToBase64(context: Context, uri: String): String? = withContext(Dispatchers.IO) {
        try {
            val length = contentLength(context, uri)
            if (length != null && length > MAX_IMAGE_BYTES) return@withContext null
            val resolved = Uri.parse(uri)
            val input = context.contentResolver.openInputStream(resolved) ?: return@withContext null
            val output = ByteArrayOutputStream()
            input.use { src ->
                Base64OutputStream(output, Base64.NO_WRAP).use { b64Out ->
                    src.copyTo(b64Out, bufferSize = 8192)
                }
            }
            output.toString(Charsets.US_ASCII.name())
        } catch (e: Exception) {
            null
        }
    }

    /** 读取 content URI 的声明长度（字节）；未知/失败返回 null。 */
    private fun contentLength(context: Context, uri: String): Long? = try {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length }
    } catch (e: Exception) {
        null
    }

    /** 把 content URI 复制到缓存临时文件，供需要可 seek 文件描述符的场景（PdfRenderer）使用；失败返回 null */
    suspend fun copyUriToTempFile(context: Context, uri: String, fileName: String): File? = withContext(Dispatchers.IO) {
        val ext = fileName.substringAfterLast('.', "bin")
        val temp = File.createTempFile("attach_", ".$ext", context.cacheDir)
        try {
            val resolved = Uri.parse(uri)
            val input = context.contentResolver.openInputStream(resolved)
                ?: throw IOException("openInputStream returned null for $uri")
            input.use { src ->
                temp.outputStream().use { dst -> src.copyTo(dst) }
            }
            temp
        } catch (e: Exception) {
            temp.delete()
            null
        }
    }

    /**
     * 直连提取文档文字。按扩展名分支：
     * PDF -> 渲染页送多模态模型；纯文本 -> 直接读；图片 -> 多模态 OCR；Office -> 报错。
     */
    suspend fun extractDocumentText(
        context: Context,
        uri: String,
        fileName: String,
        cfg: ApiConfig,
    ): String = withContext(Dispatchers.IO) {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        when {
            ext == "pdf" -> extractPdfText(context, uri, cfg)
            isImageExt(ext) -> extractImageText(context, uri, cfg)
            isTextExt(ext) -> readTextFile(context, uri)
            else -> throw Exception("暂不支持 .$ext 文档直连解析，请转为 PDF 后上传")
        }
    }

    private fun isTextExt(ext: String): Boolean = ext in TEXT_EXTENSIONS
    private fun isImageExt(ext: String): Boolean = ext in IMAGE_EXTENSIONS

    private suspend fun readTextFile(context: Context, uri: String): String {
        return try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                readBoundedUtf8(it, MAX_TEXT_BYTES)
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 有界读取 UTF-8 文本：超过 [maxBytes] 截断，防大日志/大文件整读撑爆堆。 */
    private fun readBoundedUtf8(input: java.io.InputStream, maxBytes: Long): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (total < maxBytes) {
            val n = input.read(buf)
            if (n < 0) break
            val toWrite = minOf(n.toLong(), maxBytes - total).toInt()
            out.write(buf, 0, toWrite)
            total += toWrite
            if (toWrite < n) break
        }
        return out.toString(Charsets.UTF_8.name()).trim()
    }

    /** PDF -> PdfRenderer 逐页渲染 -> 多模态模型提取文字。需当前模型支持多模态。 */
    private suspend fun extractPdfText(context: Context, uri: String, cfg: ApiConfig): String {
        if (cfg.apiKey.isBlank()) throw Exception("请先在设置页配置 API Key")
        if (!isMultimodalModel(cfg.model)) {
            throw Exception("PDF 提取需多模态模型，请在设置切换（如 GPT-4o / Qwen-VL）")
        }
        contentLength(context, uri)?.let { if (it > MAX_PDF_BYTES) throw Exception("PDF 文件过大") }
        val tmp = copyUriToTempFile(context, uri, "doc.pdf") ?: throw Exception("无法读取 PDF 文件")
        try {
            val (images, truncated) = renderPdfPages(tmp, MAX_PDF_PAGES)
            if (images.isEmpty()) throw Exception("PDF 无可渲染页面")
            val text = extractFromImages(
                cfg, images,
                "请提取并输出下列文档图片中的全部文字内容，保持原始结构与阅读顺序，仅输出文字。",
            )
            return if (truncated) "$text\n[仅前 $MAX_PDF_PAGES 页已提取]" else text
        } finally {
            tmp.delete()
        }
    }

    /** 图片文件 -> 多模态模型 OCR 提取文字。需当前模型支持多模态。 */
    private suspend fun extractImageText(context: Context, uri: String, cfg: ApiConfig): String {
        if (cfg.apiKey.isBlank()) throw Exception("请先在设置页配置 API Key")
        if (!isMultimodalModel(cfg.model)) {
            throw Exception("图片识别需多模态模型，请在设置切换（如 GPT-4o / Qwen-VL）")
        }
        val b64 = uriToBase64(context, uri) ?: throw Exception("无法读取图片")
        return extractFromImages(cfg, listOf(b64), "请提取并输出图片中的全部文字内容，仅输出文字。")
    }

    /** 渲染 PDF 前 [maxPages] 页为 base64 JPEG；返回 (图片列表, 是否还有未渲染页)。 */
    private fun renderPdfPages(file: File, maxPages: Int): Pair<List<String>, Boolean> {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        val images = mutableListOf<String>()
        val pageCount = renderer.pageCount
        val pagesToRender = minOf(pageCount, maxPages)
        try {
            for (i in 0 until pagesToRender) {
                val page = renderer.openPage(i)
                val scale = 2 // 2x 提升清晰度，便于模型识读
                val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                images.add(bitmapToBase64Jpeg(bitmap))
                bitmap.recycle() // 逐页回收，控制峰值内存
            }
        } finally {
            renderer.close()
            fd.close()
        }
        return images to (pageCount > maxPages)
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 80): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** 把若干 base64 图片 + 提取指令拼成一条多模态消息，直连对话商提取文字。 */
    private suspend fun extractFromImages(cfg: ApiConfig, base64Images: List<String>, prompt: String): String {
        val content = buildJsonArray {
            for (b64 in base64Images) {
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:${detectImageMime(b64)};base64,$b64")
                    }
                })
            }
            add(buildJsonObject {
                put("type", "text")
                put("text", prompt)
            })
        }
        val messages = listOf(ChatMessageDto(role = "user", content = content))
        return client.chatOnce(cfg.baseUrl, cfg.apiKey, cfg.model, messages)
    }

    /** 根据 base64 数据的魔术字节推断图片 MIME */
    private fun detectImageMime(b64: String): String = when {
        b64.startsWith("/9j/") -> "image/jpeg"
        b64.startsWith("iVBORw0KGgo") -> "image/png"
        b64.startsWith("R0lGODlh") -> "image/gif"
        b64.startsWith("UklGR") -> "image/webp"
        b64.startsWith("Qk") -> "image/bmp"
        else -> "image/jpeg"
    }

    companion object {
        /** PDF 提取页数上限（控制请求体积与成本） */
        private const val MAX_PDF_PAGES = 6

        /** 纯文本附件读取上限（字节）：超过截断。 */
        private const val MAX_TEXT_BYTES = 2L * 1024 * 1024

        /** 图片附件大小上限（字节）：OCR/上传用，超大拒绝避免 base64 膨胀与内存峰值。 */
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

        /** PDF 附件大小上限（字节）：仅渲染前 6 页，超大 PDF 拷贝即浪费。 */
        private const val MAX_PDF_BYTES = 50L * 1024 * 1024

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "json", "xml", "log",
            "html", "htm", "java", "kt", "kts", "py", "js", "ts", "tsx",
            "c", "cpp", "cc", "h", "hpp", "cs", "go", "rs", "rb", "php",
            "sh", "bat", "ps1", "yml", "yaml", "ini", "conf", "toml", "sql", "svg",
        )
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }
}
