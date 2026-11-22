package com.tinn.picture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private data class AlbumMediaItem(
    val uri: String,
    val isVideo: Boolean
)

private data class UserAlbum(
    val id: String,
    val name: String,
    val media: List<AlbumMediaItem>
)

private data class AlbumViewerRequest(
    val albumName: String,
    val media: List<AlbumMediaItem>,
    val startIndex: Int
)

private const val ALBUM_PREFS_NAME = "photos_by_tinn_albums"
private const val ALBUM_PREFS_KEY = "albums_json"

/** Màn quản lý album độc lập, được gọi từ tab Album trong PhotosByTinnApp(). */
@Composable
fun AlbumFeatureScreen(
    onViewerVisibilityChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var albums by remember { mutableStateOf(readAlbums(context)) }
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var viewerRequest by remember { mutableStateOf<AlbumViewerRequest?>(null) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var draftMedia by remember { mutableStateOf<List<AlbumMediaItem>>(emptyList()) }
    var pickingForNewAlbum by remember { mutableStateOf(false) }
    var addingToAlbumId by remember { mutableStateOf<String?>(null) }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { pickedUris ->
        if (pickedUris.isNotEmpty()) {
            val picked = pickedUris.map { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Một số provider không hỗ trợ persistable permission; URI vẫn dùng được trong phiên hiện tại.
                }
                AlbumMediaItem(
                    uri = uri.toString(),
                    isVideo = context.contentResolver.getType(uri)?.startsWith("video/") == true
                )
            }.distinctBy { it.uri }

            if (pickingForNewAlbum) {
                draftMedia = (draftMedia + picked).distinctBy { it.uri }
            } else {
                val targetId = addingToAlbumId
                if (targetId != null) {
                    albums = albums.map { album ->
                        if (album.id == targetId) {
                            album.copy(media = (album.media + picked).distinctBy { it.uri })
                        } else album
                    }
                    writeAlbums(context, albums)
                }
            }
        }
        pickingForNewAlbum = false
        addingToAlbumId = null
    }

    SideEffect {
        onViewerVisibilityChanged(viewerRequest != null)
    }
    DisposableEffect(Unit) {
        onDispose { onViewerVisibilityChanged(false) }
    }

    BackHandler(enabled = viewerRequest == null && selectedAlbumId != null) {
        selectedAlbumId = null
    }

    val selectedAlbum = albums.firstOrNull { it.id == selectedAlbumId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
    ) {
        when {
            viewerRequest != null -> {
                val request = viewerRequest!!
                AlbumSlideshowViewer(
                    albumName = request.albumName,
                    media = request.media,
                    startIndex = request.startIndex,
                    onClose = { viewerRequest = null }
                )
            }

            selectedAlbum != null -> {
                AlbumDetailScreen(
                    album = selectedAlbum,
                    onBack = { selectedAlbumId = null },
                    onAddMedia = {
                        pickingForNewAlbum = false
                        addingToAlbumId = selectedAlbum.id
                        mediaPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    onPlay = { index ->
                        if (selectedAlbum.media.isNotEmpty()) {
                            viewerRequest = AlbumViewerRequest(
                                albumName = selectedAlbum.name,
                                media = selectedAlbum.media,
                                startIndex = index.coerceIn(0, selectedAlbum.media.lastIndex)
                            )
                        }
                    }
                )
            }

            else -> {
                AlbumLibraryScreen(
                    albums = albums,
                    onCreateAlbum = {
                        draftName = ""
                        draftMedia = emptyList()
                        showCreateDialog = true
                    },
                    onOpenAlbum = { selectedAlbumId = it }
                )
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Tạo album", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        singleLine = true,
                        label = { Text("Tên album") },
                        placeholder = { Text("Ví dụ: Kỷ niệm 2026") }
                    )
                    GlassActionButton(
                        text = "Chọn ảnh / video (${draftMedia.size})",
                        onClick = {
                            pickingForNewAlbum = true
                            mediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Có thể chọn nhiều ảnh và video cùng lúc.",
                        color = Color(0xFFB7BBC6),
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = draftName.isNotBlank() && draftMedia.isNotEmpty(),
                    onClick = {
                        val newAlbum = UserAlbum(
                            id = UUID.randomUUID().toString(),
                            name = draftName.trim(),
                            media = draftMedia.distinctBy { it.uri }
                        )
                        albums = albums + newAlbum
                        writeAlbums(context, albums)
                        showCreateDialog = false
                        selectedAlbumId = newAlbum.id
                    }
                ) { Text("Tạo album") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Hủy", color = Color(0xFFB7BBC6))
                }
            },
            containerColor = Color(0xFF202129),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
}

@Composable
private fun AlbumLibraryScreen(
    albums: List<UserAlbum>,
    onCreateAlbum: () -> Unit,
    onOpenAlbum: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 74.dp, bottom = 94.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Album của tôi", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Sắp xếp ảnh và video theo cách của bạn", color = Color(0xFF9CA3AF), fontSize = 12.sp)
            }
            GlassIconButton(onClick = onCreateAlbum) {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Tạo album", tint = Color.White)
            }
        }
        Spacer(Modifier.height(18.dp))

        if (albums.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.AddPhotoAlternate,
                        contentDescription = null,
                        tint = Color(0xFF7C8495),
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Chưa có album nào", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Nhấn nút + để tạo album đầu tiên", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    GlassActionButton(text = "Tạo album", onClick = onCreateAlbum)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(145.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                items(albums, key = { it.id }) { album ->
                    GlassAlbumCard(album = album, onClick = { onOpenAlbum(album.id) })
                }
            }
        }
    }
}

