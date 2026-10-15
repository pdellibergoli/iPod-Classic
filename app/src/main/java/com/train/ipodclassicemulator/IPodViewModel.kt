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
     *
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

    /**
     * Registra un'attività dell'utente: riaccende la LCD e riavvia il conto alla
     * rovescia. Viene chiamato da ogni tick della ruota e da ogni pressione
     * di tasto, quindi basta un solo punto d'aggancio.
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
     * non c'è interazione con la ruota per minuti, e farle spegnere le rende
     * inutilizzabili. Vale sia per la LCD dell'iPod sia per la schermata del
     * telefono (vedi [keepScreenOn]).
     */
    val keepAwake: Boolean
        get() = showLyrics

    /**
     * Attenua la LCD dopo il ritardo scelto e la riaccende al primo input.
     *
     * Un controllo al secondo è sufficiente (e costa una scrittura di stato che,
     * se il valore non cambia, non provoca nessuna ricomposizione).
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
     * URI di contesto con cui riprodurre [tracks] (playlist/album/artista).
     *
     * Va letto da chi HA PRODOTTO la lista, non dalla voce di menu evidenziata:
     * un album trovato con la ricerca apre la lista con il menu su "Ricerca",
     * ma va suonato come album. Resta valido finché non se ne produce un'altra,
     * quindi le schermate che rientrano nella lista (MENU, "aggiungi a") lo
     * lasciano invariato.
     */
    private var trackListContextUri: String = ""

    /**
     * Titolo della status bar nella lista brani.
     *
     * Dipende da quale voce del menu Spotify l'ha prodotta, che è esattamente
     * quella a cui torna il MENU: così il titolo e la "freccia indietro"
     * raccontano la stessa cosa.
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

    /** Mostra `text` per 2,5 s; `null` lo cancella subito. */
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

    /**
     * true **solo** quando l'elenco playlist è stato scaricato con successo.
     *
     * Non basta controllare `playlists.isNotEmpty()`: la lista contiene sempre
     * la voce sentinel dei Preferiti, quindi un caricamento fallito veniva
     * scambiato per "tutto ok" e non veniva più mai riprovato.
     */
    private var playlistsLoaded = false

    /** Serializza le richieste playlist: una sola richiesta alla volta. */
    private val playlistsMutex = Mutex()

    /** Job della sincronizzazione iniziale playlist, per non lanciarla 2-3 volte. */
    private var initialSyncJob: Job? = null

    /** Job del caricamento album in background, per non duplicarlo. */
    private var albumsJob: Job? = null

    /**
     * Snapshot della playlist "Preferiti" (virtuale) con il relativo indice.
     * `tracks` viene sovrascritto quando l'utente apre album/artisti/ricerca,
     * quindi per l'avanzamento automatico non possiamo affidarci a quella lista.
     */
    private var favoritesTracks: List<SpotifyTrackDetails> = emptyList()
    private var favoritesTrackIndex = 0

    /** true se l'ultima lista caricata in `tracks` era quella dei Preferiti. */
    private var tracksFromFavorites by mutableStateOf(false)

    /**
     * true se ciò che sta suonando proviene dai Preferiti. Va deciso AL MOMENTO
     * in cui si avvia la riproduzione, non dalla selezione corrente della lista
     * (che l'utente può cambiare mentre ascolta).
     */
    private var playingFromFavorites by mutableStateOf(false)

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
    /**
     * Modalità di ripetizione corrente, nei valori di `Repeat` di Spotify:
     * 0 = OFF, 1 = ONE, 2 = ALL. Viene letta dal player, non indovinata.
     */
    var repeatMode by mutableStateOf(Repeat.OFF)
    // Workaround for PlayerState bug in Favorites playlist
    var isLocalShuffleEnabled by mutableStateOf(false)

    // Fix #11 — job per isTrackSaved: cancellato se arriva una nuova traccia prima del completamento
    private var isTrackSavedJob: Job? = null

    // ── Lyrics ────────────────────────────────────────────────────────────────

    private val lyricsRepository = LyricsRepository(
        // cacheDir del Context: sopravvive ai riavvii finché il sistema non la purga
        java.io.File(application.cacheDir, "lyrics")
    )

    /** true quando al posto delle info di riproduzione vanno mostrate le lyrics. */
    var showLyrics by mutableStateOf(false)

    /**
     * Lyrics della traccia corrente. `null` = ancora in caricamento.
     *
     * È una sola variabile perché [LyricsResult] già descrive tutti gli esiti
     * (sincronizzate / solo testo / non trovate).
     */
    var lyrics by mutableStateOf<LyricsResult?>(null)
        private set

    /** id della traccia a cui si riferiscono le lyrics attualmente in memoria. */
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

    /** Esito dell'ultima ricerca; `null` finché non si cerca. */
    var searchResults by mutableStateOf<SearchResults?>(null)
        private set

    /**
     * true quando le schermate Album/Artisti stanno mostrando risultati di
     * ricerca e non le liste "vere" (salvate/seguite).
     *
     * Serve a non sovrascrivere le liste salvate con i risultati: quando si
     * rientra dal menu le liste vanno ricaricate da capo.
     */
    private var searchListsActive by mutableStateOf(false)

    /** Lo stanno leggendo le schermate, per cambiare le etichette. */
    val viewingSearchResults: Boolean get() = searchListsActive

    /** Voce selezionata nel menu "Risultati". */
    var selectedSearchResultIndex by mutableStateOf(0)

    /** Voce del menu "Risultati": solo le sezioni che hanno davvero qualcosa. */
    val searchResultRows: List<Pair<SearchResultKind, String>>
        get() = searchResults?.let { r ->
            buildList {
                if (r.tracks.isNotEmpty()) add(SearchResultKind.TRACKS to "Brani (${r.tracks.size})")
                if (r.artists.isNotEmpty()) add(SearchResultKind.ARTISTS to "Artisti (${r.artists.size})")
                if (r.albums.isNotEmpty()) add(SearchResultKind.ALBUMS to "Album (${r.albums.size})")
            }
        } ?: emptyList()

    /**
     * Album mostrati in schermata: risultati di ricerca se si è arrivati da
     * lì, altrimenti gli album salvati.
     */
    val displayAlbums: List<SpotifyAlbumDetails>
        get() = if (searchListsActive) searchResults?.albums.orEmpty() else albums

    /** Come [displayAlbums] ma per gli artisti seguiti. */
    val displayArtists: List<SpotifyArtistDetails>
        get() = if (searchListsActive) searchResults?.artists.orEmpty() else artists

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
            // Se non c'è ancora una sessione restiamo sulla schermata di login:
            // sarà l'utente a premere "Accedi a Spotify" (niente browser ad ogni avvio).
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
            // La sessione non è più recuperabile (refresh token rifiutato/revocato).
            // Mostriamo la schermata di login IN-APP: niente browser automatico,
            // sarà l'utente a premere "Accedi a Spotify".
            Log.d(TAG, "Sessione Spotify non recuperabile. Mostro la schermata di login.")
            if (screenState != ScreenState.CREDENTIALS_SETUP) {
                statusText = "Sessione scaduta. Accedi di nuovo a Spotify."
                showLoginButton = true
                screenState = ScreenState.CREDENTIALS_SETUP
            }
        }

        when {
            spotifyManager.hasStoredSession -> {
                // Sessione salvata: rinnova il token in silenzio se serve e connetti.
                // Nessun browser, nessun login finché il refresh token è valido.
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
                // Codice rimasto a metà (l'app è stata uccisa prima dello scambio):
                // è monouso e ormai ingestibile da qui, quindi lo buttiamo via e
                // torniamo al pulsante di login — non restiamo bloccati su una
                // schermata di caricamento senza via d'uscita.
                Log.w(TAG, "initializeSpotifyServices: pendingAuthCode orfano, lo cancello")
                spotifyManager.pendingAuthCode = null
                showLoginButton = true
            }
            else -> {
                // Primo avvio: apri il browser una volta sola, poi ci si affida
                // al pulsante in-app (evita di riaprirlo ad ogni avvio).
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

        // Assicura che il repository esista prima di usarlo
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
            // Il codice OAuth è monouso: va cancellato SEMPRE, anche se lo
            // scambio fallisce, altrimenti al riavvio l'app resta in attesa
            // di un callback che non arriverà più.
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

    // Fix #6 — chiamato dal pulsante "Accedi a Spotify" esplicito in UI
    fun onLoginButtonClicked() {
        spotifyManager.requestToken(getApplication())
    }

    /**
     * Disconnette l'account Spotify: cancella tutti i token (refresh compreso),
     * svuota i dati scaricati e riporta alla schermata di login.
     */
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

        // Ricrea il repository senza token e rilegga il listener del player.
        // autoLoginAttempted è già true: niente browser, solo il pulsante in-app.
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

                // Anche il repeat è deciso da Spotify: qui ci limitiamo a rifletterlo,
                // così l'icona resta giusta se il brano cambia dall'app ufficiale.
                repeatMode = playerState.playbackOptions.repeatMode

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

                // Se l'utente sta guardando le lyrics, seguono il nuovo brano
                if (showLyrics) loadLyrics()

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

    /**
     * Aggiorna la barra dei progressi.
     *
     *  - quando non suona nulla non fa nessun lavoro (nessuna scrittura di stato);
     *  - il tempo avanza in base ai millisecondi REALMENTE trascorsi, così un
     *    ritardo del loop non fa derivare la barra;
     *  - ogni RESYNC_MS la barra viene riallineata alla posizione riportata da
     *    Spotify: è il player a decidere dove siamo, non l'orologio dell'app.
     */
    private fun startProgressTicker() {
        viewModelScope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            var lastSyncAt = 0L
            var lastAdvanceAt = 0L

            while (true) {
                if (!isTrackPlaying) {
                    // Riposo: al solo risveglio, nessun lavoro
                    delay(IDLE_TICK_MS)
                    lastTick = SystemClock.elapsedRealtime()
                    lastSyncAt = 0L
                    continue
                }

                delay(PROGRESS_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastTick).coerceIn(0L, PROGRESS_TICK_MS * 2)
                lastTick = now

                // Riallinea la barra con la posizione reale del player di Spotify:
                // è anche il modo in cui recuperiamo la durata quando non è nota.
                if (now - lastSyncAt >= RESYNC_MS) {
                    lastSyncAt = now
                    syncProgressFromPlayer()
                }

                if (trackDurationMs <= 0L) continue   // durata ancora ignota

                currentProgressMs = (currentProgressMs + elapsed).coerceAtMost(trackDurationMs)

                // Fine brano: solo i Preferiti vanno fatti avanzare da noi, perché
                // sono una playlist virtuale che Spotify non conosce.
                val nearEnd = currentProgressMs >= trackDurationMs - END_OF_TRACK_MS
                if (nearEnd && playingFromFavorites &&
                    now - lastAdvanceAt >= MIN_ADVANCE_MS &&
                    isCurrentTrackInFavorites()
                ) {
                    lastAdvanceAt = now
                    advanceFavoritesTrack()
                    lastTick = SystemClock.elapsedRealtime()
                    lastSyncAt = 0L
                }
            }
        }
    }

    /**
     * true se il brano che stiamo mostrando fa ancora parte dello snapshot dei
     * Preferiti. Se l'utente ha cambiato brano dall'app Spotify, smettiamo di
     * avanzare in autonomia: non è più una riproduzione nostra.
     */
    private fun isCurrentTrackInFavorites(): Boolean {
        val id = playingTrackDetails?.id ?: return false
        return favoritesTracks.any { it.id == id }
    }

    /** Chiede a Spotify la posizione reale della traccia in riproduzione. */
    private fun syncProgressFromPlayer() {
        spotifyManager.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { state ->
            val track = state.track ?: return@setResultCallback
            if (state.isPaused) return@setResultCallback

            // Se il player è ancora sulla traccia precedente (il play è in volo)
            // non tocchiamo nulla: altrimenti la barra tornerebbe indietro.
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

        // L'indice salvato si aizza non appena il brano corrente cambia senza
        // passare da qui (cambiato dall'app Spotify, o ripristinato all'avvio):
        // in quel caso "indice + 1" porterebbe a un brano a caso. La posizione
        // la decide il brano che stiamo suonando, non una variabile.
        val currentId = playingTrackDetails?.id
        val currentIndex = list.indexOfFirst { it.id == currentId }
        if (currentIndex >= 0) favoritesTrackIndex = currentIndex

        favoritesTrackIndex = if (isLocalShuffleEnabled) {
            list.indices.random().also { Log.d(TAG, "Fine brano: shuffle → $it") }
        } else {
            (favoritesTrackIndex + 1).coerceAtMost(list.lastIndex)
                .also { Log.d(TAG, "Fine brano: sequenziale → $it") }
        }
        val next = list[favoritesTrackIndex]
        currentProgressMs = 0L
        playingTrackDetails = next
        syncFavoritesSelection()
        musicRepository?.play(next.uri, "")
    }

    /** Se l'utente sta guardando la lista dei Preferiti, allinea anche la riga evidenziata. */
    private fun syncFavoritesSelection() {
        if (screenState == ScreenState.TRACKS &&
            selectedSpotifyMenuIndex == 0 &&
            playlists.getOrNull(selectedPlaylistIndex)?.id == FAVORITES_ID
        ) {
            selectedTrackIndex = favoritesTrackIndex
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    /**
     * Scarica l'elenco playlist.
     *
     * - **single-flight**: le chiamate concorrenti (handleAuthCode, il
     *   LaunchedEffect su `tokenRefreshTick`, `onResume` e la pressione di
     *   "Playlist" partono quasi insieme) vengono messe in coda invece di
     *   lanciare 2-3 richieste duplicate, che è probabilmente la causa del
     *   primo caricamento fallito (burst di richieste subito dopo il login);
     * - **un errore non viene memorizzato come "già caricato"**, quindi alla
     *   pressione successiva si riprova;
     * - **2 tentativi** per coprire un errore transitorio.
     *
     * @return true se la lista è pronta
     */
    private suspend fun syncPlaylists(force: Boolean = false): Boolean {
        if (playlistsLoaded && !force) return true

        return playlistsMutex.withLock {
            // Un'altra coroutine potrebbe aver completato mentre aspettavamo il lock
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
            // In caso di errore con una lista precedente, restiamo a quella
            ok || playlistsLoaded
        }
    }

    fun loadPlaylistsIfNeeded() {
        val from = screenState
        if (playlistsLoaded) { screenState = ScreenState.PLAYLISTS; return }
        viewModelScope.launch {
            syncPlaylists()
            // Si apre la lista solo se l'utente è ancora lì ad aspettarla:
            // un MENU premuto durante lo sync non deve far comparire una
            // schermata a vuoto dal nulla.
            if (screenState == from) screenState = ScreenState.PLAYLISTS
        }
    }

    fun loadAlbumsIfNeeded() {
        // Entrando dal menu si torna agli album VERI, non ai risultati ricerca
        searchListsActive = false
        if (albums.isNotEmpty()) { screenState = ScreenState.ALBUMS; return }
        viewModelScope.launch {
            isLoading = true; statusText = "Scarico Album..."
            albums = musicRepository?.getUserSavedAlbums() ?: emptyList()
            selectedAlbumIndex = 0; screenState = ScreenState.ALBUMS; isLoading = false
        }
    }

    fun loadArtistsIfNeeded() {
        // Stesso ragionamento degli album: uscendo dalla ricerca si torna
        // alla lista degli artisti seguiti
        searchListsActive = false
        if (artists.isNotEmpty()) { screenState = ScreenState.ARTISTS; return }
        viewModelScope.launch {
            isLoading = true; statusText = "Scarico Artisti..."
            artists = musicRepository?.getUserFollowedArtists() ?: emptyList()
            selectedArtistIndex = 0; screenState = ScreenState.ARTISTS; isLoading = false
        }
    }

    fun loadTracksForPlaylist() {
        // Il Select ha due significati: se si è arrivati dal "centro lungo" su
        // un brano, la playlist selezionata è la DESTINAZIONE dell'aggiunta
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
                // Se quei Preferiti sono già in riproduzione, ripartiamo dalla traccia giusta
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

    /** true quando la lista playlist è aperta per SCEGLIERE la destinazione. */
    var addingToPlaylist by mutableStateOf(false)
        private set

    private var pendingPlaylistTrack: SpotifyTrackDetails? = null

    /**
     * Centro lungo su un brano in lista: prepara l'aggiunta e apre la lista
     * playlist. L'azione si esegue solo alla scelta, o si annulla con MENU.
     */
    fun startAddToPlaylist() {
        if (screenState != ScreenState.TRACKS) return
        val track = tracks.getOrNull(selectedTrackIndex) ?: return
        pendingPlaylistTrack = track
        addingToPlaylist = true
        selectedPlaylistIndex = 0
        showListNotice(null)
        loadPlaylistsIfNeeded()
    }

    /** QUALSIASI uscita dalla lista playlist annulla l'aggiunta. */
    private fun cancelAddToPlaylist() {
        pendingPlaylistTrack = null
        addingToPlaylist = false
    }

    /** Playlist scelta: aggiunge il brano e torna alla lista da cui si partiva. */
    private fun commitAddToPlaylist() {
        val from = screenState
        val track = pendingPlaylistTrack
        val playlist = playlists.getOrNull(selectedPlaylistIndex)
        cancelAddToPlaylist()
        if (playlist == null) {
            // Nessuna lista (sync fallito): si torna indietro con un motivo
            showListNotice("Playlist non disponibili")
            screenState = ScreenState.TRACKS
            return
        }
        if (track == null) {
            screenState = ScreenState.TRACKS
            return
        }

        // I Preferiti sono una playlist virtuale: l'equivalente è salvare il
        // brano, quindi si passa dall'API "brani che mi piacciono"
        if (playlist.id == FAVORITES_ID) {
            viewModelScope.launch {
                val ok = musicRepository?.saveSpotifyTrack(track.id) == true
                if (ok && playingTrackDetails?.id == track.id) isCurrentTrackLiked = true
                // Se l'utente è già uscito con MENU, il messaggio non ha più
                // una lista dove stare
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
                    // 403: manca playlist-modify-*, sul token attuale
                    null -> "Permessi mancanti: rifai il login"
                    else -> "Aggiunta non riuscita"
                }
            )
            screenState = ScreenState.TRACKS
        }
    }

    /** Centro lungo: nelle liste brani apre "aggiungi a playlist". */
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

    /**
     * Esegue la ricerca (brani + artisti + album) e apre il menu dei
     * risultati, dove si sceglie quale sezione aprire.
     */
    fun searchTracks() {
        if (searchQuery.isBlank()) return
        val from = screenState
        viewModelScope.launch {
            isLoading = true; statusText = "Ricerca in corso..."
            val results = musicRepository?.searchSpotify(searchQuery)
            isLoading = false
            // Se l'utente è uscito durante la ricerca, non si apre il menu
            if (screenState != from) return@launch
            searchResults = results
            searchListsActive = false
            selectedSearchResultIndex = 0
            showListNotice(null)
            screenState = ScreenState.SEARCH_RESULTS
        }
    }

    /** Apre una sezione del menu risultati ricerca. */
    fun openSearchResult(kind: SearchResultKind) {
        when (kind) {
            SearchResultKind.TRACKS -> {
                // I brani sono "in mutuo possesso" col resto: vengono sempre
                // ricaricati quando si entra in playlist/album/artista, quindi
                // qui li possiamo sovrascrivere senza sorprese
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

    /**
     * Caricamento condiviso da Cronologia e Coda: entrambe producono una lista
     * di brani apribile nella schermata TRACCIE, con il MENU che torna al menu
     * Spotify (le due voci non hanno un livello sotto di loro).
     *
     * @param fetch `null` = lo scope non è concesso sul token corrente.
     */
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
            // Se durante lo scarico la sessione è caduta o l'utente è uscito,
            // non si apre una lista che non ha più senso
            if (screenState != from) return@launch

            tracks = list.orEmpty()
            tracksFromFavorites = false
            // Lista piatta: niente contesto, si riproduce la traccia scelta
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

        // I Preferiti sono decisi dalla lista che ha prodotto `tracks`, non dalla
        // selezione corrente: quest'ultima cambia mentre l'utente continua ad ascoltare.
        val isFavorites = tracksFromFavorites

        // Contesto deciso da chi ha prodotto la lista, non dalla voce di menu:
        // un album cercato va suonato come album, la cronologia no.
        val contextUri: String =
            if (isFavorites) ""          // Preferiti: lista piatta
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

    /**
     * Tasto centrale mentre si è nel player: alterna le lyrics alle info di
     * riproduzione.
     */
    fun toggleLyrics() {
        if (screenState != ScreenState.TRACK_DETAILS) return
        showLyrics = !showLyrics
        if (showLyrics) loadLyrics()
    }

    /**
     * Scarica le lyrics della traccia corrente se non le abbiamo già.
     *
     * Idempotente e a costo costante: il confronto su `lyricsTrackId` evita di
     * rifare la richiesta a ogni toggle, e cachiamo anche i "non trovati".
     */
    private fun loadLyrics() {
        val track = playingTrackDetails
        if (track == null) {
            lyrics = LyricsResult.None
            return
        }
        // Già cercate (o ricerca ancora in corso): non si ripete la richiesta
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
            favoritesTrackIndex = if (isLocalShuffleEnabled) list.indices.random()
                                  else (favoritesTrackIndex - 1).coerceAtLeast(0)
            syncFavoritesSelection()
            playFavoritesTrack(list[favoritesTrackIndex])
        } else {
            spotifyManager.skipPrevious()
            if (screenState == ScreenState.TRACKS && selectedTrackIndex > 0) selectedTrackIndex--
        }
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

    /**
     * Cicla la ripetizione: Off → Tutti → Uno → Off.
     *
     * Lo stato viene subito aggiornato in locale (l'icona deve rispondere
     * all'istante) e poi confermato a Spotify; se Spotify rifiuta, il listener
     * di player-state riporta il valore reale.
     */
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
            // Nel player il tasto centrale passa dalle info alle lyrics e ritorno
            ScreenState.TRACK_DETAILS -> toggleLyrics()
            ScreenState.SETTINGS -> when (settingsRows.getOrNull(selectedSettingsIndex)?.kind) {
                SettingsRowKind.THEME ->
                    settingsRows[selectedSettingsIndex].theme?.let { themeManager.setTheme(it) }

                // I due livelli di feedback si cambiano ciclando: Select avanza di
                // un livello e lo fa sentire subito, così l'utente capisce subito
                // la differenza.
                SettingsRowKind.HAPTIC -> {
                    feedback.hapticLevel = feedback.hapticLevel.next
                    feedback.press()
                }
                SettingsRowKind.SOUND -> {
                    feedback.soundLevel = feedback.soundLevel.next
                    feedback.click()
                }

                SettingsRowKind.BACKLIGHT -> cycleBacklight()

                // Schermata statica: niente azione oltre a navigarci dentro
                SettingsRowKind.ABOUT -> screenState = ScreenState.ABOUT

                // "Disconnetti Spotify": prima pressione chiede conferma, seconda agisce
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
                // Si esce dalle lyrics: alla rientrata si ripartono le info
                showLyrics = false
                screenState = ScreenState.TRACKS
            }
            ScreenState.TRACKS -> {
                // Uscire dai brani azzera anche un'eventuale "aggiungi a playlist"
                cancelAddToPlaylist()
                screenState = when (selectedSpotifyMenuIndex) {
                    1 -> ScreenState.ALBUMS
                    2 -> ScreenState.ARTISTS
                    // Con una ricerca aperta si torna ai risultati, non alla tastiera
                    3 -> if (searchResults != null) ScreenState.SEARCH_RESULTS
                        else ScreenState.SEARCH
                    // Cronologia e Coda non hanno un livello sotto: si torna al menu
                    4, 5 -> ScreenState.SPOTIFY_MENU
                    else -> ScreenState.PLAYLISTS
                }
            }
            ScreenState.SEARCH_RESULTS -> screenState = ScreenState.SEARCH
            // Dalla ricerca si torna ai risultati, dal menu Spotify al menu Spotify
            ScreenState.ALBUMS, ScreenState.ARTISTS ->
                screenState = if (searchListsActive) ScreenState.SEARCH_RESULTS
                    else ScreenState.SPOTIFY_MENU
            ScreenState.PLAYLISTS -> if (addingToPlaylist) {
                // Modalità "aggiungi": si torna ai brani, l'operazione decade
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
            // Salire al menu principale interrompe qualsiasi "aggiungi a playlist"
            cancelAddToPlaylist()
            previousScreen = screenState
            screenState = ScreenState.MAIN_MENU
            selectedMainMenuIndex = 0
        }
    }

    // ── Startup data load ─────────────────────────────────────────────────────

    fun loadInitialDataIfReady(forceReload: Boolean = false) {
        val repo = musicRepository ?: return

        // Se non abbiamo una sessione salvata, non facciamo nulla
        if (!spotifyManager.hasStoredSession) return

        // Guard su ogni task: handleAuthCode, il LaunchedEffect su
        // tokenRefreshTick e onResume scattano insieme e senza questi check
        // verrebbero lanciate 2-3 sincronizzazioni duplicate subito dopo il login.
        val needPlaylists = forceReload || !playlistsLoaded
        if (needPlaylists && (forceReload || initialSyncJob?.isActive != true)) {
            initialSyncJob = viewModelScope.launch { syncPlaylists(forceReload) }
        }

        // Carica le cover degli album in background per il Ken Burns nel menu
        // (solo se non già caricate, senza mostrare loading).
        // Un vuoto da errore non sovrascrive i dati già presenti.
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

    // ── Volume overlay (mostra barra volume nel player per 2s) ────────────────

    /** Mostra la barra volume al posto della progressbar per 2 secondi. */
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