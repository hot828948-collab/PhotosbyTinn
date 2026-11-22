@file:RequiresApi(Build.VERSION_CODES.N)

package com.tinn.picture

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaDetails(
    val name: String,
    val date: String,
    val resolution: String,
    val size: String,
    val path: String
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF101012),
                    surface = Color(0xFF1A1A1D),
                    primary = Color(0xFF60A5FA)
                )
            ) {
                FontPackTheme {
                    PhotosByTinnApp()
                }
            }
        }
    }
}

@Composable
fun PhotosByTinnApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var albumViewerOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var mediaUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    var barsVisible by remember { mutableStateOf(true) }

    val prefs = remember { context.getSharedPreferences("photos_by_tinn_prefs", Context.MODE_PRIVATE) }
    var borderColorHex by remember {
        mutableStateOf(prefs.getString("tab_border_color", "blue") ?: "blue")
    }
    var tabEffectMode by remember {
        mutableStateOf(prefs.getString("tab_effect_mode", "spring") ?: "spring")
    }

    LaunchedEffect(gridState) {
        var lastScrollOffset = 0
        var lastItemIndex = 0

        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .map { (index, offset) ->
                val delta = (index - lastItemIndex) * 1000 + (offset - lastScrollOffset)
                lastItemIndex = index
                lastScrollOffset = offset
                delta
            }
            .collect { delta ->
                if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 20) {
                    barsVisible = true
                } else if (delta > 25) {
                    barsVisible = false
                } else if (delta < -25) {
                    barsVisible = true
                }
            }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != 0) barsVisible = true
    }

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions.values.any { it }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            mediaUris = withContext(Dispatchers.IO) { loadDeviceMedia(context) }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101012))
            .pointerInput(selectedTab, selectedMediaUri, albumViewerOpen) {
                // Vuốt đổi tab; tắt gesture khi đang xem ảnh/video hoặc trình chiếu album.
                if (selectedMediaUri == null && !albumViewerOpen) {
                    var totalDrag = 0f
                    val swipeThreshold = 64.dp.toPx()

                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            totalDrag += dragAmount
                            change.consume()
                        },
                        onDragEnd = {
                            when {
                                totalDrag < -swipeThreshold && selectedTab < 2 -> selectedTab += 1
                                totalDrag > swipeThreshold && selectedTab > 0 -> selectedTab -= 1
                            }
                            totalDrag = 0f
                        },
                        onDragCancel = { totalDrag = 0f }
                    )
                }
            }
    ) {
        AnimatedContent(
            targetState = selectedTab,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 230, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(180))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { fullWidth -> -fullWidth / 4 },
                            animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(150)))
                } else {
                    (slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 230, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(180))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { fullWidth -> fullWidth / 4 },
                            animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(150)))
                }
            },
            label = "tab_content_transition"
        ) { tab ->
            when (tab) {
                0 -> MediaGridScreen(
                    mediaUris = mediaUris,
                    hasPermission = hasPermission,
                    gridState = gridState,
                    onRequestPermission = { permissionLauncher.launch(permissionsToRequest) },
                    onMediaClick = { uri -> selectedMediaUri = uri }
                )

                1 -> AlbumFeatureScreen(
                    onViewerVisibilityChanged = { albumViewerOpen = it }
                )

                2 -> DeveloperInfoScreen(
                    currentBorderColor = borderColorHex,
                    currentEffectMode = tabEffectMode,
                    onColorChanged = { newColor ->
                        borderColorHex = newColor
                        prefs.edit().putString("tab_border_color", newColor).apply()
                    },
                    onEffectChanged = { newEffect ->
                        tabEffectMode = newEffect
                        prefs.edit().putString("tab_effect_mode", newEffect).apply()
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = barsVisible && !albumViewerOpen,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopHeaderBar()
        }

        AnimatedVisibility(
            visible = barsVisible && !albumViewerOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            BottomVerticalFloatingTabBar(
                selectedTab = selectedTab,
                borderColorHex = borderColorHex,
                effectMode = tabEffectMode,
                onTabSelected = { selectedTab = it }
            )
        }

        AnimatedVisibility(
            visible = selectedMediaUri != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            selectedMediaUri?.let { uri ->
                FullScreenMediaViewer(
                    uri = uri,
                    onClose = { selectedMediaUri = null },
                    onDeleted = { deletedUri ->
                        mediaUris = mediaUris.filterNot { it == deletedUri }
                        selectedMediaUri = null
                        Toast.makeText(context, "Đã xóa mục này thành công", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun TopHeaderBar() {
    val context = LocalContext.current

    var currentTime by remember { mutableStateOf("") }
    var batteryLevel by remember { mutableIntStateOf(100) }

    LaunchedEffect(Unit) {
        while (true) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            currentTime = sdf.format(Date())
            delay(1000)
        }
    }

    DisposableEffect(context) {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            batteryLevel = ((level.toFloat() / scale.toFloat()) * 100).toInt()
        }
        onDispose {}
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF1C1C20).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, Color(0xFF2B2B31)),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (currentTime.isEmpty()) "--:--" else currentTime,
                        color = Color(0xFF60A5FA),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = Color(0xFF1C1C20).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, Color(0xFF2B2B31)),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryFull,
                        contentDescription = "Pin",
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "$batteryLevel%",
                        color = Color(0xFF34D399),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun MediaGridScreen(
    mediaUris: List<Uri>,
    hasPermission: Boolean,
    gridState: LazyGridState,
    onRequestPermission: () -> Unit,
    onMediaClick: (Uri) -> Unit
) {
    val context = LocalContext.current
    if (!hasPermission) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Cần quyền truy cập thư viện để hiển thị ảnh & video",
                color = Color.LightGray,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                Text("Cấp quyền ngay", color = Color.White)
            }
        }
    } else if (mediaUris.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Chưa có ảnh hoặc video nào trong máy", color = Color.Gray)
        }
    } else {
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                top = statusBarTop + 58.dp,
                bottom = navBarBottom + 96.dp,
                start = 8.dp,
                end = 8.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(mediaUris, key = { it.toString() }) { uri ->
                val isVideo = remember(uri) {
                    uri.toString().startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString())
                }

                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1D1D21))
                        .clickable { onMediaClick(uri) }
                ) {
                    if (isVideo) {
                        var videoPreview by remember(uri) { mutableStateOf<VideoPreview?>(null) }
                        LaunchedEffect(uri) {
                            videoPreview = withContext(Dispatchers.IO) {
                                loadVideoPreview(context.applicationContext, uri)
                            }
                        }

                        val previewBitmap = videoPreview?.bitmap
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "Video thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color(0xFF25252B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Video loading",
                                    tint = Color(0xFF9CA3AF),
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }

                        if (videoPreview?.durationLabel?.isNotBlank() == true) {
                            Surface(
                                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
                                shape = RoundedCornerShape(5.dp),
                                color = Color.Black.copy(alpha = 0.72f)
                            ) {
                                Text(
                                    text = videoPreview!!.durationLabel,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .size(25.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.72f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(uri)
                                .size(256)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .crossfade(false)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenMediaViewer(
    uri: Uri,
    onClose: () -> Unit,
    onDeleted: (Uri) -> Unit
) {
    val context = LocalContext.current
    BackHandler { onClose() }

    val isVideo = remember(uri) { isVideoUri(context, uri) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var controlsVisible by remember { mutableStateOf(true) }
    var showInfoSheet by remember { mutableStateOf(false) }

    var mediaDetails by remember(uri) { mutableStateOf(MediaDetails("Đang tải...", "Đang tải...", "Đang tải...", "Đang tải...", "Đang tải...")) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var backgroundAudioEnabled by remember(uri) { mutableStateOf(false) }
    val backgroundAudioStateRef = remember(uri) { mutableStateOf(false) }
    val backgroundAudioActiveState = rememberUpdatedState(backgroundAudioEnabled)
    SideEffect { backgroundAudioStateRef.value = backgroundAudioEnabled }

    LaunchedEffect(uri) {
        mediaDetails = withContext(Dispatchers.IO) { getMediaDetails(context, uri) }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(isVideo) {
        if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(uri, isVideo, mediaDetails.name, backgroundAudioEnabled) {
        if (isVideo && !backgroundAudioEnabled && mediaDetails.name.isNotBlank() && mediaDetails.name != "Đang tải...") {
            val serviceIntent = Intent(context, BackgroundAudioService::class.java).apply {
                action = BackgroundAudioService.ACTION_SHOW_VIDEO
                putExtra(BackgroundAudioService.EXTRA_TITLE, mediaDetails.name)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
    DisposableEffect(uri, isVideo) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val focusRequest = if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { }
                .build().also { req ->
                    audioManager.requestAudioFocus(req)
                }
        } else {
            null
        }

        onDispose {
            try {
                videoViewRef?.stopPlayback()
            } catch (_: Exception) {
            }
            videoViewRef = null
            if (!backgroundAudioStateRef.value) {
                context.stopService(Intent(context, BackgroundAudioService::class.java))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null && focusRequest != null) {
                try {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                } catch (_: Exception) {
                }
            }
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onDeleted(uri)
        }
    }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        if (!isVideo) {
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            if (scale > 1f) {
                offset += offsetChange
            } else {
                offset = Offset.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = {
                        if (!isVideo) {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        }
                    }
                )
            }
    ) {
        if (isVideo) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        videoViewRef = this
                        setVideoURI(uri)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            if (!backgroundAudioActiveState.value) start()
                        }
                        setOnErrorListener { _, what, extra ->
                            Toast.makeText(ctx, "Không thể phát video này ($what-$extra)", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    .size(2048)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(state = transformState)
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Surface(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(16.dp),
                shape = CircleShape,
                color = Color(0xFF1E1E20).copy(alpha = 0.85f)
            ) {
                IconButton(onClick = {
                    try {
                        videoViewRef?.stopPlayback()
                    } catch (_: Exception) {
                    }
                    onClose()
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Đóng",
                        tint = Color.White
                    )
                }
            }
        }

        if (isVideo) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Surface(
                    modifier = Modifier.statusBarsPadding().padding(16.dp),
                    shape = CircleShape,
                    color = if (backgroundAudioEnabled) Color(0xFF123D2B) else Color(0xFF1E1E20).copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, if (backgroundAudioEnabled) Color(0xFF22C55E) else Color(0xFF33333A))
                ) {
                    IconButton(
                        onClick = {
                            if (!backgroundAudioEnabled) {
                                val audioIntent = Intent(context, BackgroundAudioService::class.java).apply {
                                    action = BackgroundAudioService.ACTION_START_BACKGROUND_AUDIO
                                    putExtra(BackgroundAudioService.EXTRA_URI, uri.toString())
                                    putExtra(BackgroundAudioService.EXTRA_TITLE, mediaDetails.name)
                                }
                                ContextCompat.startForegroundService(context, audioIntent)
                                videoViewRef?.stopPlayback()
                                backgroundAudioStateRef.value = true
                                backgroundAudioEnabled = true
                                Toast.makeText(context, "Đang phát âm thanh nền", Toast.LENGTH_SHORT).show()
                            } else {
                                context.stopService(Intent(context, BackgroundAudioService::class.java))
                                backgroundAudioStateRef.value = false
                                backgroundAudioEnabled = false
                                videoViewRef?.setVideoURI(uri)
                                val showIntent = Intent(context, BackgroundAudioService::class.java).apply {
                                    action = BackgroundAudioService.ACTION_SHOW_VIDEO
                                    putExtra(BackgroundAudioService.EXTRA_TITLE, mediaDetails.name)
                                }
                                ContextCompat.startForegroundService(context, showIntent)
                                Toast.makeText(context, "Đã tắt phát nền", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = if (backgroundAudioEnabled) "Tắt phát nền" else "Phát âm thanh khi tắt màn hình",
                            tint = if (backgroundAudioEnabled) Color(0xFF4ADE80) else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF1E1E20).copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, Color(0xFF2E2E32)),
                    shadowElevation = 10.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showInfoSheet = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Chi tiết",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(onClick = { shareMedia(context, uri) }) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = "Chia sẻ",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                try {
                                    if (backgroundAudioEnabled) {
                                        context.stopService(Intent(context, BackgroundAudioService::class.java))
                                        backgroundAudioStateRef.value = false
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                                        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                                    } else {
                                        val deletedRows = context.contentResolver.delete(uri, null, null)
                                        if (deletedRows > 0) onDeleted(uri)
                                        else Toast.makeText(context, "Không xóa được tệp", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: SecurityException) {
                                    Toast.makeText(context, "Không có quyền xóa tệp này", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Lỗi khi xóa: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Xóa",
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        if (showInfoSheet) {
            ModalBottomSheet(
                onDismissRequest = { showInfoSheet = false },
                containerColor = Color(0xFF1E1E20),
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Chi tiết",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    HorizontalDivider(color = Color(0xFF2C2C30))

                    DetailRow(title = "Tên tệp", value = mediaDetails.name)
                    DetailRow(title = "Thời gian", value = mediaDetails.date)
                    DetailRow(title = "Độ phân giải", value = mediaDetails.resolution)
                    DetailRow(title = "Dung lượng", value = mediaDetails.size)
                    DetailRow(title = "Đường dẫn", value = mediaDetails.path)
                }
            }
        }
    }
}

@Composable
fun DetailRow(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            color = Color(0xFF9CA3AF)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun shareMedia(context: Context, uri: Uri) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ qua"))
    } catch (e: Exception) {
        Toast.makeText(context, "Không thể mở trình chia sẻ", Toast.LENGTH_SHORT).show()
    }
}

fun isVideoUri(context: Context, uri: Uri): Boolean {
    if (uri.toString().startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString())) {
        return true
    }
    val type = context.contentResolver.getType(uri)
    if (type?.startsWith("video/") == true) return true
    val path = uri.toString().lowercase()
    return path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".mov") || path.endsWith(".3gp")
}

fun getMediaDetails(context: Context, uri: Uri): MediaDetails {
    var name = "Không xác định"
    var dateStr = "Không xác định"
    var resolution = "Không xác định"
    var sizeStr = "Không xác định"
    var path = "Bộ nhớ thiết bị"

    val projection = arrayOf(
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.RELATIVE_PATH
    )

    try {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val takenIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val pathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

                if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name

                val dateTaken = if (takenIdx != -1) cursor.getLong(takenIdx) else 0L
                val dateMod = if (modIdx != -1) cursor.getLong(modIdx) * 1000L else 0L
                val finalTimestamp = if (dateTaken > 0L) dateTaken else dateMod

                if (finalTimestamp > 0L) {
                    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    dateStr = sdf.format(Date(finalTimestamp))
                }
                if (widthIdx != -1 && heightIdx != -1) {
                    val w = cursor.getInt(widthIdx)
                    val h = cursor.getInt(heightIdx)
                    if (w > 0 && h > 0) resolution = "$w × $h px"
                }
                if (sizeIdx != -1) {
                    val bytes = cursor.getLong(sizeIdx)
                    if (bytes > 0) sizeStr = Formatter.formatFileSize(context, bytes)
                }
                if (pathIdx != -1) {
                    val relPath = cursor.getString(pathIdx)
                    if (!relPath.isNullOrEmpty()) path = "/$relPath$name"
                }
            }
        }
    } catch (_: Exception) {
    }

    return MediaDetails(name, dateStr, resolution, sizeStr, path)
}

@Composable
fun DeveloperInfoScreen(
    currentBorderColor: String,
    currentEffectMode: String,
    onColorChanged: (String) -> Unit,
    onEffectChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("photos_by_tinn_prefs", Context.MODE_PRIVATE) }

    var bannerUri by remember {
        mutableStateOf(prefs.getString("custom_banner_uri", null)?.let { Uri.parse(it) })
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }

            bannerUri = uri
            prefs.edit().putString("custom_banner_uri", uri.toString()).apply()
        }
    }

    val packageName = context.packageName
    val buildDate = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            sdf.format(Date(packageInfo.lastUpdateTime))
        } catch (e: Exception) {
            "N/A"
        }
    }
    val currentVersionName = remember {
        try {
            context.packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }
    val androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = statusBarTop + 68.dp,
                bottom = navBarBottom + 96.dp,
                start = 22.dp,
                end = 22.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF1A1A1E),
            border = BorderStroke(1.dp, Color(0xFF2B2B31)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF242429))
                        .border(1.5.dp, Color(0xFF3B82F6), RoundedCornerShape(18.dp))
                        .clickable {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (bannerUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(bannerUri)
                                .crossfade(true)
                                .size(900)
                                .build(),
                            contentDescription = "Selected Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddPhotoAlternate,
                                contentDescription = "Chọn ảnh",
                                tint = Color(0xFF60A5FA),
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = "Chạm để thêm ảnh",
                                color = Color(0xFF9CA3AF),
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color(0xFF3B82F6)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF60A5FA)
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Change photos",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Tinn",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF2A2A30))
                Spacer(modifier = Modifier.height(14.dp))

                InfoRowItem(label = "Status", value = "Release", valueColor = Color(0xFF4ADE80))
                Spacer(modifier = Modifier.height(10.dp))
                InfoRowItem(label = "Version", value = "v$currentVersionName", valueColor = Color(0xFF60A5FA))
                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF123D2B),
                    border = BorderStroke(1.dp, Color(0xFF22C55E))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("Working", color = Color(0xFF4ADE80), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("3.0", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                val uriHandler = LocalUriHandler.current
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "GitHub • hot828948-collab",
                    color = Color(0xFF60A5FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable {
                        uriHandler.openUri("https://github.com/hot828948-collab")
                    }.padding(vertical = 8.dp)
                )

                GitHubOtaUpdaterSection(
                    owner = "hot828948-collab",
                    repository = "PhotosbyTinn",
                    currentVersionName = currentVersionName
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF2A2A30))
                Spacer(modifier = Modifier.height(16.dp))
                FontPackSettingsSection()

                Text(
                    text = "Tab Button Effect",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(10.dp))


                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EffectOptionChip(
                        label = "Spring Scale",
                        isSelected = currentEffectMode == "spring",
                        onClick = { onEffectChanged("spring") },
                        modifier = Modifier.weight(1f)
                    )
                    EffectOptionChip(
                        label = "Neon Pulse",
                        isSelected = currentEffectMode == "pulse",
                        onClick = { onEffectChanged("pulse") },
                        modifier = Modifier.weight(1f)
                    )
                    EffectOptionChip(
                        label = "Slide Pill",
                        isSelected = currentEffectMode == "slide",
                        onClick = { onEffectChanged("slide") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF2A2A30))
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Tab Bar Border Color",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ColorOptionButton(
                        label = "Blue",
                        color = Color(0xFF2196F3),
                        isSelected = currentBorderColor == "blue"
                    ) { onColorChanged("blue") }
                    ColorOptionButton(
                        label = "Yellow",
                        color = Color(0xFFF59E0B),
                        isSelected = currentBorderColor == "yellow"
                    ) { onColorChanged("yellow") }
                    ColorOptionButton(
                        label = "Red",
                        color = Color(0xFFEF4444),
                        isSelected = currentBorderColor == "red"
                    ) { onColorChanged("red") }
                    ColorOptionButton(
                        label = "Purple",
                        color = Color(0xFFA855F7),
                        isSelected = currentBorderColor == "purple"
                    ) { onColorChanged("purple") }
                    ColorOptionButton(
                        label = "RGB",
                        isRgb = true,
                        isSelected = currentBorderColor == "rgb"
                    ) { onColorChanged("rgb") }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF2A2A30))
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SmallSystemInfoRow(label = "Package name", value = packageName)
                    SmallSystemInfoRow(label = "Build date", value = buildDate)
                    SmallSystemInfoRow(label = "Android version", value = androidVersion)
                    SmallSystemInfoRow(label = "Device name", value = deviceName)
                }
            }
        }
    }
}

