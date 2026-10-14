package com.train.ipodclassicemulator.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import com.train.ipodclassicemulator.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom

class SpotifyManager(private val context: Context) {

    val spotifyClientId: String = BuildConfig.SPOTIFY_CLIENT_ID

    val redirectUri = "ipodapp://spotify-callback"
    var spotifyAppRemote: SpotifyAppRemote? = null
    var onPlayerStateChanged: ((PlayerState) -> Unit)? = null
    var isShuffling by mutableStateOf(false)

    private val prefs get() = context.getSharedPreferences("spotify_prefs", Context.MODE_PRIVATE)

    var pendingAuthCode: String?
        get() = prefs.getString("pending_auth_code", null)
        set(value) = prefs.edit().apply {
            if (value == null) remove("pending_auth_code") else putString("pending_auth_code", value)
        }.apply()

    var codeVerifier: String?
        get() = prefs.getString("code_verifier", null)
        set(value) = prefs.edit().apply {
            if (value == null) remove("code_verifier") else putString("code_verifier", value)
        }.apply()

    var savedWebToken: String?
        get() = prefs.getString("web_access_token", null)
        set(value) = prefs.edit().apply {
            if (value == null) remove("web_access_token") else putString("web_access_token", value)
        }.apply()

    /**
     * Refresh token: resta valido per mesi e permette di rinnovare l'access token
     * in silenzio, senza dover ricorrere al browser ad ogni scadenza (~1 ora).
     */
    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().apply {
            if (value == null) remove("refresh_token") else putString("refresh_token", value)
        }.apply()

    /** Epoch ms in cui l'access token scade; 0 = sconosciuto (sessioni vecchie). */
    var tokenExpiresAt: Long
        get() = prefs.getLong("token_expires_at", 0L)
        set(value) = prefs.edit().putLong("token_expires_at", value).apply()

    /** true se abbiamo una sessione salvata (access token o refresh token). */
    val hasStoredSession: Boolean
        get() = savedWebToken != null || refreshToken != null

    /**
     * true se il browser per il login è già stato aperto automaticamente una volta.
     * Serve a non riaprire accounts.spotify.com ad ogni avvio dell'app se l'utente
     * ha abbandonato il flusso: da quel momento si mostra solo il pulsante in-app.
     */
    var autoLoginAttempted: Boolean
        get() = prefs.getBoolean("auto_login_attempted", false)
        set(value) = prefs.edit().putBoolean("auto_login_attempted", value).apply()

    /** Evita di aprire più finestre di login se requestToken viene chiamato più volte. */
    private var lastAuthRequestAt = 0L

    fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val bytes = ByteArray(64)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = MessageDigest.getInstance("SHA-256")
        messageDigest.update(bytes)
        val digest = messageDigest.digest()
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun clearAllTokens() {
        prefs.edit()
            .remove("pending_auth_code")
            .remove("web_access_token")
            .remove("refresh_token")
            .remove("token_expires_at")
            .apply()
        Log.d("SpotifyManager", "Tutti i token cancellati.")
    }

