package com.train.ipodclassicemulator.data.repository

import android.util.Log
import com.train.ipodclassicemulator.data.model.PlaylistItem
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.data.remote.SpotifyApiService
import com.train.ipodclassicemulator.data.remote.SpotifyManager
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MusicRepository(private val spotifyManager: SpotifyManager) {

    // Configurazione al volo di Retrofit per parlare con le API di Spotify
    private val apiService: SpotifyApiService = Retrofit.Builder()
        .baseUrl("https://api.spotify.com/v1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SpotifyApiService::class.java)

    // Questo conterrà il token web finale una volta scambiato il codice
    private var webAccessToken: String? = null

    // 🟢 Callback per avvisare la MainActivity che il token è scaduto e serve rifare il login
    var onTokenExpired: (() -> Unit)? = null

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
            handleAuthFailure(e)
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
            handleAuthFailure(e)
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

    fun play(trackUri: String, contextUri: String? = null, trackIndex: Int = 0) {
        val playerApi = spotifyManager.spotifyAppRemote?.playerApi

        // Se c'è un contesto playlist valido e non è la cartella virtuale
        if (!contextUri.isNullOrEmpty() && !contextUri.contains("collection")) {
            try {
                playerApi?.play(contextUri)
                playerApi?.skipToIndex(contextUri, trackIndex)
                Log.d("MusicRepository", "Avviata playlist di contesto: $contextUri all'indice: $trackIndex")
            } catch (e: Exception) {
                Log.e("MusicRepository", "Errore skipToIndex, uso fallback traccia", e)
                playerApi?.play(trackUri)
            }
        } else {
            // 🟢 Brano singolo directo (usato per i preferiti gestiti in locale dall'iPod)
            playerApi?.play(trackUri)
        }
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
            handleAuthFailure(e)
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
            handleAuthFailure(e)
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
            handleAuthFailure(e)
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
            handleAuthFailure(e)
            false
        }
    }

    suspend fun getSavedTracks(): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            // Chiamiamo l'endpoint che hai già implementato nel tuo SpotifyApiService
            val response = apiService.getSavedTracks(bearerToken = "Bearer $token")
            // Estraiamo l'oggetto track da ogni elemento salvato
            response.items.map { it.track }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore nel recupero dei brani preferiti", e)
            handleAuthFailure(e)
            emptyList()
        }
    }

    // 🟢 FUNZIONE CENTRALIZZATA PER GESTIRE IL TOKEN SCADUTO (HTTP 401)
    private fun handleAuthFailure(exception: Exception) {
        if (exception is HttpException && exception.code() == 401) {
            Log.w("MusicRepository", "Rilevato errore HTTP 401: Token scaduto o non valido! Pulisco i dati...")

            // 1. Puliamo il token locale di Retrofit
            webAccessToken = null

            // 2. Puliamo il token persistente salvato in SharedPreferences tramite lo SpotifyManager
            spotifyManager.accessToken = null

            // 3. Notifichiamo l'interfaccia/MainActivity per forzare il refresh o mostrare il login
            onTokenExpired?.invoke()
        }
    }

    // 🟢 Scarica gli Album completi dalla libreria dell'utente
    suspend fun getUserSavedAlbums(): List<com.train.ipodclassicemulator.data.model.SpotifyAlbumDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            val response = apiService.getSavedAlbums(bearerToken = "Bearer $token")
            // Nel tuo file SpotifyResponses, getSavedAlbums restituisce una lista di SpotifyAlbumItem,
            // ognuno dei quali contiene una proprietà "album" di tipo SpotifyAlbumDetails!
            response.items.map { it.album }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore scaricamento album", e)
            handleAuthFailure(e)
            emptyList()
        }
    }

    // 🟢 Scarica gli Artisti completi seguiti dall'utente
    suspend fun getUserFollowedArtists(): List<com.train.ipodclassicemulator.data.model.SpotifyArtistDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            val response = apiService.getFollowedArtists(bearerToken = "Bearer $token")
            // Spotify racchiude la lista dentro l'oggetto "artists"
            response.artists.items
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore scaricamento artisti", e)
            handleAuthFailure(e)
            emptyList()
        }
    }

    // 🟢 Novità: Scarica le canzoni di un album convertendole nel formato supportato dall'app
    suspend fun getTracksForAlbum(albumId: String): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            val response = apiService.getAlbumTracks(bearerToken = "Bearer $token", albumId = albumId)
            response.items.map { simplified ->
                SpotifyTrackDetails(
                    id = simplified.id,
                    name = simplified.name,
                    uri = simplified.uri,
                    artists = simplified.artists
                )
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore scaricamento brani dell'album", e)
            handleAuthFailure(e)
            emptyList()
        }
    }

    suspend fun getTracksForArtist(artistId: String): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
        return try {
            val response = apiService.getArtistTopTracks(bearerToken = "Bearer $token", artistId = artistId)
            response.tracks.map { track ->
                SpotifyTrackDetails(
                    id = track.id,
                    name = track.name,
                    uri = track.uri,
                    artists = track.artists
                )
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore scaricamento brani artista", e)
            handleAuthFailure(e)
            emptyList()
        }
    }

    suspend fun searchSpotifyTracks(query: String): List<SpotifyTrackDetails> {
        val token = webAccessToken ?: return emptyList()
        if (query.trim().isEmpty()) return emptyList()
        return try {
            val response = apiService.searchTracks(bearerToken = "Bearer $token", query = query)
            response.tracks.items
        } catch (e: Exception) {
            Log.e("MusicRepository", "Errore durante la ricerca dei brani", e)
            handleAuthFailure(e)
            emptyList()
        }
    }

    companion object {
        const val LIKED_SONGS_ID = "liked_songs_virtual"
    }
}