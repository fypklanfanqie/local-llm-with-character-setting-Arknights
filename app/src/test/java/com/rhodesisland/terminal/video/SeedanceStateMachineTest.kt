package com.rhodesisland.terminal.video

import com.rhodesisland.terminal.data.model.SeedanceVideoState
import com.rhodesisland.terminal.data.model.SeedanceVideoState.CANCELLED
import com.rhodesisland.terminal.data.model.SeedanceVideoState.CANCEL_REQUESTED
import com.rhodesisland.terminal.data.model.SeedanceVideoState.DOWNLOADING
import com.rhodesisland.terminal.data.model.SeedanceVideoState.DOWNLOAD_PENDING
import com.rhodesisland.terminal.data.model.SeedanceVideoState.EXPIRED
import com.rhodesisland.terminal.data.model.SeedanceVideoState.FAILED_DOWNLOAD
import com.rhodesisland.terminal.data.model.SeedanceVideoState.FAILED_PROMPT
import com.rhodesisland.terminal.data.model.SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED
import com.rhodesisland.terminal.data.model.SeedanceVideoState.FAILED_QUERY
import com.rhodesisland.terminal.data.model.SeedanceVideoState.FAILED_REMOTE
import com.rhodesisland.terminal.data.model.SeedanceVideoState.FAILED_SNAPSHOT
import com.rhodesisland.terminal.data.model.SeedanceVideoState.FAILED_SUBMISSION
import com.rhodesisland.terminal.data.model.SeedanceVideoState.PROMPTING
import com.rhodesisland.terminal.data.model.SeedanceVideoState.PROMPT_PENDING
import com.rhodesisland.terminal.data.model.SeedanceVideoState.QUEUED
import com.rhodesisland.terminal.data.model.SeedanceVideoState.READY
import com.rhodesisland.terminal.data.model.SeedanceVideoState.RUNNING
import com.rhodesisland.terminal.data.model.SeedanceVideoState.SNAPSHOT_PENDING
import com.rhodesisland.terminal.data.model.SeedanceVideoState.SUBMITTING
import com.rhodesisland.terminal.data.model.SeedanceVideoState.SUBMISSION_PENDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seedance 视频任务状态机测试（Task 1）。
 *
 * 合法转换表来自计划「持久化状态机」关键转换 + 中断恢复/取消竞态/失败重试规则；
 * 同时锁定终态、禁止自转/跨阶段跳转，并覆盖存储键往返与未知值保守回落。
 */
class SeedanceStateMachineTest {

    // ---- 合法转换表 ----

    @Test
    fun keyTransitionsFromPlanAreAllowed() {
        // 快照 → 提示词
        assertTrue(canTransition(SNAPSHOT_PENDING, PROMPT_PENDING))
        assertTrue(canTransition(SNAPSHOT_PENDING, FAILED_SNAPSHOT))
        // 提示词流水线（含 Worker 开工配置校验与中断恢复重置）
        assertTrue(canTransition(PROMPT_PENDING, PROMPTING))
        assertTrue(canTransition(PROMPT_PENDING, FAILED_PROMPT_CONFIG_CHANGED))
        assertTrue(canTransition(PROMPTING, SUBMISSION_PENDING))
        assertTrue(canTransition(PROMPTING, PROMPT_PENDING))
        assertTrue(canTransition(PROMPTING, FAILED_PROMPT))
        assertTrue(canTransition(PROMPTING, FAILED_PROMPT_CONFIG_CHANGED))
        // 提交
        assertTrue(canTransition(SUBMISSION_PENDING, SUBMITTING))
        assertTrue(canTransition(SUBMITTING, QUEUED))
        assertTrue(canTransition(SUBMITTING, RUNNING))
        assertTrue(canTransition(SUBMITTING, DOWNLOAD_PENDING))
        assertTrue(canTransition(SUBMITTING, FAILED_SUBMISSION))
        assertTrue(canTransition(SUBMITTING, FAILED_REMOTE))
        // 远端排队/运行
        assertTrue(canTransition(QUEUED, RUNNING))
        assertTrue(canTransition(QUEUED, CANCEL_REQUESTED))
        assertTrue(canTransition(QUEUED, DOWNLOAD_PENDING))
        assertTrue(canTransition(QUEUED, FAILED_REMOTE))
        assertTrue(canTransition(QUEUED, FAILED_QUERY))
        assertTrue(canTransition(QUEUED, EXPIRED))
        assertTrue(canTransition(RUNNING, DOWNLOAD_PENDING))
        assertTrue(canTransition(RUNNING, FAILED_REMOTE))
        assertTrue(canTransition(RUNNING, FAILED_QUERY))
        assertTrue(canTransition(RUNNING, EXPIRED))
        // 取消竞态以服务端状态为准
        assertTrue(canTransition(CANCEL_REQUESTED, CANCELLED))
        assertTrue(canTransition(CANCEL_REQUESTED, RUNNING))
        assertTrue(canTransition(CANCEL_REQUESTED, DOWNLOAD_PENDING))
        assertTrue(canTransition(CANCEL_REQUESTED, FAILED_QUERY))
        // 下载（含中断恢复重置）
        assertTrue(canTransition(DOWNLOAD_PENDING, DOWNLOADING))
        assertTrue(canTransition(DOWNLOAD_PENDING, EXPIRED))
        assertTrue(canTransition(DOWNLOADING, READY))
        assertTrue(canTransition(DOWNLOADING, FAILED_DOWNLOAD))
        assertTrue(canTransition(DOWNLOADING, EXPIRED))
        assertTrue(canTransition(DOWNLOADING, DOWNLOAD_PENDING))
    }

