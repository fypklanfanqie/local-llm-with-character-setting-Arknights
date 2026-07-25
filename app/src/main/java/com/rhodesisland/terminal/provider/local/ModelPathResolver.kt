package com.rhodesisland.terminal.provider.local

import android.content.Context
import com.rhodesisland.terminal.data.model.ModelFormat
import java.io.File

/**
 * 模型路径解析（MNN）
 * 模型存储目录：Android/data/<package>/files/models/
 *
 * 存储形态：MNN 目录 `<id>/`，内含 `config.json` + `llm.mnn`（+ `llm.mnn.weight`
 * + `embeddings_bf16.bin` + `tokenizer.txt`，大权重可能为 `.partN` 分片，由
 * [com.rhodesisland.terminal.download.FileSplitter] 合并）。MNN 后端加载时传入目录里的
 * `config.json` 路径。
 */
object ModelPathResolver {

    /** MNN 模型目录内 MNN 引擎入口配置文件名 */
    const val MNN_CONFIG_FILE = "config.json"

    /** MNN 模型目录内模型图文件名（存在即视为有效 MNN 模型） */
    const val MNN_MODEL_FILE = "llm.mnn"

    /**
     * 获取模型存储目录
     * Android/data/com.rhodesisland.terminal/files/models/
     */
    fun getModelsDirectory(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ===== MNN（目录）=====

    /** 获取 MNN 模型目录（不论是否存在）：`<models>/<modelId>/` */
    fun getModelDir(context: Context, modelId: String): File =
        File(getModelsDirectory(context), modelId)

    /**
     * 获取 MNN 模型的 `config.json` 绝对路径（MNN 引擎入口）。
     * @return 存在且目录内含 `llm.mnn` 时返回 config.json 路径，否则 null
     */
    fun getConfigPath(context: Context, modelId: String): String? {
        val dir = getModelDir(context, modelId)
        if (!dir.isDirectory) return null
        val config = File(dir, MNN_CONFIG_FILE)
        val model = File(dir, MNN_MODEL_FILE)
        // config.json 与 llm.mnn 都在才算完整 MNN 模型（避免半下载目录误判）
        return if (config.exists() && model.exists()) config.absolutePath else null
    }

    /** MNN 模型目录是否已完整安装（含 config.json + llm.mnn） */
    fun isMnnModelInstalled(context: Context, modelId: String): Boolean =
        getConfigPath(context, modelId) != null

    // ===== 通用：按 ID 判定已安装 =====

    /**
     * 探测某 modelId 是否已安装（MNN 目录含 llm.mnn）。
     * @return 已安装返回 [ModelFormat.MNN]；未安装返回 null
     */
    fun getInstalledFormat(context: Context, modelId: String): ModelFormat? =
        if (isMnnModelInstalled(context, modelId)) ModelFormat.MNN else null

    /**
     * 取已安装模型的「加载入口路径」：MNN `config.json` 绝对路径。
     * @return 未安装返回 null
     */
    fun getLoadPath(context: Context, modelId: String): String? = getConfigPath(context, modelId)
}
