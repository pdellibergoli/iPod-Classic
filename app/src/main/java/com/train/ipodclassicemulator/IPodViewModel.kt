package com.train.ipodclassicemulator

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Repeat
import com.spotify.protocol.types.Track
import com.train.ipodclassicemulator.data.model.PlaylistItem
import com.train.ipodclassicemulator.data.model.PlaylistTracksInfo
import com.train.ipodclassicemulator.data.model.SearchResults
import com.train.ipodclassicemulator.data.model.SpotifyAlbumDetails
import com.train.ipodclassicemulator.data.model.SpotifyAlbumModelInfo
import com.train.ipodclassicemulator.data.model.SpotifyArtistDetails
import com.train.ipodclassicemulator.data.model.SpotifyArtistInfo
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.data.remote.LyricsRepository
import com.train.ipodclassicemulator.data.remote.LyricsResult
import com.train.ipodclassicemulator.data.remote.SpotifyManager
import com.train.ipodclassicemulator.data.repository.MusicRepository
import com.train.ipodclassicemulator.feedback.FeedbackManager
import com.train.ipodclassicemulator.ui.theme.IPodThemeType
import com.train.ipodclassicemulator.ui.theme.ThemeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "IPodViewModel"
private const val FAVORITES_ID = "favorites_virtual_id"

// ── Progress ticker ─────────────────────────────────────────────────────────
private const val PROGRESS_TICK_MS = 500L    // aggiornamento della barra mentre suona
private const val IDLE_TICK_MS = 1000L       // solo un controllo quando non suona nulla
private const val RESYNC_MS = 5_000L         // riallinea la barra al player reale
private const val END_OF_TRACK_MS = 1_500L   // finestra di "fine brano"
private const val MIN_ADVANCE_MS = 3_000L    // anti doppio avanzamento dei Preferiti
private const val SEEK_STEP_MS = 5_000L      // passo del salto rapido (5 s)
private const val SEEK_GRACE_MS = 1_500L     // niente riallineo subito dopo un salto
private const val RECOVER_WINDOW_MS = 5_000L // anti loop + "l'ultimo avanzamento è recente?"

enum class ScreenState {
    CREDENTIALS_SETUP, MAIN_MENU, SPOTIFY_MENU, PLAYLISTS, ALBUMS, ARTISTS, SEARCH, SEARCH_RESULTS, TRACKS, TRACK_DETAILS, SETTINGS, ABOUT
}

/** Sezione scelta dall'utente nel menu "Risultati". */
enum class SearchResultKind { TRACKS, ARTISTS, ALBUMS }

// ── Impostazioni ─────────────────────────────────────────────────────────────

/** Blocco visivo della schermata Impostazioni (separato da una riga). */
enum class SettingsSection(val title: String) {
    THEMES("Temi"),
    DISPLAY("Schermo"),
    FEEDBACK("Feedback"),
    ACCOUNT("Account"),
    SYSTEM("Sistema")
}

/** Determina cosa fa il tasto Select quando la riga è selezionata. */
enum class SettingsRowKind { THEME, HAPTIC, SOUND, BACKLIGHT, LOGOUT, ABOUT }

/**
 * Riga della schermata Impostazioni.
 *
 * @param value il testo mostrato a destra: il livello di vibrazione/sonoro
 *   corrente. Per i temi è null e si mostra la spunta "✓".
 * @param theme il tema rappresentato dalla riga (solo per `kind == THEME`).
 */
data class SettingsRow(
    val kind: SettingsRowKind,
    val label: String,
    val section: SettingsSection,
    val value: String? = null,
    val theme: IPodThemeType? = null
)

class IPodViewModel(application: Application) : AndroidViewModel(application) {

    val spotifyManager = SpotifyManager(application)

    /** Vibrazione e suono della click wheel, con livello regolabile dalle Impostazioni. */
    val feedback = FeedbackManager(application)

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
    val spotifyMenuOptions = listOf("Playlist", "Album", "Artisti", "Ricerca", "Cronologia", "Coda")

    // ── Impostazioni ──────────────────────────────────────────────────────────

    /**
     * Righe della schermata Impostazioni.
     * È una proprietà **calcolata** e non un `val`: legge `feedback.hapticLevel`
     * e `feedback.soundLevel`, così la lista si ricompone appena cambia il livello.
     */
    val settingsRows: List<SettingsRow> get() = buildList {
        IPodThemeType.values().forEach { theme ->
            add(
                SettingsRow(
                    kind = SettingsRowKind.THEME,
                    label = theme.displayName,
                    section = SettingsSection.THEMES,
                    theme = theme
                )
            )
        }
        add(
            SettingsRow(
                kind = SettingsRowKind.BACKLIGHT,
                label = "Retroilluminazione",
                section = SettingsSection.DISPLAY,
                value = backlightLabel
            )
        )
        add(
            SettingsRow(
                kind = SettingsRowKind.HAPTIC,
                label = "Risposta ruota",
                section = SettingsSection.FEEDBACK,
                value = feedback.hapticLevel.label
            )
        )
        add(
            SettingsRow(
                kind = SettingsRowKind.SOUND,
                label = "Suono ruota",
                section = SettingsSection.FEEDBACK,
                value = feedback.soundLevel.label
            )
        )
        add(
            SettingsRow(
                kind = SettingsRowKind.LOGOUT,
                label = "Disconnetti Spotify",
                section = SettingsSection.ACCOUNT
            )
        )
        add(
            SettingsRow(
                kind = SettingsRowKind.ABOUT,
                label = "Informazioni",
                section = SettingsSection.SYSTEM,
                value = appVersion
            )
        )
    }

