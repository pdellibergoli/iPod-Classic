package com.train.ipodclassicemulator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.train.ipodclassicemulator.data.model.PlaylistItem
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.data.remote.SpotifyManager
import com.train.ipodclassicemulator.data.repository.MusicRepository
import com.train.ipodclassicemulator.ui.components.ClickWheel
import com.train.ipodclassicemulator.ui.components.IPodStatusBar
import com.train.ipodclassicemulator.ui.components.PlayerScreen
import com.train.ipodclassicemulator.ui.components.SettingsScreen
import com.train.ipodclassicemulator.ui.components.scrollToKeepSelectedVisible
import com.train.ipodclassicemulator.ui.theme.IPodClassicEmulatorTheme
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import com.train.ipodclassicemulator.ui.theme.IPodThemeType
import com.train.ipodclassicemulator.ui.theme.ThemeManager
import kotlinx.coroutines.delay
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track

enum class ScreenState { PLAYLISTS, TRACKS, TRACK_DETAILS, SETTINGS }

class MainActivity : ComponentActivity() {
    private lateinit var spotifyManager: SpotifyManager
    private lateinit var musicRepository: MusicRepository
    private lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        spotifyManager = SpotifyManager(this)
        musicRepository = MusicRepository(spotifyManager)
        themeManager = ThemeManager(this)

        if (spotifyManager.accessToken == null) {
            spotifyManager.requestToken(this)
        }

