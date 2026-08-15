package com.rhodesisland.terminal.ui.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.rhodesisland.terminal.data.remote.NeteaseSong
import com.rhodesisland.terminal.data.repository.BgmTrack
import com.rhodesisland.terminal.ui.glass.GlassLargeTitle
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.theme.GlassShapes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

@Composable
fun MusicScreen(container: AppContainer) {
    val scheme = MaterialTheme.colorScheme
    // 用户播放列表（持久化：本地导入 + 网易云搜索添加）
    val userPlaylist by container.musicLibrary.playlist.collectAsState(initial = emptyList())
    // 内置 BGM 目录（AssetPaths.BGM：assets/music 本地 mp3 + 网易云 EP 曲目），常驻列表最前、不可移除。
    // 这就是本应用保留的内置音乐（方舟 OST），进程重启后依然在。
    val builtIn = remember { container.assetRepository.getBgmList() }
    val builtInKeys = remember(builtIn) { builtIn.mapTo(mutableSetOf()) { it.key } }
    // 播放列表 = 内置目录 + 用户曲目（AudioManager 以下标索引播放，须用同一组合列表）
    val playlist = remember(userPlaylist, builtIn) { builtIn + userPlaylist }
    val currentIndex by container.audioManager.currentIndexFlow.collectAsState(initial = 0)
    val isPlaying by container.audioManager.isPlayingFlow.collectAsState(initial = false)
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    val repeatMode by container.settingsRepository.musicRepeatMode.collectAsState(initial = 0)
    val shuffle by container.settingsRepository.musicShuffle.collectAsState(initial = false)
    val favorites by container.settingsRepository.musicFavorites.collectAsState(initial = emptySet())
    var showFavoritesOnly by remember { mutableStateOf(false) }
    val volume by container.settingsRepository.volume.collectAsState(initial = 70)
    var volumeSlider by remember { mutableFloatStateOf(volume.toFloat()) }
    LaunchedEffect(volume) { volumeSlider = volume.toFloat() }
    val scope = rememberCoroutineScope()

    val bgmError by container.audioManager.error.collectAsState(initial = null)

    var coverUrl by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }

    var searchResults by remember { mutableStateOf<List<NeteaseSong>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }

    val displayList = remember(playlist, showFavoritesOnly, favorites) {
        if (showFavoritesOnly) playlist.filter { it.key in favorites } else playlist
    }

    val currentTrack = playlist.getOrNull(currentIndex)

    // 持久化的循环/随机值先喂给管理器（须先于 playlist effect，保证 initBgm 时已就位）
    LaunchedEffect(repeatMode) { container.audioManager.setRepeatMode(repeatMode) }
    LaunchedEffect(shuffle) { container.audioManager.setShuffle(shuffle) }

    // 首次进音乐页自动加载第一首；之后列表增删/切页返回只同步下标，不重置第一首、不打断播放。
    // 以播放器是否已构建为界：进程被杀重启后 bgmPlayer 为 null → 会正确重新起播。
    LaunchedEffect(playlist) {
        val mgr = container.audioManager
        if (playlist.isEmpty()) {
            if (mgr.isPlayerInitialized()) mgr.syncIndex(playlist)
            return@LaunchedEffect
        }
        if (!mgr.isPlayerInitialized()) {
            mgr.initBgm(playlist)
            mgr.loadTrack(0, playlist)
        } else {
            mgr.syncIndex(playlist)
        }
    }

    // 网易云搜索：只填充搜索结果区，绝不改动播放列表（本地曲目常驻）
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            searchLoading = false
            return@LaunchedEffect
        }
        delay(450)
        searchLoading = true
        searchResults = NeteaseApiService.search(searchQuery)
        searchLoading = false
    }

    LaunchedEffect(currentIndex) {
        val track = playlist.getOrNull(currentIndex)
        val nid = track?.neteaseId
        if (nid == null) {
            coverUrl = null; lyrics = emptyList(); lyricLoading = false
            return@LaunchedEffect
        }
        coverUrl = null; lyrics = emptyList(); lyricLoading = true
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

    // 本地音乐导入：系统文件选择器多选音频 → 拷贝到内部存储并加入播放列表
    val context = LocalContext.current
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        if (uris.isNotEmpty()) scope.launch { container.musicLibrary.addLocalUris(uris) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        GlassLargeTitle("音乐") {
            IconButton(onClick = { importPicker.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Filled.Add, contentDescription = "导入本地音乐", tint = scheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                showSearch = !showSearch
                if (!showSearch) searchQuery = ""
            }) {
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = if (showSearch) scheme.primary else scheme.onSurfaceVariant)
            }
        }

        // 当前曲目
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(GlassShapes.large)
                .frostedGlass(GlassShapes.large, shadowElevation = 6.dp)
                .padding(16.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 封面
                    if (currentTrack?.neteaseId != null) {
                        if (coverUrl != null) {
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = "专辑封面",
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(scheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = scheme.primary, strokeWidth = 2.dp)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("#${currentIndex + 1} / ${playlist.size}", color = scheme.onSurfaceVariant, fontSize = 11.sp)
                        Text(
                            currentTrack?.name ?: "未在播放",
                            color = scheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                        )
                        Text(
                            if (currentTrack?.neteaseId != null) "网易云音乐" else "本地资源",
                            color = scheme.primary, fontSize = 10.sp,
                        )
                    }
                }

                if (currentTrack?.neteaseId != null) {
                    Spacer(Modifier.height(8.dp))
                    LyricView(
                        lyrics = lyrics,
                        positionMs = currentPosition,
                        loading = lyricLoading,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                }

                // 进度
                val seekProgress = if (duration > 0) {
                    if (seekDragging) seekValue else currentPosition.toFloat() / duration
                } else 0f
                Slider(
                    value = seekProgress.coerceIn(0f, 1f),
                    onValueChange = { seekDragging = true; seekValue = it },
                    onValueChangeFinished = {
                        seekDragging = false
                        if (duration > 0) container.audioManager.seekTo((seekValue * duration).toLong())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = scheme.primary,
                        activeTrackColor = scheme.primary,
                        inactiveTrackColor = scheme.surfaceVariant,
                    ),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val posMs = if (seekDragging && duration > 0) (seekValue * duration).toLong() else currentPosition
                    Text(formatTime(posMs), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                    Text(formatTime(duration), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }

                // 控制
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        scope.launch { container.settingsRepository.setMusicShuffle(!shuffle) }
                    }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "随机播放", tint = if (shuffle) scheme.primary else scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        container.audioManager.prevTrack(playlist); container.audioManager.playMusic()
                    }) { Icon(Icons.Filled.SkipPrevious, contentDescription = "上一曲", tint = scheme.onSurface, modifier = Modifier.size(28.dp)) }
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(scheme.primary)
                            .clickable { container.audioManager.togglePlay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "播放/暂停",
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    IconButton(onClick = {
                        container.audioManager.nextTrack(playlist); container.audioManager.playMusic()
                    }) { Icon(Icons.Filled.SkipNext, contentDescription = "下一曲", tint = scheme.onSurface, modifier = Modifier.size(28.dp)) }
                    IconButton(onClick = {
                        val newMode = (repeatMode + 1) % 3
                        scope.launch { container.settingsRepository.setMusicRepeatMode(newMode) }
                        container.audioManager.setRepeatMode(newMode)
                    }) {
                        Icon(
                            if (repeatMode == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = "播放模式",
                            tint = if (repeatMode != 0) scheme.primary else scheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showFavoritesOnly = !showFavoritesOnly }) {
                        Icon(
                            if (showFavoritesOnly) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (showFavoritesOnly) scheme.primary else scheme.onSurfaceVariant,
                        )
                    }
                }

                // 音量
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = "音量", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
                            thumbColor = scheme.primary,
                            activeTrackColor = scheme.primary,
                            inactiveTrackColor = scheme.surfaceVariant,
                        ),
                    )
                    Text("${volumeSlider.toInt()}", color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                }
            }
        }

        // 搜索栏
        if (showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜索歌曲…", color = scheme.onSurfaceVariant, fontSize = 13.sp) },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = scheme.surface.copy(alpha = 0.6f),
                    unfocusedContainerColor = scheme.surface.copy(alpha = 0.6f),
                    focusedTextColor = scheme.onSurface,
                    unfocusedTextColor = scheme.onSurface,
                    focusedIndicatorColor = scheme.primary,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = scheme.primary,
                ),
            )
        }

        // 播放列表（本地曲目常驻）+ 网易云搜索结果（独立区，合并滚动）+ 错误提示浮层
        val searching = showSearch && searchQuery.isNotBlank()
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (playlist.isEmpty() && !searching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无音乐", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text("导入本地音乐，或搜索网易云添加", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { importPicker.launch(arrayOf("audio/*")) }) { Text("导入本地音乐") }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                ) {
                    if (searching) {
                        item {
                            Text("搜索结果", color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        when {
                            searchLoading -> item {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp).padding(vertical = 4.dp),
                                    color = scheme.primary,
                                    strokeWidth = 2.dp,
                                )
                            }
                            searchResults.isEmpty() -> item {
                                Text("未找到相关歌曲", color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                            }
                            else -> items(searchResults, key = { "result_${it.id}" }) { song ->
                                val already = playlist.any { it.key == "search_${song.id}" }
                                SearchResultRow(
                                    song = song,
                                    alreadyAdded = already,
                                    onAdd = {
                                        scope.launch {
                                            container.musicLibrary.addOnlineTrack(
                                                BgmTrack(
                                                    file = "https://music.163.com/song/media/outer/url?id=${song.id}.mp3",
                                                    name = if (song.artist.isNotBlank()) "${song.name} - ${song.artist}" else song.name,
                                                    key = "search_${song.id}",
                                                    neteaseId = song.id,
                                                )
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                    when {
                        playlist.isEmpty() -> item {
                            // 正在搜索但本地列表为空：仍显示导入引导
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("本地暂无音乐", color = scheme.onSurfaceVariant, fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { importPicker.launch(arrayOf("audio/*")) }) { Text("导入本地音乐") }
                            }
                        }
                        displayList.isEmpty() -> item {
                            Text("暂无收藏的歌曲", color = scheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                        }
                        else -> items(displayList, key = { it.key }) { track ->
                            val originalIndex = playlist.indexOf(track)
                            PlaylistRow(
                                track = track,
                                isActive = originalIndex == currentIndex,
                                isPlaying = isPlaying && originalIndex == currentIndex,
                                isFav = track.key in favorites,
                                onClick = {
                                    if (track.file.isNotBlank()) {
                                        container.audioManager.loadTrack(originalIndex, playlist)
                                        container.audioManager.playMusic()
                                    }
                                },
                                onToggleFav = {
                                    scope.launch { container.settingsRepository.toggleMusicFavorite(track.key) }
                                },
                                onRemove = if (track.key in builtInKeys) null else {
                                    { scope.launch { container.musicLibrary.removeTrack(track) } }
                                },
                            )
                        }
                    }
                }
            }

            bgmError?.let { err ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                    action = {
                        TextButton(onClick = { container.audioManager.clearError() }) { Text("知道了") }
                    },
                ) { Text(err, color = scheme.error, fontSize = 12.sp) }
            }
        }
    }
}

/**
 * 歌词视图：随播放进度高亮当前行并自动滚动居中，紫罗兰流光。
 */
@Composable
private fun LyricView(
    lyrics: List<LrcLine>,
    positionMs: Long,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val activeIndex = remember(lyrics, positionMs) { LrcParser.currentIndex(lyrics, positionMs) }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -listState.layoutInfo.viewportSize.height / 2,
            )
        }
    }

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
                                scaleX = scale; scaleY = scale
                                shape = RoundedCornerShape(50); clip = true
                                shadowElevation = 8f * scale
                            }
                            .background(
                                Brush.horizontalGradient(listOf(scheme.primary.copy(alpha = 0.5f), scheme.primary, scheme.primary.copy(alpha = 0.5f))),
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
            Text("暂无歌词", color = scheme.onSurfaceVariant, fontSize = 12.sp)
        }
        return
    }

    Box(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.5f + 0.5f * glowPhase }
                .background(
                    Brush.radialGradient(
                        colors = listOf(scheme.primary.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(0.5f, 0.5f),
                        radius = 0.75f,
                    ),
                ),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to scheme.surface.copy(alpha = 0.6f),
                            0.28f to Color.Transparent,
                        ),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.34f),
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.72f to Color.Transparent,
                            1f to scheme.surface.copy(alpha = 0.6f),
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

