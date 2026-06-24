package com.train.ipodclassicemulator

import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.train.ipodclassicemulator.ui.components.ClickWheel
import com.train.ipodclassicemulator.ui.components.CoverFlow
import com.train.ipodclassicemulator.ui.components.IPodMenuRow
import com.train.ipodclassicemulator.ui.components.IPodStatusBar
import com.train.ipodclassicemulator.ui.components.MediaListItem
import com.train.ipodclassicemulator.ui.components.PlayerScreen
import com.train.ipodclassicemulator.ui.components.SettingsScreen
import com.train.ipodclassicemulator.ui.components.SpotifySetupScreen
import com.train.ipodclassicemulator.ui.components.scrollToKeepSelectedVisible
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import com.train.ipodclassicemulator.ui.theme.ThemeManager

@Composable
fun IPodApp(viewModel: IPodViewModel, themeManager: ThemeManager) {
    val context = LocalContext.current

    // Battery broadcast receiver
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                intent ?: return
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                if (level != -1 && scale != -1) {
                    viewModel.batteryPercentage = (level * 100 / scale.toFloat()).toInt()
                }
                val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                viewModel.isBatteryCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            }
        }
        context.registerReceiver(receiver, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Load initial Spotify data once token is ready
    LaunchedEffect(viewModel.tokenRefreshTick) { viewModel.loadInitialDataIfReady() }

    // Lazy list scroll sync
    val mainMenuListState = rememberLazyListState()
    val spotifyMenuListState = rememberLazyListState()
    val playlistListState = rememberLazyListState()
    val artistListState = rememberLazyListState()
    val trackListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()

    LaunchedEffect(viewModel.selectedMainMenuIndex, viewModel.screenState) {
        if (viewModel.screenState == ScreenState.MAIN_MENU)
            mainMenuListState.scrollToKeepSelectedVisible(viewModel.selectedMainMenuIndex)
    }
    LaunchedEffect(viewModel.selectedSpotifyMenuIndex, viewModel.screenState) {
        if (viewModel.screenState == ScreenState.SPOTIFY_MENU)
            spotifyMenuListState.scrollToKeepSelectedVisible(viewModel.selectedSpotifyMenuIndex)
    }
    LaunchedEffect(viewModel.selectedPlaylistIndex, viewModel.screenState) {
        if (viewModel.screenState == ScreenState.PLAYLISTS && viewModel.playlists.isNotEmpty())
            playlistListState.scrollToKeepSelectedVisible(viewModel.selectedPlaylistIndex)
    }
    // (nessun LaunchedEffect per album: ora usa CoverFlow che gestisce la selezione internamente)
    LaunchedEffect(viewModel.selectedArtistIndex, viewModel.screenState) {
        if (viewModel.screenState == ScreenState.ARTISTS)
            artistListState.scrollToKeepSelectedVisible(viewModel.selectedArtistIndex)
    }
    LaunchedEffect(viewModel.selectedTrackIndex, viewModel.screenState) {
        if (viewModel.screenState == ScreenState.TRACKS && viewModel.tracks.isNotEmpty())
            trackListState.scrollToKeepSelectedVisible(viewModel.selectedTrackIndex)
    }
    LaunchedEffect(viewModel.selectedSettingsIndex, viewModel.screenState) {
        if (viewModel.screenState == ScreenState.SETTINGS)
            settingsListState.scrollToKeepSelectedVisible(viewModel.selectedSettingsIndex)
    }

    // ── Root UI ───────────────────────────────────────────────────────────────

    if (viewModel.screenState == ScreenState.CREDENTIALS_SETUP) {
        SpotifySetupScreen(
            onCredentialsSaved = { id, secret -> viewModel.handleCredentialsSaved(id, secret) }
        )
        return
    }

    val colors = IPodTheme.colors

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bodyPrimary, colors.bodySecondary)))
            .padding(20.dp)
    ) {
        val displayHeight = (maxHeight * 0.55f).coerceIn(220.dp, 500.dp)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── iPod display ──────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(displayHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.bodyEdge)
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(7.dp))
                        .background(colors.screenBackground)
                        .padding(8.dp)
                ) {
                    if (viewModel.isLoading) {
                        LoadingScreen(
                            statusText = viewModel.statusText,
                            showLoginButton = viewModel.showLoginButton,
                            onLoginClick = { viewModel.onLoginButtonClicked() }
                        )
                    } else {
                        IPodScreenContent(
                            viewModel = viewModel,
                            themeManager = themeManager,
                            mainMenuListState = mainMenuListState,
                            spotifyMenuListState = spotifyMenuListState,
                            playlistListState = playlistListState,
                            artistListState = artistListState,
                            trackListState = trackListState,
                            settingsListState = settingsListState,
                        )
                    }
                }
            }

            // ── Click wheel ───────────────────────────────────────────────────
            ClickWheel(
                modifier = Modifier.padding(bottom = 32.dp, top = 24.dp),
                onScrollNext = { viewModel.onScrollNext() },
                onScrollPrevious = { viewModel.onScrollPrevious() },
                onSelectClick = { viewModel.onSelectClick(themeManager) },
                onMenuClick = { viewModel.onMenuClick() },
                onMenuLongClick = { viewModel.onMenuLongClick() },
                onPlayPauseClick = { viewModel.togglePlayPause() },
                onNextClick = { viewModel.skipNext() },
                onPreviousClick = { viewModel.skipPrevious() }
            )
        }
    }
}

