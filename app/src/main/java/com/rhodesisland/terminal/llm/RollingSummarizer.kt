package com.rhodesisland.terminal.llm

import android.util.Log
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.local.ChatDao
import com.rhodesisland.terminal.data.local.ConversationDao

/**
 * 滚动摘要折叠编排器（单聊云端）。
 *
 * 触发点：回复落库后由 ViewModel 后台协程调用 [foldIfDue]。达到阈值时取最旧一批
 * 未摘要原文连同旧摘要交给 [completeSummary]（云端模型），清洗后与新水位线一并写回；
 * 任何失败静默放弃——聊天主路径永不因摘要阻塞，下次跨阈值再试。
 *
 * 依赖以最小面注入（DAO 接口 + 两个函数）：[canAttempt] 为云端可用性闸门（如当前活跃
 * Provider 非 CLOUD 时关闸）；[completeSummary] 由容器层胶水接 DirectLlmClient.chatOnce。
 */
class RollingSummarizer(
    private val conversationDao: ConversationDao,
    private val chatDao: ChatDao,
    private val canAttempt: suspend () -> Boolean,
    /** 每次折叠的原文条数（用户可调，设置页「每 N 条压缩一次」）；调用时读取，即时生效。 */
    private val foldBatchProvider: suspend () -> Int,
    private val completeSummary: suspend (prompt: String) -> String,
) {

    /** 同一会话进行中的折叠（去重；跨会话互不阻塞）。 */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /**
     * 若会话未摘要原文超过阈值则执行一次折叠。返回是否完成了一次写回。
     * 绝不上抛模型/存储异常（内部吞并记日志）。
     */
    suspend fun foldIfDue(conversationId: Long): Boolean {
        if (!inFlight.add(conversationId)) return false // 同会话已在折叠中：让路
        try {
            if (!canAttempt()) return false
            val foldBatch = RollingSummary.coerceBatch(foldBatchProvider())
            // 特殊邂逅会话永久跳过：其活跃历史在归档表（id 是另一套序列），chat_history 行只是
            // v12 迁移留下的冻结审计副本——对它折叠会把归档 id 写进水位线、与发送路径错位。
            val isEventShell = runCatching { conversationDao.isSpecialEventShell(conversationId) }
                .getOrDefault(false)
            if (isEventShell) return false
            val conv = runCatching { conversationDao.getById(conversationId) }.getOrNull() ?: return false
            val watermark = conv.summarizedUpToMessageId ?: 0L
            val unfolded = chatDao.countUnfolded(conversationId, watermark)
            if (!RollingSummary.shouldFold(unfolded, foldBatch)) return false

            // 取最旧一批原文（id 升序=时间序）；批次为空属防御分支（阈值已过却无行，异常态直接放弃）
            val batch = chatDao.getUnfoldedOldestBatch(
                conversationId, watermark, foldBatch,
            )
            if (batch.isEmpty()) return false

            // 原文行带中性角色标签（单聊仅两方）；图片/附件消息的内容文本本身即正文
            val lines = batch.map { msg ->
                val speaker = if (msg.role == "user") "博士" else "AI"
                "$speaker：${msg.content.trim()}"
            }
            val prompt = RollingSummary.buildFoldPrompt(conv.summaryText, lines)

            val raw = try {
                completeSummary(prompt)
            } catch (e: Exception) {
                Log.w(TAG, "滚动摘要调用失败（静默放弃，下次跨阈值再试）conv=$conversationId", e)
                return false
            }
            val newSummary = RollingSummary.sanitizeSummary(raw)
            if (newSummary.isBlank()) {
                Log.w(TAG, "滚动摘要输出为空白（think-only 或空回包），不推进水位线 conv=$conversationId")
                return false
            }

            val newWatermark = batch.last().id
            return try {
                conversationDao.updateSummary(conversationId, newSummary, newWatermark) > 0
            } catch (e: Exception) {
                Log.w(TAG, "滚动摘要写回失败 conv=$conversationId", e)
                false
            }
        } finally {
            inFlight.remove(conversationId)
        }
    }

    companion object {
        private const val TAG = "RollingSummarizer"
    }
}
