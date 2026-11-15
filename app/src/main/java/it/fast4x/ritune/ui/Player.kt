package it.fast4x.ritune.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import it.fast4x.ritune.MainActivity
import it.fast4x.ritune.R
import it.fast4x.ritune.models.PlayerState
import it.fast4x.ritune.service.CommandService
import it.fast4x.ritune.ui.customui.CustomDefaultPlayerUiController
import it.fast4x.ritune.utils.DeviceInfo
import it.fast4x.ritune.utils.LyricsResolver
import it.fast4x.ritune.utils.LyricsResult
import it.fast4x.ritune.utils.SyncedLyricLine
import it.fast4x.ritune.utils.TrackMetadata
import it.fast4x.ritune.utils.TrackMetadataResolver
import it.fast4x.ritune.utils.getDeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlayerViewMode {
    AUDIO,
    LYRICS,
    VIDEO
}

@Composable
fun Player(innerPadding: PaddingValues) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val inflatedView = remember(context) {
        LayoutInflater.from(context).inflate(R.layout.youtube_player, null, false)
    }
    val onlinePlayerView = inflatedView as YouTubePlayerView

    val player = remember { mutableStateOf<YouTubePlayer?>(null) }
    val playerState = remember { mutableStateOf(PlayerConstants.PlayerState.UNSTARTED) }

    var currentSecond by remember { mutableFloatStateOf(0f) }
    var currentDuration by remember { mutableFloatStateOf(0f) }
    var enableBackgroundPlayback by remember { mutableStateOf(true) }

    var mediaId by remember { mutableStateOf("Tmod0giDy0o") }
    var deviceInfo: DeviceInfo? by remember { mutableStateOf(null) }
    var trackMetadata by remember { mutableStateOf<TrackMetadata?>(null) }

    var viewMode by remember { mutableStateOf(PlayerViewMode.AUDIO) }
    var lyrics by remember { mutableStateOf<List<SyncedLyricLine>>(emptyList()) }
    var plainLyrics by remember { mutableStateOf<String?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricsError by remember { mutableStateOf<String?>(null) }

    val commandService = remember {
        CommandService(
            context as MainActivity,
            onCommandLoad = { id, position ->
                mediaId = id
                player.value?.loadVideo(id, position)
            },
            onCommandPlay = { id ->
                if (mediaId != id) {
                    mediaId = id
                    player.value?.loadVideo(id, 0f)
                } else {
                    player.value?.play()
                }
            },
            onCommandPause = {
                player.value?.pause()
            },
            onCommandSeek = { time ->
                player.value?.seekTo(time)
            }
        )
    }

    LaunchedEffect(Unit) {
        commandService.start()
        deviceInfo = getDeviceInfo()
    }

    LaunchedEffect(mediaId) {
        lyrics = emptyList()
        plainLyrics = null
        lyricsError = null
        lyricsLoading = true
        currentSecond = 0f
        currentDuration = 0f
        trackMetadata = null
        trackMetadata = TrackMetadataResolver.resolve(mediaId)
    }

    val displayTitle = trackMetadata?.title ?: "Now Playing"
    val displayArtist = trackMetadata?.artist ?: "YouTube"
    val coverBitmap = trackMetadata?.coverBitmap

    val shouldLoadLyrics = viewMode == PlayerViewMode.LYRICS && trackMetadata != null

    LaunchedEffect(shouldLoadLyrics, mediaId, trackMetadata) {
        if (shouldLoadLyrics) {
            lyricsLoading = true
            lyricsError = null
            lyrics = emptyList()
            plainLyrics = null

            val startTime = System.currentTimeMillis()
            var fetchedResult: LyricsResult? = null

            val fetchJob = launch(Dispatchers.IO) {
                try {
                    fetchedResult = LyricsResolver.getLyrics(
                        artist = displayArtist,
                        title = displayTitle,
                        durationSeconds = currentDuration
                    )
                } catch (_: Exception) {}
            }

            fetchJob.join()

            if (fetchedResult != null && (fetchedResult!!.synced.isNotEmpty() || !fetchedResult!!.plainText.isNullOrBlank())) {
                lyrics = fetchedResult!!.synced
                plainLyrics = fetchedResult!!.plainText
                lyricsLoading = false
            } else {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 15000L) {
                    delay(15000L - elapsed)
                }
                lyricsError = "Error loading lyrics"
                lyricsLoading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(innerPadding)
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f),
            factory = {
                if (onlinePlayerView.parent != null) {
                    (onlinePlayerView.parent as ViewGroup).removeView(onlinePlayerView)
                }

                val iFramePlayerOptions = IFramePlayerOptions.Builder()
                    .controls(0)
                    .rel(0)
                    .modestBranding(1)
                    .ivLoadPolicy(3)
                    .listType("playlist")
                    .origin("https://music.youtube.com")
                    .build()

                val listener = object : AbstractYouTubePlayerListener() {
                    override fun onReady(youTubePlayer: YouTubePlayer) {
                        player.value = youTubePlayer

                        val customUiController = CustomDefaultPlayerUiController(
                            onlinePlayerView,
                            youTubePlayer,
                            onTap = { }
                        )

                        customUiController.showUi(false)
                        customUiController.showMenuButton(false)
                        customUiController.showVideoTitle(false)
                        customUiController.showPlayPauseButton(false)
                        customUiController.showDuration(false)
                        customUiController.showCurrentTime(false)
                        customUiController.showSeekBar(false)
                        customUiController.showBufferingProgress(false)
                        customUiController.showYouTubeButton(false)
                        customUiController.showFullscreenButton(false)

                        onlinePlayerView.setCustomPlayerUi(customUiController.rootView)

                        CoroutineScope(Dispatchers.IO).launch {
                            commandService.broadcastState(
                                PlayerState(
                                    mediaId = mediaId,
                                    isPlaying = false,
                                    currentTime = 0f,
                                    duration = 0f,
                                    title = displayTitle,
                                    state = playerState.value
                                )
                            )
                        }
                    }

                    override fun onCurrentSecond(
                        youTubePlayer: YouTubePlayer,
                        second: Float
                    ) {
                        super.onCurrentSecond(youTubePlayer, second)
                        currentSecond = second

                        if (playerState.value == PlayerConstants.PlayerState.PLAYING) {
                            CoroutineScope(Dispatchers.IO).launch {
                                commandService.broadcastState(
                                    PlayerState(
                                        mediaId = mediaId,
                                        isPlaying = true,
                                        currentTime = second,
                                        duration = currentDuration,
                                        title = displayTitle,
                                        state = playerState.value
                                    )
                                )
                            }
                        }

                        if (commandService.connections.isEmpty()) {
                            player.value?.pause()
                        }
                    }

                    override fun onVideoDuration(
                        youTubePlayer: YouTubePlayer,
                        duration: Float
                    ) {
                        currentDuration = duration
                    }

                    override fun onStateChange(
                        youTubePlayer: YouTubePlayer,
                        state: PlayerConstants.PlayerState
                    ) {
                        playerState.value = state

                        val isPlaying = state == PlayerConstants.PlayerState.PLAYING
                        CoroutineScope(Dispatchers.IO).launch {
                            commandService.broadcastState(
                                PlayerState(
                                    mediaId = mediaId,
                                    isPlaying = isPlaying,
                                    currentTime = currentSecond,
                                    duration = currentDuration,
                                    title = displayTitle,
                                    state = playerState.value
                                )
                            )
                        }
                    }

                    override fun onPlaybackQualityChange(
                        youTubePlayer: YouTubePlayer,
                        playbackQuality: PlayerConstants.PlaybackQuality
                    ) {}

                    override fun onError(
                        youTubePlayer: YouTubePlayer,
                        error: PlayerConstants.PlayerError
                    ) {
                        CoroutineScope(Dispatchers.IO).launch {
                            commandService.broadcastState(
                                PlayerState(
                                    mediaId = mediaId,
                                    isPlaying = false,
                                    currentTime = currentSecond,
                                    duration = currentDuration,
                                    title = displayTitle,
                                    state = playerState.value
                                )
                            )
                        }
                    }
                }

                onlinePlayerView.apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

                    enableAutomaticInitialization = false
                    if (enableBackgroundPlayback) {
                        enableBackgroundPlayback(true)
                    } else {
                        lifecycleOwner.lifecycle.addObserver(this)
                    }
                    initialize(listener, iFramePlayerOptions)
                }
            },
            update = {
                it.isFocusable = false
                it.isFocusableInTouchMode = false
                it.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                it.enableBackgroundPlayback(enableBackgroundPlayback)
                it.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        )

        if (playerState.value == PlayerConstants.PlayerState.PLAYING) {
            when (viewMode) {
                PlayerViewMode.AUDIO -> {
                    NowPlayingScreen(
                        title = displayTitle,
                        artist = displayArtist,
                        coverBitmap = coverBitmap,
                        currentSecond = currentSecond,
                        currentDuration = currentDuration,
                        mediaId = mediaId,
                        commandService = commandService,
                        deviceInfo = deviceInfo,
                        onLyricsClick = {
                            viewMode = PlayerViewMode.LYRICS
                        },
                        onVideoClick = {
                            viewMode = PlayerViewMode.VIDEO
                        },
                        onAudioClick = {
                            viewMode = PlayerViewMode.AUDIO
                        }
                    )
                }

                PlayerViewMode.LYRICS -> {
                    LyricsScreen(
                        title = displayTitle,
                        artist = displayArtist,
                        coverBitmap = coverBitmap,
                        currentSecond = currentSecond,
                        lyrics = lyrics,
                        plainLyrics = plainLyrics,
                        loading = lyricsLoading,
                        error = lyricsError,
                        commandService = commandService,
                        deviceInfo = deviceInfo,
                        onLyricsClick = {
                            viewMode = PlayerViewMode.LYRICS
                        },
                        onVideoClick = {
                            viewMode = PlayerViewMode.VIDEO
                        },
                        onAudioClick = {
                            viewMode = PlayerViewMode.AUDIO
                        }
                    )
                }

                PlayerViewMode.VIDEO -> {
                    VideoOverlay(
                        title = displayTitle,
                        artist = displayArtist,
                        coverBitmap = coverBitmap,
                        commandService = commandService,
                        deviceInfo = deviceInfo,
                        onLyricsClick = {
                            viewMode = PlayerViewMode.LYRICS
                        },
                        onVideoClick = {
                            viewMode = PlayerViewMode.VIDEO
                        },
                        onAudioClick = {
                            viewMode = PlayerViewMode.AUDIO
                        }
                    )
                }
            }
        } else {
            WelcomeScreen(
                commandService = commandService,
                deviceInfo = deviceInfo
            )
        }
    }
}