        setContent {
            IPodClassicEmulatorTheme(themeType = themeManager.currentTheme) {

                var currentScreen by remember { mutableStateOf(ScreenState.PLAYLISTS) }
                // Schermata da cui si è entrati nelle Impostazioni, per tornare indietro col MENU
                var previousScreen by remember { mutableStateOf(ScreenState.PLAYLISTS) }

                var isLoading by remember { mutableStateOf(false) }
                var playlists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
                var tracks by remember { mutableStateOf<List<SpotifyTrackDetails>>(emptyList()) }

                var selectedPlaylistIndex by remember { mutableStateOf(0) }
                var selectedTrackIndex by remember { mutableStateOf(0) }
                var selectedSettingsIndex by remember { mutableStateOf(IPodThemeType.values().indexOf(themeManager.currentTheme)) }

                var statusText by remember { mutableStateOf("In attesa di Spotify...") }
                var playingTrackDetails by remember { mutableStateOf<SpotifyTrackDetails?>(null) }

                // Stati dinamici reali agganciati all'ascoltatore di Spotify
                var currentProgressMs by remember { mutableStateOf(0L) }
                var trackDurationMs by remember { mutableStateOf(0L) }
                var currentCoverUrl by remember { mutableStateOf<String?>(null) }
                var isTrackPlaying by remember { mutableStateOf(false) }

                val playlistLazyListState = rememberLazyListState()
                val trackLazyListState = rememberLazyListState()
                val settingsLazyListState = rememberLazyListState()

                // AGGANCIO ASCOLTATORE REAL-TIME DEI METADATI DI SPOTIFY
                LaunchedEffect(Unit) {
                    spotifyManager.onPlayerStateChanged = { playerState: PlayerState ->
                        val track: Track? = playerState.track
                        if (track != null) {
                            trackDurationMs = track.duration
                            currentProgressMs = playerState.playbackPosition
                            isTrackPlaying = !playerState.isPaused

                            val imageUriStr = track.imageUri?.raw
                            if (!imageUriStr.isNullOrEmpty()) {
                                val imageId = imageUriStr.substringAfter("image:")
                                currentCoverUrl = "https://i.scdn.co/image/$imageId"
                            }
                        }
                    }
                }

                // Timer locale per far avanzare fluidamente la barra ogni secondo se il brano è in play
                LaunchedEffect(isTrackPlaying) {
                    while (isTrackPlaying) {
                        delay(1000)
                        if (currentProgressMs < trackDurationMs) {
                            currentProgressMs += 1000L
                        }
                    }
                }

                // Caricamento playlist
                LaunchedEffect(spotifyManager.accessToken) {
                    val code = spotifyManager.accessToken
                    if (code != null && playlists.isEmpty()) {
                        isLoading = true
                        statusText = "Scambio codice in corso..."
                        val success = musicRepository.fetchWebToken(code)
                        if (success) {
                            statusText = "Scarico le tue playlist..."
                            playlists = musicRepository.getUserPlaylists()
                            statusText = "Playlist caricate!"
                        } else {
                            statusText = "Errore nello scambio del token."
                        }
                        isLoading = false
                    }
                }

                // Scorrimento liste: avviene SOLO quando l'elemento selezionato esce
                // dall'area visibile, in modo identico salendo o scendendo (stile iPod).
                LaunchedEffect(selectedPlaylistIndex, currentScreen) {
                    if (currentScreen == ScreenState.PLAYLISTS && playlists.isNotEmpty()) {
                        playlistLazyListState.scrollToKeepSelectedVisible(selectedPlaylistIndex)
                    }
                }
                LaunchedEffect(selectedTrackIndex, currentScreen) {
                    if (currentScreen == ScreenState.TRACKS && tracks.isNotEmpty()) {
                        trackLazyListState.scrollToKeepSelectedVisible(selectedTrackIndex)
                    }
                }
                LaunchedEffect(selectedSettingsIndex, currentScreen) {
                    if (currentScreen == ScreenState.SETTINGS) {
                        settingsLazyListState.scrollToKeepSelectedVisible(selectedSettingsIndex)
                    }
                }

                val colors = IPodTheme.colors

                // 🎧 SCOCCA ESTERNA DELL'IPOD
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(colors.bodyPrimary, colors.bodySecondary))
                        )
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {

                    // 📺 SCHERMO LCD, con bordo incassato come l'originale
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
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
                            if (isLoading) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(color = colors.screenAccent)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(text = statusText, color = colors.screenText, fontSize = 14.sp)
                                }
                            } else {
                                when (currentScreen) {
                                    ScreenState.PLAYLISTS -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            IPodStatusBar(title = "iPod")
                                            Text(
                                                "Playlist",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.screenText,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                            )
                                            LazyColumn(state = playlistLazyListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                                itemsIndexed(playlists) { index, playlist ->
                                                    val isSelected = index == selectedPlaylistIndex
                                                    Text(
                                                        text = playlist.name,
                                                        fontSize = 16.sp,
                                                        color = if (isSelected) colors.screenSelectedText else colors.screenText,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                            .padding(4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    ScreenState.TRACKS -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            IPodStatusBar(title = "Brani")
                                            LazyColumn(state = trackLazyListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                                itemsIndexed(tracks) { index, track ->
                                                    val isSelected = index == selectedTrackIndex
                                                    val artistName = track.artists.firstOrNull()?.name ?: "Unknown"
                                                    Text(
                                                        text = "${track.name} - $artistName",
                                                        fontSize = 15.sp,
                                                        color = if (isSelected) colors.screenSelectedText else colors.screenText,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                            .padding(4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    ScreenState.TRACK_DETAILS -> {
                                        playingTrackDetails?.let { track ->
                                            PlayerScreen(
                                                track = track,
                                                coverUrl = currentCoverUrl,
                                                progressMs = currentProgressMs,
                                                durationMs = trackDurationMs
                                            )
                                        }
                                    }
                                    ScreenState.SETTINGS -> {
                                        SettingsScreen(
                                            selectedIndex = selectedSettingsIndex,
                                            currentTheme = themeManager.currentTheme,
                                            listState = settingsLazyListState
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 🎡 GHIERA TELECOMANDO COMPLETA
                    ClickWheel(
                        modifier = Modifier.padding(bottom = 32.dp, top = 24.dp),
                        onScrollNext = {
                            when (currentScreen) {
                                ScreenState.TRACK_DETAILS -> spotifyManager.adjustVolume(up = true)
                                ScreenState.PLAYLISTS -> if (playlists.isNotEmpty() && selectedPlaylistIndex < playlists.lastIndex) selectedPlaylistIndex++
                                ScreenState.TRACKS -> if (tracks.isNotEmpty() && selectedTrackIndex < tracks.lastIndex) selectedTrackIndex++
                                ScreenState.SETTINGS -> {
                                    val themeCount = IPodThemeType.values().size
                                    if (selectedSettingsIndex < themeCount - 1) selectedSettingsIndex++
                                }
                            }
                        },
                        onScrollPrevious = {
                            when (currentScreen) {
                                ScreenState.TRACK_DETAILS -> spotifyManager.adjustVolume(up = false)
                                ScreenState.PLAYLISTS -> if (selectedPlaylistIndex > 0) selectedPlaylistIndex--
                                ScreenState.TRACKS -> if (selectedTrackIndex > 0) selectedTrackIndex--
                                ScreenState.SETTINGS -> if (selectedSettingsIndex > 0) selectedSettingsIndex--
                            }
                        },
                        onSelectClick = {
                            when (currentScreen) {
                                ScreenState.PLAYLISTS -> if (playlists.isNotEmpty()) {
                                    val targetPlaylist = playlists[selectedPlaylistIndex]
                                    isLoading = true
                                    @Suppress("DEPRECATION")
                                    lifecycleScope.launchWhenStarted {
                                        tracks = musicRepository.getTracksForPlaylist(targetPlaylist.id)
                                        selectedTrackIndex = 0
                                        currentScreen = ScreenState.TRACKS
                                        isLoading = false
                                    }
                                }
                                ScreenState.TRACKS -> if (tracks.isNotEmpty()) {
                                    val selectedTrack = tracks[selectedTrackIndex]
                                    playingTrackDetails = selectedTrack
                                    currentScreen = ScreenState.TRACK_DETAILS
                                    musicRepository.play(selectedTrack.uri)
                                }
                                ScreenState.SETTINGS -> {
                                    val chosenTheme = IPodThemeType.values()[selectedSettingsIndex]
                                    themeManager.setTheme(chosenTheme)
                                }
                                ScreenState.TRACK_DETAILS -> { /* niente */ }
                            }
                        },
                        onMenuClick = {
                            when (currentScreen) {
                                ScreenState.TRACK_DETAILS -> currentScreen = ScreenState.TRACKS
                                ScreenState.TRACKS -> currentScreen = ScreenState.PLAYLISTS
                                ScreenState.SETTINGS -> currentScreen = previousScreen
                                ScreenState.PLAYLISTS -> { /* già alla radice */ }
                            }
                        },
                        onMenuLongClick = {
                            // 🔧 Tieni premuto MENU per aprire le Impostazioni da qualunque schermata
                            if (currentScreen != ScreenState.SETTINGS) {
                                previousScreen = currentScreen
                                selectedSettingsIndex = IPodThemeType.values().indexOf(themeManager.currentTheme)
                                currentScreen = ScreenState.SETTINGS
                            }
                        },
                        onPlayPauseClick = {
                            if (isTrackPlaying) {
                                spotifyManager.pausePlayback()
                                isTrackPlaying = false
                            } else {
                                spotifyManager.resumePlayback()
                                isTrackPlaying = true
                            }
                        },
                        onNextClick = {
                            spotifyManager.skipNext()
                            if (currentScreen == ScreenState.TRACKS && selectedTrackIndex < tracks.lastIndex) {
                                selectedTrackIndex++
                                playingTrackDetails = tracks[selectedTrackIndex]
                            }
                        },
                        onPreviousClick = {
                            spotifyManager.skipPrevious()
                            if (currentScreen == ScreenState.TRACKS && selectedTrackIndex > 0) {
                                selectedTrackIndex--
                                playingTrackDetails = tracks[selectedTrackIndex]
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val intentUri: Uri? = intent.data
        if (intentUri != null && intentUri.toString().startsWith(spotifyManager.redirectUri)) {
            val authCode = intentUri.getQueryParameter("code")
            if (authCode != null) {
                spotifyManager.accessToken = authCode
                try {
                    spotifyManager.connect {
                        Log.d("iPodApp", "Player remoto connesso!")
                    }
                } catch (e: Exception) {
                    Log.e("iPodApp", "Errore connect", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spotifyManager.disconnect()
    }
}