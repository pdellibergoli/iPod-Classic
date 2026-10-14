package com.train.ipodclassicemulator.data.remote

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.abs

private const val TAG = "LyricsRepository"

/**
 * Punteggio minimo perché una candidata sia considerata il brano giusto.
 * Con i pesi di [score] servono almeno titolo + artista: nessuna delle due
 * singole basta, così non si attaccano le lyrics sbagliate.
 */
private const val MIN_SCORE = 60

// ── Formato della cache su disco ──────────────────────────────────────────────

private const val KIND_SYNCED = "synced"
private const val KIND_PLAIN = "plain"

/**
 * Voce serializzata in cache. Tutti i parametri hanno un default perché a
 * Gson serve un costruttore senza argomenti per istanziarla.
 */
private data class CachedLyrics(
    val kind: String = "",
    val lines: List<LyricLine>? = null,
    val text: String? = null
)

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
 * Due livelli: la mappa in memoria, e i file sotto `cacheDir` che sopravvivono
 * al riavvio dell'app. Una sola chiamata per brano — i candidati li peschiamo
 * noi in base a titolo, artista e durata.
 *
 * @param cacheDir cartella dove scrivere la cache; `null` disabilita il disco
 */
class LyricsRepository(private val cacheDir: File? = null) {

    private val gson = Gson()

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

        // Memoria vuota: prima il disco, poi la rete
        val file = cacheFile(key)
        loadFromDisk(file)?.let {
            synchronized(cache) { cache[key] = it }
            return it
        }

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
        saveToDisk(file, result)
        return result
    }

    // ── Cache su disco ────────────────────────────────────────────────────────

    /** SHA-1 del nome: i titoli possono contenere qualsiasi carattere. */
    private fun cacheFile(key: String): File? {
        val dir = cacheDir ?: return null
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$digest.json")
    }

    private suspend fun loadFromDisk(file: File?): LyricsResult? {
        file ?: return null
        return withContext(Dispatchers.IO) {
            try {
                if (!file.isFile) return@withContext null
                val cached = gson.fromJson(file.readText(), CachedLyrics::class.java)
                    ?: return@withContext null
                when (cached.kind) {
                    KIND_SYNCED -> cached.lines
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { LyricsResult.Synced(it) }
                    KIND_PLAIN -> cached.text
                        ?.takeIf { it.isNotBlank() }
                        ?.let { LyricsResult.Plain(it) }
                    else -> null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lettura cache lyrics fallita", e)
                null
            }
        }
    }

    private suspend fun saveToDisk(file: File?, result: LyricsResult) {
        file ?: return
        // I "non trovati" NON vengono persistiti: la community di LRCLIB può
        // aggiungere la lyrics in seguito, e una richiesta in più a sessione
        // costa meno di una cache negativa che non sblocca mai.
        if (result is LyricsResult.None) return

        val cached = when (result) {
            is LyricsResult.Synced -> CachedLyrics(KIND_SYNCED, lines = result.lines)
            is LyricsResult.Plain -> CachedLyrics(KIND_PLAIN, text = result.text)
            LyricsResult.None -> return
        }
        withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(gson.toJson(cached))
            } catch (e: Exception) {
                Log.w(TAG, "Scrittura cache lyrics fallita", e)
            }
        }
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
