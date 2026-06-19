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
    val items: List<PlaylistItem>
)

data class PlaylistItem(
    val id: String,
    val name: String,
    val uri: String,
    val tracks: PlaylistTracksInfo
)

data class PlaylistTracksInfo(
    val href: String,
    val total: Int
)

// Modello per ricevere i brani di una specifica playlist
data class SpotifyPlaylistTracksResponse(
    val items: List<PlaylistTrackItem>
)

data class PlaylistTrackItem(
    val track: SpotifyTrackDetails
)

data class SpotifyTrackDetails(
    val id: String,
    val name: String,
    val uri: String,
    val artists: List<SpotifyArtistInfo>
)

data class SpotifyArtistInfo(
    val name: String
)

// Modello per la lista "Brani che mi piacciono" (Liked Songs)
data class SpotifySavedTracksResponse(
    val items: List<SavedTrackItem>
)

data class SavedTrackItem(
    val track: SpotifyTrackDetails
)

data class SpotifySavedAlbumsResponse(
    val items: List<SpotifyAlbumItem>
)

data class SpotifyAlbumItem(
    val album: SpotifyAlbumDetails
)

data class SpotifyAlbumDetails(
    val id: String,
    val name: String,
    val uri: String
)

// 📦 MODELLI WRAPPER PER GLI ARTISTI SEGUITI
data class SpotifyFollowedArtistsResponse(
    val artists: SpotifyArtistsContainer
)

data class SpotifyArtistsContainer(
    val items: List<SpotifyArtistDetails>
)

data class SpotifyArtistDetails(
    val id: String,
    val name: String,
    val uri: String
)

data class SpotifyArtistTopTracksResponse(
    val tracks: List<SpotifyArtistTrackModel>
)

data class SpotifyArtistTrackModel(
    val id: String,
    val name: String,
    val uri: String,
    val artists: List<com.train.ipodclassicemulator.data.model.SpotifyArtistInfo>
)

// 📦 MODELLI DI RISPOSTA PER GLI ALBUM
data class SpotifySavedAlbumItem(val album: SpotifyAlbumModelInfo)
data class SpotifyAlbumModelInfo(val id: String, val name: String, val uri: String)

// 📦 MODELLI DI RISPOSTA PER GLI ARTISTI
data class SpotifyArtistModelInfo(val id: String, val name: String, val uri: String)

// 📦 MODELLI DI RISPOSTA PER LE TRACCE SEMPLIFICATE DELL'ALBUM
data class SpotifyAlbumTracksResponse(val items: List<SpotifySimplifiedTrack>)
data class SpotifySimplifiedTrack(
    val id: String,
    val name: String,
    val uri: String,
    val artists: List<com.train.ipodclassicemulator.data.model.SpotifyArtistInfo>
)

data class SpotifySearchResponse(
    val tracks: SpotifySearchTracksContainer
)

data class SpotifySearchTracksContainer(
    val items: List<com.train.ipodclassicemulator.data.model.SpotifyTrackDetails>
)