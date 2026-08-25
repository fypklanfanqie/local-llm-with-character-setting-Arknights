package com.rhodesisland.terminal.video

import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import com.rhodesisland.terminal.data.remote.CreateSeedanceTask
import com.rhodesisland.terminal.data.remote.SeedanceApiException
import com.rhodesisland.terminal.data.remote.SeedanceError
import com.rhodesisland.terminal.data.remote.SeedanceImageContent
import com.rhodesisland.terminal.data.remote.SeedanceProtocol
import com.rhodesisland.terminal.data.remote.SeedanceRemoteStatus
import com.rhodesisland.terminal.data.remote.SeedanceTaskResponse
import com.rhodesisland.terminal.data.remote.MEDIA_REFERENCE_MAX_BYTES
import com.rhodesisland.terminal.data.remote.seedanceProtocolFor
import com.rhodesisland.terminal.util.seedanceUserErrorMessage
import com.rhodesisland.terminal.data.repository.SeedanceVideoRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.security.MessageDigest

/**
 * 单次流水线推进结果。
 *
 * - [Complete]：本阶段终结（READY/CANCELLED），无需再调度；
 * - [Reschedule]：需要 [delayMillis] 后再调度一次（轮询/有界重试）；
 * - [WaitingForUser]：进入 FAILED_*、EXPIRED 等需用户确认的终态。
 */
sealed interface PipelineOutcome {
    data object Complete : PipelineOutcome
    data class Reschedule(val delayMillis: Long) : PipelineOutcome
    data object WaitingForUser : PipelineOutcome
}

/**
 * 流水线持久化窄接口（JVM 单测无 Room/Android）。
 *
 * [claim] 是唯一原子原语（UPDATE ... WHERE state=:from）；[transition] 在认领成功后
 * 以 [mutate] 改写整行并写回新状态。
 */
interface SeedancePipelineStore {
    suspend fun getById(id: Long): SeedanceVideo?
    suspend fun claim(id: Long, from: SeedanceVideoState, to: SeedanceVideoState): Boolean
    suspend fun update(video: SeedanceVideo)
    suspend fun transition(
        id: Long,
        from: SeedanceVideoState,
        to: SeedanceVideoState,
        mutate: (SeedanceVideo) -> SeedanceVideo,
    ): Boolean

    suspend fun listRecoverable(now: Long): List<SeedanceVideo>
    suspend fun listByStates(states: Set<SeedanceVideoState>): List<SeedanceVideo>
}

/**
 * 生产装配：把既有 [SeedanceVideoRepository] 适配为 [SeedancePipelineStore]。
 *
 * [transition] 复用仓库的 `claim`（原子 CAS）+ `getById` + `update`：认领成功后本 Worker
 * 独占该阶段，其它 Worker 的 claim 返回 false 即放弃。
 */
class SeedanceRepositoryPipelineStore(
    private val repository: SeedanceVideoRepository,
) : SeedancePipelineStore {
    override suspend fun getById(id: Long): SeedanceVideo? = repository.getById(id)
    override suspend fun claim(id: Long, from: SeedanceVideoState, to: SeedanceVideoState): Boolean =
        repository.claim(id, from, to)
    override suspend fun update(video: SeedanceVideo) = repository.update(video)
    override suspend fun transition(
        id: Long,
        from: SeedanceVideoState,
        to: SeedanceVideoState,
        mutate: (SeedanceVideo) -> SeedanceVideo,
    ): Boolean {
        if (!repository.claim(id, from, to)) return false
        val current = repository.getById(id) ?: return false
        repository.update(mutate(current.copy(state = to)))
        return true
    }

    override suspend fun listRecoverable(now: Long): List<SeedanceVideo> =
        repository.listRecoverable(now)

    override suspend fun listByStates(states: Set<SeedanceVideoState>): List<SeedanceVideo> =
        repository.observeAll().first().filter { it.state in states }
}

/** 远端任务提交（包装 [com.rhodesisland.terminal.data.remote.SeedanceClient]）。 */
interface SeedanceSubmitter {
    suspend fun create(config: SeedanceConfig, request: CreateSeedanceTask): SeedanceTaskResponse
    suspend fun get(config: SeedanceConfig, taskId: String): SeedanceTaskResponse
    suspend fun cancel(config: SeedanceConfig, taskId: String): SeedanceTaskResponse
}

/** 远端视频流（下载后由 [SeedanceVideoFileStore] 消费并关闭）。 */
class SeedanceVideoDownload(
    val mime: String?,
    val contentLength: Long?,
    val stream: InputStream,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() = onClose()
}

/** 视频下载器（打开远端 URL 流；失败返回 null）。 */
fun interface SeedanceVideoDownloader {
    suspend fun download(url: String): SeedanceVideoDownload?
}

