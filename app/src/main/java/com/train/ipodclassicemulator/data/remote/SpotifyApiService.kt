package com.train.ipodclassicemulator.data.remote

import com.train.ipodclassicemulator.data.model.SpotifyAlbumTracksResponse
import com.train.ipodclassicemulator.data.model.SpotifyArtistTopTracksResponse
import com.train.ipodclassicemulator.data.model.SpotifyFollowedArtistsResponse
import com.train.ipodclassicemulator.data.model.SpotifyPlaylistTracksResponse
import com.train.ipodclassicemulator.data.model.SpotifyQueueResponse
import com.train.ipodclassicemulator.data.model.SpotifyRecentlyPlayedResponse
import com.train.ipodclassicemulator.data.model.SpotifySavedAlbumsResponse
import com.train.ipodclassicemulator.data.model.SpotifySavedTracksResponse
import com.train.ipodclassicemulator.data.model.SpotifySearchResponse
import com.train.ipodclassicemulator.data.model.SpotifyTokenResponse
import com.train.ipodclassicemulator.data.model.SpotifyUserPlaylistsResponse
import retrofit2.Response
import retrofit2.http.*

interface SpotifyApiService {

    @FormUrlEncoded
    @POST("https://accounts.spotify.com/api/token")
    suspend fun getAccessTokenPkce(
        @Field("client_id") clientId: String,
        @Field("grant_type") grantType: String,
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("code_verifier") codeVerifier: String
    ): SpotifyTokenResponse

    /**
     * Rinnova l'access token in silenzio usando il refresh token.
     * Nessun intervento dell'utente: evita di ricadere nel browser
     * ogni volta che l'access token scade (dopo ~1 ora).
     */
    @FormUrlEncoded
    @POST("https://accounts.spotify.com/api/token")
    suspend fun refreshAccessToken(
        @Field("client_id") clientId: String,
        @Field("grant_type") grantType: String,
        @Field("refresh_token") refreshToken: String
    ): SpotifyTokenResponse

    // Fix #2 — offset + limit per paginazione
    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Header("Authorization") bearer: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): SpotifyUserPlaylistsResponse

    // Fix #2 — offset + limit per paginazione
    @GET("playlists/{id}/tracks")
    suspend fun getPlaylistTracks(
        @Header("Authorization") bearer: String,
        @Path("id") playlistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): SpotifyPlaylistTracksResponse

    // Fix #2 — offset + limit per paginazione
    @GET("me/tracks")
    suspend fun getSavedTracks(
        @Header("Authorization") bearer: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): SpotifySavedTracksResponse

    @GET("me/tracks/contains")
    suspend fun checkTracksSaved(
        @Header("Authorization") bearer: String,
        @Query("ids") trackIds: String
    ): List<Boolean>

    @PUT("me/tracks")
    suspend fun saveTrack(
        @Header("Authorization") bearer: String,
        @Query("ids") trackIds: String
    ): Response<Unit>

    @DELETE("me/tracks")
    suspend fun removeTrack(
        @Header("Authorization") bearer: String,
        @Query("ids") trackIds: String
    ): Response<Unit>

    // Fix #2 — offset + limit per paginazione
    @GET("me/albums")
    suspend fun getSavedAlbums(
        @Header("Authorization") bearer: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): SpotifySavedAlbumsResponse

    // Fix #2 — cursor-based pagination (after) per followed artists
    @GET("me/following")
    suspend fun getFollowedArtists(
        @Header("Authorization") bearer: String,
        @Query("type") type: String = "artist",
        @Query("limit") limit: Int = 50,
        @Query("after") after: String? = null
    ): SpotifyFollowedArtistsResponse

    @GET("albums/{id}/tracks")
    suspend fun getAlbumTracks(
        @Header("Authorization") bearer: String,
        @Path("id") albumId: String,
        @Query("limit") limit: Int = 50
    ): SpotifyAlbumTracksResponse

    @GET("artists/{id}/top-tracks")
    suspend fun getArtistTopTracks(
        @Header("Authorization") bearer: String,
        @Path("id") artistId: String,
        @Query("market") market: String = "IT"
    ): SpotifyArtistTopTracksResponse

    /** Cerca brani, artisti e album in un colpo solo. */
    @GET("search")
    suspend fun search(
        @Header("Authorization") bearer: String,
        @Query("q") query: String,
        @Query("type") type: String = "track,artist,album",
        @Query("market") market: String = "IT",
        @Query("limit") limit: Int = 30
    ): SpotifySearchResponse

    @PUT("me/player/shuffle")
    suspend fun setWebShuffleMode(
        @Header("Authorization") bearer: String,
        @Query("state") state: Boolean,
        @Query("device_id") deviceId: String? = null
    ): Response<Unit>

    /** Ultime tracce riprodotte. Richiede `user-read-recently-played`. */
    @GET("me/player/recently-played")
    suspend fun getRecentlyPlayed(
        @Header("Authorization") bearer: String,
        @Query("limit") limit: Int = 50
    ): SpotifyRecentlyPlayedResponse

    /** Coda di riproduzione. Richiede `user-read-playback-state`. */
    @GET("me/player/queue")
    suspend fun getPlayerQueue(
        @Header("Authorization") bearer: String
    ): SpotifyQueueResponse

    /** Aggiunge una traccia in coda a una playlist. Richiede `playlist-modify-*`. */
    @POST("playlists/{id}/tracks")
    suspend fun addTracksToPlaylist(
        @Header("Authorization") bearer: String,
        @Path("id") playlistId: String,
        @Body body: AddTracksBody
    ): Response<Unit>
}

/** Corpo di `POST /playlists/{id}/tracks`. */
data class AddTracksBody(val uris: List<String>)
