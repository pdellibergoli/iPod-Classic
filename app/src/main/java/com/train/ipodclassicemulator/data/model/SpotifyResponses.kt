package com.train.ipodclassicemulator.data.model

// Modello per ricevere il Token di Accesso Web definitivo
data class SpotifyTokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int,
    val refresh_token: String?
)

// Modello per la lista delle Playlist
data class SpotifyUserPlaylistsResponse(
    val items: List<PlaylistItem>,
    val next: String? = null
)

data class PlaylistItem(
    val id: String,
    val name: String,
    val uri: String,
    val tracks: PlaylistTracksInfo,
    val images: List<SpotifyImage>?
)

data class PlaylistTracksInfo(
    val href: String,
    val total: Int
)

// Modello per ricevere i brani di una specifica playlist
data class SpotifyPlaylistTracksResponse(
    val items: List<PlaylistTrackItem>,
    val next: String? = null
)

data class PlaylistTrackItem(
    val track: SpotifyTrackDetails?
)

data class SpotifyTrackDetails(
    val id: String,
    val name: String,
    val uri: String,
    val artists: List<SpotifyArtistInfo>,
    val album: SpotifyAlbumModelInfo? = null
)

data class SpotifyArtistInfo(
    val name: String
)

// Modello per la lista "Brani che mi piacciono" (Liked Songs)
data class SpotifySavedTracksResponse(
    val items: List<SavedTrackItem>,
    val next: String? = null
)

data class SavedTrackItem(
    val track: SpotifyTrackDetails
)

data class SpotifySavedAlbumsResponse(
    val items: List<SpotifyAlbumItem>,
    val next: String? = null
)

data class SpotifyAlbumItem(
    val album: SpotifyAlbumDetails
)

data class SpotifyImage(
    val url: String,
    val height: Int?,
    val width: Int?
)

data class SpotifyAlbumDetails(
    val id: String,
    val name: String,
    val uri: String,
    val images: List<SpotifyImage>?,
    val artists: List<SpotifyArtistInfo>?
)

// 📦 MODELLI WRAPPER PER GLI ARTISTI SEGUITI
data class SpotifyFollowedArtistsResponse(
    val artists: SpotifyArtistsContainer
)

data class SpotifyArtistsContainer(
    val items: List<SpotifyArtistDetails>,
    val next: String? = null,
    val cursors: SpotifyArtistCursors? = null
)

data class SpotifyArtistCursors(
    val after: String? = null
)

data class SpotifyArtistDetails(
    val id: String,
    val name: String,
    val uri: String,
    val images: List<SpotifyImage>?
)

data class SpotifyArtistTopTracksResponse(
    val tracks: List<SpotifyArtistTrackModel>
)

data class SpotifyArtistTrackModel(
    val id: String,
    val name: String,
    val uri: String,
    val artists: List<com.train.ipodclassicemulator.data.model.SpotifyArtistInfo>,
    val album: SpotifyAlbumModelInfo?
)

// 📦 MODELLI DI RISPOSTA PER GLI ALBUM
data class SpotifySavedAlbumItem(val album: SpotifyAlbumModelInfo)
data class SpotifyAlbumModelInfo(
    val id: String,
    val name: String,
    val uri: String,
    val images: List<SpotifyImage>? = null
)

// 📦 MODELLI DI RISPOSTA PER GLI ARTISTI
data class SpotifyArtistModelInfo(
    val id: String,
    val name: String,
    val uri: String,
    val images: List<SpotifyImage>? = null
)

// 📦 MODELLI DI RISPOSTA PER LE TRACCE SEMPLIFICATE DELL'ALBUM
data class SpotifyAlbumTracksResponse(val items: List<SpotifySimplifiedTrack>)
data class SpotifySimplifiedTrack(
    val id: String,
    val name: String,
    val uri: String,
    val artists: List<com.train.ipodclassicemulator.data.model.SpotifyArtistInfo>
)

/**
 * Risultati di una ricerca: le tre sezioni che mostriamo nel menu.
 * Ogni lista può essere vuota (Spotify restituisce solo i tipi richiesti
 * e non trova sempre tutto).
 */
data class SearchResults(
    val tracks: List<SpotifyTrackDetails>,
    val artists: List<SpotifyArtistDetails>,
    val albums: List<SpotifyAlbumDetails>
)

data class SpotifySearchResponse(
    val tracks: SpotifySearchTracksContainer? = null,
    val artists: SpotifySearchArtistsContainer? = null,
    val albums: SpotifySearchAlbumsContainer? = null
)

data class SpotifySearchTracksContainer(val items: List<SpotifyTrackDetails>)
data class SpotifySearchArtistsContainer(val items: List<SpotifyArtistDetails>)
data class SpotifySearchAlbumsContainer(val items: List<SpotifyAlbumDetails>)

// ── CRONOLOGIA — GET me/player/recently-played ────────────────────────────────

data class SpotifyRecentlyPlayedResponse(val items: List<RecentlyPlayedItem>)

data class RecentlyPlayedItem(val track: SpotifyTrackDetails?)

// ── CODA — GET me/player/queue ────────────────────────────────────────────────

/**
 * `currently_playing` e `queue` possono contenere anche episodi (podcast):
 * vengono filtrati a valle, perché [SpotifyTrackDetails] regge solo le tracce.
 */
data class SpotifyQueueResponse(
    val currently_playing: SpotifyTrackDetails? = null,
    val queue: List<SpotifyTrackDetails?>? = null
)