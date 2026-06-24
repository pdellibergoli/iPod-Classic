package com.train.ipodclassicemulator

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
import com.train.ipodclassicemulator.data.model.PlaylistItem
import com.train.ipodclassicemulator.data.model.PlaylistTracksInfo
import com.train.ipodclassicemulator.data.model.SpotifyAlbumDetails
import com.train.ipodclassicemulator.data.model.SpotifyAlbumModelInfo
import com.train.ipodclassicemulator.data.model.SpotifyArtistDetails
import com.train.ipodclassicemulator.data.model.SpotifyArtistInfo
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.data.remote.SpotifyManager
import com.train.ipodclassicemulator.data.repository.MusicRepository
import com.train.ipodclassicemulator.ui.theme.IPodThemeType
import com.train.ipodclassicemulator.ui.theme.ThemeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "IPodViewModel"
private const val FAVORITES_ID = "favorites_virtual_id"

enum class ScreenState {
    CREDENTIALS_SETUP, MAIN_MENU, SPOTIFY_MENU, PLAYLISTS, ALBUMS, ARTISTS, SEARCH, TRACKS, TRACK_DETAILS, SETTINGS
}

class IPodViewModel(application: Application) : AndroidViewModel(application) {

    val spotifyManager = SpotifyManager(application)
    private var musicRepository: MusicRepository? = null

    // ── Navigation ────────────────────────────────────────────────────────────
    var screenState by mutableStateOf(ScreenState.MAIN_MENU)
    var previousScreen by mutableStateOf(ScreenState.MAIN_MENU)

    // ── Menu selection indices ────────────────────────────────────────────────
    var selectedMainMenuIndex by mutableStateOf(0)
    var selectedSpotifyMenuIndex by mutableStateOf(0)
    var selectedPlaylistIndex by mutableStateOf(0)
    var selectedAlbumIndex by mutableStateOf(0)
    var selectedArtistIndex by mutableStateOf(0)
    var selectedTrackIndex by mutableStateOf(0)
    var selectedSettingsIndex by mutableStateOf(0)

    val mainMenuOptions = listOf("Spotify", "Settings", "Shuffle Songs", "Now Playing")
    val spotifyMenuOptions = listOf("Playlist", "Album", "Artisti", "Ricerca")

    // ── Data lists ────────────────────────────────────────────────────────────
    var playlists by mutableStateOf<List<PlaylistItem>>(emptyList())
    var albums by mutableStateOf<List<SpotifyAlbumDetails>>(emptyList())
    var artists by mutableStateOf<List<SpotifyArtistDetails>>(emptyList())
    var tracks by mutableStateOf<List<SpotifyTrackDetails>>(emptyList())

    // ── Playback ──────────────────────────────────────────────────────────────
    var playingTrackDetails by mutableStateOf<SpotifyTrackDetails?>(null)
    var currentCoverUrl by mutableStateOf<String?>(null)
    var currentProgressMs by mutableStateOf(0L)
    var trackDurationMs by mutableStateOf(0L)
    var isTrackPlaying by mutableStateOf(false)
    var isCurrentTrackLiked by mutableStateOf(false)
    var currentPlaybackMode by mutableStateOf(0)
    // Workaround for PlayerState bug in Favorites playlist
    var isLocalShuffleEnabled by mutableStateOf(false)

    // Fix #11 — job per isTrackSaved: cancellato se arriva una nuova traccia prima del completamento
    private var isTrackSavedJob: Job? = null

    // ── Search ────────────────────────────────────────────────────────────────
    val keyboardChars = listOf(
        "_", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "🔍 OK"
    )
    var selectedCharIndex by mutableStateOf(1)
    var searchQuery by mutableStateOf("")

    // ── UI feedback ───────────────────────────────────────────────────────────
    var isLoading by mutableStateOf(false)
    var statusText by mutableStateOf("In attesa di Spotify...")

    // Fix #6 — true quando serve mostrare il pulsante "Accedi a Spotify" esplicito
    var showLoginButton by mutableStateOf(false)

