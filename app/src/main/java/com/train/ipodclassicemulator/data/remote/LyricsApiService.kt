package com.train.ipodclassicemulator.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/** Una riga di lyrics sincronizzata: tempo assoluto in ms dall'inizio del brano. */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/** Voce restituita da LRCLIB: un brano con le sue lyrics. */
data class LyricsEntry(
    @SerializedName("trackName") val trackName: String? = null,
    @SerializedName("artistName") val artistName: String? = null,
    @SerializedName("albumName") val albumName: String? = null,
    @SerializedName("duration") val duration: Double? = null,
    @SerializedName("instrumental") val instrumental: Boolean? = null,
    @SerializedName("plainLyrics") val plainLyrics: String? = null,
    @SerializedName("syncedLyrics") val syncedLyrics: String? = null
)

/**
 * lrclib.net — database aperto e gratuito di lyrics sincronizzate.
 *
 * Spotify **non** espone le lyrics dalla Web API (le sue arrivano da Musixmatch
 * e restano solo all'interno dell'app ufficiale), quindi questa è l'unica strada
 * per avere le righe con i timestamp senza chiavi API né licenze commerciali.
 *
 * Si usa `search` e non `get`: l'endpoint di match esatto richiede che titolo,
 * artista, album e durata coincidano al secondo, e l'album quasi mai combacia
 * tra la versione in archivio e quella restituita da Spotify.
 */
interface LyricsApiService {

    @GET("api/search")
    suspend fun search(
        @Query("q") query: String,
        @Header("User-Agent") userAgent: String
    ): List<LyricsEntry>

    companion object {
        const val BASE_URL = "https://lrclib.net/"

        /** LRCLIB chiede di identificare il client invece di usare l'UA di OkHttp. */
        const val USER_AGENT = "iPodClassicEmulator/1.0 (Android; open source)"
    }
}
