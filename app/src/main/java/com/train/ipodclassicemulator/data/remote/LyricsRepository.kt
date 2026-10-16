package com.train.ipodclassicemulator.data.remote

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.abs

/** Cosa serve alla UI per mostrare la schermata delle lyrics. */
sealed interface LyricsResult {
    /** Testo sincronizzato: è la riga più vicina al tempo corrente che si evidenzia. */
    data class Synced(val lines: List<LyricLine>) : LyricsResult
    /** Solo testo, senza tempi: si scorre a mano. */
    data class Plain(val text: String) : LyricsResult
    /** Non trovate. */
    object None : LyricsResult
}

/**
 * Scarica le lyrics sincronizzate da due fonti in ordine di priorità:
 * 1. LRCLIB
 * 2. lrcmux
 */
class LyricsRepository(private val context: Context) {

    private val cacheDir: File?
        get() = try {
            File(context.cacheDir, "lyrics_cache").apply { mkdirs() }
        } catch (e: Exception) {
            Log.w(TAG, "Cartella cache lyrics non disponibile", e)
            null
        }

    private val gson = Gson()

    private val apiService: LyricsApiService by lazy {
        Retrofit.Builder()
            .baseUrl(LyricsApiService.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LyricsApiService::class.java)
    }

    /** Seconda fonte: entra in gioco quando LRCLIB non ha il brano o non risponde. */
    private val lrcmuxApi: LrcmuxApiService by lazy {
        Retrofit.Builder()
            .baseUrl(LrcmuxApiService.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LrcmuxApiService::class.java)
    }

    /** Access-order così le canzoni che si ascoltano spesso restano in testa. */
    private val cache = object : LinkedHashMap<String, LyricsResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>) =
            size > 50
    }

    /**
     * @param durationMs durata nota dal player; serve solo come bonus di disambiguazione.
     * @return le lyrics, oppure [LyricsResult.None] se non trovate o in assenza di rete.
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

        var lrclibAnswered = false
        val lrclibResult = try {
            val candidates = apiService.search(
                query = "$title $artist",
                userAgent = LyricsApiService.USER_AGENT
            )
            lrclibAnswered = true
            pick(candidates, title, artist, album, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB non raggiungibile per \"$title\": ${e.message}")
            null
        }

        val fromLrclib = lrclibResult?.takeIf { it !is LyricsResult.None }

        var lrcmuxAnswered = false
        val fromLrcmux = if (fromLrclib != null) null else try {
            val fetched = fetchFromLrcmux(title, artist)
            lrcmuxAnswered = true
            fetched
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Lrcmux non raggiungibile per \"$title\": ${e.message}")
            null
        }

        val result = fromLrclib ?: fromLrcmux ?: LyricsResult.None

        val definitive = result !is LyricsResult.None || (lrclibAnswered && lrcmuxAnswered)
        if (definitive) {
            synchronized(cache) { cache[key] = result }
            saveToDisk(file, result)
        }
        return result
    }

    // ── Seconda fonte: lrcmux ─────────────────────────────────────────────────

    private suspend fun fetchFromLrcmux(title: String, artist: String): LyricsResult {
        val response = lrcmuxApi.get(
            artist = artist,
            title = title,
            format = LrcmuxApiService.FORMAT_JSON,
            level = LrcmuxApiService.LEVEL_LINE,
            userAgent = LyricsApiService.USER_AGENT
        )

        val track = response.track
        if (track == null || !isSameTrack(
                wantedTitle = title,
                gotTitle = track.title.orEmpty(),
                wantedArtist = artist,
                gotArtist = track.artist.orEmpty()
            )
        ) {
            Log.w(TAG, "Lrcmux: brano non corrispondente \"${track?.title}\"")
            return LyricsResult.None
        }

        val lines = response.lines
            .orEmpty()
            .mapNotNull { line ->
                val start = line.start ?: return@mapNotNull null
                line.text?.trim()?.takeIf { it.isNotEmpty() }?.let { LyricLine(start, it) }
            }
            .sortedBy { it.timeMs }

        val source = response.meta?.source?.name
        Log.d(TAG, "Lrcmux: ${lines.size} righe per \"$title\" (fonte: $source)")
        return if (lines.isNotEmpty()) LyricsResult.Synced(lines) else LyricsResult.None
    }

    private fun isSameTrack(
        wantedTitle: String,
        gotTitle: String,
        wantedArtist: String,
        gotArtist: String
    ): Boolean {
        val wanted = normalize(wantedTitle)
        val got = normalize(gotTitle)
        val titleOk = wanted.isEmpty() || got.isEmpty() ||
                wanted == got || wanted in got || got in wanted

        val wantedA = normalize(wantedArtist)
        val gotA = normalize(gotArtist)
        val artistOk = wantedA.isEmpty() || gotA.isEmpty() ||
                wantedA == gotA || wantedA in gotA || gotA in wantedA

        return titleOk && artistOk
    }

    // ── Cache su disco ────────────────────────────────────────────────────────

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
        .takeIf { it != null && it.second >= MIN_SCORE }
        ?.first // Corretto: chiamata con safe call '?.' perché takeIf può restituire null
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
        // Corretta la sottrazione tra entry.duration e (durationMs / 1000.0)
        if (durationMs > 0 && entry.duration != null && abs(entry.duration - (durationMs / 1000.0)) <= 3.0) {
            total += 25
        }
        if (!entry.syncedLyrics.isNullOrBlank()) total += 20

        return total
    }

    // ── Parsing LRC ───────────────────────────────────────────────────────────

    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")

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
                val millis = when (frac?.length ?: 0) {
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

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("[\u0300-\u036f]"), "")
            .lowercase()
            .trim()

    companion object {
        private const val TAG = "LyricsRepository"
        private const val MIN_SCORE = 60
        private const val KIND_SYNCED = "synced"
        private const val KIND_PLAIN = "plain"
    }

    private data class CachedLyrics(
        val kind: String?,
        val text: String? = null,
        val lines: List<LyricLine>? = null
    )
}