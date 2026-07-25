package com.rhodesisland.terminal.ui.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.remote.LrcLine
import com.rhodesisland.terminal.data.remote.LrcParser
import com.rhodesisland.terminal.data.remote.NeteaseApiService
import com.rhodesisland.terminal.data.repository.BgmTrack
import com.rhodesisland.terminal.ui.theme.PrtsColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** EP 筛选顺序（与网页版 musicData.js EP_ORDER 一致） */
private val MUSIC_EP_ORDER = listOf("系统", "Y-7", "Y-6", "Y-5", "Y-4", "Y-3", "Y-2", "Y-0～Y-1", "Overseas")

/** 毫秒 -> mm:ss */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

@Composable
fun MusicScreen(container: AppContainer) {
    val playlist = remember { container.assetRepository.getBgmList() }
    // 播放状态与当前曲目下标以 AudioManager 的真实状态为准（由 ExoPlayer 回调驱动），
    // 避免 UI 乐观设置后与播放器实际状态脱节
    val currentIndex by container.audioManager.currentIndexFlow.collectAsState(initial = 0)
    val isPlaying by container.audioManager.isPlayingFlow.collectAsState(initial = false)
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    val repeatMode by container.settingsRepository.musicRepeatMode.collectAsState(initial = 0)
    val favorites by container.settingsRepository.musicFavorites.collectAsState(initial = emptySet())
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var selectedEp by remember { mutableStateOf<String?>(null) }
    val volume by container.settingsRepository.volume.collectAsState(initial = 70)
    var volumeSlider by remember { mutableFloatStateOf(volume.toFloat()) }
    LaunchedEffect(volume) { volumeSlider = volume.toFloat() }
    val scope = rememberCoroutineScope()

    // BGM 播放错误（资源缺失 / 加载失败）提示
    val bgmError by container.audioManager.error.collectAsState(initial = null)

    // 当前曲目封面与歌词（仅网易云曲目，本地曲目不显示）
    var coverUrl by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }

    val displayList = remember(playlist, searchQuery, showFavoritesOnly, favorites, selectedEp) {
        var list = playlist
        if (selectedEp != null) {
            list = list.filter { it.ep == selectedEp }
        }
        if (searchQuery.isNotBlank()) {
            list = list.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        if (showFavoritesOnly) {
            list = list.filter { it.key in favorites }
        }
        list
    }

    val currentTrack = playlist.getOrNull(currentIndex)

    // 初始化 BGM 播放器。仅在首次进入时自动加载第一首；
    // 从其他页面返回时保持当前播放状态，不重置曲目。
    LaunchedEffect(Unit) {
        val wasInitialized = container.audioManager.isPlayerInitialized()
        container.audioManager.initBgm(playlist)
        if (!wasInitialized && playlist.isNotEmpty()) {
            container.audioManager.loadTrack(0, playlist)
        }
    }

    // 切歌时拉取封面与歌词（本地曲目 neteaseId 为 null，直接清空）
    LaunchedEffect(currentIndex) {
        val track = playlist.getOrNull(currentIndex)
        val nid = track?.neteaseId
        if (nid == null) {
            coverUrl = null
            lyrics = emptyList()
            lyricLoading = false
            return@LaunchedEffect
        }
        coverUrl = null
        lyrics = emptyList()
        lyricLoading = true
        // 封面与歌词并行获取；用 coroutineScope 使 async 成为 LaunchedEffect 协程的子协程，
        // 切歌重启 LaunchedEffect 时旧请求会一并取消，避免旧数据覆盖新曲目
        coroutineScope {
            val coverDeferred = async { NeteaseApiService.fetchCover(nid) }
            val lyricDeferred = async {
                val raw = NeteaseApiService.fetchLyric(nid)
                LrcParser.parse(raw)
            }
            coverUrl = coverDeferred.await()
            lyrics = lyricDeferred.await()
        }
        lyricLoading = false
    }

    // 进度更新
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seekDragging by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = container.audioManager.getCurrentPosition()
            duration = container.audioManager.getDuration()
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PrtsColors.BgPrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Text("AUDIO TERMINAL", modifier = Modifier.padding(16.dp), color = PrtsColors.GoldBright, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // 当前曲目（标题左侧信息，右侧封面；本地曲目无封面/歌词）
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = PrtsColors.BgTertiary),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text("#${currentIndex + 1} / ${playlist.size}", color = PrtsColors.GoldDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text(currentTrack?.name ?: "NO TRACK", color = PrtsColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (currentTrack?.neteaseId != null) "SOURCE: NETEASE CLOUD" else "SOURCE: LOCAL ASSET",
                        color = PrtsColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
                // 封面（仅网易云曲目）
                if (currentTrack?.neteaseId != null) {
                    if (coverUrl != null) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = "专辑封面",
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        // 封面加载占位
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(PrtsColors.BgInput),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = PrtsColors.GoldDim,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }

            // 歌词区（仅网易云曲目）
            if (currentTrack?.neteaseId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LyricView(
                    lyrics = lyrics,
                    positionMs = currentPosition,
                    loading = lyricLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // 进度（可拖动 seek）
        val seekProgress = if (duration > 0) {
            if (seekDragging) seekValue else currentPosition.toFloat() / duration
        } else 0f
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Slider(
                value = seekProgress.coerceIn(0f, 1f),
                onValueChange = { seekDragging = true; seekValue = it },
                onValueChangeFinished = {
                    seekDragging = false
                    if (duration > 0) container.audioManager.seekTo((seekValue * duration).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = PrtsColors.GoldBright,
                    activeTrackColor = PrtsColors.Gold,
                    inactiveTrackColor = PrtsColors.BgInput,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val posMs = if (seekDragging && duration > 0) (seekValue * duration).toLong() else currentPosition
                Text(formatTime(posMs), color = PrtsColors.TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(formatTime(duration), color = PrtsColors.TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // 音量
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.VolumeUp, tint = PrtsColors.TextDim, contentDescription = "音量", modifier = Modifier.size(18.dp))
            Slider(
                value = volumeSlider.coerceIn(0f, 100f),
                onValueChange = {
                    volumeSlider = it
                    container.audioManager.applyVolume(it.toInt())
                },
                onValueChangeFinished = {
                    scope.launch { container.settingsRepository.setVolume(volumeSlider.toInt()) }
                },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = PrtsColors.GoldBright,
                    activeTrackColor = PrtsColors.Gold,
                    inactiveTrackColor = PrtsColors.BgInput,
                ),
            )
            Text(
                "${volumeSlider.toInt()}",
                color = PrtsColors.TextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.End,
            )
        }

        // 控制
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                container.audioManager.prevTrack(playlist)
                container.audioManager.playMusic()
            }) {
                Icon(Icons.Filled.SkipPrevious, tint = PrtsColors.Gold, contentDescription = "上一曲")
            }
            IconButton(onClick = {
                container.audioManager.togglePlay()
            }) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, tint = PrtsColors.Gold, contentDescription = "播放")
            }
            IconButton(onClick = {
                container.audioManager.nextTrack(playlist)
                container.audioManager.playMusic()
            }) {
                Icon(Icons.Filled.SkipNext, tint = PrtsColors.Gold, contentDescription = "下一曲")
            }
            IconButton(onClick = {
                showSearch = !showSearch
                if (!showSearch) searchQuery = ""
            }) {
                Icon(Icons.Filled.Search, tint = if (showSearch) PrtsColors.Gold else PrtsColors.TextDim, contentDescription = "搜索")
            }
            IconButton(onClick = { showFavoritesOnly = !showFavoritesOnly }) {
                Icon(if (showFavoritesOnly) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, tint = if (showFavoritesOnly) PrtsColors.Gold else PrtsColors.TextDim, contentDescription = "收藏")
            }
            IconButton(onClick = {
                val newMode = if (repeatMode == 1) 0 else 1
                scope.launch { container.settingsRepository.setMusicRepeatMode(newMode) }
                container.audioManager.setRepeatMode(newMode)
            }) {
                Icon(Icons.Filled.Repeat, tint = if (repeatMode == 1) PrtsColors.Gold else PrtsColors.TextDim, contentDescription = "循环")
            }
        }

        // 搜索栏
        if (showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜索歌曲...", color = PrtsColors.TextDim, fontSize = 13.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = PrtsColors.BgInput,
                    unfocusedContainerColor = PrtsColors.BgInput,
                    focusedTextColor = PrtsColors.TextPrimary,
                    unfocusedTextColor = PrtsColors.TextPrimary,
                ),
                singleLine = true,
            )
        }

        // EP 筛选
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(MUSIC_EP_ORDER) { ep ->
                FilterChip(
                    selected = selectedEp == ep,
                    onClick = { selectedEp = if (selectedEp == ep) null else ep },
                    label = { Text(ep, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrtsColors.Gold,
                        selectedLabelColor = PrtsColors.BgPrimary,
                        containerColor = PrtsColors.BgInput,
                        labelColor = PrtsColors.TextSecondary,
                    ),
                )
            }
        }

        // 播放列表
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(8.dp),
        ) {
            items(displayList) { track ->
                val originalIndex = playlist.indexOf(track)
                val isFav = track.key in favorites
                PlaylistRow(
                    track = track,
                    isActive = originalIndex == currentIndex,
                    isPlaying = isPlaying && originalIndex == currentIndex,
                    isFav = isFav,
                    onClick = {
                        if (track.file.isNotBlank()) {
                            container.audioManager.loadTrack(originalIndex, playlist)
                            container.audioManager.playMusic()
                        }
                    },
                    onToggleFav = {
                        scope.launch { container.settingsRepository.toggleMusicFavorite(track.key) }
                    },
                )
            }
        }

        // 资源缺失 / 播放失败 提示
        bgmError?.let { err ->
            Snackbar(
                modifier = Modifier.padding(8.dp),
                action = {
                    TextButton(onClick = { container.audioManager.clearError() }) {
                        Text("知道了", color = PrtsColors.Gold)
                    }
                },
            ) { Text(err, color = PrtsColors.DangerBright, fontSize = 12.sp) }
        }
    }
}

