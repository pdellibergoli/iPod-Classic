package com.train.ipodclassicemulator.data.remote

import com.train.ipodclassicemulator.data.model.SpotifyAlbumTracksResponse
import com.train.ipodclassicemulator.data.model.SpotifyArtistTopTracksResponse
import com.train.ipodclassicemulator.data.model.SpotifyFollowedArtistsResponse
import com.train.ipodclassicemulator.data.model.SpotifyPlaylistTracksResponse
import com.train.ipodclassicemulator.data.model.SpotifySavedAlbumsResponse
import com.train.ipodclassicemulator.data.model.SpotifySavedTracksResponse
import com.train.ipodclassicemulator.data.model.SpotifySearchResponse
import com.train.ipodclassicemulator.data.model.SpotifyTokenResponse
import com.train.ipodclassicemulator.data.model.SpotifyUserPlaylistsResponse
import retrofit2.Response
import retrofit2.http.*

interface SpotifyApiService {

    // 🟢 L'autenticazione usa l'endpoint completo dell'Accounts Service di Spotify
    @FormUrlEncoded
    @POST("https://accounts.spotify.com/api/token")
    suspend fun getAccessToken(
        @Header("Authorization") basicAuth: String,
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String
    ): SpotifyTokenResponse

    // 🟢 Tutti gli endpoint successivi usano URL relativi puliti che si agganciano al baseUrl reale
    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Header("Authorization") bearerToken: String
    ): SpotifyUserPlaylistsResponse

    @GET("playlists/{playlist_id}/tracks")
    suspend fun getPlaylistTracks(
        @Header("Authorization") bearerToken: String,
        @Path("playlist_id") playlistId: String
    ): SpotifyPlaylistTracksResponse

    @GET("me/tracks")
    suspend fun getSavedTracks(
        @Header("Authorization") bearerToken: String,
        @Query("limit") limit: Int = 50
    ): SpotifySavedTracksResponse

    @GET("me/tracks/contains")
    suspend fun checkTracksSaved(
        @Header("Authorization") bearerToken: String,
        @Query("ids") trackIds: String
    ): List<Boolean>

    @PUT("me/tracks")
    suspend fun saveTrack(
        @Header("Authorization") bearerToken: String,
        @Query("ids") trackIds: String
    ): Response<Unit>

    @DELETE("me/tracks")
    suspend fun removeTrack(
        @Header("Authorization") bearerToken: String,
        @Query("ids") trackIds: String
    ): Response<Unit>

    @GET("me/albums")
    suspend fun getSavedAlbums(
        @Header("Authorization") bearerToken: String,
        @Query("limit") limit: Int = 30
    ): SpotifySavedAlbumsResponse

    @GET("v1/me/following?type=artist")
    suspend fun getFollowedArtists(
        @Header("Authorization") bearerToken: String,
        @Query("limit") limit: Int = 30
    ): SpotifyFollowedArtistsResponse

    @GET("albums/{id}/tracks")
    suspend fun getAlbumTracks(
        @Header("Authorization") bearerToken: String,
        @Path("id") albumId: String,
        @Query("limit") limit: Int = 50
    ): SpotifyAlbumTracksResponse

    @GET("artists/{id}/top-tracks")
    suspend fun getArtistTopTracks(
        @Header("Authorization") bearerToken: String,
        @Path("id") artistId: String,
        @Query("market") market: String = "IT"
    ): SpotifyArtistTopTracksResponse

    @GET("v1/search")
    suspend fun searchTracks(
        @Header("Authorization") bearerToken: String,
        @Query("q") query: String,
        @Query("type") type: String = "track",
        @Query("market") market: String = "IT",
        @Query("limit") limit: Int = 30
    ): SpotifySearchResponse
}
