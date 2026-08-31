package com.rhodesisland.terminal.ui.moment

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.repository.MomentRepository
import com.rhodesisland.terminal.ui.moment.MomentsViewModel.PostUi
import com.rhodesisland.terminal.util.RelativeTime

/**
 * 朋友圈（仿微信 Moments）。
 *
 * 布局（对照微信）：
 * - 顶部封面图（长按可更换，默认渐变）+ 右下角「我」的头像与昵称；
 * - 顶栏：返回 + 相机按钮（发圈入口：AI 代发 / 自己发）；
 * - 帖子流：左头像 + 昵称（角色蓝名/用户白名）+ 正文 + 图片（1 张大图 / 2-3 张宫格）
 *   + 相对时间 + 右侧「···」弹「赞/评论」；点赞与评论在浅灰缩进块内。
 * - 角色发圈：弹窗选角色 + 图数（0-3），经云端 LLM + 用户自有生图 API 生成；
 *   生成失败自动降级纯文字。评论后发帖角色必回。
 */
@Composable
fun MomentsScreen(
    container: AppContainer,
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val viewModel: MomentsViewModel = viewModel(
        factory = viewModelFactory { initializer { MomentsViewModel(app, container) } },
    )
    val state by viewModel.uiState.collectAsState()
    val coverPath by container.settingsRepository.momentCoverPath.collectAsState(initial = "")
    LaunchedEffect(Unit) { viewModel.startTick() }

    var showAiPostDialog by remember { mutableStateOf(false) }
    var showComposeDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0E1116))) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                MomentsHeader(
                    coverPath = coverPath,
                    userAvatar = state.userAvatar,
                    onPickCover = viewModel::setCoverFromUri,
                    onCameraClick = { showAiPostDialog = true },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            if (state.posts.isEmpty()) {
                item {
                    Text(
                        "还没有动态\n点右上角相机让角色发一条，或自己发一条",
                        color = Color(0xFF8A93A0),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                    )
                }
            }
            items(state.posts, key = { it.post.id }) { post ->
                MomentPostCard(
                    post = post,
                    nowMs = state.nowMs,
                    isReplying = state.generating.replyingPostId == post.post.id,
                    onToggleLike = { viewModel.toggleLike(post.post.id) },
                    onComment = { text -> viewModel.commentOnPost(post.post, text) },
                    onDelete = { viewModel.deletePost(post.post.id) },
                )
            }
            item { Spacer(Modifier.height(bottomBarHeight + 24.dp)) }
        }

        // 顶栏（覆盖在封面上）
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.Close, contentDescription = "返回", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showAiPostDialog = true }) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "让角色发朋友圈", tint = Color.White)
            }
            IconButton(onClick = { showComposeDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "自己发朋友圈", tint = Color.White)
            }
        }

        if (showAiPostDialog) {
            AiPostDialog(
                container = container,
                generating = state.generating.posting,
                onDismiss = { showAiPostDialog = false },
                onPost = { characterId, imageCount ->
                    showAiPostDialog = false
                    viewModel.postAsCharacter(characterId, imageCount)
                },
            )
        }
        if (showComposeDialog) {
            UserComposeDialog(
                onDismiss = { showComposeDialog = false },
                onSend = { text, uris ->
                    showComposeDialog = false
                    viewModel.postAsUser(text, uris)
                },
            )
        }

        state.errorMessage?.let { message ->
            Surface(
                color = Color(0xCC3A2A2A),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomBarHeight + 24.dp)
                    .clickable { viewModel.clearError() },
            ) {
                Text(
                    message, color = Color(0xFFFFC9C9), fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 封面 + 右下角「我」头像；长按封面换图。 */
@Composable
private fun MomentsHeader(
    coverPath: String,
    userAvatar: String,
    onPickCover: (Uri) -> Unit,
    onCameraClick: () -> Unit,
) {
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onPickCover)
    }
    Box(Modifier.fillMaxWidth().height(280.dp)) {
        if (coverPath.isBlank()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF3A4A66), Color(0xFF1A2230))),
                ),
            )
        } else {
            AsyncImage(
                model = coverPath,
                contentDescription = "朋友圈封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // 长按封面换图（微信同款交互）
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = {
                        coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                },
        )
        // 右下角：昵称 + 头像（微信位置）
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A303A)),
                contentAlignment = Alignment.Center,
            ) {
                if (userAvatar.isBlank()) {
                    Text("我", color = Color.White, fontSize = 24.sp)
                } else {
                    AsyncImage(
                        model = userAvatar,
                        contentDescription = "我的头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

/** 单条帖子：头像/昵称/正文/图片/时间 + 「···」赞评论；点赞评论浅灰缩进块（微信样式）。 */
@Composable
private fun MomentPostCard(
    post: PostUi,
    nowMs: Long,
    isReplying: Boolean,
    onToggleLike: () -> Unit,
    onComment: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var showActionSheet by remember { mutableStateOf(false) }
    var showCommentInput by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }
    val isUserPost = post.post.authorType == MomentRepository.AUTHOR_USER

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            // 左头像
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A303A)),
                contentAlignment = Alignment.Center,
            ) {
                if (post.authorImage.isBlank()) {
                    Text(post.authorName.take(1), color = Color.White, fontSize = 18.sp)
                } else {
                    AsyncImage(
                        model = post.authorImage,
                        contentDescription = post.authorName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    post.authorName,
                    color = if (isUserPost) Color(0xFFE6E9EE) else Color(0xFF7FA8D9),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                if (post.post.content.isNotBlank()) {
                    Text(
                        post.post.content,
                        color = Color(0xFFDDE2E9),
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // 图片：1 张大图 / 2-3 张宫格
                val images = post.images
                if (images.isNotEmpty()) {
                    if (images.size == 1) {
                        AsyncImage(
                            model = images[0],
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(0.66f).height(190.dp).clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            images.chunked(3).forEach { rowImages ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    rowImages.forEach { path ->
                                        AsyncImage(
                                            model = path,
                                            contentDescription = null,
                                            modifier = Modifier.size(104.dp).clip(RoundedCornerShape(4.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // 时间 + 「···」操作
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        RelativeTime.format(post.post.createdAt, nowMs),
                        color = Color(0xFF7A8290),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (isUserPost) {
                        Text(
                            "删除",
                            color = Color(0xFF7FA8D9),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable(onClick = onDelete)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Box(
                        Modifier
                            .background(Color(0xFF242B36), RoundedCornerShape(4.dp))
                            .clickable { showActionSheet = !showActionSheet }
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreHoriz,
                            contentDescription = "赞/评论",
                            tint = Color(0xFF7FA8D9),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // 「赞 / 评论」弹出（微信式深色小气泡；此处简化为展开行）
                if (showActionSheet) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF242B36))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                onToggleLike()
                                showActionSheet = false
                            },
                        ) {
                            Icon(
                                Icons.Filled.Favorite,
                                contentDescription = null,
                                tint = if (post.likedByUser) Color(0xFFE57373) else Color(0xFF7FA8D9),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (post.likedByUser) "取消" else "赞", color = Color(0xFFDDE2E9), fontSize = 13.sp)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                showCommentInput = true
                                showActionSheet = false
                            },
                        ) {
                            Text("评论", color = Color(0xFFDDE2E9), fontSize = 13.sp)
                        }
                    }
                }
                // 点赞 + 评论列表（浅灰缩进块，微信样式）
                if (post.likeCharacterIds.isNotEmpty() || post.comments.isNotEmpty() || isReplying) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1A2029))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (post.likeCharacterIds.isNotEmpty()) {
                            Text(
                                "❤ ${post.likeCharacterIds.size} 人觉得很赞",
                                color = Color(0xFF7FA8D9),
                                fontSize = 13.sp,
                            )
                        }
                        post.comments.forEach { comment ->
                            val commenterName = if (comment.authorType == MomentRepository.AUTHOR_USER) "我" else comment.characterId?.let { post.authorName } ?: ""
                            Text(
                                buildString {
                                    append(commenterName)
                                    append("：")
                                    append(comment.content)
                                },
                                color = Color(0xFFC9CFD8),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                        }
                        if (isReplying) {
                            Text("对方正在输入…", color = Color(0xFF7A8290), fontSize = 12.sp)
                        }
                    }
                }
                // 评论输入框（微信式行内输入）
                if (showCommentInput) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = commentText,
                            onValueChange = { commentText = it },
                            textStyle = TextStyle(color = Color(0xFFDDE2E9), fontSize = 14.sp),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1A2029))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            decorationBox = { inner ->
                                if (commentText.isEmpty()) {
                                    Text("评论", color = Color(0xFF6B7280), fontSize = 14.sp)
                                }
                                inner()
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                if (commentText.isNotBlank()) {
                                    onComment(commentText)
                                    commentText = ""
                                    showCommentInput = false
                                }
                            },
                        ) { Text("发送", color = Color(0xFF7FA8D9), fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

/** AI 代发弹窗：选角色 + 图片数（0-3）。 */
@Composable
private fun AiPostDialog(
    container: AppContainer,
    generating: Boolean,
    onDismiss: () -> Unit,
    onPost: (characterId: String, imageCount: Int) -> Unit,
) {
    val characters by container.characterRepository.characters.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var imageCount by remember { mutableStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("让角色发朋友圈") },
        text = {
            Column {
                Text("选择角色（云端 AI 生成文案与配图）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(characters) { char ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = char.id }
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedId == char.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A303A)),
                                contentAlignment = Alignment.Center,
                            ) {
                                AsyncImage(
                                    model = if (char.isCustom && char.image.isNotBlank()) char.image
                                    else container.assetRepository.getPicture(char.id),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(char.name, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("配图数量", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 2, 3).forEach { count ->
                        TextButton(onClick = { imageCount = count }) {
                            Text(
                                if (count == 0) "无图" else "${count}张",
                                color = if (imageCount == count) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (imageCount == count) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedId != null && !generating,
                onClick = { selectedId?.let { onPost(it, imageCount) } },
            ) { Text(if (generating) "生成中…" else "生成发布") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 用户自发朋友圈：文字 + 相册图（最多 3 张，不走 API）。 */
@Composable
private fun UserComposeDialog(
    onDismiss: () -> Unit,
    onSend: (String, List<Uri>) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val uris = remember { mutableStateOf(listOf<Uri>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = AppConfig.Moment.MAX_IMAGES)) { picked ->
        uris.value = picked
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发朋友圈") },
        text = {
            Column {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(10.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text("这一刻的想法…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                        inner()
                    },
                )
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (uris.value.isEmpty()) "添加图片（最多 3 张）" else "已选 ${uris.value.size} 张 · 点击更换")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() || uris.value.isNotEmpty(),
                onClick = { onSend(text, uris.value) },
            ) { Text("发表") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
