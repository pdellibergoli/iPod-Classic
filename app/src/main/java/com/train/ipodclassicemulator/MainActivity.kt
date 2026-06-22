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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.train.ipodclassicemulator.ui.components.SpotifySetupScreen
import com.train.ipodclassicemulator.ui.components.scrollToKeepSelectedVisible
import com.train.ipodclassicemulator.ui.theme.IPodClassicEmulatorTheme
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import com.train.ipodclassicemulator.ui.theme.IPodThemeType
import com.train.ipodclassicemulator.ui.theme.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track

enum class ScreenState {
    CREDENTIALS_SETUP, MAIN_MENU, SPOTIFY_MENU, PLAYLISTS, ALBUMS, ARTISTS, SEARCH, TRACKS, TRACK_DETAILS, SETTINGS
}

class MainActivity : ComponentActivity() {
    private lateinit var spotifyManager: SpotifyManager
    private var musicRepository: MusicRepository? = null
    private lateinit var themeManager: ThemeManager

    // Gestione dello stato dello schermo a livello di Activity
    private var currentScreenState by mutableStateOf(ScreenState.MAIN_MENU)
    private var tokenJustObtained = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        spotifyManager = SpotifyManager(this)
        themeManager = ThemeManager(this)

        val savedId = spotifyManager.getClientId()
        val savedSecret = spotifyManager.getClientSecret()

        if (!savedId.isNullOrBlank() && !savedSecret.isNullOrBlank()) {
            // 🟢 Le chiavi esistono: Inizializzazione standard
            initializeSpotifyServices()
            currentScreenState = ScreenState.MAIN_MENU
        } else {
            // 🔴 Chiavi mancanti: Forza il setup tramite tastiera di sistema
            currentScreenState = ScreenState.CREDENTIALS_SETUP
        }

