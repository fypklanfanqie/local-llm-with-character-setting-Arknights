package com.rhodesisland.terminal.video

import android.content.Context
import android.graphics.BitmapFactory
import com.rhodesisland.terminal.data.model.Character
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 待探测的图片来源：本地文件，或一个可重复打开的输入流（assets / content URI）。
 * 流式来源用 [ProbeSource.FromStream.openStream] 而非直接持有 InputStream：探测与复制各需独立打开一次。
 */
sealed interface ProbeSource {
    /** 本地文件路径。 */
    data class FromFile(val file: File) : ProbeSource

    /** 可重复打开的输入流；打开失败（文件缺失/无权限）应返回 null。 */
    data class FromStream(val openStream: () -> InputStream?) : ProbeSource
}

/**
 * 图片元信息（不加载完整位图）：MIME 类型、像素宽高与字节大小。
 */
data class ProbeResult(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
)

/**
 * 图片探测接口：生产实现 [AndroidImageProbe]（BitmapFactory 边界解码 + 魔数嗅探）；
 * JVM 测试注入假实现（无 Android 依赖）。
 */
fun interface ImageProbe {
    /** 探测失败（文件缺失/无法解码/非图片）返回 null。 */
    fun probe(source: ProbeSource): ProbeResult?
}

/** Seedance 参考图支持 MIME -> 目标扩展名（官方 V1 子集：heic/heif 等一律拒绝）。 */
internal val SUPPORTED_SEEDANCE_MIME_EXT: Map<String, String> = mapOf(
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/webp" to "webp",
    "image/bmp" to "bmp",
    "image/gif" to "gif",
)

/** Seedance 参考图官方约束：短边像素下限（不含）。 */
internal const val REFERENCE_MIN_SIDE_PX = 300

/** Seedance 参考图官方约束：长边像素上限（不含）。 */
internal const val REFERENCE_MAX_SIDE_PX = 6000

/** Seedance 参考图官方约束：宽高比下限（含）。 */
internal const val REFERENCE_MIN_ASPECT = 0.4

/** Seedance 参考图官方约束：宽高比上限（含）。 */
internal const val REFERENCE_MAX_ASPECT = 2.5

/** Seedance 参考图官方约束：最大字节数（30MB，不含）。 */
internal const val REFERENCE_MAX_BYTES = 30L * 1024 * 1024

/**
 * 校验探测结果是否符合 Seedance 参考图官方约束；违规返回中文原因，通过返回 null。
 *
 * 约束：支持 MIME 子集（jpeg/png/webp/bmp/gif）、单边像素 >300 且 <6000、
 * 宽高比 0.4–2.5、大小 <30MB。heic/heif 按官方 V1 子集拒绝（识别后落到 MIME 分支）。
 */
internal fun validateReferenceImage(probe: ProbeResult, label: String): String? {
    if (probe.mimeType !in SUPPORTED_SEEDANCE_MIME_EXT) {
        return "${label}格式不支持（仅支持 JPG/PNG/WebP/BMP/GIF）"
    }
    val minSide = minOf(probe.width, probe.height)
    val maxSide = maxOf(probe.width, probe.height)
    if (minSide <= REFERENCE_MIN_SIDE_PX || maxSide >= REFERENCE_MAX_SIDE_PX) {
        return "${label}单边像素需大于 $REFERENCE_MIN_SIDE_PX 且小于 $REFERENCE_MAX_SIDE_PX"
    }
    val aspect = probe.width.toDouble() / probe.height.toDouble()
    if (aspect < REFERENCE_MIN_ASPECT || aspect > REFERENCE_MAX_ASPECT) {
        return "${label}宽高比需在 $REFERENCE_MIN_ASPECT-$REFERENCE_MAX_ASPECT 之间"
    }
    if (probe.byteSize >= REFERENCE_MAX_BYTES) {
        return "${label}不能超过 30MB"
    }
    return null
}

