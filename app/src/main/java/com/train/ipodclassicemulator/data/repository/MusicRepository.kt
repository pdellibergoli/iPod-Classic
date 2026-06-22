package com.train.ipodclassicemulator.data.repository

import android.util.Log
import com.train.ipodclassicemulator.data.model.PlaylistItem
import com.train.ipodclassicemulator.data.model.SpotifyAlbumDetails
import com.train.ipodclassicemulator.data.model.SpotifyAlbumModelInfo
import com.train.ipodclassicemulator.data.model.SpotifyArtistDetails
import com.train.ipodclassicemulator.data.model.SpotifyImage
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.data.remote.SpotifyApiService
import com.train.ipodclassicemulator.data.remote.SpotifyManager
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MusicRepository(private val spotifyManager: SpotifyManager) {

    private val apiService: SpotifyApiService = Retrofit.Builder()
        .baseUrl("https://api.spotify.com/v1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SpotifyApiService::class.java)

    /**
     * Web Access Token in memoria per la sessione corrente.
     * All'avvio viene precaricato da SharedPreferences se disponibile,
     * così non serve riscambiare il codice OAuth ad ogni apertura.
     */
    private var webAccessToken: String? = spotifyManager.savedWebToken

    var onTokenExpired: (() -> Unit)? = null

    // ── AUTH ─────────────────────────────────────────────────────────────────

    /**
     * Scambia il codice OAuth monouso con il web token Bearer reale.
     * Salva il token in SharedPreferences e cancella il codice usato.
     */
    suspend fun fetchWebToken(authCode: String): Boolean {
        return try {
            val response = apiService.getAccessToken(
                basicAuth = spotifyManager.getClientHeader(),
                code = authCode,
                redirectUri = spotifyManager.redirectUri
            )
            webAccessToken = response.access_token
            // Persiste il token Bearer tra le sessioni
            spotifyManager.savedWebToken = response.access_token
            // Cancella il codice OAuth monouso: non serve più
            spotifyManager.pendingAuthCode = null
            Log.d("MusicRepository", "Web token ottenuto e salvato.")
            true
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore scambio token", e)
            handleAuthFailure(e)
            false
        }
    }

    /** true se abbiamo già un web token valido (non serve riscambiare il codice) */
    fun hasValidToken(): Boolean = webAccessToken != null

    // ── PLAYLIST ──────────────────────────────────────────────────────────────

    suspend fun getUserPlaylists(): List<PlaylistItem> {
        val token = webAccessToken ?: return emptyList()
        return try {
            apiService.getUserPlaylists("Bearer $token").items
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore playlist", e); handleAuthFailure(e); emptyList()
        }
    }

    suspend fun getTracksForPlaylist(playlistId: String): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            apiService.getPlaylistTracks("Bearer $token", playlistId).items.map { it.track }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore brani playlist", e); handleAuthFailure(e); emptyList()
        }
    }

    // ── LIKED SONGS ───────────────────────────────────────────────────────────

    suspend fun getSavedTracks(): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            apiService.getSavedTracks("Bearer $token").items.map { it.track }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore liked songs", e); handleAuthFailure(e); emptyList()
        }
    }

    suspend fun setTrackSaved(trackId: String, saved: Boolean): Boolean {
        val token = webAccessToken ?: return false
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

    suspend fun getUserSavedAlbums(): List<SpotifyAlbumDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            apiService.getSavedAlbums("Bearer $token").items.map { it.album }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore album", e); handleAuthFailure(e); emptyList()
        }
    }

    suspend fun getTracksForAlbum(
        albumId: String,
        albumImages: List<SpotifyImage>? = null
    ): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
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

    suspend fun getUserFollowedArtists(): List<SpotifyArtistDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            apiService.getFollowedArtists("Bearer $token").artists.items
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore artisti", e); handleAuthFailure(e); emptyList()
        }
    }

    suspend fun getTracksForArtist(
        artistId: String,
        artistImages: List<SpotifyImage>? = null
    ): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
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

    suspend fun searchSpotifyTracks(query: String): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return try {
            apiService.searchTracks("Bearer $token", query).tracks.items
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore ricerca", e); handleAuthFailure(e); emptyList()
        }
    }

    // ── PLAYBACK ──────────────────────────────────────────────────────────────

    fun play(trackUri: String, contextUri: String? = null, trackIndex: Int = 0) {
        val playerApi = spotifyManager.spotifyAppRemote?.playerApi
        if (!contextUri.isNullOrEmpty()) {
            try {
                playerApi?.play(contextUri)
                playerApi?.skipToIndex(contextUri, trackIndex)
            } catch (e: Exception) {
                Log.e("MusicRepository", "Fallback a traccia singola", e)
                playerApi?.play(trackUri)
            }
        } else {
            playerApi?.play(trackUri)
        }
    }

    // ── GESTIONE ERRORI AUTH ──────────────────────────────────────────────────

    private fun handleAuthFailure(e: Exception) {
        if (e is HttpException && e.code() == 401) {
            Log.w("MusicRepository", "HTTP 401 — token scaduto, cancello tutto.")
            webAccessToken = null
            spotifyManager.clearAllTokens()
            onTokenExpired?.invoke()
        }
    }

    // 🟢 Controlla se un brano specifico è tra i preferiti su Spotify
    suspend fun isTrackSaved(trackId: String): Boolean {
        val token = webAccessToken ?: return false
        return try {
            val response = apiService.checkTracksSaved(bearer = "Bearer $token", trackIds = trackId)
            response.firstOrNull() ?: false
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore check preferiti", e)
            false
        }
    }

    // 🟢 Aggiunge il brano ai preferiti
    suspend fun saveSpotifyTrack(trackId: String): Boolean {
        val token = webAccessToken ?: return false
        return try {
            val response = apiService.saveTrack(bearer = "Bearer $token", trackIds = trackId)
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // 🟢 Rimuove il brano dai preferiti
    suspend fun removeSpotifyTrack(trackId: String): Boolean {
        val token = webAccessToken ?: return false
        return try {
            val response = apiService.removeTrack(bearer = "Bearer $token", trackIds = trackId)
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // 🟢 Invia il comando di Shuffle avanzato (Standard o Smart) direttamente ai server web di Spotify
    suspend fun setSpotifyShuffleMode(enabled: Boolean): Boolean {
        val token = webAccessToken ?: return false
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
    }
}