@Composable
private fun GlassAlbumCard(album: UserAlbum, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.13f), Color(0xFF6B7280).copy(alpha = 0.07f))
                )
            )
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                RoundedCornerShape(22.dp)
            )
            .clickable(onClick = onClick)
            .padding(9.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.12f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF262832))
        ) {
            val cover = album.media.firstOrNull()
            if (cover != null) {
                AsyncImage(
                    model = Uri.parse(cover.uri),
                    contentDescription = album.name,
                    modifier = Modifier.fillMaxSize()
                )
                if (cover.isVideo) {
                    Box(Modifier.align(Alignment.Center)) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                }
            } else {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = Color(0xFF9CA3AF), modifier = Modifier.align(Alignment.Center).size(38.dp))
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.62f)
            ) {
                Text("${album.media.size}", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(album.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${album.media.count { it.isVideo }} video · ${album.media.count { !it.isVideo }} ảnh", color = Color(0xFF9CA3AF), fontSize = 11.sp)
    }
}

@Composable
private fun AlbumDetailScreen(
    album: UserAlbum,
    onBack: () -> Unit,
    onAddMedia: () -> Unit,
    onPlay: (Int) -> Unit
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 74.dp, bottom = 94.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(onClick = onBack) {
                Text("‹", color = Color.White, fontSize = 30.sp, lineHeight = 30.sp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(album.name, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${album.media.size} mục", color = Color(0xFF9CA3AF), fontSize = 12.sp)
            }
            GlassIconButton(onClick = onAddMedia) {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Thêm ảnh/video", tint = Color.White)
            }
        }
        Spacer(Modifier.height(15.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            GlassActionButton(
                text = "▶  Play",
                onClick = { if (album.media.isNotEmpty()) onPlay(0) },
                modifier = Modifier.weight(1f),
                enabled = album.media.isNotEmpty()
            )
            GlassActionButton(
                text = "Thêm ảnh/video",
                onClick = onAddMedia,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(15.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(105.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(album.media, key = { it.uri }) { item ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0xFF252832))
                        .clickable { onPlay(album.media.indexOf(item)) }
                ) {
                    AsyncImage(
                        model = Uri.parse(item.uri),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (item.isVideo) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.padding(4.dp).size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumSlideshowViewer(
    albumName: String,
    media: List<AlbumMediaItem>,
    startIndex: Int,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val previousOrientation = remember(activity) { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

    DisposableEffect(activity) {
        if (activity != null) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            if (activity != null) activity.requestedOrientation = previousOrientation
        }
    }

    var index by remember(media, startIndex) { mutableStateOf(startIndex.coerceIn(0, media.lastIndex)) }
    var slideshowPlaying by remember { mutableStateOf(false) }
    var videoStarted by remember(media, index) { mutableStateOf(false) }
    var manualVideoStartRequested by remember(media, index) { mutableStateOf(false) }
    val current = media[index]
    var videoView by remember(current.uri) { mutableStateOf<VideoView?>(null) }
    val playbackState = rememberUpdatedState(slideshowPlaying)
    val manualPlayState = rememberUpdatedState(manualVideoStartRequested)
    val nextAction = rememberUpdatedState<() -> Unit>({ index = (index + 1) % media.size })

    BackHandler(onBack = onClose)

    LaunchedEffect(slideshowPlaying, index, current.isVideo) {
        if (slideshowPlaying && !current.isVideo) {
            delay(3000L)
            index = (index + 1) % media.size
        }
    }

    DisposableEffect(current.uri) {
        onDispose {
            try {
                videoView?.stopPlayback()
            } catch (_: Exception) {
            }
            videoView = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        Crossfade(
            targetState = current.uri,
            label = "album_media_transition"
        ) { targetUri ->

            // Lấy đúng media tương ứng với trạng thái Crossfade
            val displayItem = media.firstOrNull {
                it.uri == targetUri
            } ?: current

            if (displayItem.isVideo) {
                key(displayItem.uri) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                videoView = this
                                setVideoURI(Uri.parse(displayItem.uri))

                                setOnPreparedListener { player ->
                                    player.isLooping = false

                                    if (playbackState.value || manualPlayState.value) {
                                        start()
                                        videoStarted = true
                                    }
                                }

                                setOnCompletionListener {
                                    videoStarted = false

                                    // Video phát hết thì chuyển mục tiếp theo
                                    if (playbackState.value) {
                                        nextAction.value.invoke()
                                    }
                                }

                                setOnErrorListener { _, what, extra ->
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "Không phát được video ($what/$extra)",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    false
                                }
                            }
                        },
                        update = { view ->
                            videoView = view

                            if (
                                (playbackState.value || manualPlayState.value) &&
                                !view.isPlaying
                            ) {
                                view.start()
                                videoStarted = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                AsyncImage(
                    model = Uri.parse(targetUri),
                    contentDescription = albumName,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }


        if (current.isVideo && !videoStarted && !slideshowPlaying) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.48f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.30f))
            ) {
                IconButton(onClick = {
                    manualVideoStartRequested = true
                    videoView?.start()
                    videoStarted = true
                }, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Phát video", tint = Color.White, modifier = Modifier.size(42.dp))
                }
            }
        }

        GlassPanel(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(onClick = onClose) {
                    Text("×", color = Color.White, fontSize = 27.sp, lineHeight = 28.sp)
                }
                Text(albumName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                Text("${index + 1} / ${media.size}", color = Color(0xFFE2E8F0), fontSize = 12.sp)
            }
        }

        GlassPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GlassIconButton(onClick = {
                    index = if (index == 0) media.lastIndex else index - 1
                    videoStarted = false
                    manualVideoStartRequested = false
                }) {
                    Text("‹", color = Color.White, fontSize = 32.sp, lineHeight = 32.sp)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlassIconButton(onClick = {
                        val shouldPlay = !slideshowPlaying
                        slideshowPlaying = shouldPlay
                        if (shouldPlay && current.isVideo) {
                            videoView?.start()
                            videoStarted = true
                        }
                    }, size = 54.dp) {
                        if (slideshowPlaying) {
                            Text("Ⅱ", color = Color.White, fontSize = 22.sp)
                        } else {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play slideshow", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (slideshowPlaying) "Đang trình chiếu" else "Phát tự động",
                        color = Color(0xFFE2E8F0),
                        fontSize = 10.sp
                    )
                }

                GlassIconButton(onClick = {
                    index = (index + 1) % media.size
                    videoStarted = false
                    manualVideoStartRequested = false
                }) {
                    Text("›", color = Color.White, fontSize = 32.sp, lineHeight = 32.sp)
                }
            }
        }
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(25.dp),
        color = Color(0xFFBFC7D5).copy(alpha = 0.13f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.48f),
                    Color.White.copy(alpha = 0.09f),
                    Color(0xFF93C5FD).copy(alpha = 0.22f)
                )
            )
        ),
        shadowElevation = 18.dp
    ) {
        content()
    }
}

@Composable
private fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.size(size).clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun GlassActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = Color.White.copy(alpha = if (enabled) 0.12f else 0.05f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.22f else 0.08f))
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color(0xFF7B8190),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun readAlbums(context: Context): List<UserAlbum> {
    return try {
        val raw = context.getSharedPreferences(ALBUM_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(ALBUM_PREFS_KEY, "[]") ?: "[]"
        val root = JSONArray(raw)
        buildList {
            for (i in 0 until root.length()) {
                val albumObject = root.optJSONObject(i) ?: continue
                val mediaArray = albumObject.optJSONArray("media") ?: JSONArray()
                val mediaList = buildList {
                    for (j in 0 until mediaArray.length()) {
                        val item = mediaArray.optJSONObject(j) ?: continue
                        val uri = item.optString("uri")
                        if (uri.isNotBlank()) add(AlbumMediaItem(uri, item.optBoolean("video", false)))
                    }
                }
                val id = albumObject.optString("id")
                val name = albumObject.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) add(UserAlbum(id, name, mediaList))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun writeAlbums(context: Context, albums: List<UserAlbum>) {
    val root = JSONArray()
    albums.forEach { album ->
        val mediaArray = JSONArray()
        album.media.forEach { item ->
            mediaArray.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("video", item.isVideo)
            )
        }
        root.put(
            JSONObject()
                .put("id", album.id)
                .put("name", album.name)
                .put("media", mediaArray)
        )
    }
    context.getSharedPreferences(ALBUM_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(ALBUM_PREFS_KEY, root.toString())
        .apply()
}
