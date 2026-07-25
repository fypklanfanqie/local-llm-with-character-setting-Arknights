package com.rhodesisland.terminal.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.llm.LlmMemoryEstimator
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.backend.BackendSelector
import com.rhodesisland.terminal.llm.backend.BackendType
import com.rhodesisland.terminal.llm.backend.NpuSupportDetector
import com.rhodesisland.terminal.ui.theme.PrtsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 推理引擎设置页（独立路由）
 *
 * 展示设备能力（CPU/内存/NPU·芯片等级）、后端选项（AUTO/MNN_CPU/MNN_GPU/MNN_NPU，
 * 标注支持状态与当前激活）、自动回退链（AUTO: MNN_GPU -> MNN_CPU；显式 NPU: MNN_NPU -> MNN_GPU -> MNN_CPU）。
 * 选择写入 [com.rhodesisland.terminal.data.repository.SettingsRepository.llmBackend]，
 * 与模型管理页的 BackendSelectorCard 共享同一 Flow，保持同步。
 */
@Composable
fun BackendSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pref by container.settingsRepository.llmBackend.collectAsState(initial = BackendPreference.AUTO)

    // 设备能力与运行时就绪状态：lazy 初次访问会触发 loadLibrary，放 IO 线程
    val deviceCap by produceState(initialValue = null as BackendSelector.DeviceCapability?) {
        value = withContext(Dispatchers.IO) { container.backendManager.deviceCapability }
    }
    // MNN 后端运行时就绪（libMNN.so + mnn_jni 加载；NPU 另需 libQnnHtp.so 等）
    val mnnCpuReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { container.backendManager.mnnCpuSupported }
    }
    val mnnGpuReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { container.backendManager.mnnGpuSupported }
    }
    val mnnNpuReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { container.backendManager.mnnNpuSupported }
    }
    // 回退链随偏好变化重算
    val fallbackChain by produceState(
        initialValue = emptyList<BackendType>(),
        pref, mnnCpuReady, mnnGpuReady, mnnNpuReady,
    ) {
        value = withContext(Dispatchers.IO) { container.backendManager.backendOrder(pref) }
    }
    val activeBackend = container.backendManager.lastUsedBackend

    // 推理参数（线程 / 上下文）及其"相对上次加载是否已变更"（移植自 iFeng hasConfigChanged）
    val threads by container.settingsRepository.llmThreads.collectAsState(initial = AppConfig.LLM.DEFAULT_THREADS)
    val contextLen by container.settingsRepository.llmContextLen.collectAsState(initial = AppConfig.LLM.DEFAULT_CONTEXT_LEN)
        val maxTokens by container.settingsRepository.llmMaxTokens.collectAsState(initial = AppConfig.LLM.DEFAULT_MAX_TOKENS)
    val configChanged by container.settingsRepository.llmConfigChanged.collectAsState(initial = false)

    // KV cache 内存估算：随选中模型 / 上下文长度变化重算（读模型目录 llm_config.json，IO 放后台）
    val context = LocalContext.current
    val activeModelId by container.settingsRepository.activeLocalModelId.collectAsState(initial = null)
    val memoryEstimate by produceState<LlmMemoryEstimator.MemoryEstimate>(
        initialValue = LlmMemoryEstimator.MemoryEstimate.Unavailable,
        activeModelId, contextLen,
    ) {
        value = LlmMemoryEstimator.estimate(context, container.settingsRepository, contextLen)
    }

    // 上下文长度输入框：编辑时显示用户键入值；失焦时校正并持久化
    var contextInput by remember { mutableStateOf(contextLen.toString()) }
    var contextInputFocused by remember { mutableStateOf(false) }
    LaunchedEffect(contextLen) {
        if (!contextInputFocused) contextInput = contextLen.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PrtsColors.BgPrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 顶部：返回 + 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ 返回",
                color = PrtsColors.Gold,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
            )
            Text(
                "推理引擎设置",
                color = PrtsColors.GoldBright,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // ===== 配置变更提示（iFeng hasConfigChanged 横幅）=====
        // 线程/上下文/后端改过后、且尚未成功跑一次推理时展示；发送下条消息自动重载并消失。
        if (configChanged) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PrtsColors.BgTertiary),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PrtsColors.WarnYellow),
            ) {
                Text(
                    "推理参数已变更，下次发送消息时将自动重载模型以生效。",
                    color = PrtsColors.WarnYellow,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // ===== 设备能力 =====
        SectionDivider("设备能力")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = PrtsColors.BgTertiary),
            shape = RoundedCornerShape(4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                val cap = deviceCap
                if (cap == null) {
                    Text("探测中…", color = PrtsColors.TextDim, fontSize = 12.sp)
                } else {
                    RowInfo("CPU 核心数", "${cap.cpuCoreCount}")
                    RowInfo("总内存", "${cap.totalRAMMB} MB")
                    RowInfo(
                        "NPU (Hexagon)",
                        if (cap.npuInfo.supported)
                            "支持 · ${cap.npuInfo.chipLevel.displayName} (${cap.npuInfo.socModel})"
                        else "不支持 (${cap.npuInfo.reason})",
                    )
                }
            }
        }

        // ===== 后端选项 =====
        SectionDivider("选择推理后端")
        BackendPreference.entries.forEach { entry ->
            val enabled = when (entry) {
                BackendPreference.MNN_GPU -> mnnGpuReady
                BackendPreference.MNN_NPU -> mnnNpuReady
                else -> true
            }
            val selected = pref == entry
            val desc = when (entry) {
                BackendPreference.AUTO -> when {
                    mnnGpuReady -> "自动选择（GPU 优先，回退 CPU）"
                    else -> "自动选择（回退 CPU）"
                }
                BackendPreference.MNN_CPU -> "兼容性最好，速度最慢"
                BackendPreference.MNN_GPU -> if (mnnGpuReady) "MNN OpenCL GPU（.mnn 模型）" else "需 libMNN.so + OpenCL 运行时"
                BackendPreference.MNN_NPU -> if (mnnNpuReady)
                    "MNN QNN NPU（需解锁/Root 关 SELinux，否则会崩）"
                else "不可用：需骁龙 + libQnnHtp.so + 解锁/Root（SELinux 限制 CDSP）"
            }
            BackendOptionRow(
                title = entry.displayName,
                desc = desc,
                selected = selected,
                enabled = enabled,
                isActive = !selected && entry.name == activeBackend.name &&
                    pref == BackendPreference.AUTO,
                onClick = {
                    if (enabled) scope.launch {
                        container.settingsRepository.setLlmBackend(entry)
                        // 显式切换后端偏好：重置会话级失败缓存，让被选后端重新尝试
                        // （否则某后端一次失败后整个会话期都被跳过，即便用户主动切到它）
                        container.backendManager.resetSessionFailures()
                    }
                },
            )
        }

        // ===== CPU 提频 =====
        SectionDivider("CPU 提频")
        val cpuBoost by container.settingsRepository.llmCpuBoost.collectAsState(initial = true)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = PrtsColors.BgTertiary),
            shape = RoundedCornerShape(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("推理提频", color = PrtsColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "非 root 用系统提频机制（PerformanceHint + 持续高性能 + 高线程优先级）把大核频率尽量推高，无法锁满频；会增加耗电/发热，高温仍按温控降线程",
                        color = PrtsColors.TextDim, fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = cpuBoost,
                    onCheckedChange = { scope.launch { container.settingsRepository.setLlmCpuBoost(it) } },
                )
            }
        }

        // ===== CPU 投机解码（lookahead）=====
        SectionDivider("投机解码")
        val lookahead by container.settingsRepository.llmLookahead.collectAsState(initial = true)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = PrtsColors.BgTertiary),
            shape = RoundedCornerShape(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Lookahead 投机解码", color = PrtsColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "用历史 n-gram 预测多 token 一次前向验证，CPU 上重复/代码类文本 1.5–3×；无需 draft 模型。" +
                            "仅 MNN CPU 后端生效，默认关闭--首轮无历史时 draft 全 miss 反而数倍拖慢首条回复；多轮重复文本再开。开启后下条消息自动重载",
                        color = PrtsColors.TextDim, fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = lookahead,
                    onCheckedChange = { scope.launch { container.settingsRepository.setLlmLookahead(it) } },
                )
            }
        }

        // ===== 推理参数（线程 / 上下文，对应 iFeng 的 threads / context_size）=====
        SectionDivider("推理参数")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = PrtsColors.BgTertiary),
            shape = RoundedCornerShape(4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 线程数：1..8（与 iFeng SettingsManager.coerceIn(1,8) 对齐）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CPU 线程数", color = PrtsColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("$threads", color = PrtsColors.Gold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { v ->
                        // steps 已让滑块吸附到整数档，但拖动过程仍会回调中间浮点值；
                        // 仅在跨过整数边界时写 DataStore，避免一次拖动产生十几次写。
                        val t = v.toInt().coerceIn(1, 8)
                        if (t != threads) scope.launch { container.settingsRepository.setLlmParams(threads = t) }
                    },
                    valueRange = 1f..8f,
                    steps = 6,  // 8 个整数档：1,2,3,4,5,6,7,8
                    colors = SliderDefaults.colors(
                        thumbColor = PrtsColors.Gold,
                        activeTrackColor = PrtsColors.Gold,
                        inactiveTrackColor = PrtsColors.Border,
                    ),
                )
                Text(
                    "实际生效取 min(设定值, 大核数, 温度上限)。超过大核数会跑到小核，反而变慢更耗电。",
                    color = PrtsColors.TextDim, fontSize = 10.sp,
                )

                Spacer(Modifier.height(4.dp))

                // 上下文长度：滑块 + 数字输入
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上下文长度", color = PrtsColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    BasicTextField(
                        value = contextInput,
                        onValueChange = { contextInput = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier
                            .width(72.dp)
                            .onFocusChanged { state ->
                                contextInputFocused = state.isFocused
                                if (!state.isFocused) {
                                    val parsed = contextInput.toIntOrNull() ?: contextLen
                                    val coerced = coerceContextLen(parsed)
                                    contextInput = coerced.toString()
                                    if (coerced != contextLen) {
                                        scope.launch { container.settingsRepository.setLlmParams(contextLen = coerced) }
                                    }
                                }
                            },
                        textStyle = TextStyle(
                            color = PrtsColors.Gold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(" tokens", color = PrtsColors.TextDim, fontSize = 12.sp)
                }
                Slider(
                    value = contextLen.toFloat(),
                    onValueChange = { v ->
                        // steps 已让滑块吸附到 256 的整数档，但拖动过程仍会回调中间浮点值；
                        // 仅在跨过整数边界时写 DataStore，避免一次拖动产生十几次写。
                        val coerced = coerceContextLen(v.toInt())
                        if (coerced != contextLen) scope.launch { container.settingsRepository.setLlmParams(contextLen = coerced) }
                    },
                    valueRange = MIN_CONTEXT_LEN.toFloat()..MAX_CONTEXT_LEN.toFloat(),
                    steps = (MAX_CONTEXT_LEN - MIN_CONTEXT_LEN) / CONTEXT_LEN_STEP - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = PrtsColors.Gold,
                        activeTrackColor = PrtsColors.Gold,
                        inactiveTrackColor = PrtsColors.Border,
                    ),
                )
                Text(
                    "越大越占内存；超出模型支持长度会加载失败。改值后下条消息自动重载。",
                    color = PrtsColors.TextDim, fontSize = 10.sp,
                )
                // KV cache 内存估算（按当前模型结构 + fp16 估算，仅为量级参考）
                val memoryText = when (val est = memoryEstimate) {
                    is LlmMemoryEstimator.MemoryEstimate.Value ->
                        "约 ${LlmMemoryEstimator.formatMemory(est.bytes)} KV cache（按当前模型结构估算）"
                    LlmMemoryEstimator.MemoryEstimate.Unavailable ->
                        if (activeModelId.isNullOrBlank()) "选择并下载模型后可显示内存估算"
                        else "无法读取模型结构，内存估算不可用"
                }
                Text(memoryText, color = PrtsColors.GoldDim, fontSize = 10.sp)

                Spacer(Modifier.height(4.dp))

                // 最大生成长度（max_tokens）：单次回复 token 上限。per-call 参数（不在重载指纹里），
                // 改后下条消息即生效，无需重载模型。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最大生成长度", color = PrtsColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("$maxTokens", color = PrtsColors.Gold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf(512, 1024, 2048, 4096).forEach { size ->
                        val selected = maxTokens == size
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { scope.launch { container.settingsRepository.setLlmParams(maxTokens = size) } },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) PrtsColors.BgHover else PrtsColors.BgSecondary,
                            ),
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, if (selected) PrtsColors.Gold else PrtsColors.Border,
                            ),
                        ) {
                            Text(
                                "$size",
                                color = if (selected) PrtsColors.GoldBright else PrtsColors.TextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp).fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
                Text("单次回复的 token 上限（约 1 token ≈ 0.6 汉字）。改后下条消息即生效，无需重载。", color = PrtsColors.TextDim, fontSize = 10.sp)
            }
        }

        // ===== 回退链 =====
        SectionDivider("自动回退顺序")
        Text(
            fallbackChain.joinToString("  ›  ") { it.displayName },
            color = PrtsColors.TextSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            "当前激活后端：${activeBackend.displayName}",
            color = PrtsColors.Gold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // ===== 说明 =====
        SectionDivider("说明")
        Text(
            "• MNN CPU 恒可用（libMNN.so 就绪）；OpenCL GPU 与 QNN NPU 视设备/运行时库就绪而定，不可用时自动回退 CPU。",
            color = PrtsColors.TextDim, fontSize = 10.sp,
        )
        Text(
            "• QNN NPU 需骁龙设备 + libQnnHtp.so/Skel；且需解锁 bootloader 或 Root 关 SELinux--锁定量产机 SELinux 拒绝 app 访问 CDSP，会原生崩溃。AUTO 不含 NPU，仅显式选择时尝试。",
            color = PrtsColors.TextDim, fontSize = 10.sp,
        )

        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun BackendOptionRow(
    title: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> PrtsColors.BgHover
                enabled -> PrtsColors.BgTertiary
                else -> PrtsColors.BgSecondary
            },
        ),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) PrtsColors.Gold else PrtsColors.Border,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selected) "●" else "○",
                color = if (selected) PrtsColors.Gold else PrtsColors.TextDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = when {
                            selected -> PrtsColors.GoldBright
                            enabled -> PrtsColors.TextPrimary
                            else -> PrtsColors.TextDim
                        },
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Text("● 使用中", color = PrtsColors.Success, fontSize = 10.sp)
                    }
                }
                Text(desc, color = PrtsColors.TextDim, fontSize = 10.sp)
            }
            if (!enabled) {
                Text("不可用", color = PrtsColors.Danger, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun RowInfo(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = PrtsColors.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = PrtsColors.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionDivider(text: String) {
    Text(
        text,
        color = PrtsColors.GoldDim,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
    HorizontalDivider(color = PrtsColors.AcrylicBorder)
}

// ===== 上下文长度参数 =====
private const val MIN_CONTEXT_LEN = 512
private const val MAX_CONTEXT_LEN = 8192
private const val CONTEXT_LEN_STEP = 256

/**
 * 把任意整数规整到上下文长度的合法档位：先按 [CONTEXT_LEN_STEP] 吸附到最近档位，
 * 再夹到 [MIN_CONTEXT_LEN]..[MAX_CONTEXT_LEN]。用于滑块拖动与输入框失焦校正。
 */
private fun coerceContextLen(value: Int): Int {
    val snapped = ((value + CONTEXT_LEN_STEP / 2) / CONTEXT_LEN_STEP) * CONTEXT_LEN_STEP
    return snapped.coerceIn(MIN_CONTEXT_LEN, MAX_CONTEXT_LEN)
}