@Composable
private fun LyricLine(
    text: String,
    distance: Int,
    isActive: Boolean,
    glowPhase: Float,
) {
    val scheme = MaterialTheme.colorScheme
    val targetScale = when {
        isActive -> 1.18f
        kotlin.math.abs(distance) <= 1 -> 0.98f
        else -> (1f - kotlin.math.min(kotlin.math.abs(distance), 6) * 0.05f).coerceAtLeast(0.7f)
    }
    val targetAlpha = when {
        isActive -> 1f
        kotlin.math.abs(distance) <= 1 -> 0.78f
        else -> (1f - kotlin.math.min(kotlin.math.abs(distance), 6) * 0.12f).coerceAtLeast(0.25f)
    }
    val targetOffsetX = if (!isActive) {
        (if (distance % 2 == 0) 1 else -1) * kotlin.math.min(kotlin.math.abs(distance), 5) * 7f
    } else 0f

    val jellySpec = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
    val scale by animateFloatAsState(targetScale, jellySpec, label = "lineScale")
    val alpha by animateFloatAsState(targetAlpha, tween(360, easing = FastOutSlowInEasing), label = "lineAlpha")
    val offsetX by animateFloatAsState(targetOffsetX, jellySpec, label = "lineOffsetX")

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entryScale by animateFloatAsState(
        if (entered) 1f else 0.6f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "entry",
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
                if (isActive) rotationX = (1f - glowPhase) * 4f
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isActive) {
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = scheme.primary.copy(alpha = 0.18f + 0.18f * glowPhase),
                modifier = Modifier.graphicsLayer { scaleX = 1.04f; scaleY = 1.04f }.blur(6.dp).alpha(0.6f + 0.4f * glowPhase),
            )
            val brush = Brush.horizontalGradient(
                colors = listOf(
                    scheme.primary.copy(alpha = 0.6f),
                    scheme.primary,
                    Color.White.copy(alpha = 0.95f),
                    scheme.primary,
                    scheme.primary.copy(alpha = 0.6f),
                ),
                startX = 0f,
                endX = (0.55f + 0.45f * glowPhase) * 1000f,
            )
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    brush = brush,
                    shadow = androidx.compose.ui.graphics.Shadow(color = scheme.primary.copy(alpha = 0.5f), blurRadius = 12f),
                ),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            Text(
                text = text,
                fontSize = if (kotlin.math.abs(distance) <= 1) 13.sp else 12.sp,
                fontWeight = if (kotlin.math.abs(distance) <= 1) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                color = scheme.onSurfaceVariant.copy(alpha = 0.5f + 0.5f * (1f - kotlin.math.min(kotlin.math.abs(distance), 4) * 0.18f)),
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
    onRemove: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (isActive) Modifier.background(scheme.primary.copy(alpha = 0.14f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(if (isActive) scheme.primary else scheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (isActive && isPlaying) "▶" else "♪", color = if (isActive) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                track.name,
                color = if (isActive) scheme.primary else scheme.onSurface,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Text(
                if (track.neteaseId != null) "网易云" else "本地",
                color = if (track.neteaseId != null) scheme.primary else scheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        IconButton(onClick = onToggleFav, modifier = Modifier.size(30.dp)) {
            Icon(
                if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "收藏",
                tint = if (isFav) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "移除",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 网易云搜索结果行：歌名 + 歌手 + 添加/已添加。 */
@Composable
private fun SearchResultRow(
    song: NeteaseSong,
    alreadyAdded: Boolean,
    onAdd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(song.name, color = scheme.onSurface, fontSize = 13.sp, maxLines = 1)
            if (song.artist.isNotBlank()) {
                Text(song.artist, color = scheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
            }
        }
        TextButton(
            onClick = onAdd,
            enabled = !alreadyAdded,
            modifier = Modifier.size(height = 32.dp, width = 72.dp),
        ) {
            if (alreadyAdded) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(if (alreadyAdded) "已添加" else "添加", fontSize = 12.sp)
        }
    }
}