@Composable
private fun WelcomeScreen(
    commandService: CommandService,
    deviceInfo: DeviceInfo?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .alpha(0.6f)
        ) {
            Text(
                text = deviceInfo?.let { "${it.deviceBrand} ${it.deviceModel}" } ?: "Device Unknown",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = commandService.ipAddress()?.let { "IP: $it" } ?: "IP: Resolving...",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "RiTune Logo",
                modifier = Modifier
                    .size(120.dp)
                    .padding(bottom = 16.dp),
                colorFilter = ColorFilter.tint(Color.White)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "RiTune",
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Ready to Cast",
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.5f),
                letterSpacing = 2.sp
            )
        }

        StatusIcon(
            connected = commandService.connections.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        )
    }
}

@Composable
private fun NowPlayingScreen(
    title: String,
    artist: String,
    coverBitmap: Bitmap?,
    currentSecond: Float,
    currentDuration: Float,
    mediaId: String,
    commandService: CommandService,
    deviceInfo: DeviceInfo?,
    onLyricsClick: () -> Unit,
    onVideoClick: () -> Unit,
    onAudioClick: () -> Unit
) {
    val progress = if (currentDuration > 0f) {
        (currentSecond / currentDuration).coerceIn(0f, 1f)
    } else {
        0f
    }

    val infiniteTransition = rememberInfiniteTransition(label = "coverPulse")
    val coverScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "coverScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ModeSelector(
            selectedMode = PlayerViewMode.AUDIO,
            onLyricsClick = onLyricsClick,
            onVideoClick = onVideoClick,
            onAudioClick = onAudioClick,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(320.dp)
                    .scale(coverScale)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF2A2A2A),
                                Color(0xFF161616),
                                Color(0xFF0E0E0E)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(32.dp)
                    )
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = "Cover art",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "♪",
                        modifier = Modifier.align(Alignment.Center),
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.10f)
                    )

                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "Cover art",
                        modifier = Modifier
                            .size(160.dp)
                            .align(Alignment.Center),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "NOW PLAYING",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = artist,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(28.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.95f))
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = formatDuration(currentSecond) + " / " + formatDuration(currentDuration),
                    color = Color.White.copy(alpha = 0.50f),
                    fontSize = 14.sp
                )

            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .alpha(0.6f)
        ) {
            Text(
                text = deviceInfo?.let { "${it.deviceBrand} ${it.deviceModel}" } ?: "Device Unknown",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = commandService.ipAddress()?.let { "IP: $it" } ?: "IP: Resolving...",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        StatusIcon(
            connected = commandService.connections.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        )
    }
}

