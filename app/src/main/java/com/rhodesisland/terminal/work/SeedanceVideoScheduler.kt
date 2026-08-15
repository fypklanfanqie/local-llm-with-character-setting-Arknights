package com.rhodesisland.terminal.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rhodesisland.terminal.video.SeedancePipelineCoordinator
import com.rhodesisland.terminal.video.SeedancePipelineStore
import java.util.concurrent.TimeUnit

/**
 * Seedance 视频流水线调度器。
 *
 * - [enqueue]：以唯一名 `seedance-video-{localTaskId}` + KEEP 语义入队（初始调度 / 启动恢复）。
 * - [enqueueDelayed]：REPLACE 语义的自延续重排（Worker 内 Reschedule 用，见 GreetingScheduler 的
 *   scheduleNext 模式：运行中的工作用 REPLACE 才能追加下一次）。
 * - [recoverPending]：启动时复位进程中断残留的进行中状态，再重新入队所有可自动认领且退避到期的任务。
 *
 * 约束 `NetworkType.CONNECTED`；WorkData 仅含 [KEY_LOCAL_TASK_ID]。
 */
class SeedanceVideoScheduler(
    private val context: Context,
    private val coordinator: SeedancePipelineCoordinator,
    private val store: SeedancePipelineStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 立即入队（KEEP：若已有同名工作则不重复创建）。 */
    fun enqueue(taskId: Long) = enqueue(context, taskId, 0L, ExistingWorkPolicy.KEEP)

    /** 延迟重排（REPLACE：Worker 自延续，见 [SeedanceVideoWorker]）。 */
    fun enqueueDelayed(taskId: Long, delayMillis: Long) =
        enqueue(context, taskId, delayMillis.coerceAtLeast(0L), ExistingWorkPolicy.REPLACE)

    /**
     * 启动恢复：复位残留进行中状态（PROMPTING->PROMPT_PENDING、DOWNLOADING->DOWNLOAD_PENDING、
     * SUBMITTING->FAILED_SUBMISSION/AMBIGUOUS_POST），再重新入队可自动认领的任务。幂等。
     */
    suspend fun recoverPending() {
        coordinator.normalizeStaleInProgress()
        store.listRecoverable(clock()).forEach { task -> enqueue(task.id) }
    }

    companion object {
        /** WorkData 键：本地任务主键（唯一入参）。 */
        const val KEY_LOCAL_TASK_ID = "localTaskId"

        /** 每任务的唯一工作名。 */
        fun uniqueWorkName(taskId: Long): String = "seedance-video-$taskId"

        /** 构造一个带网络约束、仅携带 [KEY_LOCAL_TASK_ID] 的一次性请求。 */
        fun buildRequest(taskId: Long, delayMillis: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SeedanceVideoWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setInputData(workDataOf(KEY_LOCAL_TASK_ID to taskId))
                .apply { if (delayMillis > 0) setInitialDelay(delayMillis, TimeUnit.MILLISECONDS) }
                .build()

        /** 入队（KEEP 或 REPLACE）。 */
        fun enqueue(context: Context, taskId: Long, delayMillis: Long, policy: ExistingWorkPolicy) {
            val request = buildRequest(taskId, delayMillis)
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName(taskId), policy, request)
        }
    }
}
