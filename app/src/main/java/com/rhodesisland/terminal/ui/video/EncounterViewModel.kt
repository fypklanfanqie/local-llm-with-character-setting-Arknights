package com.rhodesisland.terminal.ui.video

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import com.rhodesisland.terminal.data.model.prepareRetry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「邂逅」历史流 ViewModel（Task 9）。
 *
 * 观察全部 Seedance 视频任务（仓库已按 createdAt DESC 排序，最新在前），
 * 供 [EncounterScreen] 的全屏竖滑历史流渲染。任务动作（取消 / 重试 / 继续查询 /
 * 重新下载）委托到与 [com.rhodesisland.terminal.ui.chat.ChatViewModel] 相同的
 * 仓库 + 调度器入口（CAS 认领、状态回映射、入队），**绝不复制** Worker 状态机
 * 与流水线逻辑——真正的推进逻辑仍在 SeedancePipelineCoordinator / Worker 中。
 *
 * 播放与导出停留在屏幕层（Task 8 的 [SeedancePlaybackController] /
 * [com.rhodesisland.terminal.video.SeedanceVideoExporter]），不进入本 ViewModel。
 */
class EncounterViewModel(
    application: Application,
    val container: AppContainer,
) : AndroidViewModel(application) {

    /** 全部视频任务，按创建时间倒序（邂逅历史流）。空列表 = 空状态。 */
    val videos: StateFlow<List<SeedanceVideo>> =
        container.seedanceVideoRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 取消排队中的 Seedance 视频任务（仅 QUEUED 可发起；结果以服务端状态为准）。
     * 与 [com.rhodesisland.terminal.ui.chat.ChatViewModel.cancelVideoTask] 相同的入口。
     */
    fun cancel(taskId: Long) {
        viewModelScope.launch {
            val claimed = container.seedanceVideoRepository.claim(
                taskId,
                SeedanceVideoState.QUEUED,
                SeedanceVideoState.CANCEL_REQUESTED,
            )
            if (claimed) {
                container.seedanceVideoScheduler.enqueue(taskId)
            }
        }
    }

    /**
     * 重试失败/过期的任务：按当前状态映射回 Worker 可自动认领的入口状态后重新入队。
     * 与 [com.rhodesisland.terminal.ui.chat.ChatViewModel.retryVideoTask] 相同的入口。
     * 仅回 SUBMISSION_PENDING（重新生成）时 [prepareRetry] 才归档当前 remoteTaskId 并
     * generationAttempt += 1；继续查询/重新下载复用同一 remoteTaskId 不归档不加次数。
     */
    fun retry(taskId: Long) = retryTask(taskId)

    /** 继续查询远端任务状态（FAILED_QUERY -> QUEUED，Worker 认领后重新查询）。 */
    fun continueQuery(taskId: Long) = retryTask(taskId)

    /** 重新下载成品视频（FAILED_DOWNLOAD -> DOWNLOAD_PENDING，Worker 认领后重新下载）。 */
    fun retryDownload(taskId: Long) = retryTask(taskId)

    private fun retryTask(taskId: Long) {
        viewModelScope.launch {
            val video = container.seedanceVideoRepository.getById(taskId) ?: return@launch
            val entry = retryEntryStateOf(video.state) ?: return@launch
            container.seedanceVideoRepository.update(
                video.prepareRetry(entry).copy(
                    state = entry,
                    errorStage = null,
                    errorCode = null,
                    errorMessage = null,
                    retryDisposition = null,
                    nextRetryAt = null,
                ),
            )
            container.seedanceVideoScheduler.enqueue(taskId)
        }
    }

    /** 失败/过期状态 -> 可自动认领的入口状态（纯函数；非可重试状态返回 null）。
     *  与 ChatViewModel.retryEntryStateOf 保持同一映射（Task 7 验收）。 */
    private fun retryEntryStateOf(state: SeedanceVideoState): SeedanceVideoState? = when (state) {
        SeedanceVideoState.FAILED_SNAPSHOT -> SeedanceVideoState.SNAPSHOT_PENDING
        SeedanceVideoState.FAILED_PROMPT,
        SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED -> SeedanceVideoState.PROMPT_PENDING
        SeedanceVideoState.FAILED_SUBMISSION,
        SeedanceVideoState.FAILED_REMOTE,
        SeedanceVideoState.EXPIRED -> SeedanceVideoState.SUBMISSION_PENDING
        SeedanceVideoState.FAILED_QUERY -> SeedanceVideoState.QUEUED
        SeedanceVideoState.FAILED_DOWNLOAD -> SeedanceVideoState.DOWNLOAD_PENDING
        else -> null
    }
}