@Composable
fun EffectOptionChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFF283548) else Color(0xFF242429),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) Color(0xFF60A5FA) else Color(0xFF34343C)
        ),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isSelected) Color.White else Color(0xFF9CA3AF),
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ColorOptionButton(
    label: String,
    color: Color = Color.Transparent,
    isRgb: Boolean = false,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) Color(0xFF34343C) else Color(0xFF242429),
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) Color(0xFF60A5FA) else Color(0xFF34343C)
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .then(
                        if (isRgb) {
                            Modifier.background(
                                Brush.horizontalGradient(
                                    listOf(Color.Red, Color.Green, Color.Blue)
                                )
                            )
                        } else {
                            Modifier.background(color)
                        }
                    )
            )
            Text(
                text = label,
                color = if (isSelected) Color.White else Color(0xFF9CA3AF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SmallSystemInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF6B7280),
            fontSize = 11.sp
        )
        Text(
            text = value,
            color = Color(0xFF9CA3AF),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
fun InfoRowItem(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF242429), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF9CA3AF),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun BottomVerticalFloatingTabBar(
    selectedTab: Int,
    borderColorHex: String,
    effectMode: String,
    onTabSelected: (Int) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "tab_bar_anim")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val activeBorderWidth = when (effectMode) {
        "pulse" -> (1.2f + pulseAlpha * 1.1f).dp
        "slide" -> 1.75.dp
        else -> 1.5.dp
    }

    val borderBrush = when (borderColorHex) {
        "yellow" -> Brush.verticalGradient(listOf(Color(0xFFF59E0B), Color(0xFFFBBF24)))
        "red" -> Brush.verticalGradient(listOf(Color(0xFFEF4444), Color(0xFFF87171)))
        "purple" -> Brush.verticalGradient(listOf(Color(0xFFA855F7), Color(0xFFC084FC)))
        "rgb" -> Brush.verticalGradient(listOf(Color.Red, Color.Green, Color.Blue))
        else -> Brush.verticalGradient(listOf(Color(0xFF2196F3), Color(0xFF60A5FA)))
    }

    val indicatorOffsetY by animateFloatAsState(
        targetValue = selectedTab * 54f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "indicator_offset_y"
    )

    Box(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(end = 16.dp, bottom = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF1A1A1E).copy(alpha = 0.94f),
            border = BorderStroke(activeBorderWidth, borderBrush),
            shadowElevation = 10.dp
        ) {
            Box(
                modifier = Modifier.padding(6.dp)
            ) {
                if (effectMode == "slide") {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .graphicsLayer { translationY = indicatorOffsetY * density }
                            .clip(CircleShape)
                            .background(Color(0xFF334155))
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TabPillVerticalIcon(
                        icon = Icons.Rounded.Image,
                        isSelected = selectedTab == 0,
                        effectMode = effectMode,
                        pulseAlpha = pulseAlpha,
                        onClick = { onTabSelected(0) }
                    )

                    TabPillVerticalIcon(
                        icon = Icons.Rounded.Folder,
                        isSelected = selectedTab == 1,
                        effectMode = effectMode,
                        pulseAlpha = pulseAlpha,
                        onClick = { onTabSelected(1) }
                    )

                    TabPillVerticalIcon(
                        icon = Icons.Outlined.Info,
                        isSelected = selectedTab == 2,
                        effectMode = effectMode,
                        pulseAlpha = pulseAlpha,
                        onClick = { onTabSelected(2) }
                    )
                }
            }
        }
    }
}