// ── Loading overlay ───────────────────────────────────────────────────────────

@Composable
private fun LoadingScreen(statusText: String, showLoginButton: Boolean = false, onLoginClick: () -> Unit = {}) {
    val colors = IPodTheme.colors
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showLoginButton) {
            // Fix #6 — pulsante esplicito invece del solo spinner quando serve il login OAuth
            Text(
                text = statusText,
                color = colors.screenText,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.Button(
                onClick = onLoginClick,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = colors.screenAccent
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Text("Accedi a Spotify", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
            }
        } else {
            CircularProgressIndicator(color = colors.screenAccent)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = statusText, color = colors.screenText, fontSize = 14.sp)
        }
    }
}

// ── Screen content switcher ───────────────────────────────────────────────────

@Composable
private fun IPodScreenContent(
    viewModel: IPodViewModel,
    themeManager: ThemeManager,
    mainMenuListState: androidx.compose.foundation.lazy.LazyListState,
    spotifyMenuListState: androidx.compose.foundation.lazy.LazyListState,
    playlistListState: androidx.compose.foundation.lazy.LazyListState,
    artistListState: androidx.compose.foundation.lazy.LazyListState,
    trackListState: androidx.compose.foundation.lazy.LazyListState,
    settingsListState: androidx.compose.foundation.lazy.LazyListState,
) {
    val vm = viewModel
    when (vm.screenState) {

        ScreenState.MAIN_MENU -> IPodMenuScreen(
            title = "iPod",
            items = vm.mainMenuOptions,
            selectedIndex = vm.selectedMainMenuIndex,
            listState = mainMenuListState,
            batteryPercent = vm.batteryPercentage,
            isCharging = vm.isBatteryCharging,
            isMusicPlaying = vm.isTrackPlaying
        )

        ScreenState.SPOTIFY_MENU -> IPodMenuScreen(
            title = "Spotify",
            items = vm.spotifyMenuOptions,
            selectedIndex = vm.selectedSpotifyMenuIndex,
            listState = spotifyMenuListState,
            batteryPercent = vm.batteryPercentage,
            isCharging = vm.isBatteryCharging,
            isMusicPlaying = vm.isTrackPlaying
        )

        ScreenState.PLAYLISTS -> {
            val colors = IPodTheme.colors
            Column(modifier = Modifier.fillMaxSize()) {
                IPodStatusBar("Playlist", vm.batteryPercentage, vm.isBatteryCharging, vm.isTrackPlaying)
                Spacer(Modifier.height(2.dp))
                LazyColumn(state = playlistListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(vm.playlists) { index, playlist ->
                        val isFav = playlist.id == "favorites_virtual_id"
                        MediaListItem(
                            title = playlist.name,
                            subtitle = "Spotify Playlist",
                            imageUrl = playlist.images?.firstOrNull()?.url,
                            imageModel = if (isFav) R.mipmap.liked_songs else null,
                            isSelected = index == vm.selectedPlaylistIndex,
                            scaleImage = if (isFav) 1.3f else 1f,
                            showArrow = true
                        )
                    }
                }
            }
        }

        ScreenState.ALBUMS -> {
            Column(modifier = Modifier.fillMaxSize()) {
                IPodStatusBar("Album", vm.batteryPercentage, vm.isBatteryCharging, vm.isTrackPlaying)
                CoverFlow(
                    albums = vm.albums,
                    selectedIndex = vm.selectedAlbumIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }

        ScreenState.ARTISTS -> {
            Column(modifier = Modifier.fillMaxSize()) {
                IPodStatusBar("Artisti", vm.batteryPercentage, vm.isBatteryCharging, vm.isTrackPlaying)
                Spacer(Modifier.height(2.dp))
                LazyColumn(state = artistListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(vm.artists) { index, artist ->
                        MediaListItem(
                            title = artist.name,
                            subtitle = "Artista aggiunto",
                            imageUrl = artist.images?.firstOrNull()?.url,
                            isSelected = index == vm.selectedArtistIndex,
                            isCircleImage = true
                        )
                    }
                }
            }
        }

        ScreenState.TRACKS -> {
            Column(modifier = Modifier.fillMaxSize()) {
                IPodStatusBar("Brani", vm.batteryPercentage, vm.isBatteryCharging, vm.isTrackPlaying)
                Spacer(Modifier.height(2.dp))
                LazyColumn(state = trackListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(vm.tracks) { index, track ->
                        MediaListItem(
                            title = track.name,
                            subtitle = track.artists.firstOrNull()?.name ?: "Unknown",
                            imageUrl = track.album?.images?.firstOrNull()?.url,
                            isSelected = index == vm.selectedTrackIndex
                        )
                    }
                }
            }
        }

        ScreenState.SEARCH -> SearchScreen(viewModel = vm)

        ScreenState.TRACK_DETAILS -> vm.playingTrackDetails?.let { track ->
            Column(modifier = Modifier.fillMaxSize()) {
                IPodStatusBar("Now Playing", vm.batteryPercentage, vm.isBatteryCharging, vm.isTrackPlaying)
                Box(modifier = Modifier.weight(1f)) {
                    PlayerScreen(
                        track = track,
                        coverUrl = vm.currentCoverUrl,
                        progressMs = vm.currentProgressMs,
                        durationMs = vm.trackDurationMs,
                        isLiked = vm.isCurrentTrackLiked,
                        playbackMode = vm.currentPlaybackMode,
                        onLikeToggle = { vm.toggleLike(track.id) },
                        onModeToggle = { vm.toggleShuffle() }
                    )
                }
            }
        }

        ScreenState.SETTINGS -> {
            Column(modifier = Modifier.fillMaxSize()) {
                IPodStatusBar("Impostazioni", vm.batteryPercentage, vm.isBatteryCharging, vm.isTrackPlaying)
                Box(modifier = Modifier.weight(1f)) {
                    SettingsScreen(
                        selectedIndex = vm.selectedSettingsIndex,
                        currentTheme = themeManager.currentTheme,
                        listState = settingsListState
                    )
                }
            }
        }

        else -> Box(modifier = Modifier.fillMaxSize())
    }
}

// ── Simple menu screen (Main Menu / Spotify sub-menu) ─────────────────────────

@Composable
private fun IPodMenuScreen(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    batteryPercent: Int,
    isCharging: Boolean,
    isMusicPlaying: Boolean
) {
    Column(modifier = Modifier.fillMaxSize()) {
        IPodStatusBar(title, batteryPercent, isCharging, isMusicPlaying)
        Spacer(Modifier.height(4.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(items) { index, option ->
                IPodMenuRow(text = option, isSelected = index == selectedIndex)
            }
        }
    }
}

// ── Search screen ─────────────────────────────────────────────────────────────

@Composable
private fun SearchScreen(viewModel: IPodViewModel) {
    val colors = IPodTheme.colors
    val vm = viewModel
    Column(modifier = Modifier.fillMaxSize()) {
        IPodStatusBar("Ricerca", vm.batteryPercentage, vm.isBatteryCharging, vm.isTrackPlaying)
        Spacer(Modifier.height(8.dp))

        // Search query display
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(colors.screenText.copy(alpha = 0.08f))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = vm.searchQuery.ifEmpty { "Inserisci testo..." },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (vm.searchQuery.isEmpty()) colors.screenText.copy(alpha = 0.4f) else colors.screenText
            )
        }
        Spacer(Modifier.height(14.dp))

        // Character picker
        Box(
            modifier = Modifier.fillMaxWidth().height(50.dp),
            contentAlignment = Alignment.Center
        ) {
            val prev = vm.keyboardChars[(vm.selectedCharIndex - 1 + vm.keyboardChars.size) % vm.keyboardChars.size]
            val curr = vm.keyboardChars[vm.selectedCharIndex]
            val next = vm.keyboardChars[(vm.selectedCharIndex + 1) % vm.keyboardChars.size]

            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(prev, fontSize = 16.sp, color = colors.screenText.copy(alpha = 0.3f),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text(curr, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.screenSelectedText,
                    modifier = Modifier.weight(1.5f)
                        .background(colors.screenSelectedBg, RoundedCornerShape(4.dp))
                        .padding(vertical = 4.dp),
                    textAlign = TextAlign.Center)
                Text(next, fontSize = 16.sp, color = colors.screenText.copy(alpha = 0.3f),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "Ruota per scegliere ∙ Click al centro per inserire",
            fontSize = 11.sp,
            color = colors.screenText.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}