    // ── Battery (set from UI via BroadcastReceiver) ───────────────────────────
    var batteryPercentage by mutableStateOf(100)
    var isBatteryCharging by mutableStateOf(false)

    // ── Token refresh trigger ─────────────────────────────────────────────────
    var tokenRefreshTick by mutableStateOf(false)

    // ─────────────────────────────────────────────────────────────────────────
    init {
        val savedId = spotifyManager.getClientId()
        val savedSecret = spotifyManager.getClientSecret()
        screenState = if (!savedId.isNullOrBlank() && !savedSecret.isNullOrBlank()) {
            initializeSpotifyServices()
            ScreenState.MAIN_MENU
        } else {
            ScreenState.CREDENTIALS_SETUP
        }
        startProgressTicker()
    }

    // ── Init / auth ───────────────────────────────────────────────────────────

    fun initializeSpotifyServices() {
        musicRepository = MusicRepository(spotifyManager)
        musicRepository?.onTokenExpired = {
            Log.d(TAG, "Token scaduto. Richiedo nuova autenticazione...")
            // Fix #1 — porta l'utente alla schermata di login invece di lasciare lo schermo fermo
            screenState = ScreenState.CREDENTIALS_SETUP
            statusText = "Sessione scaduta. Accedi di nuovo a Spotify."
            showLoginButton = true
            spotifyManager.requestToken(getApplication())
        }
        if (spotifyManager.savedWebToken == null && spotifyManager.pendingAuthCode == null) {
            // Fix #6 — mostra il pulsante login esplicito invece del solo testo "In attesa..."
            showLoginButton = true
            spotifyManager.requestToken(getApplication())
        }
        try {
            spotifyManager.connect {
                Log.d(TAG, "App Remote connesso!")
                // Fix #7 — rilegge lo stato del player appena la connessione è disponibile
                restoreNowPlayingFromPlayerState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore auto-connessione", e)
        }
        setupPlayerStateListener()
    }

    fun handleCredentialsSaved(clientId: String, clientSecret: String) {
        spotifyManager.saveCredentials(clientId, clientSecret)
        showLoginButton = false
        initializeSpotifyServices()
        screenState = ScreenState.MAIN_MENU
    }

    fun handleAuthCode(authCode: String, lifecycleScope: Lifecycle) {
        spotifyManager.pendingAuthCode = authCode
        showLoginButton = false
        viewModelScope.launch {
            val success = musicRepository?.fetchWebToken(authCode) ?: false
            if (success) tokenRefreshTick = !tokenRefreshTick
        }
        try {
            spotifyManager.connect { Log.d(TAG, "Player remoto connesso!") }
        } catch (e: Exception) {
            Log.e(TAG, "Errore connect", e)
        }
    }

    // Fix #6 — chiamato dal pulsante "Accedi a Spotify" esplicito in UI
    fun onLoginButtonClicked() {
        spotifyManager.requestToken(getApplication())
    }

    // ── Player state listener ─────────────────────────────────────────────────

    private fun setupPlayerStateListener() {
        spotifyManager.onPlayerStateChanged = { playerState: PlayerState ->
            val track: Track? = playerState.track
            if (track != null) {
                trackDurationMs = track.duration
                currentProgressMs = playerState.playbackPosition
                isTrackPlaying = !playerState.isPaused

                val isShuffle = playerState.playbackOptions.isShuffling
                val isFavorites = playlists.getOrNull(selectedPlaylistIndex)?.id == FAVORITES_ID
                if (isFavorites) {
                    currentPlaybackMode = if (isLocalShuffleEnabled) 1 else 0
                    spotifyManager.isShuffling = isLocalShuffleEnabled
                } else {
                    currentPlaybackMode = if (isShuffle) 1 else 0
                    spotifyManager.isShuffling = isShuffle
                    isLocalShuffleEnabled = isShuffle
                }

                track.imageUri?.raw?.takeIf { it.isNotEmpty() }?.let { raw ->
                    currentCoverUrl = "https://i.scdn.co/image/${raw.substringAfter("image:")}"
                }

                val cleanedId = track.uri.substringAfter("track:")
                playingTrackDetails = SpotifyTrackDetails(
                    id = cleanedId,
                    name = track.name,
                    uri = track.uri,
                    artists = listOf(SpotifyArtistInfo(name = track.artist.name ?: "Unknown Artist")),
                    album = SpotifyAlbumModelInfo(id = "", name = track.album.name, uri = "")
                )

                // Fix #11 — cancella la chiamata precedente prima di lanciarne una nuova
                isTrackSavedJob?.cancel()
                isTrackSavedJob = viewModelScope.launch {
                    isCurrentTrackLiked = musicRepository?.isTrackSaved(cleanedId) ?: false
                }
            }
        }
    }

    // Fix #7 — rilegge lo stato corrente del player all'avvio (dopo connessione App Remote)
    private fun restoreNowPlayingFromPlayerState() {
        spotifyManager.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { state ->
            val track = state?.track ?: return@setResultCallback
            if (playingTrackDetails != null) return@setResultCallback // già ripristinato via listener

            trackDurationMs = track.duration
            currentProgressMs = state.playbackPosition
            isTrackPlaying = !state.isPaused

            track.imageUri?.raw?.takeIf { it.isNotEmpty() }?.let { raw ->
                currentCoverUrl = "https://i.scdn.co/image/${raw.substringAfter("image:")}"
            }

            val cleanedId = track.uri.substringAfter("track:")
            playingTrackDetails = SpotifyTrackDetails(
                id = cleanedId,
                name = track.name,
                uri = track.uri,
                artists = listOf(SpotifyArtistInfo(name = track.artist.name ?: "Unknown Artist")),
                album = SpotifyAlbumModelInfo(id = "", name = track.album.name, uri = "")
            )
            Log.d(TAG, "Now Playing ripristinato all'avvio: ${track.name}")
        }
    }

    // ── Progress ticker ───────────────────────────────────────────────────────

    private fun startProgressTicker() {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                if (!isTrackPlaying) continue
                if (currentProgressMs < trackDurationMs - 1500L) {
                    currentProgressMs += 1000L
                } else if (trackDurationMs > 0L) {
                    val isFavorites = playlists.getOrNull(selectedPlaylistIndex)?.id == FAVORITES_ID
                    if (isFavorites && tracks.isNotEmpty()) {
                        advanceFavoritesTrack()
                    }
                }
            }
        }
    }

    private fun advanceFavoritesTrack() {
        selectedTrackIndex = if (isLocalShuffleEnabled) {
            tracks.indices.random().also { Log.d(TAG, "Fine brano: shuffle → $it") }
        } else {
            (selectedTrackIndex + 1).coerceAtMost(tracks.lastIndex)
                .also { Log.d(TAG, "Fine brano: sequenziale → $it") }
        }
        val next = tracks[selectedTrackIndex]
        currentProgressMs = 0L
        playingTrackDetails = next
        musicRepository?.play(next.uri, "")
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    fun loadPlaylistsIfNeeded() {
        if (playlists.isNotEmpty()) { screenState = ScreenState.PLAYLISTS; return }
        viewModelScope.launch {
            isLoading = true; statusText = "Scarico Playlist..."
            val remote = musicRepository?.getUserPlaylists() ?: emptyList()
            playlists = listOf(favoritesItem()) + remote
            screenState = ScreenState.PLAYLISTS
            isLoading = false
        }
    }

    fun loadAlbumsIfNeeded() {
        if (albums.isNotEmpty()) { screenState = ScreenState.ALBUMS; return }
        viewModelScope.launch {
            isLoading = true; statusText = "Scarico Album..."
            albums = musicRepository?.getUserSavedAlbums() ?: emptyList()
            selectedAlbumIndex = 0; screenState = ScreenState.ALBUMS; isLoading = false
        }
    }

    fun loadArtistsIfNeeded() {
        if (artists.isNotEmpty()) { screenState = ScreenState.ARTISTS; return }
        viewModelScope.launch {
            isLoading = true; statusText = "Scarico Artisti..."
            artists = musicRepository?.getUserFollowedArtists() ?: emptyList()
            selectedArtistIndex = 0; screenState = ScreenState.ARTISTS; isLoading = false
        }
    }

    fun loadTracksForPlaylist() {
        val playlist = playlists.getOrNull(selectedPlaylistIndex) ?: return
        viewModelScope.launch {
            isLoading = true; statusText = "Carico i brani..."
            tracks = if (playlist.id == FAVORITES_ID) {
                musicRepository?.getSavedTracks() ?: emptyList()
            } else {
                musicRepository?.getTracksForPlaylist(playlist.id) ?: emptyList()
            }
            selectedTrackIndex = 0; screenState = ScreenState.TRACKS; isLoading = false
        }
    }

    fun loadTracksForAlbum() {
        val album = albums.getOrNull(selectedAlbumIndex) ?: return
        viewModelScope.launch {
            isLoading = true; statusText = "Carico brani album..."
            tracks = musicRepository?.getTracksForAlbum(album.id, album.images) ?: emptyList()
            selectedTrackIndex = 0; screenState = ScreenState.TRACKS; isLoading = false
        }
    }

    fun loadTracksForArtist() {
        val artist = artists.getOrNull(selectedArtistIndex) ?: return
        viewModelScope.launch {
            isLoading = true; statusText = "Carico brani artista..."
            tracks = musicRepository?.getTracksForArtist(artist.id, artist.images) ?: emptyList()
            selectedTrackIndex = 0; screenState = ScreenState.TRACKS; isLoading = false
        }
    }

    fun searchTracks() {
        if (searchQuery.isBlank()) return
        viewModelScope.launch {
            isLoading = true; statusText = "Ricerca in corso..."
            tracks = musicRepository?.searchSpotifyTracks(searchQuery) ?: emptyList()
            selectedTrackIndex = 0; screenState = ScreenState.TRACKS; isLoading = false
        }
    }

    // ── Playback actions ──────────────────────────────────────────────────────

    fun playSelectedTrack() {
        val track = tracks.getOrNull(selectedTrackIndex) ?: return
        val playlist = playlists.getOrNull(selectedPlaylistIndex)
        val contextUri = if (playlist?.id == FAVORITES_ID) "" else playlist?.uri ?: ""
        currentProgressMs = 0L
        playingTrackDetails = track
        screenState = ScreenState.TRACK_DETAILS
        musicRepository?.play(track.uri, contextUri, selectedTrackIndex)
        if (playlist?.id == FAVORITES_ID) spotifyManager.setShuffle(isLocalShuffleEnabled)
    }

    fun togglePlayPause() {
        if (isTrackPlaying) {
            spotifyManager.pausePlayback(); isTrackPlaying = false
        } else {
            spotifyManager.resumePlayback(); isTrackPlaying = true
        }
    }

    fun skipNext() {
        val isFavorites = playlists.getOrNull(selectedPlaylistIndex)?.id == FAVORITES_ID
        if (isFavorites) {
            if (tracks.isEmpty()) return
            Log.d(TAG, "onNextClick Preferiti: shuffle=$isLocalShuffleEnabled")
            selectedTrackIndex = if (isLocalShuffleEnabled) tracks.indices.random()
            else (selectedTrackIndex + 1).coerceAtMost(tracks.lastIndex)
            playFavoritesTrack(tracks[selectedTrackIndex])
        } else {
            spotifyManager.skipNext()
            if (screenState == ScreenState.TRACKS && selectedTrackIndex < tracks.lastIndex) selectedTrackIndex++
        }
    }

    fun skipPrevious() {
        val isFavorites = playlists.getOrNull(selectedPlaylistIndex)?.id == FAVORITES_ID
        if (isFavorites) {
            if (tracks.isEmpty()) return
            selectedTrackIndex = if (isLocalShuffleEnabled) tracks.indices.random()
            else (selectedTrackIndex - 1).coerceAtLeast(0)
            playFavoritesTrack(tracks[selectedTrackIndex])
        } else {
            spotifyManager.skipPrevious()
            if (screenState == ScreenState.TRACKS && selectedTrackIndex > 0) selectedTrackIndex--
        }
    }

    private fun playFavoritesTrack(track: SpotifyTrackDetails) {
        currentProgressMs = 0L
        playingTrackDetails = track
        musicRepository?.play(track.uri, "")
        if (!isLocalShuffleEnabled) spotifyManager.setShuffle(false)
    }

    fun shuffleAll() {
        if (tracks.isEmpty()) return
        val i = tracks.indices.random()
        selectedTrackIndex = i
        playingTrackDetails = tracks[i]
        currentProgressMs = 0L
        screenState = ScreenState.TRACK_DETAILS
        musicRepository?.play(tracks[i].uri, "")
    }

    fun toggleShuffle() {
        val repo = musicRepository ?: return
        val next = (currentPlaybackMode + 1) % 2
        currentPlaybackMode = next
        val enabled = next == 1
        isLocalShuffleEnabled = enabled
        spotifyManager.isShuffling = enabled
        spotifyManager.setShuffle(enabled)
        viewModelScope.launch { repo.setSpotifyShuffleMode(enabled) }
    }

    fun toggleLike(trackId: String) {
        val repo = musicRepository ?: return
        viewModelScope.launch {
            if (isCurrentTrackLiked) {
                if (repo.removeSpotifyTrack(trackId)) isCurrentTrackLiked = false
            } else {
                if (repo.saveSpotifyTrack(trackId)) isCurrentTrackLiked = true
            }
        }
    }

    // ── Click wheel: scroll ───────────────────────────────────────────────────

    fun onScrollNext() {
        when (screenState) {
            ScreenState.MAIN_MENU -> if (selectedMainMenuIndex < mainMenuOptions.lastIndex) selectedMainMenuIndex++
            ScreenState.SPOTIFY_MENU -> if (selectedSpotifyMenuIndex < spotifyMenuOptions.lastIndex) selectedSpotifyMenuIndex++
            ScreenState.PLAYLISTS -> if (playlists.isNotEmpty() && selectedPlaylistIndex < playlists.lastIndex) selectedPlaylistIndex++
            ScreenState.ALBUMS -> if (albums.isNotEmpty() && selectedAlbumIndex < albums.lastIndex) selectedAlbumIndex++
            ScreenState.ARTISTS -> if (artists.isNotEmpty() && selectedArtistIndex < artists.lastIndex) selectedArtistIndex++
            ScreenState.TRACKS -> if (tracks.isNotEmpty() && selectedTrackIndex < tracks.lastIndex) selectedTrackIndex++
            ScreenState.SETTINGS -> { val max = IPodThemeType.values().lastIndex; if (selectedSettingsIndex < max) selectedSettingsIndex++ }
            ScreenState.TRACK_DETAILS -> spotifyManager.adjustVolume(up = true)
            ScreenState.SEARCH -> selectedCharIndex = (selectedCharIndex + 1) % keyboardChars.size
            else -> Unit
        }
    }

    fun onScrollPrevious() {
        when (screenState) {
            ScreenState.MAIN_MENU -> if (selectedMainMenuIndex > 0) selectedMainMenuIndex--
            ScreenState.SPOTIFY_MENU -> if (selectedSpotifyMenuIndex > 0) selectedSpotifyMenuIndex--
            ScreenState.PLAYLISTS -> if (selectedPlaylistIndex > 0) selectedPlaylistIndex--
            ScreenState.ALBUMS -> if (selectedAlbumIndex > 0) selectedAlbumIndex--
            ScreenState.ARTISTS -> if (selectedArtistIndex > 0) selectedArtistIndex--
            ScreenState.TRACKS -> if (selectedTrackIndex > 0) selectedTrackIndex--
            ScreenState.SETTINGS -> if (selectedSettingsIndex > 0) selectedSettingsIndex--
            ScreenState.TRACK_DETAILS -> spotifyManager.adjustVolume(up = false)
            ScreenState.SEARCH -> selectedCharIndex = (selectedCharIndex - 1 + keyboardChars.size) % keyboardChars.size
            else -> Unit
        }
    }

    // ── Click wheel: select ───────────────────────────────────────────────────

    fun onSelectClick(themeManager: ThemeManager) {
        when (screenState) {
            ScreenState.MAIN_MENU -> when (selectedMainMenuIndex) {
                0 -> screenState = ScreenState.SPOTIFY_MENU
                1 -> screenState = ScreenState.SETTINGS
                2 -> shuffleAll()
                3 -> if (playingTrackDetails != null) screenState = ScreenState.TRACK_DETAILS
            }
            ScreenState.SPOTIFY_MENU -> when (selectedSpotifyMenuIndex) {
                0 -> loadPlaylistsIfNeeded()
                1 -> loadAlbumsIfNeeded()
                2 -> loadArtistsIfNeeded()
                3 -> { searchQuery = ""; screenState = ScreenState.SEARCH }
            }
            ScreenState.SEARCH -> handleSearchInput()
            ScreenState.ALBUMS -> loadTracksForAlbum()
            ScreenState.ARTISTS -> loadTracksForArtist()
            ScreenState.PLAYLISTS -> loadTracksForPlaylist()
            ScreenState.TRACKS -> playSelectedTrack()
            ScreenState.SETTINGS -> {
                val chosen = IPodThemeType.values()[selectedSettingsIndex]
                themeManager.setTheme(chosen)
            }
            else -> Unit
        }
    }

    private fun handleSearchInput() {
        when (val char = keyboardChars[selectedCharIndex]) {
            "⌫" -> if (searchQuery.isNotEmpty()) searchQuery = searchQuery.dropLast(1)
            "_" -> searchQuery += " "
            "🔍 OK" -> searchTracks()
            else -> searchQuery += char
        }
    }

    // ── Click wheel: menu ─────────────────────────────────────────────────────

    fun onMenuClick() {
        when (screenState) {
            ScreenState.TRACK_DETAILS -> screenState = ScreenState.TRACKS
            ScreenState.TRACKS -> screenState = when (selectedSpotifyMenuIndex) {
                1 -> ScreenState.ALBUMS
                2 -> ScreenState.ARTISTS
                3 -> ScreenState.SEARCH
                else -> ScreenState.PLAYLISTS
            }
            ScreenState.PLAYLISTS, ScreenState.ALBUMS, ScreenState.ARTISTS, ScreenState.SEARCH ->
                screenState = ScreenState.SPOTIFY_MENU
            ScreenState.SPOTIFY_MENU, ScreenState.SETTINGS -> screenState = ScreenState.MAIN_MENU
            else -> Unit
        }
    }

    fun onMenuLongClick() {
        if (screenState != ScreenState.MAIN_MENU && screenState != ScreenState.CREDENTIALS_SETUP) {
            previousScreen = screenState
            screenState = ScreenState.MAIN_MENU
            selectedMainMenuIndex = 0
        }
    }

    // ── Startup data load ─────────────────────────────────────────────────────

    fun loadInitialDataIfReady() {
        val repo = musicRepository ?: return
        if (playlists.isNotEmpty()) return
        if (spotifyManager.savedWebToken == null && spotifyManager.pendingAuthCode == null) return
        viewModelScope.launch {
            isLoading = true; statusText = "Sincronizzazione Spotify..."
            spotifyManager.pendingAuthCode?.let { repo.fetchWebToken(it) }
            val remote = repo.getUserPlaylists()
            if (remote.isNotEmpty() || spotifyManager.savedWebToken != null) {
                playlists = listOf(favoritesItem()) + remote
            }
            isLoading = false
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun favoritesItem() = PlaylistItem(
        id = FAVORITES_ID,
        name = "❤️ I miei Preferiti",
        uri = "",
        tracks = PlaylistTracksInfo("", 0),
        images = null
    )

    // ── ViewModel.Factory ─────────────────────────────────────────────────────

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            IPodViewModel(app) as T
    }
}
