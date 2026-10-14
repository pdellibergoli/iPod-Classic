package com.train.ipodclassicemulator.data.remote

import android.util.Log
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.Normalizer
import kotlin.math.abs

private const val TAG = "LyricsRepository"

/**
 * Punteggio minimo perché una candidata sia considerata il brano giusto.
 * Con i pesi di [score] servono almeno titolo + artista: nessuna delle due
 * singole basta, così non si attaccano le lyrics sbagliate.
 */
private const val MIN_SCORE = 60

/** Esito della ricerca delle lyrics di un brano. */
sealed class LyricsResult {
    /** Lyrics riga per riga con i timestamp: si può evidenziare la riga corrente. */
    data class Synced(val lines: List<LyricLine>) : LyricsResult()

    /** Solo testo, nessuna sincronizzazione disponibile. */
    data class Plain(val text: String) : LyricsResult()

    /** Nessuna lyrics trovata per questo brano. */
    object None : LyricsResult()
}

/**
 * Scarica le lyrics da LRCLIB e le tiene in cache.
 *
 * Una sola chiamata per brano — i candidati li peschiamo noi in base a titolo,
 * artista e durata.
 */
class LyricsRepository {

    private val apiService: LyricsApiService by lazy {
        Retrofit.Builder()
            .baseUrl(LyricsApiService.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LyricsApiService::class.java)
    }

    /** Access-order così le canzoni che si ascoltano spesso restano in testa. */
    private val cache = object : LinkedHashMap<String, LyricsResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>) =
            size > 50
    }

    /**
     * @param durationMs durata nota dal player; serve solo come bonus di
     *   disambiguazione, non è obbligatoria.
     * @return le lyrics, oppure [LyricsResult.None] se non trovate o la rete
     *   non risponde (mai una eccezione: la schermata deve solo mostrare un testo).
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long
    ): LyricsResult {
        if (title.isBlank()) return LyricsResult.None

        val key = normalize(title) + "|" + normalize(artist)
        synchronized(cache) { cache[key] }?.let { return it }
        val result = try {
            val candidates = apiService.search(
                query = "$title $artist",
                userAgent = LyricsApiService.USER_AGENT
            )
            pick(candidates, title, artist, album, durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "Scaricamento lyrics fallito per \"$title\"", e)
            LyricsResult.None
        }

        synchronized(cache) { cache[key] = result }
        return result
    }

    // ── Selezione del brano giusto ────────────────────────────────────────────

    private fun pick(
        candidates: List<LyricsEntry>,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long
    ): LyricsResult = candidates
        .filterNot { it.instrumental == true }
        .map { it to score(it, title, artist, album, durationMs) }
        .maxByOrNull { it.second }
        ?.takeIf { it.second >= MIN_SCORE }
        ?.first
        .let(::buildResult)

    private fun buildResult(entry: LyricsEntry?): LyricsResult {
        if (entry == null) return LyricsResult.None

        entry.syncedLyrics?.takeIf { it.isNotBlank() }?.let { raw ->
            val lines = parseLrc(raw)
            if (lines.isNotEmpty()) return LyricsResult.Synced(lines)
        }
        entry.plainLyrics?.takeIf { it.isNotBlank() }?.let {
            return LyricsResult.Plain(it)
        }
        return LyricsResult.None
    }

    /**
     * Titolo e artista sono obbligatori tutti e due: senza questo vincolo un
     * brano omonimo con titolo identico e durata coincidente potrebbe vincere
     * attaccando le lyrics di un'altra canzone.
     */
    private fun score(
        entry: LyricsEntry,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long
    ): Int {
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        val gotTitle = normalize(entry.trackName ?: "")
        val gotArtist = normalize(entry.artistName ?: "")

        val titleScore = when {
            gotTitle.isEmpty() -> 0
            gotTitle == wantedTitle -> 50
            // LRCLIB ha molte voci nel formato "Artista - Titolo"
            gotTitle.endsWith(" - $wantedTitle") -> 50
            wantedTitle.isNotEmpty() && (wantedTitle in gotTitle || gotTitle in wantedTitle) -> 30
            else -> 0
        }

        val artistScore = when {
            gotArtist.isEmpty() -> 0
            gotArtist == wantedArtist -> 40
            wantedArtist.isNotEmpty() && (wantedArtist in gotArtist || gotArtist in wantedArtist) -> 40
            else -> 0
        }

        if (titleScore == 0 || artistScore == 0) return 0

        var total = titleScore + artistScore

        if (album != null && entry.albumName != null && normalize(album) == normalize(entry.albumName)) {
            total += 15
        }
        // La durata è il disambiguatore migliore tra monoversioni e compilation
        if (durationMs > 0 && entry.duration != null && abs(entry.duration - durationMs / 1000.0) <= 3.0) {
            total += 25
        }
        // Il sincronizzato è quello che serve: a parità di tutto, preferiamo quello
        if (!entry.syncedLyrics.isNullOrBlank()) total += 20

        return total
    }

    // ── Parsing LRC ───────────────────────────────────────────────────────────

    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    /**
     * Converte il formato LRC in righe con il tempo in ms.
     *
     * Una riga può portare più timestamp (`[00:10.00][01:05.00] testo`): il
     * testo è sempre quello che segue l'ultimo, e viene replicato per ciascuno.
     * Le righe di metadati (`[ar: ...]`) non hanno una durata in secondi e
     * quindi non matchano.
     */
    internal fun parseLrc(raw: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        for (row in raw.lineSequence()) {
            val stamps = timestamp.findAll(row).toList()
            if (stamps.isEmpty()) continue

            val text = row.substring(stamps.last().range.last + 1).trim()
            for (stamp in stamps) {
                val min = stamp.groupValues[1].toLongOrNull() ?: continue
                val sec = stamp.groupValues[2].toLongOrNull() ?: continue
                val frac = stamp.groupValues[3]
                val millis = when (frac.length) {
                    0 -> 0L
                    1 -> (frac.toLongOrNull() ?: 0L) * 100
                    2 -> (frac.toLongOrNull() ?: 0L) * 10
                    else -> (frac.take(3).toLongOrNull() ?: 0L)
                }
                lines += LyricLine(min * 60_000 + sec * 1000 + millis, text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /** Minuscole, senza accenti e senza spazi di coda: così "città" == "citta". */
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("[\u0300-\u036f]"), "")
            .lowercase()
            .trim()
}