/**
 * Seedance 任务参考图不可变快照。
 *
 * [characterPath]/[backgroundPath] 为任务专属目录 `{targetRoot}/{taskUuid}/references/` 下的
 * 绝对路径；MIME 与 SHA-256 供后续提交/校验使用。背景可选，未配置时三个 background 字段为 null。
 */
data class SeedanceReferenceSnapshot(
    val characterPath: String,
    val characterMime: String,
    val characterSha256: String,
    val backgroundPath: String?,
    val backgroundMime: String?,
    val backgroundSha256: String?,
)

/** 单张参考图复制结果（内部值对象）。 */
private data class CopiedReference(val path: String, val mime: String, val sha256: String)

/**
 * Seedance 任务参考图快照仓库（不可变任务级复制）。
 *
 * Worker 在任务处于 SNAPSHOT_PENDING 时调用 [snapshot]，把 outbox 记录的角色图/背景图来源复制到
 * `{targetRoot}/{taskUuid}/references/`：
 *  - 角色图：内置角色解析 assets 相对路径（[openAssetStream]），自定义角色解析 `file://` 内部路径；
 *  - 背景图可选，来自全局背景内部路径（[com.rhodesisland.terminal.video.SeedanceSceneStore]）。
 *
 * 复制文件**永不静默覆盖**：目标已存在时校验 SHA-256，一致则复用、不一致直接失败（Result.failure，
 * 调用方将任务置为 FAILED_SNAPSHOT，不回滚聊天回复）。复制成功并返回路径/哈希后，调用方才能把任务
 * 推进到 PROMPT_PENDING。后续角色或全局背景被修改/删除不影响已复制成功的任务快照。
 *
 * 测试装配：构造器注入 [targetRoot] 与 [ImageProbe]，纯 JVM 可测；生产用 [production]。
 */