/**
 * 歌词视图：随播放进度高亮当前行并自动滚动居中。
 * 包含华丽「果冻感」动效：
 *  - 当前行：弹簧缩放 + 轻微弹跳位移 + 流光描边 + 双层光晕
 *  - 非当前行：随距离当前行的远近产生错位缩放/位移/透明度（洗牌果冻感）
 *  - 列表滚动：行进入视口时由弹起态沉降复位（果冻弹跳）
 *  - 背景：跟随当前行的金色辉光呼吸
 * 本地曲目不调用（neteaseId 为 null 时外部不会传入歌词）。
 */
@Composable
private fun LyricView(
    lyrics: List<LrcLine>,
    positionMs: Long,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val activeIndex = remember(lyrics, positionMs) { LrcParser.currentIndex(lyrics, positionMs) }

    // 进度变化时自动滚动到当前歌词行（居中，带弹簧果冻感）
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -listState.layoutInfo.viewportSize.height / 2,
            )
        }
    }

    // 背景辉光呼吸（随当前行在歌词中的相对位置缓慢流动）
    val glowAnim = remember { Animatable(0f) }
    var glowPhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            glowAnim.animateTo(1f, tween(4200, easing = FastOutSlowInEasing))
            glowAnim.animateTo(0f, tween(4200, easing = FastOutSlowInEasing))
            glowPhase = glowAnim.value
        }
    }
    glowPhase = glowAnim.value

    if (loading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 加载态：三条金色果冻脉冲条
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { idx ->
                    val loadAnim = remember { Animatable(0.4f) }
                    var scale by remember { mutableFloatStateOf(0.4f) }
                    LaunchedEffect(Unit) {
                        delay(idx * 160L)
                        while (true) {
                            loadAnim.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
                            loadAnim.animateTo(0.4f, tween(600, easing = FastOutSlowInEasing))
                            scale = loadAnim.value
                        }
                    }
                    scale = loadAnim.value
                    Box(
                        modifier = Modifier
                            .size(width = 26.dp, height = 6.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                shape = RoundedCornerShape(50)
                                clip = true
                                shadowElevation = 8f * scale
                            }
                            .background(
                                Brush.horizontalGradient(
                                    listOf(PrtsColors.GoldDim, PrtsColors.GoldBright, PrtsColors.GoldDim)
                                ),
                                RoundedCornerShape(50),
                            ),
                    )
                }
            }
        }
        return
    }
    if (lyrics.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无歌词",
                color = PrtsColors.TextDim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )
        }
        return
    }

    Box(modifier = modifier.clip(RoundedCornerShape(10.dp))) {
        // 跟随当前行的柔和金色辉光（呼吸）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.5f + 0.5f * glowPhase }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            PrtsColors.GoldGlow.copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                        center = Offset(0.5f, 0.5f),
                        radius = 0.75f,
                    ),
                ),
        )

        // 顶部/底部渐隐遮罩，制造聚光灯聚焦当前行的质感
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    // 上遮罩
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to PrtsColors.BgTertiary.copy(alpha = 0.95f),
                            0.28f to Color.Transparent,
                        ),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.34f),
                    )
                    // 下遮罩
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.72f to Color.Transparent,
                            1f to PrtsColors.BgTertiary.copy(alpha = 0.95f),
                        ),
                        topLeft = Offset(0f, size.height * 0.66f),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.34f),
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            items(lyrics.size) { i ->
                LyricLine(
                    text = lyrics[i].text,
                    distance = i - activeIndex,
                    isActive = i == activeIndex,
                    glowPhase = glowPhase,
                )
            }
        }
    }
}

