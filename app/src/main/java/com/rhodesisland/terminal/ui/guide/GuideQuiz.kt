package com.rhodesisland.terminal.ui.guide

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.ui.theme.GlassShapes
import kotlinx.coroutines.delay

/**
 * 嘉豪认证考试（彩蛋）：十道关于本软件底层技术的单选题，一题一屏、选中即锁定不可回退。
 *
 * - 错任意一道 → 失败 overlay（文案逐字对齐需求），「我知道了」退出 App；
 *   全程零持久化，不影响下次使用。
 * - 全对 → 奖励 overlay，关闭后回到指南首页正常浏览。
 * - 结果 overlay 为同 Dialog 内的全屏 Box（scrim + 拦截点击 + 宿主吞返回键），无逃逸口。
 */

/** 单题：恰好 4 选项；[note] 仅源码注释考察点，永不展示。 */
data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val note: String,
)

private val QUIZ_QUESTIONS: List<QuizQuestion> = listOf(

    QuizQuestion(
        question = "本应用估算本地模型运行内存时，KV cache 部分的计算方式是？",
        options = listOf(
            "参数量(十亿) × 每参数字节数 × 批大小",
            "fp16(2字节) × 2 × 层数(layerCount) × 上下文长度 × KV头数 × 头维度",
            "上下文长度 × hidden_size × 4字节 × 注意力头总数",
            "层数 × 上下文长度 × 词表大小 × 2字节",
        ),
        answerIndex = 1,
        note = "LlmMemoryEstimator 精确公式（contextTokens×layerCount×2×kvHeads×headDim×bytesPerElement）；干扰项全是“看起来像显存估算”的假公式。",
    ),

    QuizQuestion(
        question = "Lookahead 投机解码在什么情况下才真正参与推理？",
        options = listOf(
            "仅当解析出的组合命中 CPU_OPTIMIZED 变体的认证组合（profile resolver 门禁通过）",
            "打开后端设为 OpenCL GPU 并选最高速度性能模式即生效",
            "QNN NPU 后端的专属加速能力",
            "AUTO 后端在任何设备上都会自动启用",
        ),
        answerIndex = 0,
        note = "门禁在 profile resolver 层（device+model+CPU_OPTIMIZED+native 组合认证），认证前候选恒回落 lookahead=false；“最高速度模式”是最强诱饵。",
    ),

    QuizQuestion(
        question = "应用判定某个自定义接口走 Anthropic /v1/messages + x-api-key 协议的依据是？",
        options = listOf(
            "请求头中已经带了 x-api-key",
            "Base URL 的域名字段精确等于 api.anthropic.com",
            "所填模型名以 claude 开头",
            "URL 包含 anthropic/claude 字样，或路径以 /v1/messages 结尾",
        ),
        answerIndex = 3,
        note = "DirectLlmClient 按 baseUrl 字符串特征判定而非域名/请求头/模型名。",
    ),

    QuizQuestion(
        question = "云端 SSE 直连时，DeepSeek / Qwen 系模型的思考过程取自哪个字段？",
        options = listOf(
            "delta.content 里原始 <think> 标签的解析结果",
            "choices[0].delta.reasoning_content（部分端点为 reasoning），随后被包装成 <think> 展示",
            "message.reasoning_summary",
            "usage 里的 completion_tokens_details",
        ),
        answerIndex = 1,
        note = "云端字段是 reasoning_content 再包装 <think>；A 极具迷惑性——<think> 标签直读是本地 MNN 才有的路径。",
    ),

    QuizQuestion(
        question = "「免费对话」通道的真实 API Key 实际存放在哪里？",
        options = listOf(
            "编译期写入 APK 的 BuildConfig 常量里",
            "随应用内置的 assets 配置文件分发",
            "Cloudflare Worker 的加密环境变量中，对话经 Worker 代理由服务端注入",
            "Room 数据库的一张加密表里，首次联网时下载",
        ),
        answerIndex = 2,
        note = "ModelProviders：key 存 Cloudflare Worker 加密环境变量（App → Cloudflare 注入 key → 硅基流动），key 不出 Cloudflare，客户端 requiresApiKey=false。",
    ),

    QuizQuestion(
        question = "角色主动问候真正投递的必要条件，不包括以下哪项？",
        options = listOf(
            "15 分钟周期 Work 到期且 next_fire_at 门控通过",
            "处于白天时段（08:00–23:00）",
            "当日配额未耗尽（默认每天 3 条，可调 1–10）",
            "设备必须连接 Wi-Fi 网络",
        ),
        answerIndex = 3,
        note = "负向题；约束集合 = 周期心跳 + next_fire_at + 时段 + 配额 + 严格轮询，从未有网络类型判断。",
    ),

    QuizQuestion(
        question = "群聊中用户发出一条消息，最多由几名成员回复？",
        options = listOf(
            "2 名（与自动互聊每轮人数相同）",
            "3 名（群成员数的三分之一）",
            "4 名（@ 指定的成员必答并占用名额）",
            "所有群成员依次作答",
        ),
        answerIndex = 2,
        note = "MAX_REPLIES_PER_USER_MESSAGE = 4；A 用 discuss 轮人数概念混淆。",
    ),

    QuizQuestion(
        question = "Seedance 视频生成在「中转站」模式下，应用自动调用的是哪组接口？",
        options = listOf(
            "/v1/media/generate 与 /v1/media/status",
            "官方 /api/v3 下的任务创建/查询接口",
            "/v1/video/create 与 /v1/video/poll",
            "复用 /chat/completions 并附加 video 模态参数",
        ),
        answerIndex = 0,
        note = "双协议按 base URL 特征自动识别（SeedanceClient MEDIA_RELAY）；B 是官方真协议，恰是本题陷阱。",
    ),

    QuizQuestion(
        question = "世界书条目处于「蓝灯」状态意味着什么？",
        options = listOf(
            "条目已被禁用但仍占用 token 预算",
            "只有主关键词和次级关键词同时命中才注入",
            "常驻条目：无需关键词，每次请求都注入 system prompt",
            "仅在递归扫描第二遍时才会被注入",
        ),
        answerIndex = 2,
        note = "SillyTavern 语义 constant=常驻；D 用递归扫描概念混淆。",
    ),

    QuizQuestion(
        question = "偏好设置数据文件损坏（如国产 ROM 半写）时，应用的行为是？",
        options = listOf(
            "弹窗引导用户导出日志手动修复",
            "逐段尝试恢复仍可读的键值对",
            "回退读取 Room 数据库里的一份备份",
            "经 ReplaceFileCorruptionHandler 删除损坏文件并以默认值重建",
        ),
        answerIndex = 3,
        note = "DataStoreCorruption.tolerantCorruptionHandler 防闪退的删档重建设计；与 Room 无任何备份关系。",
    ),
)