class SeedanceReferenceStore(
    private val targetRoot: File,
    private val imageProbe: ImageProbe,
    private val openAssetStream: (assetPath: String) -> InputStream?,
) {

    companion object {
        /**
         * 生产装配：目标根目录 `filesDir/seedance/tasks`，内置立绘从 assets 打开，
         * 探测用 [AndroidImageProbe]（BitmapFactory 边界解码 + 魔数嗅探）。
         */
        fun production(context: Context): SeedanceReferenceStore = SeedanceReferenceStore(
            targetRoot = File(context.filesDir, "seedance/tasks"),
            imageProbe = AndroidImageProbe(),
            openAssetStream = { assetPath -> runCatching { context.assets.open(assetPath) }.getOrNull() },
        )
    }

    /**
     * 为任务 [taskUuid] 生成不可变参考图快照。
     *
     * @param character         当前角色（自定义角色取其 [Character.image] 的 `file://` 内部路径）。
     * @param builtInAssetPath  内置角色的 assets 相对路径（如 "characters/neighbor.webp"）；自定义角色传 null。
     * @param backgroundImagePath 全局背景内部路径（可选；空串/null 表示无背景）。
     */
    suspend fun snapshot(
        taskUuid: String,
        character: Character,
        builtInAssetPath: String?,
        backgroundImagePath: String?,
    ): Result<SeedanceReferenceSnapshot> = withContext(Dispatchers.IO) {
        if (taskUuid.isBlank()) {
            return@withContext Result.failure(IllegalStateException("任务标识为空，无法生成参考图快照"))
        }
        val refsDir = File(File(targetRoot, taskUuid), "references")

        // ===== 角色图（必填）=====
        val characterSource: ProbeSource = if (character.isCustom) {
            val image = character.image
            if (image.isBlank()) {
                return@withContext Result.failure(IllegalStateException("该角色未设置立绘图片，无法生成视频"))
            }
            val file = imageSourceToFile(image)
                ?: return@withContext Result.failure(IllegalStateException("角色立绘图片路径无法识别"))
            ProbeSource.FromFile(file)
        } else {
            val assetPath = builtInAssetPath
            if (assetPath.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("缺少角色立绘图片"))
            }
            ProbeSource.FromStream { openAssetStream(normalizeAssetPath(assetPath)) }
        }

        val characterCopy = copyReference(characterSource, "角色立绘图片", refsDir, "character")
            .getOrElse { e -> return@withContext Result.failure(e) }

        // ===== 背景图（可选）=====
        val backgroundCopy = backgroundImagePath
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                copyReference(ProbeSource.FromFile(File(path)), "背景图", refsDir, "background")
                    .getOrElse { e -> return@withContext Result.failure(e) }
            }

        return@withContext Result.success(
            SeedanceReferenceSnapshot(
                characterPath = characterCopy.path,
                characterMime = characterCopy.mime,
                characterSha256 = characterCopy.sha256,
                backgroundPath = backgroundCopy?.path,
                backgroundMime = backgroundCopy?.mime,
                backgroundSha256 = backgroundCopy?.sha256,
            )
        )
    }

    /**
     * 探测 -> 校验 -> 复制。目标已存在时校验 SHA-256：一致则复用、不一致则失败。
     * 复制先写入 `.tmp` 再原子改名，避免半写文件被误当作有效快照。
     */
    private fun copyReference(
        source: ProbeSource,
        label: String,
        targetDir: File,
        targetName: String,
    ): Result<CopiedReference> {
        val probe = try {
            imageProbe.probe(source)
        } catch (e: Exception) {
            null
        } ?: return Result.failure(IllegalStateException("${label}无法读取（文件可能已删除或损坏）"))
        validateReferenceImage(probe, label)?.let { message ->
            return Result.failure(IllegalStateException(message))
        }
        val ext = SUPPORTED_SEEDANCE_MIME_EXT.getValue(probe.mimeType)
        return try {
            targetDir.mkdirs()
            val target = File(targetDir, "$targetName.$ext")
            val tmp = File(targetDir, "$targetName.$ext.tmp")
            val sourceSha = writeSourceTo(source, tmp)

            val existingSha = if (target.exists()) sha256Of(target) else null
            if (existingSha != null) {
                tmp.delete()
                if (existingSha == sourceSha) {
                    Result.success(CopiedReference(target.absolutePath, probe.mimeType, existingSha))
                } else {
                    Result.failure(IllegalStateException("${label}快照文件已存在且内容不一致，无法安全覆盖"))
                }
            } else {
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    Result.failure(IllegalStateException("${label}快照写入失败（磁盘可能已满）"))
                } else {
                    Result.success(CopiedReference(target.absolutePath, probe.mimeType, sourceSha))
                }
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("${label}快照写入失败：${e.message ?: "未知错误"}"))
        }
    }

    /** 打开来源并写入目标文件，边写边计算 SHA-256。 */
    private fun writeSourceTo(source: ProbeSource, target: File): String {
        val stream = when (source) {
            is ProbeSource.FromFile -> {
                if (!source.file.isFile) throw IllegalStateException("源文件不存在")
                source.file.inputStream()
            }
            is ProbeSource.FromStream -> source.openStream() ?: throw IllegalStateException("无法打开来源数据流")
        }
        stream.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }
}

/** 字节数组 SHA-256 -> 小写十六进制。 */
private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * 解析自定义角色立绘来源（`file://` URI 或普通绝对路径）；无法识别返回 null。
 * 纯 JVM 实现（不依赖 android.net.Uri），保证 JVM 单测可跑。
 */
internal fun imageSourceToFile(image: String): File? {
    val rawPath = when {
        image.startsWith("file://") -> image.substring(7).substringBefore('?').substringBefore('#')
        image.startsWith("file:") -> image.substring(5).substringBefore('?').substringBefore('#')
        else -> {
            // 其它 scheme（http/content）或空串：V1 不支持从网络/SAF 直接复制
            if (image.contains(':')) return null
            image
        }
    }
    if (rawPath.isBlank()) return null
    val file = File(rawPath)
    if (file.exists()) return file
    // Uri.fromFile 产物可能带百分号转义（空格等），解码重试一次
    return runCatching { File(java.net.URLDecoder.decode(rawPath, "UTF-8")) }.getOrNull()
}

