package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.llm.profile.DeviceRuntimeFingerprint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * MNN 模型包完整性校验（Task 12）。
 *
 * 从模型目录 `config.json` / `llm_config.json` 推导全部必需文件（graph/weight/tokenizer/embedding/
 * visual/audio），解析每个引用路径并**拒绝逃逸** model root；校验文件存在、非空、非 `.partN` 临时
 * 分片、JSON 可解析。多模态（visual/audio）缺省视为可选缺失（警告非错误）；最小纯文本包应通过。
 *
 * [modelFingerprint] 用文件清单（名 + 大小 + mtime）生成（Task 9 的规范化哈希），不重读大文件内容；
 * 内容级校验和由下载/合并阶段（ChunkInfo.checksum，SHA-256）保证。
 */
object ModelBundleValidator {

    /** 必需的 MNN 默认文件（config.json 未显式引用时仍要求存在；tokenizer 单独兜底处理）。 */
    private val DEFAULT_REQUIRED = listOf("llm.mnn", "llm.mnn.weight")

    /** 引用路径值的文件扩展（用于从 config JSON 收集引用路径）。
     *  含 .mtok/.tok：部分 MNN 模型的 tokenizer 文件名是 tokenizer.mtok 而非 tokenizer.txt，
     *  不加会漏收集导致误报「缺少必需文件 tokenizer.txt」。 */
    private val REFERENCED_EXTENSIONS = listOf(
        ".mnn", ".weight", ".bin", ".txt", ".mtok", ".tok", ".model", ".safetensors", ".json",
    )

    /** tokenizer 兜底候选：config 未显式引用 tokenizer 时，要求以下文件至少存在一个。 */
    private val TOKENIZER_CANDIDATES = listOf("tokenizer.txt", "tokenizer.mtok", "tokenizer.bin")

    /** 已知临时/中间文件后缀（不视为完整文件）。 */
    private val PART_SUFFIX = Regex("\\.part\\d+$")

    data class ModelValidationResult(
        val valid: Boolean,
        val modelFingerprint: String,
        val requiredFiles: List<String>,
        val warnings: List<String> = emptyList(),
        val errors: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** 校验模型目录；结果由调用方决定是否允许 native 加载。 */
    fun validate(modelDir: File): ModelValidationResult {
        val root = modelDir.canonicalFile
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!root.isDirectory) {
            return ModelValidationResult(
                valid = false,
                modelFingerprint = "",
                requiredFiles = emptyList(),
                errors = listOf("模型目录不存在: $root"),
            )
        }

        val configFile = File(root, "config.json")
        val llmConfigFile = File(root, "llm_config.json")
        val referenced = mutableSetOf<String>()
        val optionalReferenced = mutableSetOf<String>()

        // 1. config.json 必须存在且 JSON 可解析。
        if (!configFile.exists()) {
            errors += "缺少 config.json"
        } else {
            parseJsonObject(configFile)?.let { obj ->
                collectReferencedPaths(obj, root, referenced, optionalReferenced, errors)
            } ?: run { errors += "config.json 不是合法 JSON" }
        }

        // 2. llm_config.json 若存在则必须可解析（可选文件）。
        if (llmConfigFile.exists()) {
            if (parseJsonObject(llmConfigFile) == null) {
                errors += "llm_config.json 不是合法 JSON"
            }
        }

        // 3. 必需文件 = 默认集 + config 引用；逐项校验（缺失即错误）。
        val required = (DEFAULT_REQUIRED + referenced).distinct()
        for (rel in required) {
            validateRequiredFile(File(root, rel), rel, root, errors, warnings)
        }
        // 3a. tokenizer 兜底：config 未显式引用 tokenizer 时，要求 tokenizer.txt / tokenizer.mtok
        //     / tokenizer.bin 至少一个存在（不同 MNN 模型 tokenizer 文件名不同，硬卡 tokenizer.txt
        //     会误报「缺少必需文件」）。解析出的实际 tokenizer 一并计入 requiredFiles / 指纹。
        val tokenizerFromConfig = required.firstOrNull { isTokenizerName(it) }
        val tokenizerPresent: String?
        if (tokenizerFromConfig != null) {
            // config 已引用 tokenizer：主循环已校验存在/为空。
            tokenizerPresent = tokenizerFromConfig
        } else {
            val existing = TOKENIZER_CANDIDATES
                .map { File(root, it) }
                .firstOrNull { it.exists() }
            when {
                existing == null -> {
                    errors += "缺少必需 tokenizer 文件（tokenizer.txt 或 tokenizer.mtok）"
                    tokenizerPresent = null
                }
                existing.length() <= 0L -> {
                    errors += "tokenizer 文件为空: ${existing.name}"
                    tokenizerPresent = null
                }
                else -> tokenizerPresent = existing.name
            }
        }
        // 3b. 可选多模态文件（visual/audio）：缺失仅告警，不阻止加载纯文本模型。
        for (rel in optionalReferenced) {
            if (!File(root, rel).exists()) {
                warnings += "可选多模态文件缺失: $rel"
            }
        }

        // 4. 分片残留 -> 警告（下载未完成/合并失败）。
        val parts = root.listFiles { f -> PART_SUFFIX.containsMatchIn(f.name) }?.toList() ?: emptyList()
        if (parts.isNotEmpty()) {
            warnings += "存在 ${parts.size} 个未合并分片（.partN）"
        }

        // 5. 模型指纹：文件清单（名 + 大小 + mtime）规范化哈希（含实际 tokenizer，保证切换
        //     tokenizer 文件名后健康记录失效、重新探测）。
        val requiredFiles = required.toMutableList()
        if (tokenizerPresent != null && tokenizerPresent !in requiredFiles) requiredFiles += tokenizerPresent
        val fingerprint = DeviceRuntimeFingerprint.computeModel(
            requiredFiles
                .filter { File(root, it).exists() }
                .associate { it to fileIdentity(File(root, it)) },
        )

        return ModelValidationResult(
            valid = errors.isEmpty(),
            modelFingerprint = fingerprint,
            requiredFiles = requiredFiles,
            warnings = warnings,
            errors = errors,
        )
    }