        setContent {
            IPodClassicEmulatorTheme(themeType = themeManager.currentTheme) {
                var batteryPercentage by remember { mutableStateOf(100) }
                var isBatteryCharging by remember { mutableStateOf(false) }

                var selectedAlbumIndex by remember { mutableStateOf(0) }
                var selectedArtistIndex by remember { mutableStateOf(0) }

                var albums: List<com.train.ipodclassicemulator.data.model.SpotifyAlbumDetails> by remember {
                    mutableStateOf(
                        emptyList()
                    )
                }
                var artists: List<com.train.ipodclassicemulator.data.model.SpotifyArtistDetails> by remember {
                    mutableStateOf(
                        emptyList()
                    )
                }
                var playlists: List<PlaylistItem> by remember { mutableStateOf(emptyList()) }
                var tracks: List<SpotifyTrackDetails> by remember { mutableStateOf(emptyList()) }

                val keyboardChars = remember {
                    listOf(
                        "_",
                        "A",
                        "B",
                        "C",
                        "D",
                        "E",
                        "F",
                        "G",
                        "H",
                        "I",
                        "J",
                        "K",
                        "L",
                        "M",
                        "N",
                        "O",
                        "P",
                        "Q",
                        "R",
                        "S",
                        "T",
                        "U",
                        "V",
                        "W",
                        "X",
                        "Y",
                        "Z",
                        "0",
                        "1",
                        "2",
                        "3",
                        "4",
                        "5",
                        "6",
                        "7",
                        "8",
                        "9",
                        "⌫",
                        "🔍 OK"
                    )
                }
                var selectedCharIndex by remember { mutableStateOf(1) }
                var searchQuery by remember { mutableStateOf("") }

                val albumLazyListState = rememberLazyListState()
                val artistLazyListState = rememberLazyListState()

                var previousScreen by remember { mutableStateOf(ScreenState.MAIN_MENU) }

                var selectedMainMenuIndex by remember { mutableStateOf(0) }
                var selectedSpotifyMenuIndex by remember { mutableStateOf(0) }

                val mainMenuOptions = listOf("Spotify", "Settings", "Shuffle Songs", "Now Playing")
                val spotifyMenuOptions = listOf("Playlist", "Album", "Artisti", "Ricerca")

                val mainMenuLazyListState = rememberLazyListState()
                val spotifyMenuLazyListState = rememberLazyListState()

                var isLoading by remember { mutableStateOf(false) }

                var selectedPlaylistIndex by remember { mutableStateOf(0) }
                var selectedTrackIndex by remember { mutableStateOf(0) }
                var selectedSettingsIndex by remember {
                    mutableStateOf(
                        IPodThemeType.values().indexOf(themeManager.currentTheme)
                    )
                }

                var statusText by remember { mutableStateOf("In attesa di Spotify...") }
                var playingTrackDetails by remember { mutableStateOf<SpotifyTrackDetails?>(null) }

                var currentProgressMs by remember { mutableStateOf(0L) }
                var trackDurationMs by remember { mutableStateOf(0L) }
                var currentCoverUrl by remember { mutableStateOf<String?>(null) }
                var isTrackPlaying by remember { mutableStateOf(false) }

                var isCurrentTrackLiked by remember { mutableStateOf(false) }
                var currentPlaybackMode by remember { mutableStateOf(0) }

                val playlistLazyListState = rememberLazyListState()
                val trackLazyListState = rememberLazyListState()
                val settingsLazyListState = rememberLazyListState()

                val context = androidx.compose.ui.platform.LocalContext.current

                DisposableEffect(Unit) {
                    val batteryReceiver = object : android.content.BroadcastReceiver() {
                        override fun onReceive(
                            context: android.content.Context?,
                            intent: android.content.Intent?
                        ) {
                            intent?.let {
                                val level =
                                    it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                                val scale =
                                    it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                                if (level != -1 && scale != -1) {
                                    batteryPercentage = (level * 100 / scale.toFloat()).toInt()
                                }
                                val status =
                                    it.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                                isBatteryCharging =
                                    status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                            }
                        }
                    }
                    val filter =
                        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                    context.registerReceiver(batteryReceiver, filter)
                    onDispose { context.unregisterReceiver(batteryReceiver) }
                }

                LaunchedEffect(selectedMainMenuIndex, currentScreenState) {
                    if (currentScreenState == ScreenState.MAIN_MENU) mainMenuLazyListState.scrollToKeepSelectedVisible(
                        selectedMainMenuIndex
                    )
                }
                LaunchedEffect(selectedSpotifyMenuIndex, currentScreenState) {
                    if (currentScreenState == ScreenState.SPOTIFY_MENU) spotifyMenuLazyListState.scrollToKeepSelectedVisible(
                        selectedSpotifyMenuIndex
                    )
                }
                LaunchedEffect(selectedAlbumIndex, currentScreenState) {
                    if (currentScreenState == ScreenState.ALBUMS) albumLazyListState.scrollToKeepSelectedVisible(
                        selectedAlbumIndex
                    )
                }
                LaunchedEffect(selectedArtistIndex, currentScreenState) {
                    if (currentScreenState == ScreenState.ARTISTS) artistLazyListState.scrollToKeepSelectedVisible(
                        selectedArtistIndex
                    )
                }

                LaunchedEffect(musicRepository) {
                    musicRepository?.let { repo ->
                        spotifyManager.onPlayerStateChanged = { playerState: PlayerState ->
                            val track: Track? = playerState.track
                            if (track != null) {
                                trackDurationMs = track.duration
                                currentProgressMs = playerState.playbackPosition
                                isTrackPlaying = !playerState.isPaused

                                val isShuffle = playerState.playbackOptions.isShuffling
                                currentPlaybackMode = if (isShuffle) 1 else 0

                                val imageUriStr = track.imageUri?.raw
                                if (!imageUriStr.isNullOrEmpty()) {
                                    val imageId = imageUriStr.substringAfter("image:")
                                    currentCoverUrl = "https://i.scdn.co/image/$imageId"
                                }

                                val cleanedId = track.uri.substringAfter("track:")
                                playingTrackDetails = SpotifyTrackDetails(
                                    id = cleanedId,
                                    name = track.name,
                                    uri = track.uri,
                                    artists = listOf(
                                        com.train.ipodclassicemulator.data.model.SpotifyArtistInfo(
                                            name = track.artist.name ?: "Unknown Artist"
                                        )
                                    )
                                )

                                lifecycleScope.launch {
                                    isCurrentTrackLiked = repo.isTrackSaved(cleanedId)
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(isTrackPlaying, currentProgressMs, trackDurationMs) {
                    if (isTrackPlaying) {
                        delay(1000)
                        if (currentProgressMs < trackDurationMs - 1500L) {
                            currentProgressMs += 1000L
                        } else if (trackDurationMs > 0L) {
                            val targetPlaylist = playlists.getOrNull(selectedPlaylistIndex)
                            if (targetPlaylist?.id == "favorites_virtual_id") {
                                if (selectedTrackIndex < tracks.lastIndex) {
                                    selectedTrackIndex++
                                    val nextTrack = tracks[selectedTrackIndex]
                                    currentProgressMs = 0L
                                    playingTrackDetails = nextTrack
                                    musicRepository?.play(nextTrack.uri, "")
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(tokenJustObtained.value, musicRepository) {
                    val repo = musicRepository
                    if (repo != null && playlists.isEmpty() && (spotifyManager.savedWebToken != null || spotifyManager.pendingAuthCode != null)) {
                        isLoading = true
                        statusText = "Sincronizzazione Spotify..."

                        spotifyManager.pendingAuthCode?.let { authCode ->
                            repo.fetchWebToken(authCode)
                        }

                        val remotePlaylists = repo.getUserPlaylists()
                        if (remotePlaylists.isNotEmpty() || spotifyManager.savedWebToken != null) {
                            val virtualFavorites = PlaylistItem(
                                id = "favorites_virtual_id",
                                name = "❤️ I miei Preferiti",
                                uri = "",
                                tracks = com.train.ipodclassicemulator.data.model.PlaylistTracksInfo(
                                    "",
                                    0
                                )
                            )
                            playlists = listOf(virtualFavorites) + remotePlaylists
                        }
                        isLoading = false
                    }
                }

                LaunchedEffect(selectedPlaylistIndex, currentScreenState) {
                    if (currentScreenState == ScreenState.PLAYLISTS && playlists.isNotEmpty()) {
                        playlistLazyListState.scrollToKeepSelectedVisible(selectedPlaylistIndex)
                    }
                }
                LaunchedEffect(selectedTrackIndex, currentScreenState) {
                    if (currentScreenState == ScreenState.TRACKS && tracks.isNotEmpty()) {
                        trackLazyListState.scrollToKeepSelectedVisible(selectedTrackIndex)
                    }
                }
                LaunchedEffect(selectedSettingsIndex, currentScreenState) {
                    if (currentScreenState == ScreenState.SETTINGS) {
                        settingsLazyListState.scrollToKeepSelectedVisible(selectedSettingsIndex)
                    }
                }

                val colors = IPodTheme.colors

                // 🛑 RAMO SPECIALE DI SETUP: Esce dalla grafica dell'iPod per usare la tastiera nativa
                if (currentScreenState == ScreenState.CREDENTIALS_SETUP) {
                    SpotifySetupScreen(
                        onCredentialsSaved = { clientId, clientSecret ->
                            spotifyManager.saveCredentials(clientId, clientSecret)
                            initializeSpotifyServices()
                            currentScreenState = ScreenState.MAIN_MENU
                        }
                    )
                } else {
                    // ⚙️ INTERFACCIA STANDARD DELL'IPOD CLASSIC (Gestita da Ghiera)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        colors.bodyPrimary,
                                        colors.bodySecondary
                                    )
                                )
                            )
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
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
                                        Text(
                                            text = statusText,
                                            color = colors.screenText,
                                            fontSize = 14.sp
                                        )
                                    }
                                } else {
                                    when (currentScreenState) {
                                        ScreenState.MAIN_MENU -> {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                IPodStatusBar(
                                                    title = "iPod",
                                                    batteryPercent = batteryPercentage,
                                                    isCharging = isBatteryCharging
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LazyColumn(
                                                    state = mainMenuLazyListState,
                                                    modifier = Modifier.fillMaxWidth().weight(1f)
                                                ) {
                                                    itemsIndexed(mainMenuOptions) { index, option ->
                                                        val isSelected =
                                                            index == selectedMainMenuIndex
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                                .padding(
                                                                    horizontal = 6.dp,
                                                                    vertical = 5.dp
                                                                ),
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(
                                                                text = option,
                                                                fontSize = 16.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                color = if (isSelected) colors.screenSelectedText else colors.screenText
                                                            )
                                                            Text(
                                                                text = "›",
                                                                fontSize = 16.sp,
                                                                color = if (isSelected) colors.screenSelectedText else colors.screenText.copy(
                                                                    alpha = 0.5f
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        ScreenState.SPOTIFY_MENU -> {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                IPodStatusBar(
                                                    title = "Spotify",
                                                    batteryPercent = batteryPercentage,
                                                    isCharging = isBatteryCharging
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LazyColumn(
                                                    state = spotifyMenuLazyListState,
                                                    modifier = Modifier.fillMaxWidth().weight(1f)
                                                ) {
                                                    itemsIndexed(spotifyMenuOptions) { index, option ->
                                                        val isSelected =
                                                            index == selectedSpotifyMenuIndex
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                                .padding(
                                                                    horizontal = 6.dp,
                                                                    vertical = 5.dp
                                                                ),
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(
                                                                text = option,
                                                                fontSize = 16.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                color = if (isSelected) colors.screenSelectedText else colors.screenText
                                                            )
                                                            Text(
                                                                text = "›",
                                                                fontSize = 16.sp,
                                                                color = if (isSelected) colors.screenSelectedText else colors.screenText.copy(
                                                                    alpha = 0.5f
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        ScreenState.PLAYLISTS -> {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                IPodStatusBar(
                                                    title = "Playlist",
                                                    batteryPercent = batteryPercentage,
                                                    isCharging = isBatteryCharging
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LazyColumn(
                                                    state = playlistLazyListState,
                                                    modifier = Modifier.fillMaxWidth().weight(1f)
                                                ) {
                                                    itemsIndexed(playlists) { index, playlist ->
                                                        val isSelected =
                                                            index == selectedPlaylistIndex
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                                .padding(
                                                                    horizontal = 6.dp,
                                                                    vertical = 5.dp
                                                                ),
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(
                                                                text = playlist.name,
                                                                fontSize = 16.sp,
                                                                color = if (isSelected) colors.screenSelectedText else colors.screenText
                                                            )
                                                            Text(
                                                                text = "›",
                                                                fontSize = 16.sp,
                                                                color = if (isSelected) colors.screenSelectedText else colors.screenText.copy(
                                                                    alpha = 0.5f
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        ScreenState.ALBUMS -> {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                IPodStatusBar(
                                                    title = "Album",
                                                    batteryPercent = batteryPercentage,
                                                    isCharging = isBatteryCharging
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LazyColumn(
                                                    state = albumLazyListState,
                                                    modifier = Modifier.fillMaxWidth().weight(1f)
                                                ) {
                                                    itemsIndexed(albums) { index, album ->
                                                        val isSelected = index == selectedAlbumIndex
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                                .padding(
                                                                    horizontal = 6.dp,
                                                                    vertical = 5.dp
                                                                )
                                                        ) {
                                                            Text(
                                                                text = album.name,
                                                                fontSize = 16.sp,
                                                                color = if (isSelected) colors.screenSelectedText else colors.screenText
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        ScreenState.ARTISTS -> {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                IPodStatusBar(
                                                    title = "Artisti",
                                                    batteryPercent = batteryPercentage,
                                                    isCharging = isBatteryCharging
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LazyColumn(
                                                    state = artistLazyListState,
                                                    modifier = Modifier.fillMaxWidth().weight(1f)
                                                ) {
                                                    itemsIndexed(artists) { index, artist ->
                                                        val isSelected =
                                                            index == selectedArtistIndex
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                                .padding(
                                                                    horizontal = 6.dp,
                                                                    vertical = 5.dp
                                                                )
                                                        ) {
                                                            Text(
                                                                text = artist.name,
                                                                fontSize = 16.sp,
                                                                color = if (isSelected) colors.screenSelectedText else colors.screenText
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        ScreenState.SEARCH -> {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                IPodStatusBar(
                                                    title = "Ricerca",
                                                    batteryPercent = batteryPercentage,
                                                    isCharging = isBatteryCharging
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))

                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(38.dp)
                                                        .background(colors.screenText.copy(alpha = 0.08f))
                                                        .padding(horizontal = 8.dp),
                                                    contentAlignment = Alignment.CenterStart
                                                ) {
                                                    Text(
                                                        text = if (searchQuery.isEmpty()) "Inserisci testo..." else searchQuery,
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (searchQuery.isEmpty()) colors.screenText.copy(
                                                            alpha = 0.4f
                                                        ) else colors.screenText
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(14.dp))

                                                Box(
                                                    modifier = Modifier.fillMaxWidth()
                                                        .height(50.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.Center
                                                    ) {
                                                        val prevChar =
                                                            keyboardChars[(selectedCharIndex - 1 + keyboardChars.size) % keyboardChars.size]
                                                        val currentChar =
                                                            keyboardChars[selectedCharIndex]
                                                        val nextChar =
                                                            keyboardChars[(selectedCharIndex + 1) % keyboardChars.size]

                                                        Text(
                                                            text = prevChar,
                                                            fontSize = 16.sp,
                                                            color = colors.screenText.copy(alpha = 0.3f),
                                                            modifier = Modifier.weight(1f),
                                                            textAlign = TextAlign.Center
                                                        )
                                                        Text(
                                                            text = currentChar,
                                                            fontSize = 22.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = colors.screenSelectedText,
                                                            modifier = Modifier
                                                                .weight(1.5f)
                                                                .background(
                                                                    colors.screenSelectedBg,
                                                                    RoundedCornerShape(4.dp)
                                                                )
                                                                .padding(vertical = 4.dp),
                                                            textAlign = TextAlign.Center
                                                        )
                                                        Text(
                                                            text = nextChar,
                                                            fontSize = 16.sp,
                                                            color = colors.screenText.copy(alpha = 0.3f),
                                                            modifier = Modifier.weight(1f),
                                                            textAlign = TextAlign.Center
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.weight(1f))
                                                Text(
                                                    text = "Ruota per scegliere ∙ Click al centro per inserire",
                                                    fontSize = 11.sp,
                                                    color = colors.screenText.copy(alpha = 0.5f),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }

                                        ScreenState.TRACKS -> {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                IPodStatusBar(
                                                    title = "Brani",
                                                    batteryPercent = batteryPercentage,
                                                    isCharging = isBatteryCharging
                                                )
                                                LazyColumn(
                                                    state = trackLazyListState,
                                                    modifier = Modifier.fillMaxWidth().weight(1f)
                                                ) {
                                                    itemsIndexed(tracks) { index, track ->
                                                        val isSelected = index == selectedTrackIndex
                                                        val artistName =
                                                            track.artists.firstOrNull()?.name
                                                                ?: "Unknown"
                                                        Text(
                                                            text = "${track.name} - $artistName",
                                                            fontSize = 15.sp,
                                                            color = if (isSelected) colors.screenSelectedText else colors.screenText,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                                .padding(
                                                                    horizontal = 6.dp,
                                                                    vertical = 5.dp
                                                                )
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        ScreenState.TRACK_DETAILS -> {
                                            playingTrackDetails?.let { track ->
                                                Column(modifier = Modifier.fillMaxSize()) {
                                                    IPodStatusBar(
                                                        title = "Now Playing",
                                                        batteryPercent = batteryPercentage,
                                                        isCharging = isBatteryCharging
                                                    )
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        PlayerScreen(
                                                            track = track,
                                                            coverUrl = currentCoverUrl,
                                                            progressMs = currentProgressMs,
                                                            durationMs = trackDurationMs,
                                                            isLiked = isCurrentTrackLiked,
                                                            playbackMode = currentPlaybackMode,
                                                            onLikeToggle = {
                                                                musicRepository?.let { repo ->
                                                                    lifecycleScope.launch {
                                                                        if (isCurrentTrackLiked) {
                                                                            val success =
                                                                                repo.removeSpotifyTrack(
                                                                                    track.id
                                                                                )
                                                                            if (success) isCurrentTrackLiked =
                                                                                false
                                                                        } else {
                                                                            val success =
                                                                                repo.saveSpotifyTrack(
                                                                                    track.id
                                                                                )
                                                                            if (success) isCurrentTrackLiked =
                                                                                true
                                                                        }
                                                                    }
                                                                }
                                                            },
                                                            onModeToggle = {
                                                                musicRepository?.let { repo ->
                                                                    // Cicla linearmente su 2 stati: 0 = Off, 1 = Std
                                                                    val nextMode = (currentPlaybackMode + 1) % 2
                                                                    currentPlaybackMode = nextMode

                                                                    when (nextMode) {
                                                                        0 -> { // ➡️ SHUFFLE DISATTIVATO
                                                                            spotifyManager.setShuffle(false)
                                                                            lifecycleScope.launch {
                                                                                repo.setSpotifyShuffleMode(false)
                                                                            }
                                                                        }
                                                                        1 -> { // 🔀 SHUFFLE STANDARD
                                                                            spotifyManager.setShuffle(true)
                                                                            lifecycleScope.launch {
                                                                                repo.setSpotifyShuffleMode(true)
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        ScreenState.SETTINGS -> {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                IPodStatusBar(
                                                    title = "Impostazioni",
                                                    batteryPercent = batteryPercentage,
                                                    isCharging = isBatteryCharging
                                                )
                                                Box(modifier = Modifier.weight(1f)) {
                                                    SettingsScreen(
                                                        selectedIndex = selectedSettingsIndex,
                                                        currentTheme = themeManager.currentTheme,
                                                        listState = settingsLazyListState
                                                    )
                                                }
                                            }
                                        }

                                        else -> { /* No-op */
                                        }
                                    }
                                }
                            }
                        }

                        ClickWheel(
                            modifier = Modifier.padding(bottom = 32.dp, top = 24.dp),
                            onScrollNext = {
                                when (currentScreenState) {
                                    ScreenState.MAIN_MENU -> if (selectedMainMenuIndex < mainMenuOptions.lastIndex) selectedMainMenuIndex++
                                    ScreenState.SPOTIFY_MENU -> if (selectedSpotifyMenuIndex < spotifyMenuOptions.lastIndex) selectedSpotifyMenuIndex++
                                    ScreenState.PLAYLISTS -> if (playlists.isNotEmpty() && selectedPlaylistIndex < playlists.lastIndex) selectedPlaylistIndex++
                                    ScreenState.ALBUMS -> if (albums.isNotEmpty() && selectedAlbumIndex < albums.lastIndex) selectedAlbumIndex++
                                    ScreenState.ARTISTS -> if (artists.isNotEmpty() && selectedArtistIndex < artists.lastIndex) selectedArtistIndex++
                                    ScreenState.TRACKS -> if (tracks.isNotEmpty() && selectedTrackIndex < tracks.lastIndex) selectedTrackIndex++
                                    ScreenState.SETTINGS -> {
                                        val themeCount = IPodThemeType.values().size
                                        if (selectedSettingsIndex < themeCount - 1) selectedSettingsIndex++
                                    }

                                    ScreenState.TRACK_DETAILS -> spotifyManager.adjustVolume(up = true)
                                    ScreenState.SEARCH -> selectedCharIndex =
                                        (selectedCharIndex + 1) % keyboardChars.size

                                    else -> { /* No-op */
                                    }
                                }
                            },
                            onScrollPrevious = {
                                when (currentScreenState) {
                                    ScreenState.MAIN_MENU -> if (selectedMainMenuIndex > 0) selectedMainMenuIndex--
                                    ScreenState.SPOTIFY_MENU -> if (selectedSpotifyMenuIndex > 0) selectedSpotifyMenuIndex--
                                    ScreenState.PLAYLISTS -> if (selectedPlaylistIndex > 0) selectedPlaylistIndex--
                                    ScreenState.ALBUMS -> if (selectedAlbumIndex > 0) selectedAlbumIndex--
                                    ScreenState.ARTISTS -> if (selectedArtistIndex > 0) selectedArtistIndex--
                                    ScreenState.TRACKS -> if (selectedTrackIndex > 0) selectedTrackIndex--
                                    ScreenState.SETTINGS -> if (selectedSettingsIndex > 0) selectedSettingsIndex--
                                    ScreenState.TRACK_DETAILS -> spotifyManager.adjustVolume(up = false)
                                    ScreenState.SEARCH -> selectedCharIndex =
                                        (selectedCharIndex - 1 + keyboardChars.size) % keyboardChars.size

                                    else -> { /* No-op */
                                    }
                                }
                            },
                            onSelectClick = {
                                when (currentScreenState) {
                                    ScreenState.MAIN_MENU -> {
                                        when (selectedMainMenuIndex) {
                                            0 -> currentScreenState = ScreenState.SPOTIFY_MENU
                                            1 -> currentScreenState = ScreenState.SETTINGS
                                            2 -> {
                                                musicRepository?.let { repo ->
                                                    if (tracks.isNotEmpty()) {
                                                        val randomIndex = (tracks.indices).random()
                                                        selectedTrackIndex = randomIndex
                                                        playingTrackDetails = tracks[randomIndex]
                                                        currentProgressMs = 0L
                                                        currentScreenState =
                                                            ScreenState.TRACK_DETAILS
                                                        repo.play(tracks[randomIndex].uri, "")
                                                    }
                                                }
                                            }

                                            3 -> if (playingTrackDetails != null) currentScreenState =
                                                ScreenState.TRACK_DETAILS
                                        }
                                    }

                                    ScreenState.SPOTIFY_MENU -> {
                                        val repo = musicRepository
                                        if (repo != null) {
                                            when (selectedSpotifyMenuIndex) {
                                                0 -> {
                                                    if (playlists.isEmpty()) {
                                                        isLoading = true
                                                        statusText = "Scarico Playlist..."
                                                        lifecycleScope.launch {
                                                            val remotePlaylists =
                                                                repo.getUserPlaylists()
                                                            val virtualFavorites = PlaylistItem(
                                                                id = "favorites_virtual_id",
                                                                name = "❤️ I miei Preferiti",
                                                                uri = "",
                                                                tracks = com.train.ipodclassicemulator.data.model.PlaylistTracksInfo(
                                                                    "",
                                                                    0
                                                                )
                                                            )
                                                            playlists =
                                                                listOf(virtualFavorites) + remotePlaylists
                                                            currentScreenState =
                                                                ScreenState.PLAYLISTS
                                                            isLoading = false
                                                        }
                                                    } else {
                                                        currentScreenState = ScreenState.PLAYLISTS
                                                    }
                                                }

                                                1 -> {
                                                    if (albums.isEmpty()) {
                                                        isLoading = true
                                                        statusText = "Scarico Album..."
                                                        lifecycleScope.launch {
                                                            albums = repo.getUserSavedAlbums()
                                                            selectedAlbumIndex = 0
                                                            currentScreenState = ScreenState.ALBUMS
                                                            isLoading = false
                                                        }
                                                    } else {
                                                        currentScreenState = ScreenState.ALBUMS
                                                    }
                                                }

                                                2 -> {
                                                    if (artists.isEmpty()) {
                                                        isLoading = true
                                                        statusText = "Scarico Artisti..."
                                                        lifecycleScope.launch {
                                                            artists = repo.getUserFollowedArtists()
                                                            selectedArtistIndex = 0
                                                            currentScreenState = ScreenState.ARTISTS
                                                            isLoading = false
                                                        }
                                                    } else {
                                                        currentScreenState = ScreenState.ARTISTS
                                                    }
                                                }

                                                3 -> {
                                                    searchQuery = ""
                                                    currentScreenState = ScreenState.SEARCH
                                                }
                                            }
                                        }
                                    }

                                    ScreenState.SEARCH -> {
                                        val chosenChar = keyboardChars[selectedCharIndex]
                                        when (chosenChar) {
                                            "⌫" -> if (searchQuery.isNotEmpty()) searchQuery =
                                                searchQuery.dropLast(1)

                                            "_" -> searchQuery += " "
                                            "🔍 OK" -> {
                                                musicRepository?.let { repo ->
                                                    if (searchQuery.trim().isNotEmpty()) {
                                                        isLoading = true
                                                        statusText = "Ricerca in corso..."
                                                        lifecycleScope.launch {
                                                            tracks =
                                                                repo.searchSpotifyTracks(searchQuery)
                                                            selectedTrackIndex = 0
                                                            currentScreenState = ScreenState.TRACKS
                                                            isLoading = false
                                                        }
                                                    }
                                                }
                                            }

                                            else -> searchQuery += chosenChar
                                        }
                                    }

                                    ScreenState.ALBUMS -> {
                                        musicRepository?.let { repo ->
                                            if (albums.isNotEmpty()) {
                                                val targetAlbum = albums[selectedAlbumIndex]
                                                isLoading = true
                                                statusText = "Carico brani album..."
                                                lifecycleScope.launch {
                                                    tracks = repo.getTracksForAlbum(targetAlbum.id)
                                                    selectedTrackIndex = 0
                                                    currentScreenState = ScreenState.TRACKS
                                                    isLoading = false
                                                }
                                            }
                                        }
                                    }

                                    ScreenState.ARTISTS -> {
                                        musicRepository?.let { repo ->
                                            if (artists.isNotEmpty()) {
                                                val targetArtist = artists[selectedArtistIndex]
                                                isLoading = true
                                                statusText = "Carico brani artista..."
                                                lifecycleScope.launch {
                                                    tracks =
                                                        repo.getTracksForArtist(targetArtist.id)
                                                    selectedTrackIndex = 0
                                                    currentScreenState = ScreenState.TRACKS
                                                    isLoading = false
                                                }
                                            }
                                        }
                                    }

                                    ScreenState.PLAYLISTS -> {
                                        musicRepository?.let { repo ->
                                            if (playlists.isNotEmpty()) {
                                                val targetPlaylist =
                                                    playlists[selectedPlaylistIndex]
                                                isLoading = true
                                                statusText = "Carico i brani..."
                                                lifecycleScope.launch {
                                                    tracks =
                                                        if (targetPlaylist.id == "favorites_virtual_id") {
                                                            repo.getSavedTracks()
                                                        } else {
                                                            repo.getTracksForPlaylist(targetPlaylist.id)
                                                        }
                                                    selectedTrackIndex = 0
                                                    currentScreenState = ScreenState.TRACKS
                                                    isLoading = false
                                                }
                                            }
                                        }
                                    }

                                    ScreenState.TRACKS -> {
                                        musicRepository?.let { repo ->
                                            if (tracks.isNotEmpty()) {
                                                val selectedTrack = tracks[selectedTrackIndex]
                                                val targetPlaylist =
                                                    playlists.getOrNull(selectedPlaylistIndex)
                                                val contextUri =
                                                    if (targetPlaylist?.id == "favorites_virtual_id") "" else targetPlaylist?.uri
                                                        ?: ""

                                                currentProgressMs = 0L
                                                playingTrackDetails = selectedTrack
                                                currentScreenState = ScreenState.TRACK_DETAILS
                                                repo.play(
                                                    selectedTrack.uri,
                                                    contextUri,
                                                    selectedTrackIndex
                                                )
                                            }
                                        }
                                    }

                                    ScreenState.SETTINGS -> {
                                        val chosenTheme =
                                            IPodThemeType.values()[selectedSettingsIndex]
                                        themeManager.setTheme(chosenTheme)
                                    }

                                    else -> { /* No-op */
                                    }
                                }
                            },
                            onMenuClick = {
                                when (currentScreenState) {
                                    ScreenState.TRACK_DETAILS -> currentScreenState =
                                        ScreenState.TRACKS

                                    ScreenState.TRACKS -> {
                                        when (selectedSpotifyMenuIndex) {
                                            1 -> currentScreenState = ScreenState.ALBUMS
                                            2 -> currentScreenState = ScreenState.ARTISTS
                                            3 -> currentScreenState = ScreenState.SEARCH
                                            else -> currentScreenState = ScreenState.PLAYLISTS
                                        }
                                    }

                                    ScreenState.PLAYLISTS, ScreenState.ALBUMS, ScreenState.ARTISTS, ScreenState.SEARCH -> currentScreenState =
                                        ScreenState.SPOTIFY_MENU

                                    ScreenState.SPOTIFY_MENU, ScreenState.SETTINGS -> currentScreenState =
                                        ScreenState.MAIN_MENU

                                    ScreenState.MAIN_MENU -> { /* No-op */
                                    }

                                    else -> { /* No-op */
                                    }
                                }
                            },
                            onMenuLongClick = {
                                if (currentScreenState != ScreenState.SETTINGS && currentScreenState != ScreenState.CREDENTIALS_SETUP) {
                                    previousScreen = currentScreenState
                                    selectedSettingsIndex =
                                        IPodThemeType.values().indexOf(themeManager.currentTheme)
                                    currentScreenState = ScreenState.SETTINGS
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
                                val targetPlaylist = playlists.getOrNull(selectedPlaylistIndex)
                                if (targetPlaylist?.id == "favorites_virtual_id") {
                                    if (selectedTrackIndex < tracks.lastIndex) {
                                        selectedTrackIndex++
                                        val nextTrack = tracks[selectedTrackIndex]
                                        currentProgressMs = 0L
                                        playingTrackDetails = nextTrack
                                        musicRepository?.play(nextTrack.uri, "")
                                    }
                                } else {
                                    spotifyManager.skipNext()
                                    if (currentScreenState == ScreenState.TRACKS && selectedTrackIndex < tracks.lastIndex) {
                                        selectedTrackIndex++
                                    }
                                }
                            },
                            onPreviousClick = {
                                val targetPlaylist = playlists.getOrNull(selectedPlaylistIndex)
                                if (targetPlaylist?.id == "favorites_virtual_id") {
                                    if (selectedTrackIndex > 0) {
                                        selectedTrackIndex--
                                        val prevTrack = tracks[selectedTrackIndex]
                                        currentProgressMs = 0L
                                        playingTrackDetails = prevTrack
                                        musicRepository?.play(prevTrack.uri, "")
                                    }
                                } else {
                                    spotifyManager.skipPrevious()
                                    if (currentScreenState == ScreenState.TRACKS && selectedTrackIndex > 0) {
                                        selectedTrackIndex--
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    // 🟢 FUNZIONE HELPER: Incapsula l'avvio standard di Spotify una volta che le credenziali sono pronte
    private fun initializeSpotifyServices() {
        musicRepository = MusicRepository(spotifyManager)

        musicRepository?.onTokenExpired = {
            Log.d("MainActivity", "Token scaduto. Richiedo nuova autenticazione...")
            spotifyManager.requestToken(this)
        }

        if (spotifyManager.savedWebToken == null && spotifyManager.pendingAuthCode == null) {
            spotifyManager.requestToken(this)
        }

        try {
            spotifyManager.connect { Log.d("iPodApp", "App Remote connesso!") }
        } catch (e: Exception) {
            Log.e("iPodApp", "Errore auto-connessione", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val intentUri: Uri? = intent.data
        if (intentUri != null && intentUri.toString().startsWith(spotifyManager.redirectUri)) {
            val authCode = intentUri.getQueryParameter("code")
            if (authCode != null) {
                spotifyManager.pendingAuthCode = authCode
                lifecycleScope.launch {
                    val success = musicRepository?.fetchWebToken(authCode) ?: false
                    if (success) {
                        tokenJustObtained.value = !tokenJustObtained.value
                    }
                }
                try {
                    spotifyManager.connect { Log.d("iPodApp", "Player remoto connesso!") }
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