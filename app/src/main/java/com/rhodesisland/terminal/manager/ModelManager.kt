package com.rhodesisland.terminal.manager

import android.content.Context
import android.util.Log
import com.rhodesisland.terminal.data.model.DEFAULT_MNN_MODELS
import com.rhodesisland.terminal.data.model.DownloadState
import com.rhodesisland.terminal.data.model.InstalledModel
import com.rhodesisland.terminal.data.model.ModelInfo
import com.rhodesisland.terminal.data.model.ModelListResponse
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.download.DownloadManager
import com.rhodesisland.terminal.llm.backend.BackendManager
import com.rhodesisland.terminal.provider.local.ModelPathResolver
import kotlinx.coroutines.flow.*

/**
 * 模型管理器
 *
 * 职责：
 * - 模型清单：直接使用内置 [DEFAULT_MNN_MODELS]（**不从网络拉取模型市场**）。
 * - 扫描本地已安装 MNN 模型目录。
 * - 下载 / 删除 / 切换模型（下载层走多文件 + 分片合并）。
 */
class ModelManager(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val settings: SettingsRepository,
    private val backendManager: BackendManager,
) {

    companion object {
        private const val TAG = "ModelManager"
    }

    /** 内置 MNN 清单 + 本地安装状态 */
    private val _modelList = MutableStateFlow(ModelListResponse(models = DEFAULT_MNN_MODELS))
    val modelList: StateFlow<ModelListResponse> = _modelList

    /** 下载状态流（代理 DownloadManager） */
    val downloadStates: StateFlow<Map<String, DownloadState>> = downloadManager.states

    /** 当前选中的本地模型 ID */
    val activeLocalModelId: Flow<String?> = settings.activeLocalModelId

    /**
     * 刷新模型清单：直接使用内置 [DEFAULT_MNN_MODELS]（无网络请求），并同步本地已安装状态。
     */
    suspend fun fetchModelList(): Result<ModelListResponse> {
        _modelList.value = ModelListResponse(models = DEFAULT_MNN_MODELS)
        syncInstalledStates()
        return Result.success(_modelList.value)
    }

    /** 同步本地已安装 MNN 模型到下载状态 */
    private fun syncInstalledStates() {
        val installed = downloadManager.scanInstalledModels()
        for (modelId in installed) {
            val current = downloadManager.getState(modelId)
            if (current is DownloadState.NotDownloaded) {
                val path = ModelPathResolver.getLoadPath(context, modelId)
                if (path != null) {
                    downloadManager.markInstalled(modelId, path)
                }
            }
        }
    }

    /** 获取已安装模型列表 */
    fun getInstalledModels(): List<InstalledModel> {
        val installed = downloadManager.scanInstalledModels()
        return _modelList.value.models
            .filter { it.id in installed }
            .map { info ->
                InstalledModel(
                    info = info,
                    localPath = ModelPathResolver.getLoadPath(context, info.id) ?: "",
                    verified = true,
                )
            }
    }

    /** 开始下载模型（DownloadManager 多文件 + 分片合并） */
    fun download(model: ModelInfo) {
        downloadManager.startDownload(model)
    }

    /** 暂停下载 */
    fun pause(modelId: String) {
        downloadManager.pause(modelId)
    }

    /** 恢复下载 */
    fun resume(model: ModelInfo) {
        downloadManager.resume(model)
    }

    /** 删除模型（MNN 目录）。若为当前已加载模型，先释放 native 句柄。 */
    suspend fun delete(modelId: String) {
        val wasActive = settings.getActiveLocalModelIdNow() == modelId
        // 若删除的正是当前已加载到内存的模型，先释放 MNN 后端 native 句柄（数 GB 内存）。
        if (wasActive) {
            backendManager.release()
        }
        downloadManager.delete(modelId) // 删目录
        if (wasActive) {
            settings.setActiveLocalModelId(null)
        }
    }

    /** 切换当前活跃本地模型 */
    suspend fun setActiveModel(modelId: String?) {
        settings.setActiveLocalModelId(modelId)
    }

    /** 获取下载状态 */
    fun getDownloadState(modelId: String): DownloadState = downloadManager.getState(modelId)
}
