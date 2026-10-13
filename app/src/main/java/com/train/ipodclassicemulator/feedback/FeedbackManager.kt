package com.train.ipodclassicemulator.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

private const val TAG = "FeedbackManager"

/** Intensità della vibrazione della ruota. */
enum class HapticLevel(val label: String) {
    OFF("Spenta"), LIGHT("Leggera"), MEDIUM("Media"), STRONG("Forte");

    /** Livello successivo nel ciclo, usato dal tasto Select nelle Impostazioni. */
    val next: HapticLevel get() = values()[(ordinal + 1) % values().size]
}

/** Volume del "click" meccanico della ruota. */
enum class SoundLevel(val label: String) {
    OFF("Spento"), QUIET("Sottovoce"), NORMAL("Medio"), LOUD("Forte");

    val next: SoundLevel get() = values()[(ordinal + 1) % values().size]

    /** Guadagno 0..1 applicato alla riproduzione. */
    internal val gain: Float
        get() = when (this) {
            OFF -> 0f
            QUIET -> 0.25f
            NORMAL -> 0.6f
            LOUD -> 1f
        }
}

/**
 * Feedback fisico e sonoro della click wheel.
 *
 * **Vibrazione**: usa il `Vibrator` di sistema con `VibrationEffect` ad ampiezza
 * controllata, quindi è molto più marcata del vecchio `HapticFeedbackType.TextHandleMove`
 * e l'utente può scegliere tra 4 livelli.
 *
 * **Suono**: non servono risorse audio: il "detent" viene sintetizzato in PCM,
 * scritto una volta come WAV nella cache e caricato in `SoundPool`, l'unico
 * componente pensato a riattivare lo stesso effetto sonoro decine di volte al
 * secondo con latenza minima (serve durante lo scorrimento veloce della ruota).
 */
class FeedbackManager(context: Context) {

    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    private val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _hapticLevel = mutableStateOf(loadHaptic())
    private val _soundLevel = mutableStateOf(loadSound())

    /** Intensità della vibrazione, salvata tra le sessioni. */
    var hapticLevel: HapticLevel
        get() = _hapticLevel.value
        set(value) {
            _hapticLevel.value = value
            prefs.edit().putString(KEY_HAPTIC, value.name).apply()
        }

    /** Volume del click della ruota, salvato tra le sessioni. */
    var soundLevel: SoundLevel
        get() = _soundLevel.value
        set(value) {
            _soundLevel.value = value
            prefs.edit().putString(KEY_SOUND, value.name).apply()
        }

    // ── API usata dalla ruota ─────────────────────────────────────────────────

    /** "Detent" durante lo scorrimento: breve e secco. */
    fun click() {
        vibrate(baseDurationMs = 16, baseAmplitude = 140)
        playSound(gain = 0.5f)
    }

    /** Pressione di un tasto: più lunga e decisa del detent. */
    fun press() {
        vibrate(baseDurationMs = 28, baseAmplitude = 255)
        playSound(gain = 1f)
    }

    /** Chiude l'audio. Va chiamato quando il ViewModel muore. */
    fun release() {
        soundPool?.release()
        soundPool = null
        soundLoaded = false
    }

    // ── Vibrazione ────────────────────────────────────────────────────────────

    private fun vibrate(baseDurationMs: Long, baseAmplitude: Int) {
        val level = hapticLevel
        if (level == HapticLevel.OFF) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        val duration: Long
        val amplitude: Int
        when (level) {
            HapticLevel.OFF -> return
            HapticLevel.LIGHT -> {
                duration = baseDurationMs
                amplitude = (baseAmplitude * 0.35f).toInt()
            }
            HapticLevel.MEDIUM -> {
                duration = baseDurationMs + 4
                amplitude = (baseAmplitude * 0.7f).toInt()
            }
            HapticLevel.STRONG -> {
                duration = baseDurationMs + 10
                amplitude = baseAmplitude
            }
        }
        try {
            // Senza cancel() i tick rapidi si accatasterebbero e diventerebbero
            // un ronzio continuo invece di detenti netti.
            v.cancel()
            v.vibrate(VibrationEffect.createOneShot(duration, amplitude.coerceIn(1, 255)))
        } catch (e: Throwable) {
            Log.w(TAG, "Vibrazione non disponibile", e)
        }
    }

    // ── Suono ─────────────────────────────────────────────────────────────────

    private var soundPool: SoundPool? = null
    private var soundId = 0
    private var soundLoaded = false

    /** Crea una sola volta il SoundPool con dentro il click sintetizzato. */
    private fun ensureSoundReady() {
        if (soundPool != null || soundLevel == SoundLevel.OFF) return
        synchronized(this) {
            if (soundPool != null) return
            val file = writeClickWav() ?: return
            try {
                val pool = SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // USAGE_MEDIA: il click segue il volume musicale,
                            // quindi è udibile anche durante la riproduzione.
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                pool.setOnLoadCompleteListener { _, _, status ->
                    // status == 0 significa "caricato con successo" (nessuna costante pubblica)
                    soundLoaded = status == 0
                    if (!soundLoaded) Log.w(TAG, "Caricamento del suono della ruota fallito")
                }
                soundId = pool.load(file.absolutePath, 1)
                soundPool = pool
            } catch (e: Throwable) {
                Log.w(TAG, "SoundPool non disponibile", e)
            }
        }
    }