/**
 * 归一化内置立绘来源为 assets 相对路径：兼容 `file:///android_asset/...` / `asset://...` /
 * 裸相对路径三种形态（AssetRepository.getPicture 可能返回 CDN URL，此时 assets 打开失败 -> 探测 null）。
 */
internal fun normalizeAssetPath(source: String): String = when {
    source.startsWith("file:///android_asset/") -> source.removePrefix("file:///android_asset/")
    source.startsWith("asset:///") -> source.removePrefix("asset:///")
    source.startsWith("asset://") -> source.removePrefix("asset://")
    else -> source
}

/**
 * 生产图片探测：BitmapFactory 边界解码（只读头部，不缓冲完整位图）获取尺寸/编码 MIME +
 * 魔数嗅探确定真实 MIME（BitmapFactory 无法解析的 heic/heif 也能被识别并交给校验层拒绝）。
 * 字节大小**从不整读内存**：文件源用 File.length()；流式源用有界缓冲计数，超过 30MB 上限即提前终止，
 * 返回上限值触发校验层“不能超过 30MB”拒绝，而不是继续读满整个大文件（避免 OOM）。
 */
class AndroidImageProbe : ImageProbe {
    override fun probe(source: ProbeSource): ProbeResult? {
        // 字节大小：文件直接取长度；流式源有界计数，超过上限提前终止。
        val byteSize = when (source) {
            is ProbeSource.FromFile -> {
                if (!source.file.isFile) return null
                source.file.length()
            }
            is ProbeSource.FromStream -> countStreamBytes(source) ?: return null
        }

        // 尺寸：边界解码（仅头部，不缓冲完整位图），每次用全新流。
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val decodedMime = try {
            when (source) {
                is ProbeSource.FromFile -> source.file.inputStream().use {
                    BitmapFactory.decodeStream(it, null, options); options.outMimeType
                }
                is ProbeSource.FromStream -> source.openStream()?.use {
                    BitmapFactory.decodeStream(it, null, options); options.outMimeType
                }
            }
        } catch (e: Exception) {
            null
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        // MIME：魔数嗅探优先（只需头部若干字节），回退到 BitmapFactory 识别结果。
        val mime = sniffHeaderMime(source) ?: decodedMime ?: return null
        return ProbeResult(
            mimeType = mime,
            width = options.outWidth,
            height = options.outHeight,
            byteSize = byteSize,
        )
    }

    /** 流式源字节计数：达到/超过 30MB 上限立即终止并返回上限值，不再读满全量。 */
    private fun countStreamBytes(source: ProbeSource.FromStream): Long? {
        val stream = source.openStream() ?: return null
        return stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total >= REFERENCE_MAX_BYTES) return@use REFERENCE_MAX_BYTES
            }
            total
        }
    }

    /** 读取头部少量字节做魔数嗅探（文件源/流式源各自独立打开）。 */
    private fun sniffHeaderMime(source: ProbeSource): String? {
        val header = try {
            when (source) {
                is ProbeSource.FromFile -> source.file.inputStream().use { readUpTo(it, 32) }
                is ProbeSource.FromStream -> source.openStream()?.use { readUpTo(it, 32) }
            }
        } catch (e: Exception) {
            null
        } ?: return null
        return sniffMime(header)
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray? {
        val buffer = ByteArray(max)
        var count = 0
        while (count < max) {
            val n = input.read(buffer, count, max - count)
            if (n < 0) break
            count += n
        }
        return if (count == 0) null else buffer.copyOf(count)
    }

    companion object {
        /** 魔数嗅探：仅识别 Seedance V1 支持集 + heic/heif（识别后由校验层拒绝）。 */
        internal fun sniffMime(bytes: ByteArray): String? = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
            bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
            bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"
            bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte() -> "image/gif"
            bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() && bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() -> when {
                bytes[8] == 'h'.code.toByte() && bytes[9] == 'e'.code.toByte() -> "image/heic"
                bytes[8] == 'h'.code.toByte() && bytes[9] == 'i'.code.toByte() -> "image/heif"
                else -> null
            }
            else -> null
        }
    }
}
