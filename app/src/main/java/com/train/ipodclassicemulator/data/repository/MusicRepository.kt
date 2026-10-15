package com.train.ipodclassicemulator.data.repository

import android.util.Log
import com.train.ipodclassicemulator.data.model.PlaylistItem
import com.train.ipodclassicemulator.data.model.SearchResults
import com.train.ipodclassicemulator.data.model.SpotifyAlbumDetails
import com.train.ipodclassicemulator.data.model.SpotifyAlbumModelInfo
import com.train.ipodclassicemulator.data.model.SpotifyArtistDetails
import com.train.ipodclassicemulator.data.model.SpotifyImage
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.data.model.SpotifyTokenResponse
import com.train.ipodclassicemulator.data.remote.AddTracksBody
import com.train.ipodclassicemulator.data.remote.SpotifyApiService
import com.train.ipodclassicemulator.data.remote.SpotifyManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MusicRepository(private val spotifyManager: SpotifyManager) {

    private val TAG = "MusicRepository"

    // Fix #10 — Retrofit è un singleton: non viene ricreato ad ogni istanza del repository
    private val apiService: SpotifyApiService get() = Companion.apiService

    /**
     * Web Access Token in memoria per la sessione corrente.
     * All'avvio viene precaricato da SharedPreferences se disponibile,
     * così non serve riscambiare il codice OAuth ad ogni apertura.
     */
    private var webAccessToken: String? = spotifyManager.savedWebToken

    /** Epoch ms di scadenza dell'access token corrente (0 = sconosciuto). */
    private var tokenExpiresAtMs: Long = spotifyManager.tokenExpiresAt

    var onTokenExpired: (() -> Unit)? = null

    // ── RINNOVO DEL TOKEN ─────────────────────────────────────────────────────

    /** Garantisce che solo un task alla volta tenti il refresh. */
    private val refreshMutex = Mutex()

    /** Perché l'ultimo tentativo di refresh non ha funzionato. */
    private enum class RefreshFailure { NONE, TRANSIENT, PERMANENT }
    private var lastRefreshFailure = RefreshFailure.NONE

    /** Tentativi di refresh falliti per errore di rete consecutivi. */
    private var transientRefreshFailures = 0

    /** true se l'access token in memoria è valido per almeno un altro minuto. */
    private fun isTokenFresh(): Boolean =
        webAccessToken != null &&
                tokenExpiresAtMs > 0L &&
                System.currentTimeMillis() < tokenExpiresAtMs - 60_000L

    /**
     * Rinnova l'access token con il refresh token, se quello corrente sta per scadere.
     *
     * @return true se al termine abbiamo un token che possiamo considerare valido.
     */
    suspend fun ensureFreshToken(): Boolean = refreshMutex.withLock {
        if (isTokenFresh()) {
            lastRefreshFailure = RefreshFailure.NONE
            return@withLock true
        }

        val storedRefresh = spotifyManager.refreshToken
        if (storedRefresh == null) {
            // Sessioni salvate con versioni precedenti dell'app: niente refresh
            // token, quindi ci limitiamo a usare l'access token così com'è.
            // Se è davvero scaduto ci penserà il 401 a farci tornare al login.
            lastRefreshFailure = RefreshFailure.NONE
            return@withLock false
        }

        try {
            val response = apiService.refreshAccessToken(
                clientId = spotifyManager.spotifyClientId,
                grantType = "refresh_token",
                refreshToken = storedRefresh
            )
            storeToken(response)
            transientRefreshFailures = 0
            lastRefreshFailure = RefreshFailure.NONE
            Log.d(TAG, "Access token rinnovato in silenzio, nessun login richiesto")
            true
        } catch (e: HttpException) {
            // Spotify ha rifiutato il refresh token: la sessione è davvero finita.
            transientRefreshFailures = 0
            lastRefreshFailure = RefreshFailure.PERMANENT
            Log.w(TAG, "Refresh token rifiutato (HTTP ${e.code()})")
            false
        } catch (e: Exception) {
            // Errore di rete: NON buttiamo via la sessione, nemmeno dopo molti
            // tentativi. Un tunnel, l'aeroplano o un Wi-Fi morto non sono una
            // revoca del token: qui si può solo riprovare, e lo rifaremo alla
            // prossima chiamata finché la rete non torna.
            transientRefreshFailures++
            lastRefreshFailure = RefreshFailure.TRANSIENT
            Log.e(TAG, "Refresh token fallito (rete, tentativo $transientRefreshFailures)", e)
            false
        }
    }

    /**
     * Token "Bearer" da usare per le chiamate API.
     * Rinnova in silenzio quando serve; ritorna null solo se non c'è una sessione.
     */
    suspend fun authHeader(): String? {
        if (webAccessToken == null) webAccessToken = spotifyManager.savedWebToken
        if (webAccessToken == null && spotifyManager.refreshToken == null) return null

        if (ensureFreshToken()) return webAccessToken

        if (lastRefreshFailure == RefreshFailure.PERMANENT) {
            expireSession()
            return null
        }
        // Nessun refresh token o guasto transitorio: proviamoci con quello che c'è
        return webAccessToken
    }

    /** Salva access token + scadenza (e il refresh token, quando presente). */
    private fun storeToken(response: SpotifyTokenResponse) {
        webAccessToken = response.access_token
        tokenExpiresAtMs = System.currentTimeMillis() + response.expires_in * 1000L
        spotifyManager.savedWebToken = response.access_token
        spotifyManager.tokenExpiresAt = tokenExpiresAtMs
        response.refresh_token?.let { spotifyManager.refreshToken = it }
    }

    /** Unica via d'uscita quando la sessione non è più recuperabile. */
    private fun expireSession() {
        Log.w(TAG, "Sessione Spotify non recuperabile: serve un nuovo login")
        webAccessToken = null
        spotifyManager.clearAllTokens()
        onTokenExpired?.invoke()
    }

    // ── AUTH ─────────────────────────────────────────────────────────────────

    /**
     * Scambia il codice OAuth monouso con il web token Bearer reale.
     * Salva il token in SharedPreferences e cancella il codice usato.
     */
    suspend fun fetchWebToken(authCode: String, codeVerifier: String): Boolean {
        return try {
            val response = apiService.getAccessTokenPkce(
                clientId = spotifyManager.spotifyClientId,
                grantType = "authorization_code",
                code = authCode,
                redirectUri = spotifyManager.redirectUri,
                codeVerifier = codeVerifier
            )
            storeToken(response)
            true
        } catch (e: Exception) {
            handleAuthFailure(e)
            false
        }
    }

    /** true se abbiamo già un web token valido (non serve riscambiare il codice) */
    fun hasValidToken(): Boolean = webAccessToken != null

    // ── PLAYLIST ──────────────────────────────────────────────────────────────

    // Fix #2 — paginazione completa con loop su `offset`
    /**
     * @return la lista delle playlist dell'utente, oppure **null se la chiamata è
     *   fallita**. Distinguere "errore" da "lista davvero vuota" è fondamentale:
     *   un errore non va memorizzato come "già caricato", altrimenti la lista non
     *   verrebbe più mai riprovata fino al riavvio dell'app.
     */
    suspend fun getUserPlaylists(): List<PlaylistItem>? {
        val token = authHeader() ?: return null
        val result = mutableListOf<PlaylistItem>()
        var offset = 0
        val limit = 50
        return try {
            do {
                val page = apiService.getUserPlaylists("Bearer $token", limit = limit, offset = offset)
                result.addAll(page.items)
                offset += limit
            } while (page.next != null)
            result
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore playlist", e); handleAuthFailure(e); null
        }
    }

    suspend fun getTracksForPlaylist(playlistId: String): List<SpotifyTrackDetails> {
        val token = authHeader() ?: return emptyList()
        val result = mutableListOf<SpotifyTrackDetails>()
        var offset = 0
        val limit = 100
        return try {
            do {
                val page = apiService.getPlaylistTracks("Bearer $token", playlistId, limit = limit, offset = offset)
                result.addAll(page.items.mapNotNull { it.track })
                offset += limit
            } while (page.next != null)
            // Una playlist può ospitare episodi: stessa forma JSON, ma senza
            // `artists` → la lista brani darebbe NPE alla prima riga
            result.filter { it.uri.startsWith("spotify:track:") }
        } catch (e: Exception) {
            Log.e(TAG, "Errore brani playlist", e); handleAuthFailure(e); result
        }
    }

    // ── LIKED SONGS ───────────────────────────────────────────────────────────

    // Fix #2 — paginazione completa
    suspend fun getSavedTracks(): List<SpotifyTrackDetails> {
        val token = authHeader() ?: return emptyList()
        val result = mutableListOf<SpotifyTrackDetails>()
        var offset = 0
        val limit = 50
        return try {
            do {
                val page = apiService.getSavedTracks("Bearer $token", limit = limit, offset = offset)
                result.addAll(page.items.map { it.track })
                offset += limit
            } while (page.next != null)
            // La libreria può contenere episodi: senza `artists` darebbero NPE
            result.filter { it.uri.startsWith("spotify:track:") }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore liked songs", e); handleAuthFailure(e); result
        }
    }

    suspend fun setTrackSaved(trackId: String, saved: Boolean): Boolean {
        val token = authHeader() ?: return false
        return try {
            val r = if (saved)
                apiService.saveTrack("Bearer $token", trackId)
            else
                apiService.removeTrack("Bearer $token", trackId)
            r.isSuccessful
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore set saved", e); false
        }
    }

    // ── ALBUM ─────────────────────────────────────────────────────────────────

    // Fix #2 — paginazione completa
    suspend fun getUserSavedAlbums(): List<SpotifyAlbumDetails> {
        val token = authHeader() ?: return emptyList()
        val result = mutableListOf<SpotifyAlbumDetails>()
        var offset = 0
        val limit = 50
        return try {
            do {
                val page = apiService.getSavedAlbums("Bearer $token", limit = limit, offset = offset)
                result.addAll(page.items.map { it.album })
                offset += limit
            } while (page.next != null)
            result
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore album", e); handleAuthFailure(e); result
        }
    }

    suspend fun getTracksForAlbum(
        albumId: String,
        albumImages: List<SpotifyImage>? = null
    ): List<SpotifyTrackDetails> {
        val token = authHeader() ?: return emptyList()
        return try {
            apiService.getAlbumTracks("Bearer $token", albumId).items.map {
                SpotifyTrackDetails(
                    id = it.id,
                    name = it.name,
                    uri = it.uri,
                    artists = it.artists,
                    album = SpotifyAlbumModelInfo(
                        id = albumId,
                        name = "",
                        uri = "",
                        images = albumImages
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore brani album", e); handleAuthFailure(e); emptyList()
        }
    }

    // ── ARTISTI ───────────────────────────────────────────────────────────────

    // Fix #2 — paginazione completa (cursor-based per followed artists)
    suspend fun getUserFollowedArtists(): List<SpotifyArtistDetails> {
        val token = authHeader() ?: return emptyList()
        val result = mutableListOf<SpotifyArtistDetails>()
        var after: String? = null
        val limit = 50
        return try {
            do {
                val page = apiService.getFollowedArtists("Bearer $token", limit = limit, after = after)
                result.addAll(page.artists.items)
                after = page.artists.cursors?.after
            } while (page.artists.next != null)
            result
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore artisti", e); handleAuthFailure(e); result
        }
    }

    suspend fun getTracksForArtist(artistId: String): List<SpotifyTrackDetails> {
        val token = authHeader() ?: return emptyList()
        return try {
            apiService.getArtistTopTracks("Bearer $token", artistId).tracks.map {
                SpotifyTrackDetails(
                    id = it.id,
                    name = it.name,
                    uri = it.uri,
                    artists = it.artists,
                    album = it.album?.let { alb ->
                        SpotifyAlbumModelInfo(alb.id, alb.name, alb.uri, alb.images)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore brani artista", e); handleAuthFailure(e); emptyList()
        }
    }

    // ── RICERCA ───────────────────────────────────────────────────────────────

    /**
     * Cerca brani, artisti e album con un'unica chiamata (il tipo è
     * "track,artist,album", quindi Spotify restituisce tre sezioni).
     *
     * @return le tre liste; in errore sono tutte vuote, mai una eccezione.
     */
    suspend fun searchSpotify(query: String): SearchResults {
        val empty = SearchResults(emptyList(), emptyList(), emptyList())
        val token = authHeader() ?: return empty
        if (query.isBlank()) return empty
        return try {
            val response = apiService.search("Bearer $token", query)
            SearchResults(
                tracks = response.tracks?.items.orEmpty(),
                artists = response.artists?.items.orEmpty(),
                albums = response.albums?.items.orEmpty()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Errore ricerca", e); handleAuthFailure(e); empty
        }
    }

    // ── CRONOLOGIA ───────────────────────────────────────────────────────────

    /**
     * Ultime tracce riprodotte dall'utente.
     *
     * Gli episodi (podcast) hanno la stessa forma JSON ma non hanno `artists`:
     * verranno deserializzati con i campi mancanti a null, quindi vengono
     * scartati prima di poterli toccare.
     *
     * @return le tracce; `null` se lo scope non è concesso (serve un nuovo
     *   accesso), lista vuota in caso di errore di rete o lista davvero vuota.
     */
    suspend fun getRecentlyPlayed(): List<SpotifyTrackDetails>? {
        val token = authHeader() ?: return emptyList()
        return try {
            apiService.getRecentlyPlayed("Bearer $token")
                .items
                .mapNotNull { it.track }
                .filter { it.uri.startsWith("spotify:track:") }
        } catch (e: HttpException) {
            Log.e(TAG, "Errore cronologia: HTTP ${e.code()}", e); handleAuthFailure(e)
            // 403 = lo scope "user-read-recently-played" non è nel token attuale:
            // non è che la lista sia vuota, è che serve un nuovo accesso
            if (e.code() == 403) null else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Errore cronologia", e); handleAuthFailure(e); emptyList()
        }
    }

    // ── CODA ─────────────────────────────────────────────────────────────────

    /**
     * Prossimi brani in coda di riproduzione (il brano in corso è
     * `currently_playing` e non fa parte della coda).
     *
     * @return come [getRecentlyPlayed].
     */
    suspend fun getPlaybackQueue(): List<SpotifyTrackDetails>? {
        val token = authHeader() ?: return emptyList()
        return try {
            apiService.getPlayerQueue("Bearer $token")
                .queue
                .orEmpty()
                .filterNotNull()
                .filter { it.uri.startsWith("spotify:track:") }
        } catch (e: HttpException) {
            Log.e(TAG, "Errore coda: HTTP ${e.code()}", e); handleAuthFailure(e)
            // 403 = manca "user-read-playback-state"
            if (e.code() == 403) null else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Errore coda", e); handleAuthFailure(e); emptyList()
        }
    }

    // ── PLAYLIST: aggiunta di un brano ───────────────────────────────────────

    /**
     * @return `true` se il brano è stato aggiunto, `false` se Spotify ha
     *   rifiutato la richiesta, `null` se manca lo scope `playlist-modify-*`
     *   (403) e serve un nuovo accesso.
     */
    suspend fun addTrackToPlaylist(playlistId: String, trackUri: String): Boolean? {
        val token = authHeader() ?: return false
        return try {
            val response = apiService.addTracksToPlaylist(
                bearer = "Bearer $token",
                playlistId = playlistId,
                body = AddTracksBody(uris = listOf(trackUri))
            )
            when {
                response.isSuccessful -> true
                response.code() == 403 -> null
                else -> {
                    Log.w(TAG, "Aggiunta rifiutata: HTTP ${response.code()}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore aggiunta a playlist", e); handleAuthFailure(e); false
        }
    }

    // ── PLAYBACK ──────────────────────────────────────────────────────────────

    fun play(trackUri: String, contextUri: String? = null, trackIndex: Int = 0) {
        val playerApi = spotifyManager.spotifyAppRemote?.playerApi
        if (playerApi == null) {
            Log.e("MusicRepository", "Errore: SpotifyAppRemote è NULL! Non posso riprodurre.")
            return
        }
        Log.d("MusicRepository", "Tentativo di riproduzione: $trackUri")
        if (!contextUri.isNullOrEmpty()) {
            // Il salto va INCATENATO al play, non lanciato in parallelo:
            // skipToIndex applicato prima che il contesto sia partito viene
            // ignorato (o peggio, applicato al contesto precedente) e la coda
            // che resta a Spotify non è più la playlist — da lì il "brano
            // successivo" sbagliato quando una traccia finisce da sola.
            // Con un contesto non si può suonare la sola traccia: l'indice
            // dentro il contesto è l'unica via per arrivare a `trackUri`.
            playerApi.play(contextUri).setResultCallback {
                playerApi.skipToIndex(contextUri, trackIndex)
                    .setErrorCallback { e ->
                        Log.e("MusicRepository", "skipToIndex fallito", e)
                        playerApi.play(trackUri)
                    }
            }.setErrorCallback { e ->
                Log.e("MusicRepository", "play del contesto fallito, traccia singola", e)
                playerApi.play(trackUri)
            }
        } else {
            // Senza contesto il comando va applicato così com'è: se fallisce
            // non deve restare silenzioso, altrimenti la riproduzione prosegue
            // con l'autoplay di Spotify e non si capisce perché.
            playerApi.play(trackUri).setErrorCallback { e ->
                Log.e("MusicRepository", "play della traccia singola fallito: $trackUri", e)
            }
        }
    }

    // ── GESTIONE ERRORI AUTH ──────────────────────────────────────────────────

    private suspend fun handleAuthFailure(e: Exception) {
        if (e !is HttpException || e.code() != 401) return

        // Prima di tutto proviamo a rinnovare il token: se funziona l'utente
        // non deve fare nulla e alla prossima chiamata torna a funzionare.
        if (ensureFreshToken()) return

        if (lastRefreshFailure == RefreshFailure.TRANSIENT) {
            Log.w(TAG, "401 ma il refresh è fallito per la rete: mantengo la sessione")
            return
        }
        Log.w(TAG, "HTTP 401 e refresh non possibile — serve un nuovo login")
        expireSession()
    }

    // 🟢 Controlla se un brano specifico è tra i preferiti su Spotify
    suspend fun isTrackSaved(trackId: String): Boolean {
        val token = authHeader() ?: return false
        return try {
            val response = apiService.checkTracksSaved(bearer = "Bearer $token", trackIds = trackId)
            response.firstOrNull() ?: false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // ri-lancia sempre la cancellazione — non loggarla come errore
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore check preferiti", e)
            false
        }
    }

    // 🟢 Aggiunge il brano ai preferiti
    suspend fun saveSpotifyTrack(trackId: String): Boolean {
        val token = authHeader() ?: return false
        return try {
            val response = apiService.saveTrack(bearer = "Bearer $token", trackIds = trackId)
            response.isSuccessful
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // 🟢 Rimuove il brano dai preferiti
    suspend fun removeSpotifyTrack(trackId: String): Boolean {
        val token = authHeader() ?: return false
        return try {
            val response = apiService.removeTrack(bearer = "Bearer $token", trackIds = trackId)
            response.isSuccessful
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // 🟢 Invia il comando di Shuffle avanzato direttamente ai server web di Spotify
    suspend fun setSpotifyShuffleMode(enabled: Boolean): Boolean {
        val token = authHeader() ?: return false
        return try {
            val response = apiService.setWebShuffleMode(bearer = "Bearer $token", state = enabled)
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore impostazione Shuffle Web", e)
            false
        }
    }

    // Mantenuto per compatibilità con il codice legacy
    fun getTestSpotifyTracks(): List<TrackModel> = listOf(
        TrackModel("1", "Toxicity", "System Of A Down", true, "spotify:track:0snQkGI5qnAmohLE7jTsTn")
    )

    companion object {
        const val LIKED_SONGS_ID = "liked_songs_virtual"

        // Fix #10 — Retrofit singleton condiviso da tutte le istanze di MusicRepository
        private val apiService: SpotifyApiService by lazy {
            Retrofit.Builder()
                .baseUrl("https://api.spotify.com/v1/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SpotifyApiService::class.java)
        }
    }
}