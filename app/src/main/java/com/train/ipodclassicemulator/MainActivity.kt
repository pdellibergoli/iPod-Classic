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
import com.train.ipodclassicemulator.ui.components.scrollToKeepSelectedVisible
import com.train.ipodclassicemulator.ui.theme.IPodClassicEmulatorTheme
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import com.train.ipodclassicemulator.ui.theme.IPodThemeType
import com.train.ipodclassicemulator.ui.theme.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
import com.train.ipodclassicemulator.data.model.SpotifyAlbumModelInfo
import com.train.ipodclassicemulator.data.model.SpotifyArtistModelInfo

enum class ScreenState {
    MAIN_MENU, SPOTIFY_MENU, PLAYLISTS, ALBUMS, ARTISTS, SEARCH, TRACKS, TRACK_DETAILS, SETTINGS
}

class MainActivity : ComponentActivity() {
    private lateinit var spotifyManager: SpotifyManager
    private lateinit var musicRepository: MusicRepository
    private lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        spotifyManager = SpotifyManager(this)
        musicRepository = MusicRepository(spotifyManager)
        themeManager = ThemeManager(this)

        musicRepository.onTokenExpired = {
            Log.d("MainActivity", "Token scaduto. Richiedo nuova autenticazione...")
            spotifyManager.requestToken(this)
        }

        if (spotifyManager.accessToken == null) {
            spotifyManager.requestToken(this)
        } else {
            try {
                spotifyManager.connect {
                    Log.d("iPodApp", "Auto-connessione all'App Remote riuscita!")
                }
            } catch (e: Exception) {
                Log.e("iPodApp", "Errore auto-connessione", e)
            }
        }

