package com.train.ipodclassicemulator

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.train.ipodclassicemulator.data.repository.LocalMusicRepository
import com.train.ipodclassicemulator.data.repository.LocalTrack
import com.train.ipodclassicemulator.data.repository.MusicRepository
import com.train.ipodclassicemulator.ui.theme.IPodThemeType
import com.train.ipodclassicemulator.ui.theme.ThemeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "IPodViewModel"
private const val FAVORITES_ID = "favorites_virtual_id"

enum class ScreenState {
    CREDENTIALS_SETUP, MAIN_MENU, SPOTIFY_MENU,
    LOCAL_MUSIC_MENU, LOCAL_FOLDERS, LOCAL_FOLDER_TRACKS,
    LOCAL_ALBUMS, LOCAL_ALBUM_TRACKS, LOCAL_ARTISTS, LOCAL_ARTIST_TRACKS, LOCAL_TRACKS,
    PLAYLISTS, ALBUMS, ARTISTS, SEARCH, TRACKS, TRACK_DETAILS, SETTINGS
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

    val mainMenuOptions = listOf("Spotify", "Music", "Settings", "Shuffle Songs", "Now Playing")
    val spotifyMenuOptions = listOf("Playlist", "Album", "Artisti", "Ricerca")

    // ── Musica locale ─────────────────────────────────────────────────────────
    var localTracks by mutableStateOf<List<LocalTrack>>(emptyList())
    var localMenuIndex by mutableIntStateOf(0)
    val localMenuOptions = listOf("Cartelle", "Album", "Artisti", "Tutti")

    // Gruppi
    var localFolders  by mutableStateOf<List<String>>(emptyList())
    var localAlbums   by mutableStateOf<List<String>>(emptyList())
    var localArtists  by mutableStateOf<List<String>>(emptyList())

    // Indici selezione per ogni vista
    var selectedLocalMenuIndex   by mutableIntStateOf(0)
    var selectedFolderIndex      by mutableIntStateOf(0)
    var selectedLocalAlbumIndex  by mutableIntStateOf(0)
    var selectedLocalArtistIndex by mutableIntStateOf(0)
    var selectedLocalTrackIndex  by mutableIntStateOf(0)

    // Brani filtrati per la vista corrente (cartella/album/artista o tutti)
    var filteredLocalTracks by mutableStateOf<List<LocalTrack>>(emptyList())

    var localMediaPlayer: android.media.MediaPlayer? = null   // tenuto solo per compatibilità reference
    var isLocalMusicPlaying by mutableStateOf(false)