    /** true quando "Disconnetti Spotify" è selezionato e si aspetta la seconda pressione. */
    var confirmLogout by mutableStateOf(false)

    // ── Retroilluminazione ────────────────────────────────────────────────────

    /** Ritardi possibili in secondi; 0 = la LCD non si attenua mai. */
    val backlightOptions = listOf(0, 15, 30, 60)

    private val displayPrefs = application.getSharedPreferences(
        "ipod_display_prefs", android.content.Context.MODE_PRIVATE
    )

    /** Secondi di inattività prima che la LCD si attenui (0 = mai). */
    var backlightDelaySec by mutableStateOf(displayPrefs.getInt("backlight_delay_sec", 30))
        private set

    /** true quando la LCD è attenuata per inattività. */
    var isScreenDimmed by mutableStateOf(false)
        private set

    /** Etichetta mostrata a destra della riga "Retroilluminazione". */
    val backlightLabel: String
        get() = if (backlightDelaySec <= 0) "Mai" else "$backlightDelaySec s"

    /** Versione dell'app, mostrata a destra della riga "Informazioni". */
    val appVersion: String = try {
        @Suppress("DEPRECATION")
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        info.versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private var lastInteractionAt = SystemClock.elapsedRealtime()

    /** Ultimo salto rapido: per un attimo la barra non viene riallineata. */
    private var lastSeekAt = 0L

    /** Ultimo ripristino dell'autoplay a fine brano (anti loop). */
    private var lastRecoverAt = 0L

    /** Ultimo avanzamento automatico dei Preferiti (anti doppio avanzamento). */
    private var lastAdvanceAt = 0L

    /**
     * Registra un'attività dell'utente: riaccende la LCD e riavvia il conto alla rovescia.
     */
    fun noteInteraction() {
        lastInteractionAt = SystemClock.elapsedRealtime()
        isScreenDimmed = false
    }

    /** Cicla il ritardo di attenuazione: Mai → 15 s → 30 s → 60 s → Mai. */
    fun cycleBacklight() {
        val position = backlightOptions.indexOf(backlightDelaySec).coerceAtLeast(0)
        backlightDelaySec = backlightOptions[(position + 1) % backlightOptions.size]
        displayPrefs.edit().putInt("backlight_delay_sec", backlightDelaySec).apply()
        noteInteraction()
    }

    /**
     * true quando lo schermo NON deve spegnersi: mentre si leggono le lyrics
     * non c'è interazione con la ruota per minuti.
     */
    val keepAwake: Boolean
        get() = showLyrics

    /**
     * Attenua la LCD dopo il ritardo scelto e la riaccende al primo input.
     */
    private fun startBacklightWatcher() {
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val delaySec = backlightDelaySec
                isScreenDimmed = if (delaySec <= 0 || keepAwake) false
                else SystemClock.elapsedRealtime() - lastInteractionAt >= delaySec * 1_000L
            }
        }
    }

    // ── Data lists ────────────────────────────────────────────────────────────
    var playlists by mutableStateOf<List<PlaylistItem>>(emptyList())
    var albums by mutableStateOf<List<SpotifyAlbumDetails>>(emptyList())
    var artists by mutableStateOf<List<SpotifyArtistDetails>>(emptyList())
    var tracks by mutableStateOf<List<SpotifyTrackDetails>>(emptyList())

    /**
     * URI di contesto con cui riprodurre tracks (playlist/album/artista).
     */
    private var trackListContextUri: String = ""

    /**
     * Titolo della status bar nella lista brani.
     */
    val trackListTitle: String
        get() = when (selectedSpotifyMenuIndex) {
            3 -> if (searchResults != null) "Risultati" else "Brani"
            4 -> "Cronologia"
            5 -> "Coda"
            else -> "Brani"
        }

    /** Breve messaggio mostrato al posto del titolo della lista brani. */
    var listNotice by mutableStateOf<String?>(null)
        private set

    private var noticeJob: Job? = null

    /** Mostra text per 2,5 s; null lo cancella subito. */
    private fun showListNotice(text: String?) {
        noticeJob?.cancel()
        listNotice = text
        if (text != null) {
            noticeJob = viewModelScope.launch {
                delay(2_500)
                listNotice = null
            }
        }
    }

    private var playlistsLoaded = false
    private val playlistsMutex = Mutex()
    private var initialSyncJob: Job? = null
    private var albumsJob: Job? = null

    private var favoritesTracks: List<SpotifyTrackDetails> = emptyList()
    private var favoritesTrackIndex = 0
    private var tracksFromFavorites by mutableStateOf(false)
    private var playingFromFavorites by mutableStateOf(false)

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
    var repeatMode by mutableStateOf(Repeat.OFF)
    var isLocalShuffleEnabled by mutableStateOf(false)

    private var isTrackSavedJob: Job? = null

    // ── Lyrics ────────────────────────────────────────────────────────────────

    // CORREZIONE: Passiamo direttamente 'application' (che è un Context Android)
    private val lyricsRepository = LyricsRepository(application)

    /** true quando al posto delle info di riproduzione vanno mostrate le lyrics. */
    var showLyrics by mutableStateOf(false)

    var lyrics by mutableStateOf<LyricsResult?>(null)
        private set

    private var lyricsTrackId: String? = null
    private var lyricsJob: Job? = null

    // ── Search ────────────────────────────────────────────────────────────────
    val keyboardChars = listOf(
        "_", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "🔍 OK"
    )
    var selectedCharIndex by mutableStateOf(1)
    var searchQuery by mutableStateOf("")

    // ── Risultati di ricerca ──────────────────────────────────────────────────
    var searchResults by mutableStateOf<SearchResults?>(null)
        private set

    private var searchListsActive by mutableStateOf(false)
    val viewingSearchResults: Boolean get() = searchListsActive
    var selectedSearchResultIndex by mutableStateOf(0)

    val searchResultRows: List<Pair<SearchResultKind, String>>
        get() = searchResults?.let { r ->
            buildList {
                if (r.tracks.isNotEmpty()) add(SearchResultKind.TRACKS to "Brani (${r.tracks.size})")
                if (r.artists.isNotEmpty()) add(SearchResultKind.ARTISTS to "Artisti (${r.artists.size})")
                if (r.albums.isNotEmpty()) add(SearchResultKind.ALBUMS to "Album (${r.albums.size})")
            }
        } ?: emptyList()

    val displayAlbums: List<SpotifyAlbumDetails>
        get() = if (searchListsActive) searchResults?.albums.orEmpty() else albums

    val displayArtists: List<SpotifyArtistDetails>
        get() = if (searchListsActive) searchResults?.artists.orEmpty() else artists

    // ── UI feedback ───────────────────────────────────────────────────────────
    var isLoading by mutableStateOf(false)
    var statusText by mutableStateOf("In attesa di Spotify...")
    var showLoginButton by mutableStateOf(false)

    // ── Battery ───────────────────────────────────────────────────────────────
    var batteryPercentage by mutableStateOf(100)
    var isBatteryCharging by mutableStateOf(false)

    // ── Volume ────────────────────────────────────────────────────────────────
    var showVolumeBar by mutableStateOf(false)
    var currentVolumePercent by mutableStateOf(0f)
    private var volumeHideJob: Job? = null

    // ── Token refresh trigger ─────────────────────────────────────────────────
    var tokenRefreshTick by mutableStateOf(false)
    var currentCodeVerifier: String? = null

    init {
        Log.d(TAG, "Client ID letto: '${spotifyManager.spotifyClientId}'")

        if (spotifyManager.spotifyClientId.isNotBlank() && spotifyManager.spotifyClientId != "null") {
            initializeSpotifyServices()
            screenState = if (spotifyManager.hasStoredSession) ScreenState.MAIN_MENU
            else ScreenState.CREDENTIALS_SETUP
        } else {
            screenState = ScreenState.CREDENTIALS_SETUP
        }
        startProgressTicker()
        startBacklightWatcher()
    }

    // ── Init / auth ───────────────────────────────────────────────────────────

    fun initializeSpotifyServices() {
        musicRepository = MusicRepository(spotifyManager)
        musicRepository?.onTokenExpired = {
            Log.d(TAG, "Sessione Spotify non recuperabile. Mostro la schermata di login.")
            if (screenState != ScreenState.CREDENTIALS_SETUP) {
                statusText = "Sessione scaduta. Accedi di nuovo a Spotify."
                showLoginButton = true
                screenState = ScreenState.CREDENTIALS_SETUP
            }
        }

        when {
            spotifyManager.hasStoredSession -> {
                viewModelScope.launch {
                    musicRepository?.ensureFreshToken()
                    try {
                        spotifyManager.connect {
                            Log.d(TAG, "App Remote connesso con sessione esistente")
                            restoreNowPlayingFromPlayerState()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Errore auto-connessione", e)
                    }
                }
            }
            spotifyManager.pendingAuthCode != null -> {
                Log.w(TAG, "initializeSpotifyServices: pendingAuthCode orfano, lo cancello")
                spotifyManager.pendingAuthCode = null
                showLoginButton = true
            }
            else -> {
                showLoginButton = true
                if (!spotifyManager.autoLoginAttempted) {
                    spotifyManager.autoLoginAttempted = true
                    spotifyManager.requestToken(getApplication())
                }
            }
        }

        setupPlayerStateListener()
    }

    fun handleAuthCode(authCode: String) {
        spotifyManager.pendingAuthCode = authCode
        showLoginButton = false

        if (musicRepository == null) {
            musicRepository = MusicRepository(spotifyManager)
        }

        viewModelScope.launch {
            val verifier = spotifyManager.codeVerifier ?: run {
                Log.e(TAG, "handleAuthCode: codeVerifier nullo — impossibile scambiare il codice")
                showLoginButton = true
                screenState = ScreenState.CREDENTIALS_SETUP
                return@launch
            }
            val success = musicRepository?.fetchWebToken(authCode, verifier) ?: false
            spotifyManager.pendingAuthCode = null
            if (success) {
                Log.d(TAG, "Token ottenuto con successo")
                tokenRefreshTick = !tokenRefreshTick
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

    fun onLoginButtonClicked() {
        spotifyManager.requestToken(getApplication())
    }

    fun disconnectSpotify() {
        Log.d(TAG, "Disconnessione da Spotify richiesta dall'utente")
        confirmLogout = false

        spotifyManager.disconnect()
        spotifyManager.clearAllTokens()

        playlists = emptyList()
        playlistsLoaded = false
        showLyrics = false
        lyrics = null
        lyricsTrackId = null
        lyricsJob?.cancel()
        albums = emptyList()
        artists = emptyList()
        tracks = emptyList()
        trackListContextUri = ""
        favoritesTracks = emptyList()
        favoritesTrackIndex = 0
        tracksFromFavorites = false
        playingFromFavorites = false
        playingTrackDetails = null
        currentCoverUrl = null
        currentProgressMs = 0L
        trackDurationMs = 0L
        isTrackPlaying = false
        isCurrentTrackLiked = false
        currentPlaybackMode = 0
        repeatMode = Repeat.OFF
        isLocalShuffleEnabled = false
        showVolumeBar = false
        selectedPlaylistIndex = 0
        selectedAlbumIndex = 0
        selectedArtistIndex = 0
        selectedTrackIndex = 0
        selectedSpotifyMenuIndex = 0
        searchResults = null
        searchListsActive = false
        selectedSearchResultIndex = 0
        cancelAddToPlaylist()
        showListNotice(null)

        initializeSpotifyServices()

        statusText = "Disconnesso da Spotify."
        showLoginButton = true
        screenState = ScreenState.CREDENTIALS_SETUP
    }

    // ── Player state listener ─────────────────────────────────────────────────

    private fun setupPlayerStateListener() {
        spotifyManager.onPlayerStateChanged = { playerState: PlayerState ->
            val track: Track? = playerState.track
            if (track != null) {
                val cleanedId = track.uri.substringAfter("track:")

                val previousId = playingTrackDetails?.id
                val now = SystemClock.elapsedRealtime()
                val autoAdvanced = playingFromFavorites &&
                        previousId != null && previousId != cleanedId &&
                        favoritesTracks.any { it.id == previousId } &&
                        !favoritesTracks.any { it.id == cleanedId } &&
                        now - lastRecoverAt >= RECOVER_WINDOW_MS

                trackDurationMs = track.duration
                currentProgressMs = playerState.playbackPosition
                isTrackPlaying = !playerState.isPaused

                val isShuffle = playerState.playbackOptions.isShuffling
                if (playingFromFavorites) {
                    currentPlaybackMode = if (isLocalShuffleEnabled) 1 else 0
                    spotifyManager.isShuffling = isLocalShuffleEnabled
                } else {
                    currentPlaybackMode = if (isShuffle) 1 else 0
                    spotifyManager.isShuffling = isShuffle
                    isLocalShuffleEnabled = isShuffle
                }

                repeatMode = playerState.playbackOptions.repeatMode

                track.imageUri?.raw?.let { raw ->
                    currentCoverUrl = "https://i.scdn.co/image/${raw.substringAfter("image:")}"
                }

                playingTrackDetails = SpotifyTrackDetails(
                    id = cleanedId,
                    name = track.name,
                    uri = track.uri,
                    artists = listOf(SpotifyArtistInfo(name = track.artist.name ?: "Unknown Artist")),
                    album = SpotifyAlbumModelInfo(id = "", name = track.album.name, uri = "")
                )

                if (autoAdvanced) {
                    lastRecoverAt = now
                    recoverFavoritesAutoAdvance(previousId)
                }

                if (showLyrics) loadLyrics()

                isTrackSavedJob?.cancel()
                isTrackSavedJob = viewModelScope.launch {
                    val likedId = playingTrackDetails?.id ?: cleanedId
                    isCurrentTrackLiked = musicRepository?.isTrackSaved(likedId) ?: false
                }
            }
        }
    }

    private fun restoreNowPlayingFromPlayerState() {
        spotifyManager.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { state ->
            val track = state?.track ?: return@setResultCallback
            if (playingTrackDetails != null) return@setResultCallback

            trackDurationMs = track.duration
            currentProgressMs = state.playbackPosition
            isTrackPlaying = !state.isPaused
            repeatMode = state.playbackOptions.repeatMode

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
            if (showLyrics) loadLyrics()
        }
    }

    // ── Progress ticker ───────────────────────────────────────────────────────

    private fun startProgressTicker() {
        viewModelScope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            var lastSyncAt = 0L

            while (true) {
                if (!isTrackPlaying) {
                    delay(IDLE_TICK_MS)
                    lastTick = SystemClock.elapsedRealtime()
                    lastSyncAt = 0L
                    continue
                }

                delay(PROGRESS_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                // CORREZIONE: reinserito l'operatore di moltiplicazione mancante (*)
                val elapsed = (now - lastTick).coerceIn(0L, PROGRESS_TICK_MS * 2)
                lastTick = now

                if (now - lastSyncAt >= RESYNC_MS) {
                    lastSyncAt = now
                    syncProgressFromPlayer()
                }

                if (trackDurationMs <= 0L) continue

                currentProgressMs = (currentProgressMs + elapsed).coerceAtMost(trackDurationMs)

                // CORREZIONE: reinserito l'operatore di sottrazione mancante (-)
                val nearEnd = currentProgressMs >= trackDurationMs - END_OF_TRACK_MS
                if (nearEnd && playingFromFavorites &&
                    favoritesTracks.isNotEmpty() &&
                    now - lastAdvanceAt >= MIN_ADVANCE_MS
                ) {
                    advanceFavoritesTrack()
                    lastTick = SystemClock.elapsedRealtime()
                    lastSyncAt = 0L
                }
            }
        }
    }

    private fun syncProgressFromPlayer() {
        if (SystemClock.elapsedRealtime() - lastSeekAt < SEEK_GRACE_MS) return

        spotifyManager.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { state ->
            val track = state.track ?: return@setResultCallback
            if (state.isPaused) return@setResultCallback

            val stateId = track.uri.substringAfter("track:")
            val shownId = playingTrackDetails?.id
            if (shownId != null && shownId != stateId) return@setResultCallback

            trackDurationMs = track.duration
            currentProgressMs = state.playbackPosition
        }
    }

    private fun advanceFavoritesTrack() {
        val list = favoritesTracks
        if (list.isEmpty()) return

        val fromName = playingTrackDetails?.name ?: "?"
        val fromIndex = list.indexOfFirst { it.id == playingTrackDetails?.id }
        if (SystemClock.elapsedRealtime() - lastAdvanceAt >= RECOVER_WINDOW_MS && fromIndex >= 0) {
            favoritesTrackIndex = fromIndex
        }

        favoritesTrackIndex = if (isLocalShuffleEnabled) {
            list.indices.random()
        } else {
            (favoritesTrackIndex + 1).coerceAtMost(list.lastIndex)
        }
        val next = list[favoritesTrackIndex]
        lastAdvanceAt = SystemClock.elapsedRealtime()
        currentProgressMs = 0L
        playingTrackDetails = next
        Log.d(TAG, "Fine brano: da [$fromIndex] \"$fromName\" a [$favoritesTrackIndex] \"${next.name}\"")
        if (showLyrics) loadLyrics()
        syncFavoritesSelection()
        musicRepository?.play(next.uri, "")
    }

    private fun recoverFavoritesAutoAdvance(endedId: String?) {
        val list = favoritesTracks
        if (list.isEmpty() || endedId == null) return

        if (SystemClock.elapsedRealtime() - lastAdvanceAt < RECOVER_WINDOW_MS) {
            val chosen = list.getOrNull(favoritesTrackIndex) ?: return
            Log.w(TAG, "Autoplay intercettato: ripristino \"${chosen.name}\" (indice $favoritesTrackIndex)")
            playFavoritesTrack(chosen)
            return
        }

        val endedIndex = list.indexOfFirst { it.id == endedId }
        if (endedIndex < 0) return
        Log.w(TAG, "Autoplay intercettato: Spotify toglie \"${list[endedIndex].name}\" " +
                "(indice $endedIndex) → avanzo al successivo")
        favoritesTrackIndex = endedIndex
        advanceFavoritesTrack()
    }

    private fun syncFavoritesSelection() {
        if (screenState == ScreenState.TRACKS &&
            selectedSpotifyMenuIndex == 0 &&
            playlists.getOrNull(selectedPlaylistIndex)?.id == FAVORITES_ID
        ) {
            selectedTrackIndex = favoritesTrackIndex
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private suspend fun syncPlaylists(force: Boolean = false): Boolean {
        if (playlistsLoaded && !force) return true

        return playlistsMutex.withLock {
            if (playlistsLoaded && !force) return@withLock true
            val repo = musicRepository ?: return@withLock false

            var attempt = 0
            var ok = false
            while (!ok && attempt < 2) {
                attempt++
                isLoading = true
                statusText = "Scarico Playlist..."
                val remote = repo.getUserPlaylists()
                if (remote != null) {
                    playlists = listOf(favoritesItem()) + remote
                    playlistsLoaded = true
                    ok = true
                } else {
                    Log.w(TAG, "Sync playlist fallita (tentativo $attempt/2)")
                }
            }
            isLoading = false
            ok || playlistsLoaded
        }
    }

    fun loadPlaylistsIfNeeded() {
        val from = screenState
        if (playlistsLoaded) { screenState = ScreenState.PLAYLISTS; return }
        viewModelScope.launch {
            syncPlaylists()
            if (screenState == from) screenState = ScreenState.PLAYLISTS
        }
    }

    fun loadAlbumsIfNeeded() {
        searchListsActive = false
        if (albums.isNotEmpty()) { screenState = ScreenState.ALBUMS; return }
        viewModelScope.launch {
            isLoading = true; statusText = "Scarico Album..."
            albums = musicRepository?.getUserSavedAlbums() ?: emptyList()
            selectedAlbumIndex = 0; screenState = ScreenState.ALBUMS; isLoading = false
        }
    }

    fun loadArtistsIfNeeded() {
        searchListsActive = false
        if (artists.isNotEmpty()) { screenState = ScreenState.ARTISTS; return }
        viewModelScope.launch {
            isLoading = true; statusText = "Scarico Artisti..."
            artists = musicRepository?.getUserFollowedArtists() ?: emptyList()
            selectedArtistIndex = 0; screenState = ScreenState.ARTISTS; isLoading = false
        }
    }

    fun loadTracksForPlaylist() {
        if (addingToPlaylist) { commitAddToPlaylist(); return }

        val playlist = playlists.getOrNull(selectedPlaylistIndex) ?: return
        val isFavorites = playlist.id == FAVORITES_ID
        viewModelScope.launch {
            isLoading = true; statusText = "Carico i brani..."
            val list = if (isFavorites) {
                musicRepository?.getSavedTracks() ?: emptyList()
            } else {
                musicRepository?.getTracksForPlaylist(playlist.id) ?: emptyList()
            }
            tracks = list
            tracksFromFavorites = isFavorites
            trackListContextUri = if (isFavorites) "" else playlist.uri
            showListNotice(null)
            if (isFavorites) {
                favoritesTracks = list
                favoritesTrackIndex = list.indexOfFirst { it.id == playingTrackDetails?.id }
                    .coerceAtLeast(0)
                selectedTrackIndex = favoritesTrackIndex
            } else {
                selectedTrackIndex = 0
            }
            screenState = ScreenState.TRACKS
            isLoading = false
        }
    }

    // ── Aggiungi a playlist ───────────────────────────────────────────────────

    var addingToPlaylist by mutableStateOf(false)
        private set

    private var pendingPlaylistTrack: SpotifyTrackDetails? = null

    fun startAddToPlaylist() {
        if (screenState != ScreenState.TRACKS) return
        val track = tracks.getOrNull(selectedTrackIndex) ?: return
        pendingPlaylistTrack = track
        addingToPlaylist = true
        selectedPlaylistIndex = 0
        showListNotice(null)
        loadPlaylistsIfNeeded()
    }

    private fun cancelAddToPlaylist() {
        pendingPlaylistTrack = null
        addingToPlaylist = false
    }

    private fun commitAddToPlaylist() {
        val from = screenState
        val track = pendingPlaylistTrack
        val playlist = playlists.getOrNull(selectedPlaylistIndex)
        cancelAddToPlaylist()
        if (playlist == null) {
            showListNotice("Playlist non disponibili")
            screenState = ScreenState.TRACKS
            return
        }
        if (track == null) {
            screenState = ScreenState.TRACKS
            return
        }

        if (playlist.id == FAVORITES_ID) {
            viewModelScope.launch {
                val ok = musicRepository?.saveSpotifyTrack(track.id) == true
                if (ok && playingTrackDetails?.id == track.id) isCurrentTrackLiked = true
                if (screenState != from) return@launch
                showListNotice(if (ok) "Salvato nei Preferiti" else "Salvataggio non riuscito")
                screenState = ScreenState.TRACKS
            }
            return
        }

        viewModelScope.launch {
            isLoading = true; statusText = "Aggiungo il brano..."
            val outcome = musicRepository?.addTrackToPlaylist(playlist.id, track.uri)
            isLoading = false
            if (screenState != from) return@launch
            showListNotice(
                when (outcome) {
                    true -> "Aggiunto a \"${playlist.name}\""
                    null -> "Permessi mancanti: rifai il login"
                    else -> "Aggiunta non riuscita"
                }
            )
            screenState = ScreenState.TRACKS
        }
    }

    fun onSelectLongClick() {
        startAddToPlaylist()
    }

    fun loadTracksForAlbum() {
        val album = displayAlbums.getOrNull(selectedAlbumIndex) ?: return
        viewModelScope.launch {
            isLoading = true; statusText = "Carico brani album..."
            tracks = musicRepository?.getTracksForAlbum(album.id, album.images) ?: emptyList()
            tracksFromFavorites = false
            trackListContextUri = album.uri
            showListNotice(null)
            selectedTrackIndex = 0; screenState = ScreenState.TRACKS; isLoading = false
        }
    }

    fun loadTracksForArtist() {
        val artist = displayArtists.getOrNull(selectedArtistIndex) ?: return
        viewModelScope.launch {
            isLoading = true; statusText = "Carico brani artista..."
            tracks = musicRepository?.getTracksForArtist(artist.id) ?: emptyList()
            tracksFromFavorites = false
            trackListContextUri = artist.uri
            showListNotice(null)
            selectedTrackIndex = 0; screenState = ScreenState.TRACKS; isLoading = false
        }
    }

    fun searchTracks() {
        if (searchQuery.isBlank()) return
        val from = screenState
        viewModelScope.launch {
            isLoading = true; statusText = "Ricerca in corso..."
            val results = musicRepository?.searchSpotify(searchQuery)
            isLoading = false
            if (screenState != from) return@launch
            searchResults = results
            searchListsActive = false
            selectedSearchResultIndex = 0
            showListNotice(null)
            screenState = ScreenState.SEARCH_RESULTS
        }
    }

    fun openSearchResult(kind: SearchResultKind) {
        when (kind) {
            SearchResultKind.TRACKS -> {
                tracks = searchResults?.tracks.orEmpty()
                tracksFromFavorites = false
                trackListContextUri = ""
                selectedTrackIndex = 0
                showListNotice(null)
                screenState = ScreenState.TRACKS
            }

            SearchResultKind.ARTISTS -> {
                searchListsActive = true
                selectedArtistIndex = 0
                screenState = ScreenState.ARTISTS
            }

            SearchResultKind.ALBUMS -> {
                searchListsActive = true
                selectedAlbumIndex = 0
                screenState = ScreenState.ALBUMS
            }
        }
    }

    // ── Cronologia e Coda ─────────────────────────────────────────────────────

    fun loadChronology() = loadRemoteTrackList(
        status = "Scarico la cronologia...",
        emptyNotice = "Nessun brano in cronologia",
        fetch = { musicRepository?.getRecentlyPlayed() }
    )

    fun loadQueue() = loadRemoteTrackList(
        status = "Leggo la coda...",
        emptyNotice = "La coda è vuota",
        fetch = { musicRepository?.getPlaybackQueue() }
    )

    private fun loadRemoteTrackList(
        status: String,
        emptyNotice: String,
        fetch: suspend () -> List<SpotifyTrackDetails>?
    ) {
        val from = screenState
        viewModelScope.launch {
            isLoading = true; statusText = status
            val list = fetch()
            isLoading = false
            if (screenState != from) return@launch

            tracks = list.orEmpty()
            tracksFromFavorites = false
            trackListContextUri = ""
            selectedTrackIndex = 0
            showListNotice(
                when {
                    list == null -> "Permessi mancanti: rifai il login"
                    list.isEmpty() -> emptyNotice
                    else -> null
                }
            )
            screenState = ScreenState.TRACKS
        }
    }

    // ── Playback actions ──────────────────────────────────────────────────────

    fun playSelectedTrack() {
        val track = tracks.getOrNull(selectedTrackIndex) ?: return
        val isFavorites = tracksFromFavorites

        val contextUri: String =
            if (isFavorites) ""
            else trackListContextUri

        if (isFavorites) favoritesTrackIndex = selectedTrackIndex
        playingFromFavorites = isFavorites
        currentProgressMs = 0L
        playingTrackDetails = track
        showLyrics = false
        screenState = ScreenState.TRACK_DETAILS
        musicRepository?.play(track.uri, contextUri, selectedTrackIndex)
        if (isFavorites) spotifyManager.setShuffle(isLocalShuffleEnabled)
    }

    fun togglePlayPause() {
        if (isTrackPlaying) {
            spotifyManager.pausePlayback(); isTrackPlaying = false
        } else {
            spotifyManager.resumePlayback(); isTrackPlaying = true
        }
    }

    fun toggleLyrics() {
        if (screenState != ScreenState.TRACK_DETAILS) return
        showLyrics = !showLyrics
        if (showLyrics) loadLyrics()
    }

    private fun loadLyrics() {
        val track = playingTrackDetails
        if (track == null) {
            lyrics = LyricsResult.None
            return
        }
        if (track.id == lyricsTrackId && (lyrics != null || lyricsJob?.isActive == true)) return

        lyricsTrackId = track.id
        lyrics = null
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            lyrics = lyricsRepository.getLyrics(
                title = track.name,
                artist = track.artists.firstOrNull()?.name ?: "",
                album = track.album?.name,
                durationMs = trackDurationMs
            )
        }
    }

    fun skipNext() {
        if (playingFromFavorites) {
            val list = favoritesTracks
            if (list.isEmpty()) return
            Log.d(TAG, "skipNext Preferiti: shuffle=$isLocalShuffleEnabled")
            favoritesTrackIndex = if (isLocalShuffleEnabled) list.indices.random()
            else (favoritesTrackIndex + 1).coerceAtMost(list.lastIndex)
            syncFavoritesSelection()
            playFavoritesTrack(list[favoritesTrackIndex])
        } else {
            spotifyManager.skipNext()
            if (screenState == ScreenState.TRACKS && selectedTrackIndex < tracks.lastIndex) selectedTrackIndex++
        }
    }

    fun skipPrevious() {
        if (playingFromFavorites) {
            val list = favoritesTracks
            if (list.isEmpty()) return
            // CORREZIONE: reinserito l'operatore di sottrazione mancante (-)
            favoritesTrackIndex = if (isLocalShuffleEnabled) list.indices.random()
            else (favoritesTrackIndex - 1).coerceAtLeast(0)
            syncFavoritesSelection()
            playFavoritesTrack(list[favoritesTrackIndex])
        } else {
            spotifyManager.skipPrevious()
            if (screenState == ScreenState.TRACKS && selectedTrackIndex > 0) selectedTrackIndex--
        }
    }

    fun seekForward()  = seekBy(+SEEK_STEP_MS)
    fun seekBackward() = seekBy(-SEEK_STEP_MS)

    private fun seekBy(deltaMs: Long) {
        if (playingTrackDetails == null) return
        noteInteraction()

        val from = currentProgressMs
        val target = if (trackDurationMs > 0L)
            (from + deltaMs).coerceIn(0L, trackDurationMs)
        else
            (from + deltaMs).coerceAtLeast(0L)
        if (target == from) return

        currentProgressMs = target
        lastSeekAt = SystemClock.elapsedRealtime()
        spotifyManager.seekRelative(deltaMs)
    }

    private fun playFavoritesTrack(track: SpotifyTrackDetails) {
        currentProgressMs = 0L
        playingTrackDetails = track
        if (showLyrics) loadLyrics()
        musicRepository?.play(track.uri, "")
        if (!isLocalShuffleEnabled) spotifyManager.setShuffle(false)
    }

    fun shuffleAll() {
        if (tracks.isEmpty()) return
        val i = tracks.indices.random()
        selectedTrackIndex = i
        playingFromFavorites = tracksFromFavorites
        if (tracksFromFavorites) favoritesTrackIndex = i
        playingTrackDetails = tracks[i]
        currentProgressMs = 0L
        showLyrics = false
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

    fun toggleRepeat() {
        val next = when (repeatMode) {
            Repeat.OFF -> Repeat.ALL
            Repeat.ALL -> Repeat.ONE
            else -> Repeat.OFF
        }
        repeatMode = next
        spotifyManager.setRepeatMode(next)
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
            ScreenState.ALBUMS -> if (displayAlbums.isNotEmpty() && selectedAlbumIndex < displayAlbums.lastIndex) selectedAlbumIndex++
            ScreenState.ARTISTS -> if (displayArtists.isNotEmpty() && selectedArtistIndex < displayArtists.lastIndex) selectedArtistIndex++
            ScreenState.SEARCH_RESULTS -> if (searchResultRows.isNotEmpty() && selectedSearchResultIndex < searchResultRows.lastIndex) selectedSearchResultIndex++
            ScreenState.TRACKS -> if (tracks.isNotEmpty() && selectedTrackIndex < tracks.lastIndex) selectedTrackIndex++
            ScreenState.SETTINGS -> {
                if (selectedSettingsIndex < settingsRows.lastIndex) {
                    selectedSettingsIndex++
                    confirmLogout = false
                }
            }
            ScreenState.TRACK_DETAILS -> { spotifyManager.adjustVolume(up = true); showVolumeOverlay() }
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
            ScreenState.SEARCH_RESULTS -> if (selectedSearchResultIndex > 0) selectedSearchResultIndex--
            ScreenState.TRACKS -> if (selectedTrackIndex > 0) selectedTrackIndex--
            ScreenState.SETTINGS -> {
                if (selectedSettingsIndex > 0) {
                    selectedSettingsIndex--
                    confirmLogout = false
                }
            }
            ScreenState.TRACK_DETAILS -> { spotifyManager.adjustVolume(up = false); showVolumeOverlay() }
            // CORREZIONE: reinserito l'operatore di sottrazione mancante (-)
            ScreenState.SEARCH -> selectedCharIndex = (selectedCharIndex - 1 + keyboardChars.size) % keyboardChars.size
            else -> Unit
        }
    }

    // ── Click wheel: select ───────────────────────────────────────────────────

    fun onSelectClick(themeManager: ThemeManager) {
        when (screenState) {
            ScreenState.MAIN_MENU -> when (selectedMainMenuIndex) {
                0 -> screenState = ScreenState.SPOTIFY_MENU
                1 -> { confirmLogout = false; screenState = ScreenState.SETTINGS }
                2 -> shuffleAll()
                3 -> if (playingTrackDetails != null) {
                    showLyrics = false
                    screenState = ScreenState.TRACK_DETAILS
                }
            }
            ScreenState.SPOTIFY_MENU -> when (selectedSpotifyMenuIndex) {
                0 -> loadPlaylistsIfNeeded()
                1 -> loadAlbumsIfNeeded()
                2 -> loadArtistsIfNeeded()
                3 -> { searchQuery = ""; screenState = ScreenState.SEARCH }
                4 -> loadChronology()
                5 -> loadQueue()
            }
            ScreenState.SEARCH -> handleSearchInput()
            ScreenState.SEARCH_RESULTS ->
                searchResultRows.getOrNull(selectedSearchResultIndex)
                    ?.first
                    ?.let { openSearchResult(it) }
            ScreenState.ALBUMS -> loadTracksForAlbum()
            ScreenState.ARTISTS -> loadTracksForArtist()
            ScreenState.PLAYLISTS -> loadTracksForPlaylist()
            ScreenState.TRACKS -> playSelectedTrack()
            ScreenState.TRACK_DETAILS -> toggleLyrics()
            ScreenState.SETTINGS -> when (settingsRows.getOrNull(selectedSettingsIndex)?.kind) {
                SettingsRowKind.THEME ->
                    settingsRows[selectedSettingsIndex].theme?.let { themeManager.setTheme(it) }

                SettingsRowKind.HAPTIC -> {
                    feedback.hapticLevel = feedback.hapticLevel.next
                    feedback.press()
                }
                SettingsRowKind.SOUND -> {
                    feedback.soundLevel = feedback.soundLevel.next
                    feedback.click()
                }

                SettingsRowKind.BACKLIGHT -> cycleBacklight()
                SettingsRowKind.ABOUT -> screenState = ScreenState.ABOUT
                SettingsRowKind.LOGOUT -> if (!confirmLogout) confirmLogout = true else disconnectSpotify()

                null -> Unit
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
                showLyrics = false
                screenState = ScreenState.TRACKS
            }
            ScreenState.TRACKS -> {
                cancelAddToPlaylist()
                screenState = when (selectedSpotifyMenuIndex) {
                    1 -> ScreenState.ALBUMS
                    2 -> ScreenState.ARTISTS
                    3 -> if (searchResults != null) ScreenState.SEARCH_RESULTS
                    else ScreenState.SEARCH
                    4, 5 -> ScreenState.SPOTIFY_MENU
                    else -> ScreenState.PLAYLISTS
                }
            }
            ScreenState.SEARCH_RESULTS -> screenState = ScreenState.SEARCH
            ScreenState.ALBUMS, ScreenState.ARTISTS ->
                screenState = if (searchListsActive) ScreenState.SEARCH_RESULTS
                else ScreenState.SPOTIFY_MENU
            ScreenState.PLAYLISTS -> if (addingToPlaylist) {
                cancelAddToPlaylist()
                screenState = ScreenState.TRACKS
            } else {
                screenState = ScreenState.SPOTIFY_MENU
            }
            ScreenState.SEARCH -> screenState = ScreenState.SPOTIFY_MENU
            ScreenState.ABOUT -> screenState = ScreenState.SETTINGS
            ScreenState.SPOTIFY_MENU, ScreenState.SETTINGS -> {
                confirmLogout = false
                screenState = ScreenState.MAIN_MENU
            }
            else -> Unit
        }
    }

    fun onMenuLongClick() {
        if (screenState != ScreenState.MAIN_MENU && screenState != ScreenState.CREDENTIALS_SETUP) {
            cancelAddToPlaylist()
            previousScreen = screenState
            screenState = ScreenState.MAIN_MENU
            selectedMainMenuIndex = 0
        }
    }

    // ── Startup data load ─────────────────────────────────────────────────────

    fun loadInitialDataIfReady(forceReload: Boolean = false) {
        val repo = musicRepository ?: return

        if (!spotifyManager.hasStoredSession) return

        val needPlaylists = forceReload || !playlistsLoaded
        if (needPlaylists && (forceReload || initialSyncJob?.isActive != true)) {
            initialSyncJob = viewModelScope.launch { syncPlaylists(forceReload) }
        }

        val needAlbums = forceReload || albums.isEmpty()
        if (needAlbums && (forceReload || albumsJob?.isActive != true)) {
            albumsJob = viewModelScope.launch {
                val list = repo.getUserSavedAlbums()
                if (list.isNotEmpty() || albums.isEmpty()) albums = list
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

    // ── Volume overlay ────────────────────────────────────────────────────────

    private fun showVolumeOverlay() {
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

    // ── Ciclo di vita ─────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        feedback.release()
    }

    // ── ViewModel.Factory ─────────────────────────────────────────────────────

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            IPodViewModel(app) as T
    }
}