        setContent {
            IPodClassicEmulatorTheme(themeType = themeManager.currentTheme) {
                var batteryPercentage by remember { mutableStateOf(100) }
                var isBatteryCharging by remember { mutableStateOf(false) }

                var selectedAlbumIndex by remember { mutableStateOf(0) }
                var selectedArtistIndex by remember { mutableStateOf(0) }

                var albums: List<com.train.ipodclassicemulator.data.model.SpotifyAlbumDetails> by remember { mutableStateOf(emptyList()) }
                var artists: List<com.train.ipodclassicemulator.data.model.SpotifyArtistDetails> by remember { mutableStateOf(emptyList()) }
                var playlists: List<PlaylistItem> by remember { mutableStateOf(emptyList()) }
                var tracks: List<SpotifyTrackDetails> by remember { mutableStateOf(emptyList()) }

                // 🟢 CONFIGURAZIONE TASTIERA RUOTA IPOD FOR SEARCH
                val keyboardChars = remember { listOf("_", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "🔍 OK") }
                var selectedCharIndex by remember { mutableStateOf(1) } // Parte dalla lettera 'A'
                var searchQuery by remember { mutableStateOf("") }

                val albumLazyListState = rememberLazyListState()
                val artistLazyListState = rememberLazyListState()

                var currentScreen by remember { mutableStateOf(ScreenState.MAIN_MENU) }
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
                var selectedSettingsIndex by remember { mutableStateOf(IPodThemeType.values().indexOf(themeManager.currentTheme)) }

                var statusText by remember { mutableStateOf("In attesa di Spotify...") }
                var playingTrackDetails by remember { mutableStateOf<SpotifyTrackDetails?>(null) }

                var currentProgressMs by remember { mutableStateOf(0L) }
                var trackDurationMs by remember { mutableStateOf(0L) }
                var currentCoverUrl by remember { mutableStateOf<String?>(null) }
                var isTrackPlaying by remember { mutableStateOf(false) }

                val playlistLazyListState = rememberLazyListState()
                val trackLazyListState = rememberLazyListState()
                val settingsLazyListState = rememberLazyListState()

                val context = androidx.compose.ui.platform.LocalContext.current

                DisposableEffect(Unit) {
                    val batteryReceiver = object : android.content.BroadcastReceiver() {
                        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                            intent?.let {
                                val level = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                                val scale = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                                if (level != -1 && scale != -1) {
                                    batteryPercentage = (level * 100 / scale.toFloat()).toInt()
                                }
                                val status = it.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                                isBatteryCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                            }
                        }
                    }
                    val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                    context.registerReceiver(batteryReceiver, filter)
                    onDispose { context.unregisterReceiver(batteryReceiver) }
                }

                LaunchedEffect(selectedMainMenuIndex, currentScreen) {
                    if (currentScreen == ScreenState.MAIN_MENU) mainMenuLazyListState.scrollToKeepSelectedVisible(selectedMainMenuIndex)
                }
                LaunchedEffect(selectedSpotifyMenuIndex, currentScreen) {
                    if (currentScreen == ScreenState.SPOTIFY_MENU) spotifyMenuLazyListState.scrollToKeepSelectedVisible(selectedSpotifyMenuIndex)
                }
                LaunchedEffect(selectedAlbumIndex, currentScreen) {
                    if (currentScreen == ScreenState.ALBUMS) albumLazyListState.scrollToKeepSelectedVisible(selectedAlbumIndex)
                }
                LaunchedEffect(selectedArtistIndex, currentScreen) {
                    if (currentScreen == ScreenState.ARTISTS) artistLazyListState.scrollToKeepSelectedVisible(selectedArtistIndex)
                }

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

                            playingTrackDetails = SpotifyTrackDetails(
                                id = track.uri.substringAfter("track:"),
                                name = track.name,
                                uri = track.uri,
                                artists = listOf(com.train.ipodclassicemulator.data.model.SpotifyArtistInfo(name = track.artist.name ?: "Unknown Artist"))
                            )
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
                                    musicRepository.play(nextTrack.uri, "")
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(spotifyManager.accessToken) {
                    val code = spotifyManager.accessToken
                    if (code != null && playlists.isEmpty()) {
                        isLoading = true
                        statusText = "Sincronizzazione Spotify..."
                        val success = musicRepository.fetchWebToken(code)
                        if (success) {
                            val remotePlaylists = musicRepository.getUserPlaylists()
                            val virtualFavorites = PlaylistItem(
                                id = "favorites_virtual_id",
                                name = "❤️ I miei Preferiti",
                                uri = "",
                                tracks = com.train.ipodclassicemulator.data.model.PlaylistTracksInfo("", 0)
                            )
                            playlists = listOf(virtualFavorites) + remotePlaylists
                        }
                        isLoading = false
                    }
                }

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

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(colors.bodyPrimary, colors.bodySecondary)))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 📺 SCHERMO LCD (RIPRISTINATO COMPLETO A SCHERMO INTERO)
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
                                    ScreenState.MAIN_MENU -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            IPodStatusBar(title = "iPod", batteryPercent = batteryPercentage, isCharging = isBatteryCharging)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LazyColumn(state = mainMenuLazyListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                                itemsIndexed(mainMenuOptions) { index, option ->
                                                    val isSelected = index == selectedMainMenuIndex
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                            .padding(horizontal = 6.dp, vertical = 5.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(text = option, fontSize = 16.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) colors.screenSelectedText else colors.screenText)
                                                        Text(text = "›", fontSize = 16.sp, color = if (isSelected) colors.screenSelectedText else colors.screenText.copy(alpha = 0.5f))
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    ScreenState.SPOTIFY_MENU -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            IPodStatusBar(title = "Spotify", batteryPercent = batteryPercentage, isCharging = isBatteryCharging)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LazyColumn(state = spotifyMenuLazyListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                                itemsIndexed(spotifyMenuOptions) { index, option ->
                                                    val isSelected = index == selectedSpotifyMenuIndex
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                            .padding(horizontal = 6.dp, vertical = 5.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(text = option, fontSize = 16.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) colors.screenSelectedText else colors.screenText)
                                                        Text(text = "›", fontSize = 16.sp, color = if (isSelected) colors.screenSelectedText else colors.screenText.copy(alpha = 0.5f))
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    ScreenState.PLAYLISTS -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            IPodStatusBar(title = "Playlist", batteryPercent = batteryPercentage, isCharging = isBatteryCharging)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LazyColumn(state = playlistLazyListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                                itemsIndexed(playlists) { index, playlist ->
                                                    val isSelected = index == selectedPlaylistIndex
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                            .padding(horizontal = 6.dp, vertical = 5.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(text = playlist.name, fontSize = 16.sp, color = if (isSelected) colors.screenSelectedText else colors.screenText)
                                                        Text(text = "›", fontSize = 16.sp, color = if (isSelected) colors.screenSelectedText else colors.screenText.copy(alpha = 0.5f))
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    ScreenState.ALBUMS -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            IPodStatusBar(title = "Album", batteryPercent = batteryPercentage, isCharging = isBatteryCharging)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LazyColumn(state = albumLazyListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                                itemsIndexed(albums) { index, album ->
                                                    val isSelected = index == selectedAlbumIndex
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                            .padding(horizontal = 6.dp, vertical = 5.dp)
                                                    ) {
                                                        Text(text = album.name, fontSize = 16.sp, color = if (isSelected) colors.screenSelectedText else colors.screenText)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    ScreenState.ARTISTS -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            IPodStatusBar(title = "Artisti", batteryPercent = batteryPercentage, isCharging = isBatteryCharging)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LazyColumn(state = artistLazyListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                                itemsIndexed(artists) { index, artist ->
                                                    val isSelected = index == selectedArtistIndex
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                                                            .padding(horizontal = 6.dp, vertical = 5.dp)
                                                    ) {
                                                        Text(text = artist.name, fontSize = 16.sp, color = if (isSelected) colors.screenSelectedText else colors.screenText)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 🟢 INTERFACCIA TASTIERA DI RICERCA DIGITALE A GHIERA IPOD
                                    ScreenState.SEARCH -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            IPodStatusBar(title = "Ricerca", batteryPercent = batteryPercentage, isCharging = isBatteryCharging)
                                            Spacer(modifier = Modifier.height(8.dp))

                                            // Box Parola Composta
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
                                                    color = if (searchQuery.isEmpty()) colors.screenText.copy(alpha = 0.4f) else colors.screenText
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(14.dp))

                                            // Carosello Lettere Orizzontali da scorrere con la ClickWheel
                                            Box(
                                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    // Mostra 3 caratteri (Precedente, Selezionato, Successivo)
                                                    val prevChar = keyboardChars[(selectedCharIndex - 1 + keyboardChars.size) % keyboardChars.size]
                                                    val currentChar = keyboardChars[selectedCharIndex]
                                                    val nextChar = keyboardChars[(selectedCharIndex + 1) % keyboardChars.size]

                                                    Text(text = prevChar, fontSize = 16.sp, color = colors.screenText.copy(alpha = 0.3f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                                    Text(
                                                        text = currentChar,
                                                        fontSize = 22.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = colors.screenSelectedText,
                                                        modifier = Modifier
                                                            .weight(1.5f)
                                                            .background(colors.screenSelectedBg, RoundedCornerShape(4.dp))
                                                            .padding(vertical = 4.dp),
                                                        textAlign = TextAlign.Center
                                                    )
                                                    Text(text = nextChar, fontSize = 16.sp, color = colors.screenText.copy(alpha = 0.3f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
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
                                            IPodStatusBar(title = "Brani", batteryPercent = batteryPercentage, isCharging = isBatteryCharging)
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
                                                            .padding(horizontal = 6.dp, vertical = 5.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    ScreenState.TRACK_DETAILS -> {
                                        playingTrackDetails?.let { track ->
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                IPodStatusBar(title = "Now Playing", batteryPercent = batteryPercentage, isCharging = isBatteryCharging)
                                                Box(modifier = Modifier.weight(1f)) {
                                                    PlayerScreen(track = track, coverUrl = currentCoverUrl, progressMs = currentProgressMs, durationMs = trackDurationMs)
                                                }
                                            }
                                        }
                                    }

                                    ScreenState.SETTINGS -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            IPodStatusBar(title = "Impostazioni", batteryPercent = batteryPercentage, isCharging = isBatteryCharging)
                                            Box(modifier = Modifier.weight(1f)) {
                                                SettingsScreen(selectedIndex = selectedSettingsIndex, currentTheme = themeManager.currentTheme, listState = settingsLazyListState)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 🎡 GHIERA TELECOMANDO INTERATTIVA CLICKWHEEL
                    ClickWheel(
                        modifier = Modifier.padding(bottom = 32.dp, top = 24.dp),
                        onScrollNext = {
                            when (currentScreen) {
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
                                ScreenState.SEARCH -> {
                                    // Scorre in avanti le lettere della tastiera virtuale
                                    selectedCharIndex = (selectedCharIndex + 1) % keyboardChars.size
                                }
                            }
                        },
                        onScrollPrevious = {
                            when (currentScreen) {
                                ScreenState.MAIN_MENU -> if (selectedMainMenuIndex > 0) selectedMainMenuIndex--
                                ScreenState.SPOTIFY_MENU -> if (selectedSpotifyMenuIndex > 0) selectedSpotifyMenuIndex--
                                ScreenState.PLAYLISTS -> if (selectedPlaylistIndex > 0) selectedPlaylistIndex--
                                ScreenState.ALBUMS -> if (selectedAlbumIndex > 0) selectedAlbumIndex--
                                ScreenState.ARTISTS -> if (selectedArtistIndex > 0) selectedArtistIndex--
                                ScreenState.TRACKS -> if (selectedTrackIndex > 0) selectedTrackIndex--
                                ScreenState.SETTINGS -> if (selectedSettingsIndex > 0) selectedSettingsIndex--
                                ScreenState.TRACK_DETAILS -> spotifyManager.adjustVolume(up = false)
                                ScreenState.SEARCH -> {
                                    // Scorre all'indietro le lettere della tastiera virtuale
                                    selectedCharIndex = (selectedCharIndex - 1 + keyboardChars.size) % keyboardChars.size
                                }
                            }
                        },
                        onSelectClick = {
                            when (currentScreen) {
                                ScreenState.MAIN_MENU -> {
                                    when (selectedMainMenuIndex) {
                                        0 -> currentScreen = ScreenState.SPOTIFY_MENU
                                        1 -> currentScreen = ScreenState.SETTINGS
                                        2 -> {
                                            if (tracks.isNotEmpty()) {
                                                val randomIndex = (tracks.indices).random()
                                                selectedTrackIndex = randomIndex
                                                playingTrackDetails = tracks[randomIndex]
                                                currentProgressMs = 0L
                                                currentScreen = ScreenState.TRACK_DETAILS
                                                musicRepository.play(tracks[randomIndex].uri, "")
                                            }
                                        }
                                        3 -> if (playingTrackDetails != null) currentScreen = ScreenState.TRACK_DETAILS
                                    }
                                }
                                ScreenState.SPOTIFY_MENU -> {
                                    val token = spotifyManager.accessToken
                                    when (selectedSpotifyMenuIndex) {
                                        0 -> {
                                            if (playlists.isEmpty() && token != null) {
                                                isLoading = true
                                                statusText = "Scarico Playlist..."
                                                lifecycleScope.launch {
                                                    musicRepository.fetchWebToken(token)
                                                    val remotePlaylists = musicRepository.getUserPlaylists()
                                                    val virtualFavorites = PlaylistItem(
                                                        id = "favorites_virtual_id",
                                                        name = "❤️ I miei Preferiti",
                                                        uri = "",
                                                        tracks = com.train.ipodclassicemulator.data.model.PlaylistTracksInfo("", 0)
                                                    )
                                                    playlists = listOf(virtualFavorites) + remotePlaylists
                                                    currentScreen = ScreenState.PLAYLISTS
                                                    isLoading = false
                                                }
                                            } else {
                                                currentScreen = ScreenState.PLAYLISTS
                                            }
                                        }
                                        1 -> {
                                            if (albums.isEmpty() && token != null) {
                                                isLoading = true
                                                statusText = "Scarico Album..."
                                                lifecycleScope.launch {
                                                    musicRepository.fetchWebToken(token)
                                                    albums = musicRepository.getUserSavedAlbums()
                                                    selectedAlbumIndex = 0
                                                    currentScreen = ScreenState.ALBUMS
                                                    isLoading = false
                                                }
                                            } else {
                                                currentScreen = ScreenState.ALBUMS
                                            }
                                        }
                                        2 -> {
                                            isLoading = true
                                            statusText = "Scarico Artisti..."
                                            lifecycleScope.launch {
                                                if (token != null) musicRepository.fetchWebToken(token)
                                                artists = musicRepository.getUserFollowedArtists() // 🟢 Assegna la lista corretta
                                                selectedArtistIndex = 0
                                                currentScreen = ScreenState.ARTISTS
                                                isLoading = false
                                            }
                                        }
                                        3 -> {
                                            searchQuery = "" // Reset della ricerca quando si entra
                                            currentScreen = ScreenState.SEARCH
                                        }
                                    }
                                }

                                // 🟢 COMPOSIZIONE STRINGA TRAMITE CLICK CENTRALE
                                ScreenState.SEARCH -> {
                                    val chosenChar = keyboardChars[selectedCharIndex]
                                    when (chosenChar) {
                                        "⌫" -> {
                                            if (searchQuery.isNotEmpty()) searchQuery = searchQuery.dropLast(1)
                                        }
                                        "_" -> {
                                            searchQuery += " " // Spazio vuoto
                                        }
                                        "🔍 OK" -> {
                                            if (searchQuery.trim().isNotEmpty()) {
                                                isLoading = true
                                                statusText = "Ricerca in corso..."
                                                lifecycleScope.launch {
                                                    tracks = musicRepository.searchSpotifyTracks(searchQuery)
                                                    selectedTrackIndex = 0
                                                    currentScreen = ScreenState.TRACKS // Mostra i risultati
                                                    isLoading = false
                                                }
                                            }
                                        }
                                        else -> {
                                            searchQuery += chosenChar
                                        }
                                    }
                                }
                                ScreenState.ALBUMS -> {
                                    if (albums.isNotEmpty()) {
                                        val targetAlbum = albums[selectedAlbumIndex]
                                        isLoading = true
                                        statusText = "Carico brani album..."
                                        lifecycleScope.launch {
                                            tracks = musicRepository.getTracksForAlbum(targetAlbum.id)
                                            selectedTrackIndex = 0
                                            currentScreen = ScreenState.TRACKS
                                            isLoading = false
                                        }
                                    }
                                }
                                ScreenState.ARTISTS -> {
                                    if (artists.isNotEmpty()) {
                                        val targetArtist = artists[selectedArtistIndex]
                                        isLoading = true
                                        statusText = "Carico brani artista..."
                                        lifecycleScope.launch {
                                            tracks = musicRepository.getTracksForArtist(targetArtist.id)
                                            selectedTrackIndex = 0
                                            currentScreen = ScreenState.TRACKS
                                            isLoading = false
                                        }
                                    }
                                }
                                ScreenState.PLAYLISTS -> {
                                    if (playlists.isNotEmpty()) {
                                        val targetPlaylist = playlists[selectedPlaylistIndex]
                                        isLoading = true
                                        statusText = "Carico i brani..."
                                        lifecycleScope.launch {
                                            tracks = if (targetPlaylist.id == "favorites_virtual_id") {
                                                musicRepository.getSavedTracks()
                                            } else {
                                                musicRepository.getTracksForPlaylist(targetPlaylist.id)
                                            }
                                            selectedTrackIndex = 0
                                            currentScreen = ScreenState.TRACKS
                                            isLoading = false
                                        }
                                    }
                                }
                                ScreenState.TRACKS -> {
                                    if (tracks.isNotEmpty()) {
                                        val selectedTrack = tracks[selectedTrackIndex]
                                        val targetPlaylist = playlists.getOrNull(selectedPlaylistIndex)
                                        val contextUri = if (targetPlaylist?.id == "favorites_virtual_id") "" else targetPlaylist?.uri ?: ""

                                        currentProgressMs = 0L
                                        playingTrackDetails = selectedTrack
                                        currentScreen = ScreenState.TRACK_DETAILS
                                        musicRepository.play(selectedTrack.uri, contextUri, selectedTrackIndex)
                                    }
                                }
                                ScreenState.SETTINGS -> {
                                    val chosenTheme = IPodThemeType.values()[selectedSettingsIndex]
                                    themeManager.setTheme(chosenTheme)
                                }
                                ScreenState.TRACK_DETAILS -> { /* No-op */ }
                            }
                        },
                        onMenuClick = {
                            when (currentScreen) {
                                ScreenState.TRACK_DETAILS -> currentScreen = ScreenState.TRACKS
                                ScreenState.TRACKS -> {
                                    when (selectedSpotifyMenuIndex) {
                                        1 -> currentScreen = ScreenState.ALBUMS
                                        2 -> currentScreen = ScreenState.ARTISTS
                                        3 -> currentScreen = ScreenState.SEARCH // Torna alla tastiera se arrivavi da una ricerca
                                        else -> currentScreen = ScreenState.PLAYLISTS
                                    }
                                }
                                ScreenState.PLAYLISTS, ScreenState.ALBUMS, ScreenState.ARTISTS, ScreenState.SEARCH -> {
                                    currentScreen = ScreenState.SPOTIFY_MENU
                                }
                                ScreenState.SPOTIFY_MENU, ScreenState.SETTINGS -> currentScreen = ScreenState.MAIN_MENU
                                ScreenState.MAIN_MENU -> { /* No-op */ }
                            }
                        },
                        onMenuLongClick = {
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
                            val targetPlaylist = playlists.getOrNull(selectedPlaylistIndex)
                            if (targetPlaylist?.id == "favorites_virtual_id") {
                                if (selectedTrackIndex < tracks.lastIndex) {
                                    selectedTrackIndex++
                                    val nextTrack = tracks[selectedTrackIndex]
                                    currentProgressMs = 0L
                                    playingTrackDetails = nextTrack
                                    musicRepository.play(nextTrack.uri, "")
                                }
                            } else {
                                spotifyManager.skipNext()
                                if (currentScreen == ScreenState.TRACKS && selectedTrackIndex < tracks.lastIndex) {
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
                                    musicRepository.play(prevTrack.uri, "")
                                }
                            } else {
                                spotifyManager.skipPrevious()
                                if (currentScreen == ScreenState.TRACKS && selectedTrackIndex > 0) {
                                    selectedTrackIndex--
                                }
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
                    spotifyManager.connect { Log.d("iPodApp", "Player remoto connesso!") }
                } catch (e: Exception) { Log.e("iPodApp", "Errore connect", e) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spotifyManager.disconnect()
    }
}