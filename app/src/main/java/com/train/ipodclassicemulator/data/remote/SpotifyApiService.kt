package com.train.ipodclassicemulator.data.remote

import com.train.ipodclassicemulator.data.model.SpotifyPlaylistTracksResponse
import com.train.ipodclassicemulator.data.model.SpotifySavedTracksResponse
import com.train.ipodclassicemulator.data.model.SpotifyTokenResponse
import com.train.ipodclassicemulator.data.model.SpotifyUserPlaylistsResponse
import retrofit2.Response
import retrofit2.http.*

interface SpotifyApiService {

    // 🚀 URL ufficiale per scambiare il Code con il Token definitivo
    @FormUrlEncoded
    @POST("https://accounts.spotify.com/api/token")
    suspend fun getAccessToken(
        @Header("Authorization") basicAuth: String,
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String
    ): SpotifyTokenResponse

    // 🚀 URL ufficiale per scaricare le playlist reali dell'utente
    @GET("https://api.spotify.com/v1/me/playlists")
    suspend fun getUserPlaylists(
        @Header("Authorization") bearerToken: String
    ): SpotifyUserPlaylistsResponse

    // 🚀 Recupera i brani contenuti in una specifica playlist
    @GET("https://api.spotify.com/v1/playlists/{playlist_id}/tracks")
    suspend fun getPlaylistTracks(
        @Header("Authorization") bearerToken: String,
        @Path("playlist_id") playlistId: String
    ): SpotifyPlaylistTracksResponse

    // 💜 Recupera i "Brani che mi piacciono" (Liked Songs)
    @GET("https://api.spotify.com/v1/me/tracks")
    suspend fun getSavedTracks(
        @Header("Authorization") bearerToken: String,
        @Query("limit") limit: Int = 50
    ): SpotifySavedTracksResponse

    // ❤️ Controlla se uno o più brani sono già nei preferiti
    @GET("https://api.spotify.com/v1/me/tracks/contains")
    suspend fun checkTracksSaved(
        @Header("Authorization") bearerToken: String,
        @Query("ids") trackIds: String
    ): List<Boolean>

    // ❤️ Aggiunge un brano ai preferiti
    @PUT("https://api.spotify.com/v1/me/tracks")
    suspend fun saveTrack(
        @Header("Authorization") bearerToken: String,
        @Query("ids") trackIds: String
    ): Response<Unit>

    // 💔 Rimuove un brano dai preferiti
    @DELETE("https://api.spotify.com/v1/me/tracks")
    suspend fun removeTrack(
        @Header("Authorization") bearerToken: String,
        @Query("ids") trackIds: String
    ): Response<Unit>
}