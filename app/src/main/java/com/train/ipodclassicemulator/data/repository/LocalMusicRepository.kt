package com.train.ipodclassicemulator.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

data class LocalTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val folderPath: String,   // nome cartella (ultima parte del path)
    val durationMs: Long,
    val contentUri: Uri,
    val albumArtUri: Uri?
)

object LocalMusicRepository {

    fun loadTracks(context: Context): List<LocalTrack> {
        val tracks = mutableListOf<LocalTrack>()

        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA          // path completo per estrarre la cartella
        )

        val selection  = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder  = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(
                collection, projection, selection, null, sortOrder
            )?.use { cursor ->
                val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id      = cursor.getLong(idCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val data    = cursor.getString(dataCol) ?: ""
                    val folder  = data.substringBeforeLast("/").substringAfterLast("/")

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    )
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), albumId
                    )
                    tracks.add(
                        LocalTrack(
                            id         = id,
                            title      = cursor.getString(titleCol) ?: "Sconosciuto",
                            artist     = cursor.getString(artistCol) ?: "Artista sconosciuto",
                            album      = cursor.getString(albumCol)  ?: "",
                            folderPath = folder.ifBlank { "Altro" },
                            durationMs = cursor.getLong(durationCol),
                            contentUri = contentUri,
                            albumArtUri = albumArtUri
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("LocalMusicRepository", "Errore lettura MediaStore", e)
        }

        Log.d("LocalMusicRepository", "Trovati ${tracks.size} brani locali")
        return tracks
    }

    /** Restituisce le cartelle distinte ordinate alfabeticamente */
    fun folders(tracks: List<LocalTrack>): List<String> =
        tracks.map { it.folderPath }.distinct().sorted()

    /** Restituisce gli album distinti ordinati alfabeticamente */
    fun albums(tracks: List<LocalTrack>): List<String> =
        tracks.map { it.album }.filter { it.isNotBlank() }.distinct().sorted()

    /** Restituisce gli artisti distinti ordinati alfabeticamente */
    fun artists(tracks: List<LocalTrack>): List<String> =
        tracks.map { it.artist }.distinct().sorted()
}
