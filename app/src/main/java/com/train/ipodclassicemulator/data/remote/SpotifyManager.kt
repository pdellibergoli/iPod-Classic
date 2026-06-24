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
    init {
        context.getSharedPreferences("spotify_settings", Context.MODE_PRIVATE).edit().clear().apply()
    }
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
        prefs.edit().remove("pending_auth_code").remove("web_access_token").apply()
        Log.d("SpotifyManager", "Tutti i token cancellati.")
    }

    fun requestToken(ctx: Context) {
        if (spotifyClientId.isBlank()) return

        val verifier = generateCodeVerifier()
        this.codeVerifier = verifier

        val challenge = generateCodeChallenge(verifier)

        val scopes = "playlist-read-private playlist-read-collaborative user-library-read user-library-modify user-follow-read user-modify-playback-state"

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
        ctx.startActivity(intent)
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

        val params = ConnectionParams.Builder(spotifyClientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d("SpotifyManager", "Spotify App Remote connesso con successo!")
                subscribeToPlayerState()
                onConnected()
            }

            override fun onFailure(t: Throwable) {
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
    fun disconnect()                   { SpotifyAppRemote.disconnect(spotifyAppRemote) }

    fun adjustVolume(up: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
            android.media.AudioManager.FLAG_SHOW_UI
        )
    }

    fun setShuffle(enabled: Boolean) {
        spotifyAppRemote?.playerApi?.setShuffle(enabled)
            ?.setErrorCallback { Log.e("SpotifyManager", "Errore impostazione Shuffle", it) }
    }

    fun setRepeatMode(mode: Int) {
        val spotifyRepeatMode = when(mode) {
            1 -> com.spotify.protocol.types.Repeat.ALL
            2 -> com.spotify.protocol.types.Repeat.ONE
            else -> com.spotify.protocol.types.Repeat.OFF
        }
        spotifyAppRemote?.playerApi?.setRepeat(spotifyRepeatMode)
            ?.setErrorCallback { Log.e("SpotifyManager", "Errore impostazione Repeat", it) }
    }

    companion object { const val REQUEST_CODE = 1337 }
}