/** 参考图快照函数（= [SeedanceReferenceStore.snapshot] 的窄化签名）。 */
fun interface SeedanceReferenceSnapshooter {
    suspend fun snapshot(
        taskUuid: String,
        character: Character,
        builtInAssetPath: String?,
        backgroundImagePath: String?,
    ): Result<SeedanceReferenceSnapshot>
}

/** 快照来源解析结果（把 outbox 来源快照解析为参考图仓库所需入参）。 */
data class SeedanceSnapshotSources(
    val character: Character,
    val builtInAssetPath: String?,
    val backgroundImagePath: String?,
)

/** 快照来源解析：outbox 的 `characterImageSourceSnapshot`/`backgroundImageSourceSnapshot` -> 入参。 */
fun interface SeedanceSnapshotSourceResolver {
    suspend fun resolve(task: SeedanceVideo): SeedanceSnapshotSources?
}

/**
 * 参考图编码：内部文件路径 + MIME -> base64 图片内容。
 * [maxBytes] 为单张图片（base64 解码后）字节上限，由调用方按协议传入
 * （方舟 30MB；中转站媒体协议 10MB）。实现须保证返回内容不超过该上限（必要时重编码压缩）。
 */
fun interface SeedanceImageEncoder {
    suspend fun encode(path: String, mime: String, maxBytes: Long): SeedanceImageContent
}

/** 提示词生成（= [SeedancePromptGenerator.generate] 的窄化签名）。 */
fun interface SeedancePromptProvider {
    suspend fun generate(apiConfig: ApiConfig, input: SeedancePromptInput): SeedancePromptDocument
}

/**
 * 前情对话提供者：按会话取最近若干条消息，格式化为纯文本（不含本次已入参的用户发言/角色回复）。
 * 供提示词生成器理解对话上下文；取不到/出错时返回空串即可，绝不让历史读取阻塞视频流水线。
 */
fun interface SeedanceConversationContextProvider {
    suspend fun recentDialogue(
        conversationId: Long,
        currentUserText: String,
        currentAssistantText: String,
        maxTurns: Int,
    ): String
}

/** 提交歧义错误码（POST 可能已到服务端但未持久化任务 ID，绝不自动重发）。 */
const val ERROR_CODE_AMBIGUOUS_POST = "AMBIGUOUS_POST"

/**
 * 视频签名 URL 预估有效期。volcengine 官方文档：output.video_url 约 24 小时后删除失效
 * （重新生成需付费），故此处对齐官方值（24h = 86_400_000 ms）。该过期判断只是兜底——
 * 成功轮询到 URL 后立即进入下载阶段（[advanceDownload]），正常路径远早于过期前完成下载，
 * 不会触发付费性重新生成。
 */
private const val URL_TTL_MILLIS = 24 * 60 * 60_000L

/** SUBMITTING 残留判定阈值：超过该时长未完成即视为中断，可复位为 FAILED_SUBMISSION（歧义）。 */
private const val SUBMISSION_STALE_THRESHOLD_MS = 5 * 60_000L

/** 提示词生成时注入的「前情对话」消息条数上限（不含本次用户/助手消息）。 */
private const val MAX_PROMPT_CONTEXT_TURNS = 8

private const val STAGE_SNAPSHOT = "SNAPSHOT"
private const val STAGE_PROMPT = "PROMPT"
private const val STAGE_SUBMIT = "SUBMIT"
private const val STAGE_REMOTE = "REMOTE"
private const val STAGE_QUERY = "QUERY"
private const val STAGE_DOWNLOAD = "DOWNLOAD"

/**
 * Seedance 视频流水线协调器（Task 6 状态机核心，纯 JVM 可测）。
 *
 * 单次 [advance] 根据当前状态推进一个阶段；阶段所有权靠 [SeedancePipelineStore.claim] 的
 * CAS 保证（同一阶段仅一个 Worker 认领成功）。不依赖 Room/Android，全部副作用经注入接口。
 *
 * 关键不变量：
 * - 提示词生成前先过「配置变更门禁」：当前 ApiConfig 与任务快照不一致时拒绝静默换模型；
 * - POST 前持久化 SUBMITTING/submissionAttemptId/submissionStartedAt/requestFingerprint，
 *   提交歧义（AMBIGUOUS_TRANSPORT）置 FAILED_SUBMISSION/AMBIGUOUS_POST + requiresCostConfirmation，
 *   **绝不自动重发**；
 * - GET/下载的瞬时失败按 [SeedanceRetryPolicy] 有界退避；远端模型失败/过期只等用户确认；
 * - READY 仅在成品经原子改名 + 哈希校验后置位，重复 Worker 识别既有成品即幂等 READY。
 */