@Composable
fun TabPillVerticalIcon(
    icon: ImageVector,
    isSelected: Boolean,
    effectMode: String,
    pulseAlpha: Float,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val targetScale = when {
        isPressed && effectMode == "spring" -> 0.86f
        isSelected && effectMode == "spring" -> 1.12f
        isSelected && effectMode == "pulse" -> 0.96f + (pulseAlpha * 0.1f)
        isSelected -> 1.04f
        else -> 1f
    }

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tab_icon_scale"
    )

    val bgColor by animateColorAsState(
        targetValue = when {
            effectMode == "slide" -> Color.Transparent
            isSelected -> Color(0xFF2E323C)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 200),
        label = "tab_icon_bg"
    )

    val iconTint by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF60A5FA) else Color(0xFF9CA3AF),
        animationSpec = tween(durationMillis = 200),
        label = "tab_icon_tint"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
    }
}

private data class VideoPreview(val bitmap: Bitmap?, val durationLabel: String)

private object VideoPreviewMemoryCache {
    private val cache = object : LruCache<String, VideoPreview>(8 * 1024) {
        override fun sizeOf(key: String, value: VideoPreview): Int =
            (value.bitmap?.byteCount?.div(1024) ?: 1).coerceAtLeast(1)
    }

    fun get(key: String): VideoPreview? = cache.get(key)
    fun put(key: String, value: VideoPreview) { cache.put(key, value) }
}