private const val QUIZ_TOTAL = 10
private val OPTION_LETTERS = listOf("A", "B", "C", "D")

/**
 * 考试页组合函数（由 GuideDialog 的 Quiz 态调用）。
 *
 * @param onPassHome 全对后点「收下奖励」→ 回指南首页。
 * @param onFailExit 失败兜底回调（实际退出在内部 [exitApplication] 完成；保留参数以防流程变化）。
 * @param onOverlayShowingChanged 结果 overlay 出现/消失时通知宿主（宿主据此吞返回键）。
 */
@Composable
fun JiahaoQuizPage(
    onPassHome: () -> Unit,
    onFailExit: () -> Unit,
    onOverlayShowingChanged: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    // questions.size 与 QUIZ_TOTAL 一致（10 题）；answers 按题序记录所选索引。
    val questions = remember { QUIZ_QUESTIONS }
    var index by remember { mutableIntStateOf(0) }
    var answers by remember { mutableStateOf(IntArray(questions.size) { -1 }) }

    // null = 未出结果；false = 失败；true = 全对。
    var result by remember { mutableStateOf<Boolean?>(null) }

    // overlay 状态上报宿主（宿主据此吞返回键）。
    LaunchedEffect(result) {
        onOverlayShowingChanged(result != null)
    }

    fun submitAnswer(picked: Int) {
        if (picked < 0) return
        val next = answers.copyOf()
        next[index] = picked
        answers = next
        if (index + 1 < questions.size) {
            index += 1
        } else {
            // 判卷：错任意一道即失败。
            result = questions.indices.all { i -> next[i] == questions[i].answerIndex }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 自绘头部：标题 + 进度（不放关闭钮，杜绝失败弹窗逃逸口）----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "🏆 嘉豪认证考试",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text("第 ${index + 1} / $QUIZ_TOTAL 题", color = scheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(scheme.outline.copy(alpha = 0.35f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(index.toFloat() / QUIZ_TOTAL)
                        .height(4.dp)
                        .background(scheme.primary),
                )
            }

            // ---- 当前题目（一题一屏；picked 以 index 为 key 重置，物理上不可回改）----
            val question = questions[index]
            var picked by remember(index) { mutableIntStateOf(-1) }

            // 选中即锁定：短暂展示锁定反馈后提交并推进（第 10 题后触发判卷）。
            LaunchedEffect(picked) {
                if (picked >= 0) {
                    delay(320)
                    submitAnswer(picked)
                }
            }

            Text(
                question.question,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                lineHeight = 20.sp,
            )

            question.options.forEachIndexed { optionIndex, option ->
                val isPicked = picked == optionIndex
                val dimmed = picked >= 0 && !isPicked
                Surface(
                    shape = GlassShapes.cardSmall,
                    color = when {
                        isPicked -> scheme.primary.copy(alpha = 0.14f)
                        else -> scheme.surfaceContainerHigh.copy(alpha = 0.6f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (dimmed) Modifier.alpha(0.45f) else Modifier)
                        .then(
                            if (isPicked) Modifier.border(1.5.dp, scheme.primary, GlassShapes.cardSmall) else Modifier
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (picked == -1) {
                                picked = optionIndex
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("${OPTION_LETTERS[optionIndex]}.", color = scheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(option, color = scheme.onSurface, fontSize = 12.5.sp, lineHeight = 18.sp)
                    }
                }
            }

            Text(
                "选定后立即锁定，无法修改",
                color = scheme.onSurfaceVariant, fontSize = 10.sp,
            )

            Spacer(Modifier.height(8.dp))
        }

        // ---- 结果 overlay：全屏遮罩拦截点击，配合宿主 BackHandler 吞键，无逃逸口 ----
        val quizResult = result
        if (quizResult != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.scrim.copy(alpha = 0.92f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* 拦截一切点击 */ },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                        .clip(GlassShapes.card)
                        .background(scheme.surfaceContainerHigh.copy(alpha = 0.97f))
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (quizResult) {
                        Text("🎉 恭喜你", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = scheme.primary)
                        Text(
                            "恭喜你，你确实有几把刷子，凭此弹窗的截图可以找我，有奖励！",
                            color = scheme.onSurface, fontSize = 14.sp, lineHeight = 21.sp,
                        )
                        Button(
                            onClick = {
                                result = null
                                onPassHome()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("收下奖励", color = scheme.onPrimary)
                        }
                    } else {
                        Text("💔 很遗憾", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = scheme.error)
                        Text(
                            "很遗憾嘉豪你没有通过测试，本软件不再为你提供任何服务",
                            color = scheme.onSurface, fontSize = 14.sp, lineHeight = 21.sp,
                        )
                        Button(
                            onClick = {
                                // 先礼貌关掉任务栈，再兜底杀进程；零持久化，下次启动一切如常。
                                exitApplication(context)
                                onFailExit()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = scheme.error),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("我知道了", color = scheme.onError)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 退出 App：Dialog 场景下 LocalContext 是包裹 Context，须 ContextWrapper 循环解包到 Activity；
 * finishAffinity 关任务栈后 killProcess 兜底杀进程。
 * android.os.Process 用全限定名，避免与 java.lang.Process 混淆。
 */
private fun exitApplication(context: Context) {
    var ctx: Context = context
    while (ctx is ContextWrapper && ctx !is Activity) {
        ctx = ctx.baseContext
    }
    (ctx as? Activity)?.finishAffinity()
    android.os.Process.killProcess(android.os.Process.myPid())
}