    private fun playSound(gain: Float) {
        if (soundLevel == SoundLevel.OFF) return
        ensureSoundReady()
        val pool = soundPool ?: return
        if (!soundLoaded || soundId == 0) return
        try {
            val volume = soundLevel.gain * gain
            pool.play(soundId, volume, volume, 1, 0, 1f)
        } catch (e: Throwable) {
            Log.w(TAG, "Riproduzione del click fallita", e)
        }
    }

    /** Scrive il click sintetizzato come WAV nella cache e ne ritorna il file. */
    private fun writeClickWav(): File? = try {
        val file = File(appContext.cacheDir, "wheel_click.wav")
        val samples = synthesizeClick()
        val dataSize = samples.size * 2

        val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray())
        bytes.putInt(36 + dataSize)
        bytes.put("WAVE".toByteArray())
        bytes.put("fmt ".toByteArray())
        bytes.putInt(16)                  // dimensione del chunk fmt
        bytes.putShort(1)                 // formato PCM
        bytes.putShort(1)                 // mono
        bytes.putInt(SAMPLE_RATE)
        bytes.putInt(SAMPLE_RATE * 2)     // byte al secondo
        bytes.putShort(2)                 // block align
        bytes.putShort(16)                // bit per campione
        bytes.put("data".toByteArray())
        bytes.putInt(dataSize)
        samples.forEach { bytes.putShort(it) }

        FileOutputStream(file).use { it.write(bytes.array()) }
        file
    } catch (e: Throwable) {
        Log.w(TAG, "Impossibile creare il suono della ruota", e)
        null
    }

    /**
     * "Click" della ruota: tre modi risonanti che decadono a velocità diverse,
     * cioè il modello fisico di un detent di plastica/legno.
     *
     * L'orecchio percepisce una brillantezza iniziale di pochi millisecondi che
     * decade subito in un "toc" caldo: è quello che lo avvicina al click
     * originale dell'iPod invece di un bip fastidioso.
     *
     * Le due cose che lo rendevano sgradevole sono state eliminate:
     * - il transiente iniziale a gradino (un edge broadband, tipo "clic" digitale);
     * - una parziale acuta sempre accesa, che dava il carattere metallino.
     * Al loro posto c'è una rampa di attacco di ~1 ms: nessun salto di ampiezza,
     * quindi nessun "pop".
     */
    private fun synthesizeClick(): ShortArray {
        val count = (SAMPLE_RATE * CLICK_DURATION_SEC).toInt()
        val samples = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack = (t / ATTACK_SEC).coerceAtMost(1.0)

            var value = 0.0
            for ((frequency, amplitude, decay) in MODES) {
                value += amplitude * sin(2.0 * PI * frequency * t) * exp(-t * decay)
            }
            value *= attack

            // fade-out finale di sicurezza
            if (i > count - FADE_SAMPLES) value *= (count - i).toDouble() / FADE_SAMPLES

            samples[i] = (value * PEAK_LEVEL).coerceIn(-32_768.0, 32_767.0).toInt().toShort()
        }
        return samples
    }

    private fun loadHaptic(): HapticLevel {
        val saved = prefs.getString(KEY_HAPTIC, null) ?: return HapticLevel.MEDIUM
        return try {
            HapticLevel.valueOf(saved)
        } catch (e: IllegalArgumentException) {
            HapticLevel.MEDIUM
        }
    }

    private fun loadSound(): SoundLevel {
        val saved = prefs.getString(KEY_SOUND, null) ?: return SoundLevel.NORMAL
        return try {
            SoundLevel.valueOf(saved)
        } catch (e: IllegalArgumentException) {
            SoundLevel.NORMAL
        }
    }

    private companion object {
        private const val PREFS_NAME = "ipod_feedback_prefs"
        private const val KEY_HAPTIC = "haptic_level"
        private const val KEY_SOUND = "sound_level"

        private const val SAMPLE_RATE = 44_100
        private const val CLICK_DURATION_SEC = 0.026
        private const val ATTACK_SEC = 0.0012
        private const val FADE_SAMPLES = 320
        private const val PEAK_LEVEL = 17_000.0

        /**
         * Modi del "toc": frequenza (Hz), ampiezza e decadimento (1/s).
         * I modi acuti decadono molto più in fretta del fondamentale, così la
         * brillantezza dura solo l'attimo del contatto e poi resta il corpo.
         */
        private val MODES = listOf(
            Triple(1020.0, 1.00, 170.0),   // fondamentale calda (~5,9 ms di τ)
            Triple(2960.0, 0.10, 420.0),   // 1° parziale inarmonico, muore subito
            Triple(5150.0, 0.035, 900.0)   // 2° parziale: solo un accenno
        )
    }
}