    // ── Binding al LocalMusicService ──────────────────────────────────────
    private var musicService: LocalMusicService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? LocalMusicService.LocalBinder ?: return
            musicService = localBinder.getService().also { svc ->
                svc.onPlaybackStateChanged = { playing, posMs ->
                    isTrackPlaying = playing
                    isLocalMusicPlaying = playing || (musicService?.mediaPlayer != null)
                    currentProgressMs = posMs
                }
                svc.onTrackCompleted = {
                    isLocalMusicPlaying = false
                    isTrackPlaying = false
                    // Avanza automaticamente al brano successivo (se esiste)
                    val next = (selectedLocalTrackIndex + 1)
                    if (next <= filteredLocalTracks.lastIndex) {
                        selectedLocalTrackIndex = next
                        playLocalTrack(filteredLocalTracks[next])
                    }
                }
                svc.onError = {
                    isLocalMusicPlaying = false
                    isTrackPlaying = false
                }
                svc.onNextRequested     = { skipNext() }
                svc.onPreviousRequested = { skipPrevious() }
            }
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
        }
    }

    /** Da chiamare in MainActivity.onCreate */
    fun bindMusicService() {
        val app = getApplication<Application>()
        val intent = Intent(app, LocalMusicService::class.java)
        app.startService(intent)   // garantisce che il service sopravviva all'unbind
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /** Da chiamare in MainActivity.onDestroy */
    fun unbindMusicService() {
        if (isServiceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isServiceBound = false
        }
    }


    fun loadLocalMusic() {
        viewModelScope.launch {
            isLoading = true
            statusText = "Lettura musica dispositivo..."
            val all = LocalMusicRepository.loadTracks(getApplication())
            localTracks  = all
            localFolders = LocalMusicRepository.folders(all)
            localAlbums  = LocalMusicRepository.albums(all)
            localArtists = LocalMusicRepository.artists(all)
            selectedLocalMenuIndex = 0
            screenState = ScreenState.LOCAL_MUSIC_MENU
            isLoading = false
        }
    }

    fun openLocalFolder() {
        val folder = localFolders.getOrNull(selectedFolderIndex) ?: return
        filteredLocalTracks = localTracks.filter { it.folderPath == folder }
        selectedLocalTrackIndex = 0
        screenState = ScreenState.LOCAL_FOLDER_TRACKS
    }

    fun openLocalAlbum() {
        val album = localAlbums.getOrNull(selectedLocalAlbumIndex) ?: return
        filteredLocalTracks = localTracks.filter { it.album == album }
        selectedLocalTrackIndex = 0
        screenState = ScreenState.LOCAL_ALBUM_TRACKS
    }

    fun openLocalArtist() {
        val artist = localArtists.getOrNull(selectedLocalArtistIndex) ?: return
        filteredLocalTracks = localTracks.filter { it.artist == artist }
        selectedLocalTrackIndex = 0
        screenState = ScreenState.LOCAL_ARTIST_TRACKS
    }

    fun openAllLocalTracks() {
        filteredLocalTracks = localTracks
        selectedLocalTrackIndex = 0
        screenState = ScreenState.LOCAL_TRACKS
    }

    fun playLocalTrack(track: LocalTrack) {
        // Costruisce un SpotifyTrackDetails fittizio per riutilizzare PlayerScreen invariato
        playingTrackDetails = SpotifyTrackDetails(
            id      = track.id.toString(),
            name    = track.title,
            uri     = track.contentUri.toString(),
            artists = listOf(SpotifyArtistInfo(name = track.artist)),
            album   = SpotifyAlbumModelInfo(id = "", name = track.album, uri = "")
        )
        currentCoverUrl   = track.albumArtUri?.toString()
        currentProgressMs = 0L
        trackDurationMs   = track.durationMs
        isTrackPlaying    = true
        isLocalMusicPlaying = true
        screenState = ScreenState.TRACK_DETAILS

        // Delega al service (che gestisce anche la notifica e l'audio focus)
        musicService?.playTrack(track)
    }

    fun stopLocalPlayback() {
        musicService?.pausePlayback()
        // Ferma il service in foreground
        getApplication<Application>().stopService(
            Intent(getApplication(), LocalMusicService::class.java)
        )
        isLocalMusicPlaying = false
        isTrackPlaying = false
    }

    // ── Data lists ────────────────────────────────────────────────────────────
    var playlists by mutableStateOf<List<PlaylistItem>>(emptyList())
    var albums by mutableStateOf<List<SpotifyAlbumDetails>>(emptyList())
    var artists by mutableStateOf<List<SpotifyArtistDetails>>(emptyList())
    var tracks by mutableStateOf<List<SpotifyTrackDetails>>(emptyList())

    // Cover URL di tutti gli album salvati — usate per il Ken Burns nel menu
    val allAlbumCoverUrls: List<String>
        get() = albums.mapNotNull { it.images?.firstOrNull()?.url }.distinct()

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

    // ── Volume (mostrato al posto della progressbar nel player) ───────────────
    var showVolumeBar by mutableStateOf(false)
    var currentVolumePercent by mutableStateOf(0f)
    private var volumeHideJob: Job? = null

    // ── Token refresh trigger ─────────────────────────────────────────────────
    var tokenRefreshTick by mutableStateOf(false)
    var currentCodeVerifier: String? = null

    // ─────────────────────────────────────────────────────────────────────────
    init {
        Log.d(TAG, "Client ID letto: '${spotifyManager.spotifyClientId}'")

        if (spotifyManager.spotifyClientId.isNotBlank() && spotifyManager.spotifyClientId != "null") {
            initializeSpotifyServices()
            screenState = ScreenState.MAIN_MENU
        } else {
            screenState = ScreenState.CREDENTIALS_SETUP
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
            viewModelScope.launch {
                delay(1000)
                spotifyManager.connect {
                    Log.d("IPodViewModel", "App Remote connesso dopo delay!")
                    restoreNowPlayingFromPlayerState()
                }
            }
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
        showLoginButton = false
        initializeSpotifyServices()
        screenState = ScreenState.MAIN_MENU
    }

    fun handleAuthCode(authCode: String, lifecycleScope: Lifecycle) {
        spotifyManager.pendingAuthCode = authCode
        showLoginButton = false

        // Assicura che il repository esista prima di usarlo
        if (musicRepository == null) {
            musicRepository = MusicRepository(spotifyManager)
        }

        viewModelScope.launch {
            val verifier = spotifyManager.codeVerifier ?: run {
                Log.e(TAG, "handleAuthCode: codeVerifier nullo — impossibile scambiare il codice")
                // Recovery: rimanda l'utente al login per ricominciare il flusso PKCE
                showLoginButton = true
                screenState = ScreenState.CREDENTIALS_SETUP
                return@launch
            }
            val success = musicRepository?.fetchWebToken(authCode, verifier) ?: false
            if (success) {
                Log.d(TAG, "Token ottenuto con successo")
                tokenRefreshTick = !tokenRefreshTick
                // Naviga subito al menu principale senza aspettare loadInitialDataIfReady
                screenState = ScreenState.MAIN_MENU
                loadInitialDataIfReady()
            } else {
                Log.e(TAG, "fetchWebToken fallito — rimando al login")
                showLoginButton = true
                screenState = ScreenState.CREDENTIALS_SETUP
            }
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

                track.imageUri?.raw?.let { raw ->
                    val imageId = raw.substringAfter("image:")
                    val finalUrl = "https://i.scdn.co/image/$imageId"
                    Log.d("IPodViewModel", "DEBUG URL: $finalUrl")
                    currentCoverUrl = finalUrl
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
                if (isLocalMusicPlaying || musicService?.mediaPlayer != null) {
                    musicService?.let { svc ->
                        try { currentProgressMs = svc.currentPositionMs() } catch (_: Exception) {}
                    }
                } else if (currentProgressMs < trackDurationMs - 1500L) {
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
        if (isLocalMusicPlaying || musicService?.mediaPlayer != null) {
            val svc = musicService ?: return
            if (isTrackPlaying) {
                svc.pausePlayback()
                isTrackPlaying = false
            } else {
                svc.resumePlayback()
                isTrackPlaying = true
            }
        } else {
            if (isTrackPlaying) {
                spotifyManager.pausePlayback(); isTrackPlaying = false
            } else {
                spotifyManager.resumePlayback(); isTrackPlaying = true
            }
        }
    }

    fun skipNext() {
        if (isLocalMusicPlaying || musicService?.mediaPlayer != null) {
            if (filteredLocalTracks.isEmpty()) return
            val nextIndex = (selectedLocalTrackIndex + 1).coerceAtMost(filteredLocalTracks.lastIndex)
            selectedLocalTrackIndex = nextIndex
            playLocalTrack(filteredLocalTracks[nextIndex])
            return
        }
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
        if (isLocalMusicPlaying || musicService?.mediaPlayer != null) {
            if (filteredLocalTracks.isEmpty()) return
            if (currentProgressMs > 3000L) {
                musicService?.seekTo(0)
                currentProgressMs = 0L
            } else {
                val prevIndex = (selectedLocalTrackIndex - 1).coerceAtLeast(0)
                selectedLocalTrackIndex = prevIndex
                playLocalTrack(filteredLocalTracks[prevIndex])
            }
            return
        }
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
            ScreenState.LOCAL_MUSIC_MENU   -> if (selectedLocalMenuIndex < localMenuOptions.lastIndex) selectedLocalMenuIndex++
            ScreenState.LOCAL_FOLDERS      -> if (localFolders.isNotEmpty() && selectedFolderIndex < localFolders.lastIndex) selectedFolderIndex++
            ScreenState.LOCAL_ALBUMS       -> if (localAlbums.isNotEmpty() && selectedLocalAlbumIndex < localAlbums.lastIndex) selectedLocalAlbumIndex++
            ScreenState.LOCAL_ARTISTS      -> if (localArtists.isNotEmpty() && selectedLocalArtistIndex < localArtists.lastIndex) selectedLocalArtistIndex++
            ScreenState.LOCAL_FOLDER_TRACKS,
            ScreenState.LOCAL_ALBUM_TRACKS,
            ScreenState.LOCAL_ARTIST_TRACKS,
            ScreenState.LOCAL_TRACKS       -> if (filteredLocalTracks.isNotEmpty() && selectedLocalTrackIndex < filteredLocalTracks.lastIndex) selectedLocalTrackIndex++
            ScreenState.SPOTIFY_MENU -> if (selectedSpotifyMenuIndex < spotifyMenuOptions.lastIndex) selectedSpotifyMenuIndex++
            ScreenState.PLAYLISTS -> if (playlists.isNotEmpty() && selectedPlaylistIndex < playlists.lastIndex) selectedPlaylistIndex++
            ScreenState.ALBUMS -> if (albums.isNotEmpty() && selectedAlbumIndex < albums.lastIndex) selectedAlbumIndex++
            ScreenState.ARTISTS -> if (artists.isNotEmpty() && selectedArtistIndex < artists.lastIndex) selectedArtistIndex++
            ScreenState.TRACKS -> if (tracks.isNotEmpty() && selectedTrackIndex < tracks.lastIndex) selectedTrackIndex++
            ScreenState.SETTINGS -> { val max = IPodThemeType.values().lastIndex; if (selectedSettingsIndex < max) selectedSettingsIndex++ }
            ScreenState.TRACK_DETAILS -> { spotifyManager.adjustVolume(up = true); showVolumeOverlay(up = true) }
            ScreenState.SEARCH -> selectedCharIndex = (selectedCharIndex + 1) % keyboardChars.size
            else -> Unit
        }
    }

    fun onScrollPrevious() {
        when (screenState) {
            ScreenState.MAIN_MENU -> if (selectedMainMenuIndex > 0) selectedMainMenuIndex--
            ScreenState.LOCAL_MUSIC_MENU   -> if (selectedLocalMenuIndex > 0) selectedLocalMenuIndex--
            ScreenState.LOCAL_FOLDERS      -> if (selectedFolderIndex > 0) selectedFolderIndex--
            ScreenState.LOCAL_ALBUMS       -> if (selectedLocalAlbumIndex > 0) selectedLocalAlbumIndex--
            ScreenState.LOCAL_ARTISTS      -> if (selectedLocalArtistIndex > 0) selectedLocalArtistIndex--
            ScreenState.LOCAL_FOLDER_TRACKS,
            ScreenState.LOCAL_ALBUM_TRACKS,
            ScreenState.LOCAL_ARTIST_TRACKS,
            ScreenState.LOCAL_TRACKS       -> if (selectedLocalTrackIndex > 0) selectedLocalTrackIndex--
            ScreenState.SPOTIFY_MENU -> if (selectedSpotifyMenuIndex > 0) selectedSpotifyMenuIndex--
            ScreenState.PLAYLISTS -> if (selectedPlaylistIndex > 0) selectedPlaylistIndex--
            ScreenState.ALBUMS -> if (selectedAlbumIndex > 0) selectedAlbumIndex--
            ScreenState.ARTISTS -> if (selectedArtistIndex > 0) selectedArtistIndex--
            ScreenState.TRACKS -> if (selectedTrackIndex > 0) selectedTrackIndex--
            ScreenState.SETTINGS -> if (selectedSettingsIndex > 0) selectedSettingsIndex--
            ScreenState.TRACK_DETAILS -> { spotifyManager.adjustVolume(up = false); showVolumeOverlay(up = false) }
            ScreenState.SEARCH -> selectedCharIndex = (selectedCharIndex - 1 + keyboardChars.size) % keyboardChars.size
            else -> Unit
        }
    }

    // ── Click wheel: select ───────────────────────────────────────────────────

    fun onSelectClick(themeManager: ThemeManager) {
        when (screenState) {
            ScreenState.MAIN_MENU -> when (selectedMainMenuIndex) {
                0 -> screenState = ScreenState.SPOTIFY_MENU
                1 -> loadLocalMusic()
                2 -> screenState = ScreenState.SETTINGS
                3 -> shuffleAll()
                4 -> if (playingTrackDetails != null) screenState = ScreenState.TRACK_DETAILS
            }
            ScreenState.LOCAL_MUSIC_MENU -> when (selectedLocalMenuIndex) {
                0 -> { selectedFolderIndex = 0; screenState = ScreenState.LOCAL_FOLDERS }
                1 -> { selectedLocalAlbumIndex = 0; screenState = ScreenState.LOCAL_ALBUMS }
                2 -> { selectedLocalArtistIndex = 0; screenState = ScreenState.LOCAL_ARTISTS }
                3 -> openAllLocalTracks()
            }
            ScreenState.LOCAL_FOLDERS      -> openLocalFolder()
            ScreenState.LOCAL_ALBUMS       -> openLocalAlbum()
            ScreenState.LOCAL_ARTISTS      -> openLocalArtist()
            ScreenState.LOCAL_FOLDER_TRACKS,
            ScreenState.LOCAL_ALBUM_TRACKS,
            ScreenState.LOCAL_ARTIST_TRACKS,
            ScreenState.LOCAL_TRACKS -> {
                val track = filteredLocalTracks.getOrNull(selectedLocalTrackIndex) ?: return
                playLocalTrack(track)
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
            ScreenState.TRACK_DETAILS -> {
                if (isLocalMusicPlaying) {
                    // Torna alla lista locale da cui proveniva il brano
                    screenState = when {
                        filteredLocalTracks == localTracks -> ScreenState.LOCAL_TRACKS
                        localFolders.getOrNull(selectedFolderIndex)?.let { folder ->
                            filteredLocalTracks.firstOrNull()?.folderPath == folder
                        } == true -> ScreenState.LOCAL_FOLDER_TRACKS
                        localAlbums.getOrNull(selectedLocalAlbumIndex)?.let { album ->
                            filteredLocalTracks.firstOrNull()?.album == album
                        } == true -> ScreenState.LOCAL_ALBUM_TRACKS
                        else -> ScreenState.LOCAL_ARTIST_TRACKS
                    }
                } else {
                    screenState = ScreenState.TRACKS
                }
            }
            ScreenState.TRACKS -> screenState = when (selectedSpotifyMenuIndex) {
                1 -> ScreenState.ALBUMS
                2 -> ScreenState.ARTISTS
                3 -> ScreenState.SEARCH
                else -> ScreenState.PLAYLISTS
            }
            ScreenState.PLAYLISTS, ScreenState.ALBUMS, ScreenState.ARTISTS, ScreenState.SEARCH ->
                screenState = ScreenState.SPOTIFY_MENU
            ScreenState.LOCAL_MUSIC_MENU -> screenState = ScreenState.MAIN_MENU
            ScreenState.LOCAL_FOLDERS, ScreenState.LOCAL_ALBUMS,
            ScreenState.LOCAL_ARTISTS, ScreenState.LOCAL_TRACKS -> screenState = ScreenState.LOCAL_MUSIC_MENU
            ScreenState.LOCAL_FOLDER_TRACKS  -> screenState = ScreenState.LOCAL_FOLDERS
            ScreenState.LOCAL_ALBUM_TRACKS   -> screenState = ScreenState.LOCAL_ALBUMS
            ScreenState.LOCAL_ARTIST_TRACKS  -> screenState = ScreenState.LOCAL_ARTISTS
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

    fun loadInitialDataIfReady(forceReload: Boolean = false) {
        val repo = musicRepository ?: return

        // Se non abbiamo un token, non facciamo nulla
        if (spotifyManager.savedWebToken == null) return

        // Se le playlist sono vuote (o forziamo il reload), ricarichiamo
        if (playlists.isEmpty() || forceReload) {
            viewModelScope.launch {
                isLoading = true
                statusText = "Sincronizzazione..."
                val remote = repo.getUserPlaylists()
                playlists = listOf(favoritesItem()) + remote
                isLoading = false
            }
        }

        // Carica le cover degli album in background per il Ken Burns nel menu
        // (solo se non già caricate, senza mostrare loading)
        if (albums.isEmpty() || forceReload) {
            viewModelScope.launch {
                albums = repo.getUserSavedAlbums()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun favoritesItem() = PlaylistItem(
        id = FAVORITES_ID,
        name = "I miei Preferiti ❤️",
        uri = "",
        tracks = PlaylistTracksInfo("", 0),
        images = null
    )

    // ── Volume overlay (mostra barra volume nel player per 2s) ────────────────

    private fun showVolumeOverlay(up: Boolean) {
        val am = getApplication<Application>()
            .getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
        val curVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
        currentVolumePercent = if (maxVol > 0f) curVol / maxVol else 0f
        showVolumeBar = true
        volumeHideJob?.cancel()
        volumeHideJob = viewModelScope.launch {
            delay(2000)
            showVolumeBar = false
        }
    }

    // ── ViewModel.Factory ─────────────────────────────────────────────────────

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            IPodViewModel(app) as T
    }
}