/**
 * 单行歌词。依据与当前行的距离产生：
 *  - 弹簧缩放（越近越大，当前行最大）
 *  - 错位位移（洗牌果冻：非当前行前后交错偏移）
 *  - 透明度渐变
 *  - 当前行附带流光描边 + 双层呼吸光晕
 */
@Composable
private fun LyricLine(
    text: String,
    distance: Int,
    isActive: Boolean,
    glowPhase: Float,
) {
    // 目标缩放：当前行 1.18，相邻 1.0，越远越小（但保留可读性）
    val targetScale = when {
        isActive -> 1.22f
        distance == -1 -> 0.98f
        distance == 1 -> 0.98f
        else -> (1f - kotlin.math.min(kotlin.math.abs(distance), 6) * 0.05f).coerceAtLeast(0.7f)
    }
    // 目标透明度
    val targetAlpha = when {
        isActive -> 1f
        distance == -1 || distance == 1 -> 0.78f
        else -> (1f - kotlin.math.min(kotlin.math.abs(distance), 6) * 0.12f).coerceAtLeast(0.25f)
    }
    // 错位果冻位移：奇数距离向左漂、偶数向右漂，制造洗牌抖动
    val targetOffsetX = if (!isActive) {
        (if (distance % 2 == 0) 1 else -1) * kotlin.math.min(kotlin.math.abs(distance), 5) * 7f
    } else 0f

    // 弹簧动画：果冻弹跳核心（低阻尼、高刚度 → 回弹过冲）
    val jellySpec = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
    val scale by animateFloatAsState(targetScale, jellySpec, label = "lineScale")
    val alpha by animateFloatAsState(targetAlpha, tween(360, easing = FastOutSlowInEasing), label = "lineAlpha")
    val offsetX by animateFloatAsState(targetOffsetX, jellySpec, label = "lineOffsetX")

    // 进入视口的弹起沉降（果冻到位感）：用 key + LaunchedEffect 触发一次弹簧
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entryScale by animateFloatAsState(
        if (entered) 1f else 0.6f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "entry",
    )
    val entryY by animateFloatAsState(
        if (entered) 0f else 26f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "entryY",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp)
            .graphicsLayer {
                this.scaleX = scale * entryScale
                this.scaleY = scale * entryScale
                this.alpha = alpha
                this.translationX = offsetX
                this.translationY = entryY
                // 当前行轻微 3D 旋转，强化立体果冻感
                if (isActive) {
                    rotationX = (1f - glowPhase) * 4f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 当前行：流光描边文字（金色渐变 + 呼吸亮度）
        if (isActive) {
            // 外层光晕
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = PrtsColors.Gold.copy(alpha = 0.18f + 0.18f * glowPhase),
                letterSpacing = 1.sp,
                modifier = Modifier
                    .graphicsLayer { scaleX = 1.04f; scaleY = 1.04f }
                    .blur(6.dp)
                    .alpha(0.6f + 0.4f * glowPhase),
            )
            // 流光文字
            val brush = Brush.horizontalGradient(
                colors = listOf(
                    PrtsColors.GoldDim,
                    PrtsColors.GoldBright,
                    Color.White.copy(alpha = 0.95f),
                    PrtsColors.GoldBright,
                    PrtsColors.GoldDim,
                ),
                startX = 0f,
                endX = (0.55f + 0.45f * glowPhase) * 1000f,
            )
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp,
                style = androidx.compose.ui.text.TextStyle(
                    brush = brush,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = PrtsColors.Gold.copy(alpha = 0.5f),
                        blurRadius = 12f,
                    ),
                ),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            Text(
                text = text,
                fontSize = if (kotlin.math.abs(distance) <= 1) 13.sp else 12.sp,
                fontWeight = if (kotlin.math.abs(distance) <= 1) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                color = PrtsColors.TextSecondary.copy(alpha = 0.5f + 0.5f * (1f - kotlin.math.min(kotlin.math.abs(distance), 4) * 0.18f)),
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun PlaylistRow(
    track: BgmTrack,
    isActive: Boolean,
    isPlaying: Boolean,
    isFav: Boolean,
    onClick: () -> Unit,
    onToggleFav: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(if (isActive) PrtsColors.BgCard else PrtsColors.BgSecondary, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "♪",
            color = if (isActive) PrtsColors.Gold else PrtsColors.TextDim,
            fontSize = 14.sp,
        )
        Text(
            track.name,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            color = if (isActive) PrtsColors.GoldBright else PrtsColors.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
        )
        IconButton(onClick = onToggleFav, modifier = Modifier.size(32.dp)) {
            Icon(
                if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "收藏",
                tint = if (isFav) PrtsColors.Gold else PrtsColors.TextDim,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