@Composable
private fun ModeSelector(
    selectedMode: PlayerViewMode,
    onLyricsClick: () -> Unit,
    onVideoClick: () -> Unit,
    onAudioClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lyricsFocusRequester = remember { FocusRequester() }
    val videoFocusRequester = remember { FocusRequester() }
    val audioFocusRequester = remember { FocusRequester() }

    LaunchedEffect(selectedMode) {
        runCatching {
            when (selectedMode) {
                PlayerViewMode.LYRICS -> lyricsFocusRequester.requestFocus()
                PlayerViewMode.VIDEO -> videoFocusRequester.requestFocus()
                PlayerViewMode.AUDIO -> audioFocusRequester.requestFocus()
            }
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModeButton(
            text = "Lyrics",
            icon = Icons.Default.MusicNote,
            selected = selectedMode == PlayerViewMode.LYRICS,
            modifier = Modifier.focusRequester(lyricsFocusRequester),
            onClick = onLyricsClick
        )

        ModeButton(
            text = "Video",
            icon = Icons.Default.Movie,
            selected = selectedMode == PlayerViewMode.VIDEO,
            modifier = Modifier.focusRequester(videoFocusRequester),
            onClick = onVideoClick
        )

        ModeButton(
            text = "Audio",
            icon = Icons.Default.Audiotrack,
            selected = selectedMode == PlayerViewMode.AUDIO,
            modifier = Modifier.focusRequester(audioFocusRequester),
            onClick = onAudioClick
        )
    }
}

@Composable
private fun ModeButton(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        label = "scale"
    )

    val containerColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.10f)

    val contentColor = if (isFocused) Color.Black else Color.White

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.scale(scale),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.size(8.dp))

        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun LyricsScreen(
    title: String,
    artist: String,
    coverBitmap: Bitmap?,
    currentSecond: Float,
    lyrics: List<SyncedLyricLine>,
    plainLyrics: String?,
    loading: Boolean,
    error: String?,
    commandService: CommandService,
    deviceInfo: DeviceInfo?,
    onLyricsClick: () -> Unit,
    onVideoClick: () -> Unit,
    onAudioClick: () -> Unit
) {
    val currentIndex = remember(currentSecond, lyrics) {
        lyrics.indexOfLast {
            it.timeMs <= currentSecond * 1000L
        }.coerceAtLeast(0)
    }

    val scrollState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (lyrics.isNotEmpty() && currentIndex in lyrics.indices) {
            scrollState.animateScrollToItem(index = currentIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 24.dp)
        ) {
            ModeSelector(
                selectedMode = PlayerViewMode.LYRICS,
                onLyricsClick = onLyricsClick,
                onVideoClick = onVideoClick,
                onAudioClick = onAudioClick,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 70.dp, vertical = 24.dp)
            ) {
                when {
                    loading -> {
                        Text(
                            text = "Loading lyrics...",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 26.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    error != null -> {
                        Text(
                            text = error,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 26.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    lyrics.isNotEmpty() -> {
                        LazyColumn(
                            state = scrollState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            itemsIndexed(lyrics) { index, line ->
                                Text(
                                    text = line.text.ifBlank { " " },
                                    color = if (index == currentIndex) {
                                        Color.White
                                    } else {
                                        Color.White.copy(alpha = 0.35f)
                                    },
                                    fontSize = if (index == currentIndex) 34.sp else 27.sp,
                                    fontWeight = if (index == currentIndex) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    !plainLyrics.isNullOrBlank() -> {
                        val lines = remember(plainLyrics) { plainLyrics.lines() }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            items(lines) { line ->
                                Text(
                                    text = line.ifBlank { " " },
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 28.sp,
                                    lineHeight = 38.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = "Lyrics not available",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 26.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            BottomTrackBar(
                title = title,
                artist = artist,
                coverBitmap = coverBitmap,
                commandService = commandService,
                deviceInfo = deviceInfo
            )
        }
    }
}

@Composable
private fun BottomTrackBar(
    title: String,
    artist: String,
    coverBitmap: Bitmap?,
    commandService: CommandService,
    deviceInfo: DeviceInfo?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = deviceInfo?.let {
                    "${it.deviceBrand} ${it.deviceModel}"
                } ?: "Device Unknown",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = commandService.ipAddress()?.let {
                    "IP: $it"
                } ?: "IP: Resolving...",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(
            modifier = Modifier.weight(1.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap.asImageBitmap(),
                    contentDescription = "Cover art",
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(modifier = Modifier.size(12.dp))
            }

            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = artist,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "RiTune",
                modifier = Modifier.size(54.dp),
                colorFilter = ColorFilter.tint(Color.White)
            )
        }
    }
}

@Composable
private fun VideoOverlay(
    title: String,
    artist: String,
    coverBitmap: Bitmap?,
    commandService: CommandService,
    deviceInfo: DeviceInfo?,
    onLyricsClick: () -> Unit,
    onVideoClick: () -> Unit,
    onAudioClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black
                        )
                    )
                )
                .padding(horizontal = 32.dp, vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                ModeSelector(
                    selectedMode = PlayerViewMode.VIDEO,
                    onLyricsClick = onLyricsClick,
                    onVideoClick = onVideoClick,
                    onAudioClick = onAudioClick,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(14.dp))

                BottomTrackBar(
                    title = title,
                    artist = artist,
                    coverBitmap = coverBitmap,
                    commandService = commandService,
                    deviceInfo = deviceInfo
                )
            }
        }
    }
}

private fun formatDuration(seconds: Float): String {
    val totalSeconds = seconds.toInt()
    val minutes = totalSeconds / 60
    val remainingSeconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, remainingSeconds)
}

@Composable
private fun StatusIcon(
    connected: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Image(
        painter = painterResource(
            if (connected) R.drawable.cast_connected else R.drawable.cast_disconnected
        ),
        contentDescription = "Cast Status",
        modifier = modifier
            .size(42.dp)
            .scale(scale)
            .alpha(alpha),
        colorFilter = ColorFilter.tint(Color.White)
    )
}