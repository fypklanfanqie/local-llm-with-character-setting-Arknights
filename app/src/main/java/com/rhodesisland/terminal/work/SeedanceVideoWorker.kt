package com.rhodesisland.terminal.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rhodesisland.terminal.RhodesApp
import com.rhodesisland.terminal.video.PipelineOutcome

/**
 * Seedance 视频流水线 Worker。
 *
 * WorkData 仅携带 `localTaskId`（[SeedanceVideoScheduler.KEY_LOCAL_TASK_ID]），不落任何密钥/提示词/图片。
 * 所有路径都以 success 返回并自行按 [PipelineOutcome] 重排（有界退避/轮询），避免 WorkManager
 * 指数退避风暴；真正等待用户的失败态返回 success 后不再调度。
 *
 * 不创建通知渠道、不起前台服务；唯一工作名 `seedance-video-{localTaskId}` 由调度器统一管理。
 */
class SeedanceVideoWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(SeedanceVideoScheduler.KEY_LOCAL_TASK_ID, -1L)
        if (taskId <= 0) return Result.success()

        val container = (applicationContext as RhodesApp).container
        val outcome = container.seedancePipelineCoordinator.advance(taskId)
        return when (outcome) {
            is PipelineOutcome.Complete, is PipelineOutcome.WaitingForUser -> Result.success()
            is PipelineOutcome.Reschedule -> {
                container.seedanceVideoScheduler.enqueueDelayed(taskId, outcome.delayMillis)
                Result.success()
            }
        }
    }
}
