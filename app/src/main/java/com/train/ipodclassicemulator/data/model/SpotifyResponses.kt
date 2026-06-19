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