    @Test
    fun failureRetriesFollowPlanRules() {
        assertTrue(canTransition(FAILED_SNAPSHOT, SNAPSHOT_PENDING))
        assertTrue(canTransition(FAILED_PROMPT, PROMPT_PENDING))
        assertTrue(canTransition(FAILED_PROMPT_CONFIG_CHANGED, PROMPT_PENDING))
        assertTrue(canTransition(FAILED_SUBMISSION, SUBMISSION_PENDING))
        assertTrue(canTransition(FAILED_REMOTE, SUBMISSION_PENDING))
        assertTrue(canTransition(EXPIRED, SUBMISSION_PENDING))
        // 查询/下载可自动重试；FAILED_QUERY 复用 remoteTaskId，以服务端状态为准
        assertTrue(canTransition(FAILED_QUERY, QUEUED))
        assertTrue(canTransition(FAILED_QUERY, RUNNING))
        assertTrue(canTransition(FAILED_QUERY, DOWNLOAD_PENDING))
        assertTrue(canTransition(FAILED_DOWNLOAD, DOWNLOAD_PENDING))
        assertTrue(canTransition(FAILED_DOWNLOAD, EXPIRED))
    }

    // ---- 非法转换 ----

    @Test
    fun noStateCanTransitionToItself() {
        for (state in SeedanceVideoState.entries) {
            assertFalse("状态不应原地自转：$state", canTransition(state, state))
        }
    }

    @Test
    fun readyIsTerminalAndNeverLeaves() {
        for (target in SeedanceVideoState.entries) {
            assertFalse("READY 是终态，不应迁出到 $target", canTransition(READY, target))
        }
    }

    @Test
    fun cancelledIsTerminal() {
        for (target in SeedanceVideoState.entries) {
            assertFalse("CANCELLED 是终态，不应迁出到 $target", canTransition(CANCELLED, target))
        }
    }

    @Test
    fun forwardChainNeverReverses() {
        // 注意：PROMPTING→PROMPT_PENDING 与 DOWNLOADING→DOWNLOAD_PENDING 是中断恢复重置，属合法。
        assertFalse(canTransition(PROMPTING, SNAPSHOT_PENDING))
        assertFalse(canTransition(SUBMITTING, SUBMISSION_PENDING))
        assertFalse(canTransition(SUBMITTING, PROMPT_PENDING))
        assertFalse(canTransition(QUEUED, SUBMITTING))
        assertFalse(canTransition(RUNNING, QUEUED))
        assertFalse(canTransition(RUNNING, SUBMITTING))
        assertFalse(canTransition(DOWNLOADING, QUEUED))
        assertFalse(canTransition(EXPIRED, QUEUED))
    }

    @Test
    fun stageSkippingIsRejected() {
        assertFalse(canTransition(SNAPSHOT_PENDING, PROMPTING))
        assertFalse(canTransition(SNAPSHOT_PENDING, SUBMISSION_PENDING))
        assertFalse(canTransition(SNAPSHOT_PENDING, READY))
        assertFalse(canTransition(PROMPT_PENDING, SUBMISSION_PENDING))
        assertFalse(canTransition(PROMPT_PENDING, QUEUED))
        assertFalse(canTransition(SUBMISSION_PENDING, QUEUED))
        assertFalse(canTransition(SUBMISSION_PENDING, RUNNING))
        assertFalse(canTransition(QUEUED, READY))
        assertFalse(canTransition(RUNNING, READY))
        assertFalse(canTransition(DOWNLOAD_PENDING, READY))
        assertFalse(canTransition(DOWNLOADING, CANCELLED))
    }

    @Test
    fun ambiguousSubmissionCannotSkipUserConfirmation() {
        // FAILED_SUBMISSION（AMBIGUOUS_POST）结构上只能回到 SUBMISSION_PENDING，等待用户确认后重新提交。
        assertFalse(canTransition(FAILED_SUBMISSION, SUBMITTING))
        assertFalse(canTransition(FAILED_SUBMISSION, PROMPT_PENDING))
        assertFalse(canTransition(FAILED_SUBMISSION, SNAPSHOT_PENDING))
        // 远端失败/过期同样不得绕过提交待定直接重发。
        assertFalse(canTransition(FAILED_REMOTE, SUBMITTING))
        assertFalse(canTransition(EXPIRED, SUBMITTING))
    }

    @Test
    fun legalTransitionCountMatchesContract() {
        val states = SeedanceVideoState.entries
        val legalCount = states.sumOf { from -> states.count { to -> canTransition(from, to) } }
        assertEquals(45, legalCount)
    }

    // ---- 存储键 ----

    @Test
    fun storageKeysRoundTripEveryState() {
        for (state in SeedanceVideoState.entries) {
            assertEquals(state, SeedanceVideoState.fromStorageKey(state.storageKey))
        }
    }

    @Test
    fun storageKeysAreUnique() {
        val keys = SeedanceVideoState.entries.map { it.storageKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun unknownOrMissingStorageKeyFallsBackConservatively() {
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, SeedanceVideoState.fromStorageKey(null))
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, SeedanceVideoState.fromStorageKey(""))
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, SeedanceVideoState.fromStorageKey("not-a-real-state"))
    }
}
