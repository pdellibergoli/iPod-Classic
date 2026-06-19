package com.train.ipodclassicemulator.data.repository

import android.util.Log
import com.train.ipodclassicemulator.data.model.PlaylistItem
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.data.remote.SpotifyApiService
import com.train.ipodclassicemulator.data.remote.SpotifyManager
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MusicRepository(private val spotifyManager: SpotifyManager) {

    // Configurazione al volo di Retrofit per parlare con le API di Spotify
    private val apiService: SpotifyApiService = Retrofit.Builder()
        .baseUrl("https://api.spotify.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SpotifyApiService::class.java)

    // Questo conterrà il token web finale una volta scambiato il codice
    private var webAccessToken: String? = null

    // 🚀 Usa finalmente "getClientHeader" per scambiare il codice provvisorio con il Token Reale
    suspend fun fetchWebToken(authCode: String): Boolean {
        return try {
            val response = apiService.getAccessToken(
                basicAuth = spotifyManager.getClientHeader(), // <-- Eccola usata qui!
                code = authCode,
                redirectUri = spotifyManager.redirectUri
            )
            webAccessToken = response.access_token
            Log.d("MusicRepository", "Web Access Token recuperato con successo!")
            true
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore durante il recupero del Web Token", e)
            false
        }
    }

    // Recupera le playlist reali dal tuo account Spotify
    suspend fun getUserPlaylists(): List<PlaylistItem> {
        val token = webAccessToken ?: return emptyList()
        return try {
            val response = apiService.getUserPlaylists(bearerToken = "Bearer $token")
            response.items
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore durante il recupero delle playlist", e)
            emptyList()
        }
    }

    // Vecchio metodo di fallback per i test, lo teniamo per sicurezza
    fun getTestSpotifyTracks(): List<TrackModel> {
        return listOf(
            TrackModel("1", "Toxicity", "System Of A Down", true, "spotify:track:0snQkGI5qnAmohLE7jTsTn"),
            TrackModel("2", "Holding Back teh Years", "Simply Red", true, "spotify:track:1yg7fwwYmx9DQ2TdXUmfpJ")
        )
    }

    fun play(trackUri: String) {
        spotifyManager.playSpotifyUri(trackUri)
        spotifyManager.resumePlayback()
    }

    suspend fun getTracksForPlaylist(playlistId: String): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            val response = apiService.getPlaylistTracks(
                bearerToken = "Bearer $token",
                playlistId = playlistId
            )
            // Estraiamo solo i dettagli della traccia saltando gli oggetti wrapper
            response.items.map { it.track }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore scaricamento brani playlist", e)
            emptyList()
        }
    }

    // 💜 Recupera i "Brani che mi piacciono" (Liked Songs) come fosse una playlist
    suspend fun getLikedSongs(): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            val response = apiService.getSavedTracks(bearerToken = "Bearer $token")
            response.items.map { it.track }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore scaricamento Brani che mi piacciono", e)
            emptyList()
        }
    }

    // ❤️ Controlla se il brano è già nei preferiti dell'utente
    suspend fun isTrackSaved(trackId: String): Boolean {
        val token = webAccessToken ?: return false
        return try {
            val result = apiService.checkTracksSaved(bearerToken = "Bearer $token", trackIds = trackId)
            result.firstOrNull() ?: false
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore controllo brano preferito", e)
            false
        }
    }

    // ❤️ Aggiunge o rimuove davvero il brano dai preferiti. Ritorna true se l'operazione è andata a buon fine.
    suspend fun setTrackSaved(trackId: String, saved: Boolean): Boolean {
        val token = webAccessToken ?: return false
        return try {
            val response = if (saved) {
                apiService.saveTrack(bearerToken = "Bearer $token", trackIds = trackId)
            } else {
                apiService.removeTrack(bearerToken = "Bearer $token", trackIds = trackId)
            }
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore salvataggio/rimozione preferito", e)
            false
        }
    }

    companion object {
        // ID fittizio usato solo lato app per rappresentare la voce
        // "Brani che mi piacciono" nella lista delle playlist.
        const val LIKED_SONGS_ID = "liked_songs_virtual"
    }
}