    fun requestToken(ctx: Context) {
        if (spotifyClientId.isBlank()) return

        // Guard: se la richiesta è partita meno di 5s fa non ne apriamo un'altra
        // (evita più schede del browser quando più chiamate falliscono insieme).
        val now = System.currentTimeMillis()
        if (now - lastAuthRequestAt < 5_000L) {
            Log.d("SpotifyManager", "Login già richiesta di recente, ignoro")
            return
        }
        lastAuthRequestAt = now

        val verifier = generateCodeVerifier()
        this.codeVerifier = verifier

        val challenge = generateCodeChallenge(verifier)

        // `playlist-modify-*` servono per "aggiungi a playlist", `user-read-recently-played`
        // per la Cronologia e `user-read-playback-state` per la Coda.
        val scopes = "playlist-read-private playlist-read-collaborative " +
                "playlist-modify-private playlist-modify-public " +
                "user-library-read user-library-modify user-follow-read " +
                "user-modify-playback-state user-read-playback-state " +
                "user-read-recently-played"

        val authUrl = "https://accounts.spotify.com/authorize" +
                "?client_id=$spotifyClientId" +
                "&response_type=code" +
                "&redirect_uri=${Uri.encode(redirectUri)}" +
                "&scope=${Uri.encode(scopes)}" +
                "&code_challenge_method=S256" +
                "&code_challenge=$challenge"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            lastAuthRequestAt = 0L // nessun browser aperto: permetti un altro tentativo
            Log.e("SpotifyManager", "Impossibile aprire il browser per il login", e)
        }
    }


    private var isConnecting = false
    /**
     * Connects to Spotify App Remote if not already connected, then plays the given URI.
     * This avoids the "AppRemote is NULL" crash by reconnecting transparently on each tap.
     */
    fun connectAndPlay(uri: String, contextUri: String? = null, trackIndex: Int = 0) {
        if (spotifyAppRemote?.isConnected == true) {
            playAfterConnect(uri, contextUri, trackIndex)
            return
        }
        if (isConnecting) {
            Log.w("SpotifyManager", "Connessione già in corso, ignoro tap duplicato")
            return
        }

        isConnecting = true
        Log.d("SpotifyManager", "App Remote non connesso, riconnessione in corso...")
        val params = ConnectionParams.Builder(spotifyClientId)
            .setRedirectUri(redirectUri)
            .showAuthView(false)
            .build()

        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                isConnecting = false
                spotifyAppRemote = appRemote
                subscribeToPlayerState()
                Log.d("SpotifyManager", "Riconnesso! Avvio riproduzione: $uri")
                playAfterConnect(uri, contextUri, trackIndex)
            }

            override fun onFailure(t: Throwable) {
                isConnecting = false
                Log.e("SpotifyManager", "Connessione fallita: ${t.message}", t)
            }
        })
    }

    private fun playAfterConnect(uri: String, contextUri: String?, trackIndex: Int) {
        val playerApi = spotifyAppRemote?.playerApi ?: return
        if (!contextUri.isNullOrEmpty()) {
            playerApi.play(contextUri)
            playerApi.skipToIndex(contextUri, trackIndex)
        } else {
            playerApi.play(uri)
        }
    }

    fun connect(onConnected: () -> Unit) {
        Log.d("SpotifyManager", "Tentativo di connessione con Client ID: $spotifyClientId")
        if (spotifyClientId.isBlank()) {
            Log.e("SpotifyManager", "Impossibile connettere l'App Remote: Client ID vuoto!")
            return
        }
        // Già connesso: ripartire da qui farebbe comparire di nuovo la finestra
        // di autorizzazione di Spotify senza alcun motivo.
        if (spotifyAppRemote?.isConnected == true) {
            onConnected()
            return
        }
        if (isConnecting) {
            Log.d("SpotifyManager", "Connessione già in corso, ignoro la richiesta")
            return
        }
        isConnecting = true

        val params = ConnectionParams.Builder(spotifyClientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                isConnecting = false
                spotifyAppRemote = appRemote
                Log.d("SpotifyManager", "Spotify App Remote connesso con successo!")
                subscribeToPlayerState()
                onConnected()
            }

            override fun onFailure(t: Throwable) {
                isConnecting = false
                Log.e("SpotifyManager", "Dettaglio errore: ${t.cause?.message}", t)
            }
        })
    }

    fun subscribeToPlayerState() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { state ->
            isShuffling = state.playbackOptions.isShuffling
            onPlayerStateChanged?.invoke(state)
        }
    }

    fun playSpotifyUri(uri: String)    { spotifyAppRemote?.playerApi?.play(uri) }
    fun resumePlayback()               { spotifyAppRemote?.playerApi?.resume() }
    fun pausePlayback()                { spotifyAppRemote?.playerApi?.pause() }
    fun skipNext()                     { spotifyAppRemote?.playerApi?.skipNext() }
    fun skipPrevious()                 { spotifyAppRemote?.playerApi?.skipPrevious() }
    fun disconnect() {
        SpotifyAppRemote.disconnect(spotifyAppRemote)
        spotifyAppRemote = null
    }

    fun adjustVolume(up: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
            0 // FLAG_SHOW_UI rimosso: il volume si mostra nella UI del player
        )
    }

    fun setShuffle(enabled: Boolean) {
        spotifyAppRemote?.playerApi?.setShuffle(enabled)
            ?.setErrorCallback { Log.e("SpotifyManager", "Errore impostazione Shuffle", it) }
    }

    /**
     * Imposta la modalità di ripetizione.
     *
     * @param repeat uno dei valori di [com.spotify.protocol.types.Repeat]:
     *   `OFF`, `ONE`, `ALL`. Il valore va passato **così com'è**: l'API di
     *   Spotify usa 0=OFF, 1=ONE, 2=ALL, quindi nessuna conversione.
     */
    fun setRepeatMode(repeat: Int) {
        spotifyAppRemote?.playerApi?.setRepeat(repeat)
            ?.setErrorCallback { Log.e("SpotifyManager", "Errore impostazione Repeat", it) }
    }

    companion object { const val REQUEST_CODE = 1337 }
}