private fun loadVideoPreview(context: Context, uri: Uri): VideoPreview {
    VideoPreviewMemoryCache.get(uri.toString())?.let { return it }

    var bitmap: Bitmap? = null
    var durationLabel = ""
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        if (durationMs > 0L) durationLabel = formatVideoDuration(durationMs)

        bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                480,
                480
            )
        } else {
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }

        if (bitmap != null) {
            val source = bitmap!!
            val maxSide = 480
            val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== source) source.recycle()
                bitmap = scaled
            }
        }
    } catch (_: Exception) {
        // Hỏng/không hỗ trợ thumbnail thì lưới sẽ giữ placeholder play icon.
    } finally {
        try { retriever.release() } catch (_: Exception) { }
    }

    return VideoPreview(bitmap, durationLabel).also {
        VideoPreviewMemoryCache.put(uri.toString(), it)
    }
}

private fun formatVideoDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

fun loadDeviceMedia(context: Context): List<Uri> {
    data class MediaItem(val uri: Uri, val timestamp: Long)
    val mediaItems = mutableListOf<MediaItem>()

    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_ADDED
    )

    val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    context.contentResolver.query(imageUri, projection, null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val addedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val dateTaken = if (takenCol != -1) cursor.getLong(takenCol) else 0L
            val dateMod = if (modCol != -1) cursor.getLong(modCol) * 1000L else 0L
            val dateAdded = if (addedCol != -1) cursor.getLong(addedCol) * 1000L else 0L

            val finalTime = when {
                dateTaken > 0L -> dateTaken
                dateMod > 0L -> dateMod
                else -> dateAdded
            }
            mediaItems.add(MediaItem(ContentUris.withAppendedId(imageUri, id), finalTime))
        }
    }

    val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    context.contentResolver.query(videoUri, projection, null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val addedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val dateTaken = if (takenCol != -1) cursor.getLong(takenCol) else 0L
            val dateMod = if (modCol != -1) cursor.getLong(modCol) * 1000L else 0L
            val dateAdded = if (addedCol != -1) cursor.getLong(addedCol) * 1000L else 0L

            val finalTime = when {
                dateTaken > 0L -> dateTaken
                dateMod > 0L -> dateMod
                else -> dateAdded
            }
            mediaItems.add(MediaItem(ContentUris.withAppendedId(videoUri, id), finalTime))
        }
    }

    return mediaItems.sortedByDescending { it.timestamp }.map { it.uri }
}