class SeedancePipelineCoordinator(
    private val store: SeedancePipelineStore,
    private val submitter: SeedanceSubmitter,
    private val promptProvider: SeedancePromptProvider,
    private val conversationContextProvider: SeedanceConversationContextProvider,
    private val snapshooter: SeedanceReferenceSnapshooter,
    private val resolveSnapshotSources: SeedanceSnapshotSourceResolver,
    private val downloadVideo: SeedanceVideoDownloader,
    private val fileStore: SeedanceVideoFileStore,
    private val encoder: SeedanceImageEncoder,
    private val apiConfigProvider: suspend () -> ApiConfig,
    private val seedanceConfigProvider: suspend () -> SeedanceConfig,
    private val onReady: suspend (SeedanceVideo) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { newAttemptId(clock()) },
    private val retryPolicy: SeedanceRetryPolicy = SeedanceRetryPolicy(),
) {

    private val promptJson = Json { encodeDefaults = true }

    /** 推进任务一个阶段。 */
    suspend fun advance(taskId: Long): PipelineOutcome {
        val task = store.getById(taskId) ?: return PipelineOutcome.Complete
        return when (task.state) {
            SeedanceVideoState.SNAPSHOT_PENDING -> advanceSnapshot(task)
            SeedanceVideoState.PROMPT_PENDING -> advancePrompt(task)
            SeedanceVideoState.SUBMISSION_PENDING -> advanceSubmission(task)
            SeedanceVideoState.QUEUED, SeedanceVideoState.RUNNING, SeedanceVideoState.CANCEL_REQUESTED -> advancePoll(task)
            SeedanceVideoState.DOWNLOAD_PENDING -> advanceDownload(task)
            // 进行中状态由「拥有者 Worker」独占推进；被并发/重复 Worker 碰到时不做任何事
            // （复位只在启动恢复 [normalizeStaleInProgress] 中做，避免误复位在途工作）。
            SeedanceVideoState.PROMPTING,
            SeedanceVideoState.SUBMITTING,
            SeedanceVideoState.DOWNLOADING,
            -> PipelineOutcome.Complete
            SeedanceVideoState.READY, SeedanceVideoState.CANCELLED -> PipelineOutcome.Complete
            else -> PipelineOutcome.WaitingForUser
        }
    }

    /** 进程中断恢复：把残留的进行中状态复位，使其可被自动认领。 */
    suspend fun normalizeStaleInProgress() {
        store.listByStates(setOf(SeedanceVideoState.PROMPTING)).forEach { stale ->
            store.claim(stale.id, SeedanceVideoState.PROMPTING, SeedanceVideoState.PROMPT_PENDING)
        }
        store.listByStates(setOf(SeedanceVideoState.DOWNLOADING)).forEach { stale ->
            store.claim(stale.id, SeedanceVideoState.DOWNLOADING, SeedanceVideoState.DOWNLOAD_PENDING)
        }
        // SUBMITTING 可能是并发恢复中被重新认领的「在途」任务，仅当确实陈旧（无 startedAt 或
        // 超过阈值）才按歧义复位，避免误复位仍在进行的 POST。
        val now = clock()
        store.listByStates(setOf(SeedanceVideoState.SUBMITTING)).forEach { stale ->
            val startedAt = stale.submissionStartedAt
            val isStale = startedAt == null || now - startedAt > SUBMISSION_STALE_THRESHOLD_MS
            if (isStale) {
                store.transition(stale.id, SeedanceVideoState.SUBMITTING, SeedanceVideoState.FAILED_SUBMISSION) {
                    it.copy(errorStage = STAGE_SUBMIT, errorCode = ERROR_CODE_AMBIGUOUS_POST,
                        errorMessage = "提交中断，无法确认任务是否已创建，请确认后重试",
                        requiresCostConfirmation = true, retryDisposition = "ambiguous_post")
                }
            }
        }
    }

    // ===== 快照 =====

    private suspend fun advanceSnapshot(task: SeedanceVideo): PipelineOutcome {
        val sources = resolveSnapshotSources.resolve(task)
            ?: return fail(task, SeedanceVideoState.SNAPSHOT_PENDING, SeedanceVideoState.FAILED_SNAPSHOT,
                STAGE_SNAPSHOT, "CHARACTER_MISSING", "缺少角色立绘图片（角色可能已被删除），无法生成视频")

        val snapshot = snapshooter.snapshot(
            task.taskUuid, sources.character, sources.builtInAssetPath, sources.backgroundImagePath,
        ).getOrElse { e ->
            return fail(task, SeedanceVideoState.SNAPSHOT_PENDING, SeedanceVideoState.FAILED_SNAPSHOT,
                STAGE_SNAPSHOT, "SNAPSHOT_FAILED", "参考图快照复制失败")
        }

        // 先写回参考图字段（幂等），再 CAS 推进状态；崩溃后仍在 SNAPSHOT_PENDING 可重跑。
        val fresh = store.getById(task.id) ?: return PipelineOutcome.Complete
        if (fresh.state != SeedanceVideoState.SNAPSHOT_PENDING) return PipelineOutcome.Reschedule(0)
        store.update(fresh.copy(
            characterImagePath = snapshot.characterPath,
            characterImageMime = snapshot.characterMime,
            characterImageSha256 = snapshot.characterSha256,
            backgroundImagePath = snapshot.backgroundPath,
            backgroundImageMime = snapshot.backgroundMime,
            backgroundImageSha256 = snapshot.backgroundSha256,
            errorStage = null, errorCode = null, errorMessage = null, nextRetryAt = null,
        ))
        store.claim(task.id, SeedanceVideoState.SNAPSHOT_PENDING, SeedanceVideoState.PROMPT_PENDING)
        return PipelineOutcome.Reschedule(0)
    }

    // ===== 提示词 =====

    private suspend fun advancePrompt(task: SeedanceVideo): PipelineOutcome {
        if (!store.claim(task.id, SeedanceVideoState.PROMPT_PENDING, SeedanceVideoState.PROMPTING)) {
            return PipelineOutcome.Reschedule(0)
        }
        val apiConfig = apiConfigProvider()

        // 配置变更门禁：当前基地址/模型与任务快照不一致时拒绝静默换模型。
        if (apiConfig.baseUrl != task.promptBaseUrlSnapshot || apiConfig.model != task.promptModelSnapshot) {
            store.transition(task.id, SeedanceVideoState.PROMPTING, SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED) {
                it.copy(errorStage = STAGE_PROMPT, errorCode = "CONFIG_CHANGED",
                    errorMessage = "模型/服务地址已变更，请确认后重试", retryDisposition = "manual")
            }
            return PipelineOutcome.WaitingForUser
        }
        if (apiConfig.apiKey.isBlank()) {
            store.transition(task.id, SeedanceVideoState.PROMPTING, SeedanceVideoState.FAILED_PROMPT) {
                it.copy(errorStage = STAGE_PROMPT, errorCode = "MISSING_API_KEY",
                    errorMessage = "未配置模型 API Key，无法生成提示词", retryDisposition = "manual")
            }
            return PipelineOutcome.WaitingForUser
        }

        val doc = try {
            promptProvider.generate(apiConfig, buildPromptInput(task))
        } catch (e: SeedancePromptParseException) {
            store.transition(task.id, SeedanceVideoState.PROMPTING, SeedanceVideoState.FAILED_PROMPT) {
                it.copy(errorStage = STAGE_PROMPT, errorCode = "PROMPT_PARSE",
                    errorMessage = "提示词生成失败，请稍后重试", retryDisposition = "manual")
            }
            return PipelineOutcome.WaitingForUser
        } catch (e: Exception) {
            return retryTransient(task, SeedanceVideoState.PROMPTING, SeedanceVideoState.PROMPT_PENDING,
                SeedanceVideoState.FAILED_PROMPT, STAGE_PROMPT, "PROMPT_TRANSIENT", "提示词生成暂时失败")
        }

        val docJson = promptJson.encodeToString(SeedancePromptDocument.serializer(), doc)
        store.transition(task.id, SeedanceVideoState.PROMPTING, SeedanceVideoState.SUBMISSION_PENDING) {
            it.copy(promptJson = docJson, finalPrompt = doc.finalPrompt,
                errorStage = null, errorCode = null, errorMessage = null, nextRetryAt = null)
        }
        return PipelineOutcome.Reschedule(0)
    }

    private suspend fun buildPromptInput(task: SeedanceVideo): SeedancePromptInput {
        // 前情对话仅作上下文；提供者失败（库不可用/IO 异常）绝不能阻塞视频流水线，静默降级为空。
        val recentContext = runCatching {
            conversationContextProvider.recentDialogue(
                conversationId = task.sourceConversationId,
                currentUserText = task.userTextSnapshot,
                currentAssistantText = task.assistantTextSnapshot,
                maxTurns = MAX_PROMPT_CONTEXT_TURNS,
            )
        }.getOrDefault("")
        return SeedancePromptInput(
            characterName = task.characterNameSnapshot,
            characterRole = task.characterRoleSnapshot,
            characterSystemPrompt = task.characterSystemPromptSnapshot,
            userText = task.userTextSnapshot,
            assistantText = task.assistantTextSnapshot,
            sceneDescription = task.sceneDescriptionSnapshot,
            hasBackgroundReference = !task.backgroundImageSourceSnapshot.isNullOrBlank(),
            recentContext = recentContext,
            variant = task.modelVariant,
            resolution = task.resolution,
            ratio = task.ratio,
            durationSeconds = task.durationSeconds,
        )
    }

    // ===== 提交 =====

    private suspend fun advanceSubmission(task: SeedanceVideo): PipelineOutcome {
        if (!store.claim(task.id, SeedanceVideoState.SUBMISSION_PENDING, SeedanceVideoState.SUBMITTING)) {
            return PipelineOutcome.Reschedule(0)
        }
        val config = seedanceConfigProvider()

        val validation = validateSeedanceRequest(config, task.characterImagePath.orEmpty())
        if (validation is SeedanceValidationResult.Invalid) {
            store.transition(task.id, SeedanceVideoState.SUBMITTING, SeedanceVideoState.FAILED_SUBMISSION) {
                it.copy(errorStage = STAGE_SUBMIT, errorCode = "INVALID_REQUEST",
                    errorMessage = validation.message, retryDisposition = "manual")
            }
            return PipelineOutcome.WaitingForUser
        }
        val charPath = task.characterImagePath
        val charMime = task.characterImageMime
        val charSha = task.characterImageSha256
        if (charPath.isNullOrBlank() || charMime.isNullOrBlank() || charSha.isNullOrBlank()) {
            store.transition(task.id, SeedanceVideoState.SUBMITTING, SeedanceVideoState.FAILED_SUBMISSION) {
                it.copy(errorStage = STAGE_SUBMIT, errorCode = "MISSING_REFERENCE",
                    errorMessage = "参考图快照缺失，无法提交", retryDisposition = "manual")
            }
            return PipelineOutcome.WaitingForUser
        }
        val finalPrompt = task.finalPrompt
        if (finalPrompt.isNullOrBlank()) {
            store.transition(task.id, SeedanceVideoState.SUBMITTING, SeedanceVideoState.FAILED_SUBMISSION) {
                it.copy(errorStage = STAGE_SUBMIT, errorCode = "MISSING_PROMPT",
                    errorMessage = "最终提示词缺失，无法提交", retryDisposition = "manual")
            }
            return PipelineOutcome.WaitingForUser
        }

        val attemptId = idGenerator()
        val startedAt = clock()
        val fingerprint = requestFingerprint(task, finalPrompt)

        // 先持久化 SUBMITTING 元数据，再真正提交（进程若在此后死亡，恢复流程按歧义处理）。
        store.getById(task.id)?.let { fresh ->
            store.update(fresh.copy(
                submissionAttemptId = attemptId, submissionStartedAt = startedAt, requestFingerprint = fingerprint,
            ))
        }

        // 参考图编码 + 请求构造与提交放入同一段受控流程：编码抛异常（参考图缺失/不可读）
        // 时转 FAILED_SUBMISSION/SNAPSHOT 等待用户，而不是把任务留在 SUBMITTING 直到下次重启。
        // 单图字节预算按协议传入：方舟 30MB；中转站媒体协议 10MB（其文档的单文件上限）。
        val imageBudget = if (seedanceProtocolFor(config.baseUrl) == SeedanceProtocol.MEDIA_RELAY) {
            MEDIA_REFERENCE_MAX_BYTES
        } else {
            REFERENCE_MAX_BYTES
        }
        val request = try {
            CreateSeedanceTask(
                finalPrompt = finalPrompt,
                character = encoder.encode(charPath, charMime, imageBudget),
                background = task.backgroundImagePath?.takeIf { it.isNotBlank() }?.let { path ->
                    encoder.encode(path, task.backgroundImageMime ?: charMime, imageBudget)
                },
                variant = task.modelVariant,
                resolution = task.resolution,
                ratio = task.ratio,
                durationSeconds = task.durationSeconds,
                watermark = task.watermark,
            )
        } catch (e: Exception) {
            store.transition(task.id, SeedanceVideoState.SUBMITTING, SeedanceVideoState.FAILED_SUBMISSION) {
                it.copy(errorStage = STAGE_SNAPSHOT, errorCode = "SNAPSHOT_ENCODE_FAILED",
                    errorMessage = "参考图缺失或不可读", requiresCostConfirmation = false,
                    retryDisposition = "manual",
                    submissionAttemptId = attemptId, submissionStartedAt = startedAt, requestFingerprint = fingerprint)
            }
            return PipelineOutcome.WaitingForUser
        }

        val response = try {
            submitter.create(config, request)
        } catch (e: SeedanceApiException) {
            return handleSubmitFailure(task, e, attemptId, startedAt, fingerprint)
        } catch (e: Exception) {
            return handleSubmitFailure(
                task, SeedanceApiException(SeedanceError.AMBIGUOUS_TRANSPORT, "网络错误，无法确认任务状态"),
                attemptId, startedAt, fingerprint,
            )
        }

        // 成功但未返回任务 ID：无法管理，按歧义处理（可能已产生费用）。
        if (response.id.isNullOrBlank()) {
            store.transition(task.id, SeedanceVideoState.SUBMITTING, SeedanceVideoState.FAILED_SUBMISSION) {
                it.copy(errorStage = STAGE_SUBMIT, errorCode = ERROR_CODE_AMBIGUOUS_POST,
                    errorMessage = "服务端未返回任务 ID，请确认是否已产生费用",
                    requiresCostConfirmation = true, retryDisposition = "ambiguous_post",
                    submissionAttemptId = attemptId, submissionStartedAt = startedAt, requestFingerprint = fingerprint)
            }
            return PipelineOutcome.WaitingForUser
        }

        // 提交阶段已 CAS 认领 SUBMISSION_PENDING->SUBMITTING，此处以 SUBMITTING 作为后续状态
        // 转换的起点（applyRemoteStatus 用 task.state 作 `from`，不能用陈旧的读值）。
        return applyRemoteStatus(task.copy(state = SeedanceVideoState.SUBMITTING), response)
    }

    private suspend fun handleSubmitFailure(
        task: SeedanceVideo,
        e: SeedanceApiException,
        attemptId: String,
        startedAt: Long,
        fingerprint: String,
    ): PipelineOutcome {
        val code = if (e.classification == SeedanceError.AMBIGUOUS_TRANSPORT) ERROR_CODE_AMBIGUOUS_POST else e.classification.name
        return when (e.classification) {
            SeedanceError.AMBIGUOUS_TRANSPORT -> {
                // 歧义：POST 可能已到服务端，绝不自动重发。
                store.transition(task.id, SeedanceVideoState.SUBMITTING, SeedanceVideoState.FAILED_SUBMISSION) {
                    it.copy(errorStage = STAGE_SUBMIT, errorCode = ERROR_CODE_AMBIGUOUS_POST,
                        errorMessage = seedanceUserErrorMessage(e.classification), requiresCostConfirmation = true,
                        retryDisposition = "ambiguous_post",
                        submissionAttemptId = attemptId, submissionStartedAt = startedAt, requestFingerprint = fingerprint)
                }
                PipelineOutcome.WaitingForUser
            }
            // 任何非歧义的明确失败（含 429/5xx）：POST **绝不**自动重发，一律转 FAILED_SUBMISSION 等待用户。
            // 5xx（尤其 502/504 网关错误）可能在服务端已创建任务后才返回，自动重发会重复计费，因此
            // 重试须先经费用确认（requiresCostConfirmation=true）；4xx（含 429）为明确未受理，保持 false。
            else -> {
                val costConfirmation = e.httpStatus != null && e.httpStatus >= 500
                store.transition(task.id, SeedanceVideoState.SUBMITTING, SeedanceVideoState.FAILED_SUBMISSION) {
                    it.copy(errorStage = STAGE_SUBMIT, errorCode = code,
                        errorMessage = seedanceUserErrorMessage(e.classification), retryDisposition = "manual",
                        requiresCostConfirmation = costConfirmation,
                        submissionAttemptId = attemptId, submissionStartedAt = startedAt, requestFingerprint = fingerprint)
                }
                PipelineOutcome.WaitingForUser
            }
        }
    }

    // ===== 轮询 / 取消 =====

    private suspend fun advancePoll(task: SeedanceVideo): PipelineOutcome {
        val config = seedanceConfigProvider()
        val remoteId = task.remoteTaskId
        if (remoteId.isNullOrBlank()) {
            return fail(task, task.state, SeedanceVideoState.FAILED_SUBMISSION,
                STAGE_SUBMIT, ERROR_CODE_AMBIGUOUS_POST, "缺少远端任务 ID", costConfirmation = true)
        }
        // 取消请求：再次确认取消（仅 queued 可取消），随后一律以服务端状态为准。
        if (task.state == SeedanceVideoState.CANCEL_REQUESTED) {
            try { submitter.cancel(config, remoteId) } catch (_: Exception) { /* GET 兜底 */ }
        }
        val response = try {
            submitter.get(config, remoteId)
        } catch (e: SeedanceApiException) {
            return handlePollFailure(task, e)
        } catch (e: Exception) {
            return handlePollFailure(task, SeedanceApiException(SeedanceError.AMBIGUOUS_TRANSPORT, "网络错误"))
        }
        return applyRemoteStatus(task, response)
    }

    private suspend fun handlePollFailure(task: SeedanceVideo, e: SeedanceApiException): PipelineOutcome {
        val delay = retryPolicy.retryDelayMillis(task.automaticRetryCount, e.retryAfterMillis)
        if (delay == null) {
            store.transition(task.id, task.state, SeedanceVideoState.FAILED_QUERY) {
                it.copy(errorStage = STAGE_QUERY, errorCode = e.classification.name,
                    errorMessage = seedanceUserErrorMessage(e.classification), retryDisposition = "manual")
            }
            return PipelineOutcome.WaitingForUser
        }
        updateFields(task.id) {
            it.copy(nextRetryAt = clock() + delay, automaticRetryCount = it.automaticRetryCount + 1,
                errorStage = STAGE_QUERY, errorCode = e.classification.name,
                errorMessage = seedanceUserErrorMessage(e.classification), retryDisposition = "bounded_retry")
        }
        return PipelineOutcome.Reschedule(delay)
    }

    /** 依据远端状态推进（提交成功后 / 轮询后共用）；取消竞态以服务端状态为准。 */
    private suspend fun applyRemoteStatus(task: SeedanceVideo, response: SeedanceTaskResponse): PipelineOutcome {
        val remoteId = response.id ?: task.remoteTaskId
        val status = response.remoteStatus ?: SeedanceRemoteStatus.FAILED
        fun SeedanceVideo.withRemote() = copy(
            remoteTaskId = remoteId,
            remoteStatus = status.storageKey,
            remoteRequestId = response.requestId ?: remoteRequestId,
            nextRetryAt = null, errorStage = null, errorCode = null, errorMessage = null,
        )

        return when (status) {
            SeedanceRemoteStatus.QUEUED -> {
                when (task.state) {
                    SeedanceVideoState.CANCEL_REQUESTED -> updateFields(task.id) { it.withRemote() }
                    SeedanceVideoState.QUEUED -> updateFields(task.id) { it.withRemote() }
                    else -> store.transition(task.id, task.state, SeedanceVideoState.QUEUED) { it.withRemote() }
                }
                PipelineOutcome.Reschedule(SeedanceRetryPolicy.POLL_INTERVAL_MILLIS)
            }
            SeedanceRemoteStatus.RUNNING -> {
                if (task.state == SeedanceVideoState.RUNNING) updateFields(task.id) { it.withRemote() }
                else store.transition(task.id, task.state, SeedanceVideoState.RUNNING) { it.withRemote() }
                PipelineOutcome.Reschedule(SeedanceRetryPolicy.POLL_INTERVAL_MILLIS)
            }
            SeedanceRemoteStatus.SUCCEEDED -> {
                val url = response.output?.videoUrl
                if (url.isNullOrBlank()) {
                    if (task.state == SeedanceVideoState.CANCEL_REQUESTED) updateFields(task.id) { it.withRemote() }
                    else store.transition(task.id, task.state, SeedanceVideoState.QUEUED) { it.withRemote() }
                    PipelineOutcome.Reschedule(SeedanceRetryPolicy.POLL_INTERVAL_MILLIS)
                } else {
                    store.transition(task.id, task.state, SeedanceVideoState.DOWNLOAD_PENDING) {
                        it.withRemote().copy(
                            remoteVideoUrl = url,
                            remoteVideoUrlObservedAt = clock(),
                            remoteVideoUrlExpiresAt = clock() + URL_TTL_MILLIS,
                        )
                    }
                    PipelineOutcome.Reschedule(0)
                }
            }
            SeedanceRemoteStatus.CANCELLED -> {
                store.transition(task.id, task.state, SeedanceVideoState.CANCELLED) { it.withRemote() }
                PipelineOutcome.Complete
            }
            SeedanceRemoteStatus.FAILED -> {
                store.transition(task.id, task.state, SeedanceVideoState.FAILED_REMOTE) {
                    it.withRemote().copy(errorStage = STAGE_REMOTE, errorCode = "REMOTE_FAILED",
                        errorMessage = "远端视频生成失败，请稍后重试",
                        retryDisposition = "manual")
                }
                PipelineOutcome.WaitingForUser
            }
            SeedanceRemoteStatus.EXPIRED -> {
                store.transition(task.id, task.state, SeedanceVideoState.EXPIRED) {
                    it.withRemote().copy(errorStage = STAGE_REMOTE, errorCode = "EXPIRED",
                        errorMessage = "远端视频任务已过期", retryDisposition = "manual")
                }
                PipelineOutcome.WaitingForUser
            }
        }
    }

    // ===== 下载 =====

    private suspend fun advanceDownload(task: SeedanceVideo): PipelineOutcome {
        val url = task.remoteVideoUrl
        if (url.isNullOrBlank()) {
            store.transition(task.id, SeedanceVideoState.DOWNLOAD_PENDING, SeedanceVideoState.QUEUED) {
                it.copy(errorStage = null, errorCode = null, errorMessage = null)
            }
            return PipelineOutcome.Reschedule(SeedanceRetryPolicy.POLL_INTERVAL_MILLIS)
        }
        // URL 过期：需用户确认后重新提交。
        val expiresAt = task.remoteVideoUrlExpiresAt
        if (expiresAt != null && clock() >= expiresAt) {
            store.transition(task.id, SeedanceVideoState.DOWNLOAD_PENDING, SeedanceVideoState.EXPIRED) {
                it.copy(errorStage = STAGE_REMOTE, errorCode = "URL_EXPIRED",
                    errorMessage = "视频下载地址已过期，请重新生成", retryDisposition = "manual")
            }
            return PipelineOutcome.WaitingForUser
        }
        // 已有校验通过的成品 -> 幂等 READY。
        val expectedSha = task.videoSha256
        if (expectedSha != null) {
            val existing = fileStore.verifyExisting(task.taskUuid, expectedSha)
            if (existing != null) {
                store.transition(task.id, SeedanceVideoState.DOWNLOAD_PENDING, SeedanceVideoState.READY) {
                    it.copy(localVideoPath = existing.path, videoMime = existing.mime,
                        videoByteSize = existing.byteSize, videoSha256 = existing.sha256,
                        downloadedAt = clock(), errorStage = null, errorCode = null,
                        errorMessage = null, nextRetryAt = null)
                }
                store.getById(task.id)?.let { ready -> onReady(ready) }
                return PipelineOutcome.Complete
            }
        }

        if (!store.claim(task.id, SeedanceVideoState.DOWNLOAD_PENDING, SeedanceVideoState.DOWNLOADING)) {
            return PipelineOutcome.Reschedule(0)
        }
        val download = try { downloadVideo.download(url) } catch (e: Exception) { null }
        if (download == null) {
            return retryTransient(task, SeedanceVideoState.DOWNLOADING, SeedanceVideoState.DOWNLOAD_PENDING,
                SeedanceVideoState.FAILED_DOWNLOAD, STAGE_DOWNLOAD, "DOWNLOAD_TRANSIENT", "视频下载失败")
        }
        return download.use {
            val saved = fileStore.save(task.taskUuid, download.mime, download.contentLength, download.stream)
                .getOrElse { e ->
                    store.transition(task.id, SeedanceVideoState.DOWNLOADING, SeedanceVideoState.FAILED_DOWNLOAD) {
                        it.copy(errorStage = STAGE_DOWNLOAD, errorCode = "DOWNLOAD_FAILED",
                            errorMessage = "视频下载失败，请稍后重试", retryDisposition = "manual")
                    }
                    return PipelineOutcome.WaitingForUser
                }
            store.transition(task.id, SeedanceVideoState.DOWNLOADING, SeedanceVideoState.READY) {
                it.copy(localVideoPath = saved.path, videoMime = saved.mime,
                    videoByteSize = saved.byteSize, videoSha256 = saved.sha256,
                    downloadedAt = clock(), errorStage = null, errorCode = null,
                    errorMessage = null, nextRetryAt = null)
            }
            store.getById(task.id)?.let { ready -> onReady(ready) }
            PipelineOutcome.Complete
        }
    }

    // ===== 通用 =====

    private suspend fun fail(
        task: SeedanceVideo,
        from: SeedanceVideoState,
        to: SeedanceVideoState,
        stage: String,
        code: String,
        message: String,
        costConfirmation: Boolean = false,
    ): PipelineOutcome {
        store.transition(task.id, from, to) {
            it.copy(errorStage = stage, errorCode = code, errorMessage = message,
                retryDisposition = "manual",
                requiresCostConfirmation = it.requiresCostConfirmation || costConfirmation,
                nextRetryAt = null)
        }
        return PipelineOutcome.WaitingForUser
    }

    private suspend fun retryTransient(
        task: SeedanceVideo,
        from: SeedanceVideoState,
        backTo: SeedanceVideoState,
        exhaustedState: SeedanceVideoState,
        stage: String,
        code: String,
        message: String?,
        retryAfterMillis: Long? = null,
    ): PipelineOutcome {
        val delay = retryPolicy.retryDelayMillis(task.automaticRetryCount, retryAfterMillis)
        if (delay == null) {
            store.transition(task.id, from, exhaustedState) {
                it.copy(errorStage = stage, errorCode = code,
                    errorMessage = fixedPipelineMessage(stage, message), retryDisposition = "manual")
            }
            return PipelineOutcome.WaitingForUser
        }
        store.transition(task.id, from, backTo) {
            it.copy(nextRetryAt = clock() + delay, automaticRetryCount = it.automaticRetryCount + 1,
                errorStage = stage, errorCode = code,
                errorMessage = fixedPipelineMessage(stage, message), retryDisposition = "bounded_retry")
        }
        return PipelineOutcome.Reschedule(delay)
    }

    private fun fixedPipelineMessage(stage: String, fallback: String?): String = when (stage) {
        STAGE_PROMPT -> "提示词生成暂时失败，请稍后重试"
        STAGE_DOWNLOAD -> "视频下载失败，请稍后重试"
        STAGE_QUERY -> "查询视频任务失败，请稍后重试"
        else -> fallback?.takeIf { it in setOf("视频下载失败", "提示词生成暂时失败") } ?: "操作失败，请稍后重试"
    }

    private suspend fun updateFields(taskId: Long, mutate: (SeedanceVideo) -> SeedanceVideo) {
        store.getById(taskId)?.let { store.update(mutate(it)) }
    }

    private fun requestFingerprint(task: SeedanceVideo, finalPrompt: String): String {
        val material = listOf(
            finalPrompt, task.modelVariant.storageKey, task.resolution.storageKey, task.ratio.storageKey,
            task.durationSeconds.toString(), task.watermark.toString(),
            task.characterImageSha256.orEmpty(), task.backgroundImageSha256.orEmpty(),
        ).joinToString("|")
        return sha256Hex(material.toByteArray(Charsets.UTF_8))
    }
}

/** 提交尝试 ID：单调时间戳 + 随机十六进制后缀（不依赖 java.util.UUID）。 */
internal fun newAttemptId(now: Long): String =
    "$now-" + (1..16).joinToString("") { "0123456789abcdef"[kotlin.random.Random.nextInt(16)].toString() }

/** SHA-256 -> 小写十六进制。 */
private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
