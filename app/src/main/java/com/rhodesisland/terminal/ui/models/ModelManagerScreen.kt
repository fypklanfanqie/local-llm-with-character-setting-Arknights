package com.rhodesisland.terminal.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.DownloadState
import com.rhodesisland.terminal.data.model.ModelInfo
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.backend.NpuSupportDetector
import com.rhodesisland.terminal.ui.theme.PrtsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelManagerScreen(container: AppContainer) {
    val modelList by container.modelManager.modelList.collectAsState()
    val downloadStates by container.modelManager.downloadStates.collectAsState()
    val activeModelId by container.modelManager.activeLocalModelId.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        container.modelManager.fetchModelList()
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PrtsColors.BgPrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("MODEL MANAGER", color = PrtsColors.GoldBright, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("本地 AI 模型管理", color = PrtsColors.TextDim, fontSize = 12.sp)
            }
            TextButton(onClick = {
                scope.launch {
                    loading = true
                    container.modelManager.fetchModelList()
                    loading = false
                }
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = PrtsColors.Gold)
                Text("刷新", color = PrtsColors.Gold)
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrtsColors.Gold)
            }
            return
        }

        if (modelList.models.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CloudOff, contentDescription = null, tint = PrtsColors.TextDim, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("无法获取模型列表", color = PrtsColors.TextDim, fontSize = 14.sp)
                    Text("请检查网络或配置 MODEL_LIST_URL", color = PrtsColors.TextDim, fontSize = 11.sp)
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { BackendSelectorCard(container) }
            items(modelList.models) { model ->
                ModelCard(
                    model = model,
                    state = downloadStates[model.id] ?: DownloadState.NotDownloaded,
                    isActive = activeModelId == model.id,
                    onDownload = { container.modelManager.download(model) },
                    onPause = { container.modelManager.pause(model.id) },
                    onResume = { container.modelManager.resume(model) },
                    onDelete = { scope.launch { container.modelManager.delete(model.id) } },
                    onSetActive = {
                        scope.launch { container.modelManager.setActiveModel(model.id) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    state: DownloadState,
    isActive: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) PrtsColors.BgCard.copy(alpha = 0.9f) else PrtsColors.BgTertiary
        ),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) PrtsColors.Gold else if (model.recommended) PrtsColors.GoldDim.copy(alpha = 0.5f) else PrtsColors.Border
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, color = PrtsColors.GoldBright, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (model.recommended) {
                            Spacer(Modifier.width(8.dp))
                            Text("⭐ 推荐", color = PrtsColors.WarnYellow, fontSize = 10.sp)
                        }
                    }
                    Text(model.description, color = PrtsColors.TextSecondary, fontSize = 11.sp)
                    Text(
                        "大小: ${formatSize(model.size)} · 格式: MNN · 版本: ${model.version}",
                        color = PrtsColors.TextDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 下载状态 + 操作
            when (state) {
                is DownloadState.NotDownloaded -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = PrtsColors.Gold.copy(alpha = 0.15f))) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("下载", color = PrtsColors.Gold, fontSize = 12.sp)
                        }
                    }
                }
                is DownloadState.Downloading -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = PrtsColors.Gold,
                            trackColor = PrtsColors.BgInput,
                        )
                        Text(
                            "${formatSize(state.downloadedBytes)} / ${formatSize(state.totalBytes)} (${(state.progress * 100).toInt()}%)",
                            color = PrtsColors.GoldDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onPause) {
                                Text("暂停", color = PrtsColors.GoldDim, fontSize = 11.sp)
                            }
                        }
                    }
                }
                is DownloadState.Paused -> {
                    Column {
                        Text("已暂停", color = PrtsColors.WarnYellow, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onResume) {
                                Text("继续", color = PrtsColors.Gold, fontSize = 11.sp)
                            }
                            TextButton(onClick = onDelete) {
                                Text("删除", color = PrtsColors.DangerBright, fontSize = 11.sp)
                            }
                        }
                    }
                }
                is DownloadState.Verifying -> {
                    Column {
                        Text("校验中...", color = PrtsColors.AccentBlue, fontSize = 11.sp)
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = PrtsColors.AccentBlue,
                            trackColor = PrtsColors.BgInput,
                        )
                    }
                }
                is DownloadState.Completed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isActive) {
                            Text("✓ 当前使用", color = PrtsColors.Success, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Button(onClick = onSetActive, colors = ButtonDefaults.buttonColors(containerColor = PrtsColors.Gold.copy(alpha = 0.15f))) {
                                Text("切换使用", color = PrtsColors.Gold, fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = onDelete) {
                            Text("删除", color = PrtsColors.DangerBright, fontSize = 11.sp)
                        }
                    }
                }
                is DownloadState.Failed -> {
                    Column {
                        Text("失败: ${state.error}", color = PrtsColors.DangerBright, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onDownload) {
                                Text("重试", color = PrtsColors.Gold, fontSize = 11.sp)
                            }
                            TextButton(onClick = onDelete) {
                                Text("删除", color = PrtsColors.DangerBright, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024 * 1024)
    val mb = bytes / (1024.0 * 1024)
    return when {
        gb >= 1 -> String.format("%.1f GB", gb)
        mb >= 1 -> String.format("%.0f MB", mb)
        else -> "$bytes B"
    }
}

/**
 * 推理后端选择卡：AUTO / MNN_CPU / MNN_GPU / MNN_NPU。
 *
 * 显示设备 NPU（高通骁龙 + libQnnHtp.so 就绪）支持情况与 MNN 各后端运行时就绪，未就绪时禁用
 * 对应选项并提示。详细设备能力与回退链见 BackendSettingsScreen。
 */
@Composable
private fun BackendSelectorCard(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val pref by container.settingsRepository.llmBackend.collectAsState(initial = BackendPreference.AUTO)

    // NPU 支持（设备 + libQnnHtp.so 运行时就绪），IO 线程读 lazy 缓存
    val mnnCpuReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { container.backendManager.mnnCpuSupported }
    }
    val mnnGpuReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { container.backendManager.mnnGpuSupported }
    }
    val mnnNpuReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { container.backendManager.mnnNpuSupported }
    }
    val npuInfo by produceState(
        initialValue = NpuSupportDetector.NpuSupportInfo(
            false, "", "", "", NpuSupportDetector.ChipLevel.UNSUPPORTED, "探测中…",
        ),
    ) {
        value = withContext(Dispatchers.IO) { container.backendManager.deviceCapability.npuInfo }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PrtsColors.BgTertiary),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PrtsColors.Border),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("推理后端", color = PrtsColors.GoldBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))

            // 支持状态行
            val cpuStatus = if (mnnCpuReady) "运行时就绪" else "未就绪"
            Text(
                "MNN CPU: $cpuStatus",
                color = if (mnnCpuReady) PrtsColors.Success else PrtsColors.WarnYellow,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
            Text(
                "MNN OpenCL GPU: " + if (mnnGpuReady) "运行时就绪" else "运行时未就绪",
                color = if (mnnGpuReady) PrtsColors.Success else PrtsColors.TextDim,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
            val npuStatus = if (!npuInfo.supported) "不支持" else "支持 · ${npuInfo.chipLevel.displayName}"
            Text(
                "MNN QNN NPU: $npuStatus" + if (mnnNpuReady) "（运行时就绪）" else "",
                color = if (mnnNpuReady) PrtsColors.Success else PrtsColors.TextDim,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(8.dp))

            BackendPreference.entries.forEach { entry ->
                val enabled = when (entry) {
                    BackendPreference.MNN_GPU -> mnnGpuReady
                    BackendPreference.MNN_NPU -> mnnNpuReady
                    else -> true
                }
                val selected = pref == entry
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) {
                            scope.launch { container.settingsRepository.setLlmBackend(entry) }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selected) "●" else "○",
                        color = if (selected) PrtsColors.Gold else PrtsColors.TextDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entry.displayName,
                            color = when {
                                selected -> PrtsColors.GoldBright
                                enabled -> PrtsColors.TextPrimary
                                else -> PrtsColors.TextDim
                            },
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                        val desc = when (entry) {
                            BackendPreference.AUTO -> when {
                                mnnGpuReady -> "自动选择（GPU 优先，回退 CPU）"
                                else -> "自动选择（回退 CPU）"
                            }
                            BackendPreference.MNN_CPU -> "兼容性最好，速度最慢"
                            BackendPreference.MNN_GPU -> if (mnnGpuReady) "MNN OpenCL GPU（.mnn 模型）" else "需 libMNN.so + OpenCL 运行时"
                            BackendPreference.MNN_NPU -> if (mnnNpuReady) "MNN QNN NPU（需解锁/Root 关 SELinux）" else "不可用：需骁龙+libQnnHtp.so+解锁/Root（SELinux 限制 CDSP）"
                        }
                        Text(desc, color = PrtsColors.TextDim, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
