package com.rhodesisland.terminal.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.DownloadState
import com.rhodesisland.terminal.data.model.ModelInfo
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.backend.NpuSupportDetector
import com.rhodesisland.terminal.ui.glass.GlassLargeTitle
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.theme.GlassShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelManagerScreen(container: AppContainer) {
    val scheme = MaterialTheme.colorScheme
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
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        GlassLargeTitle("模型管理") {
            TextButton(onClick = {
                scope.launch {
                    loading = true
                    container.modelManager.fetchModelList()
                    loading = false
                }
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = scheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新", color = scheme.primary)
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = scheme.primary)
            }
            return
        }

        if (modelList.models.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CloudOff, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("无法获取模型列表", color = scheme.onSurface, fontSize = 14.sp)
                    Text("请检查网络或配置 MODEL_LIST_URL", color = scheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 100.dp),
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
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlassShapes.large)
            .frostedGlass(GlassShapes.large, shadowElevation = if (isActive) 10.dp else 4.dp)
            .then(if (isActive) Modifier.border(2.dp, scheme.primary, GlassShapes.large) else Modifier)
            .padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (model.recommended) {
                    Row(
                        modifier = Modifier
                            .clip(GlassShapes.pill)
                            .background(scheme.tertiary.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("推荐", color = scheme.tertiary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (model.description.isNotBlank()) {
                Text(model.description, color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                "大小: ${formatSize(model.size)} · MNN · v${model.version}",
                color = scheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )

            Spacer(Modifier.height(10.dp))

            when (state) {
                is DownloadState.NotDownloaded -> {
                    Button(onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("下载", color = scheme.onPrimary, fontSize = 12.sp)
                    }
                }
                is DownloadState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                        color = scheme.primary,
                        trackColor = scheme.surfaceVariant,
                    )
                    Text(
                        "${formatSize(state.downloadedBytes)} / ${formatSize(state.totalBytes)} (${(state.progress * 100).toInt()}%)",
                        color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = onPause) { Text("暂停", color = scheme.onSurfaceVariant, fontSize = 11.sp) }
                }
                is DownloadState.Paused -> {
                    Text("已暂停", color = scheme.tertiary, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onResume) { Text("继续", color = scheme.primary, fontSize = 11.sp) }
                        TextButton(onClick = onDelete) { Text("删除", color = scheme.error, fontSize = 11.sp) }
                    }
                }
                is DownloadState.Verifying -> {
                    Text("校验中…", color = scheme.primary, fontSize = 11.sp)
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                        color = scheme.primary,
                        trackColor = scheme.surfaceVariant,
                    )
                }
                is DownloadState.Completed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isActive) {
                            Text("✓ 当前使用", color = scheme.tertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Button(onClick = onSetActive, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)) {
                                Text("切换使用", color = scheme.onPrimary, fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = onDelete) { Text("删除", color = scheme.error, fontSize = 11.sp) }
                    }
                }
                is DownloadState.Failed -> {
                    Text("失败：${state.error}", color = scheme.error, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDownload) { Text("重试", color = scheme.primary, fontSize = 11.sp) }
                        TextButton(onClick = onDelete) { Text("删除", color = scheme.error, fontSize = 11.sp) }
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
 * 推理后端选择卡：AUTO / MNN_CPU / MNN_GPU / MNN_NPU，标注支持状态。
 */
@Composable
private fun BackendSelectorCard(container: AppContainer) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val pref by container.settingsRepository.llmBackend.collectAsState(initial = BackendPreference.AUTO)

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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlassShapes.large)
            .frostedGlass(GlassShapes.large, shadowElevation = 4.dp)
            .padding(14.dp),
    ) {
        Column {
            Text("推理后端", color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            BackendStatusRow("MNN CPU", if (mnnCpuReady) "运行时就绪" else "未就绪", mnnCpuReady)
            BackendStatusRow("MNN OpenCL GPU", if (mnnGpuReady) "运行时就绪" else "运行时未就绪", mnnGpuReady)
            val npuStatus = if (!npuInfo.supported) "不支持" else "支持 · ${npuInfo.chipLevel.displayName}"
            BackendStatusRow("MNN QNN NPU", npuStatus + if (mnnNpuReady) "（运行时就绪）" else "", mnnNpuReady)

            Spacer(Modifier.height(8.dp))

            BackendPreference.entries.forEachIndexed { idx, entry ->
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
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (selected) scheme.primary else scheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Box(Modifier.size(10.dp).background(scheme.primary, CircleShape))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.displayName,
                            color = if (selected) scheme.onSurface else if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        val desc = when (entry) {
                            BackendPreference.AUTO -> when {
                                mnnGpuReady -> "自动选择（GPU 优先，回退 CPU）"
                                else -> "自动选择（回退 CPU）"
                            }
                            BackendPreference.MNN_CPU -> "兼容性最好，速度最慢"
                            BackendPreference.MNN_GPU -> if (mnnGpuReady) "MNN OpenCL GPU" else "需 OpenCL 运行时"
                            BackendPreference.MNN_NPU -> if (mnnNpuReady) "MNN QNN NPU（需解锁/Root）" else "需骁龙 + 解锁/Root"
                        }
                        Text(desc, color = scheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                    if (!enabled) Text("不可用", color = scheme.error, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun BackendStatusRow(label: String, status: String, ready: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = scheme.onSurfaceVariant, fontSize = 11.sp)
        Text(status, color = if (ready) scheme.tertiary else scheme.onSurfaceVariant, fontSize = 11.sp)
    }
}
