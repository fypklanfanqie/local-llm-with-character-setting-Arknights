package com.rhodesisland.terminal.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SeedanceVideoWorker] 唯一工作/恢复接线契约测试（androidTest，CI-only，work-testing）。
 *
 * 覆盖：唯一工作名格式、WorkData 仅含 localTaskId（不落密钥/提示词/图片）、
 * Worker 对缺失任务幂等 success（走真实 RhodesApp 容器 + Room）。
 */
@RunWith(AndroidJUnit4::class)
class SeedanceVideoWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun uniqueWorkNameIsPerTaskId() {
        assertEquals("seedance-video-7", SeedanceVideoScheduler.uniqueWorkName(7L))
        assertEquals("seedance-video-42", SeedanceVideoScheduler.uniqueWorkName(42L))
    }

    @Test
    fun workDataContainsOnlyLocalTaskId() {
        val request = SeedanceVideoScheduler.buildRequest(taskId = 42L, delayMillis = 0L)
        val keys = request.workSpec.input.keyValueMap.keys
        assertEquals(setOf(SeedanceVideoScheduler.KEY_LOCAL_TASK_ID), keys)
        assertEquals(42L, request.workSpec.input.getLong(SeedanceVideoScheduler.KEY_LOCAL_TASK_ID, -1L))
    }

    @Test
    fun workerWithNegativeTaskIdReturnsSuccess() = runBlocking {
        val worker = TestListenableWorkerBuilder<SeedanceVideoWorker>(context)
            .setInputData(workDataOf(SeedanceVideoScheduler.KEY_LOCAL_TASK_ID to -1L))
            .build()
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun workerReturnsSuccessForMissingTask() = runBlocking {
        // Long.MAX_VALUE 不可能存在：advance -> getById null -> Complete -> success。
        val worker = TestListenableWorkerBuilder<SeedanceVideoWorker>(context)
            .setInputData(workDataOf(SeedanceVideoScheduler.KEY_LOCAL_TASK_ID to Long.MAX_VALUE))
            .build()
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
    }
}