    private fun validateRequiredFile(
        file: File, rel: String, root: File, errors: MutableList<String>, warnings: MutableList<String>,
    ) {
        val canonical = runCatching { file.canonicalFile }.getOrNull()
        if (canonical == null || !canonical.path.startsWith(root.path + File.separator)) {
            errors += "路径逃逸模型目录: $rel"
            return
        }
        if (PART_SUFFIX.containsMatchIn(file.name)) {
            errors += "必需文件仍是分片: $rel"
            return
        }
        if (!file.exists()) {
            errors += "缺少必需文件: $rel"
            return
        }
        if (file.length() <= 0L) {
            errors += "必需文件为空: $rel"
        }
        if (file.name.endsWith(".json") && parseJsonObject(file) == null) {
            errors += "JSON 文件不可解析: $rel"
        }
    }

    private fun parseJsonObject(file: File): JsonObject? = try {
        json.parseToJsonElement(file.readText()).jsonObject
    } catch (e: Exception) {
        null
    }

    /** 递归收集 config 中引用为相对路径的字符串值；拒绝逃逸 root。visual/audio 视为可选多模态。 */
    private fun collectReferencedPaths(
        element: kotlinx.serialization.json.JsonElement,
        root: File,
        referenced: MutableSet<String>,
        optionalReferenced: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (value is JsonPrimitive && value.isString) {
                    val s = value.content.trim()
                    val looksLikePath = key.contains("path", ignoreCase = true) ||
                        REFERENCED_EXTENSIONS.any { s.endsWith(it, ignoreCase = true) }
                    if (looksLikePath && s.isNotEmpty()) {
                        val optional = key.contains("visual", ignoreCase = true) ||
                            key.contains("audio", ignoreCase = true)
                        val target = if (optional) optionalReferenced else referenced
                        target += s
                        val resolved = File(root, s).canonicalFile
                        if (!resolved.path.startsWith(root.path + File.separator)) {
                            errors += "config 引用路径逃逸模型目录: $s"
                        }
                    }
                } else {
                    collectReferencedPaths(value, root, referenced, optionalReferenced, errors)
                }
            }
            is JsonArray -> element.forEach { collectReferencedPaths(it, root, referenced, optionalReferenced, errors) }
            else -> {}
        }
    }

    private fun fileIdentity(file: File): String = "${file.length()}:${file.lastModified()}"

    /** 是否为 tokenizer 文件（文件名含 tokenizer，兼容 tokenizer.txt / tokenizer.mtok / tokenizer.bin 等）。 */
    private fun isTokenizerName(name: String): Boolean = name.lowercase().contains("tokenizer")
}
