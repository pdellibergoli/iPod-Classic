package com.train.ipodclassicemulator.data.remote

import android.app.Activity
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

class SpotifyManager(private val context: Context) {

    val spotifyClientId: String get() = getClientId() ?: ""
    val spotifyClientSecret: String get() = getClientSecret() ?: ""

    val redirectUri = "ipodapp://spotify-callback"
    var spotifyAppRemote: SpotifyAppRemote? = null
    var onPlayerStateChanged: ((PlayerState) -> Unit)? = null
    var isShuffling by mutableStateOf(false)

    private val prefs get() = context.getSharedPreferences("spotify_prefs", Context.MODE_PRIVATE)

    /**
     * Codice OAuth monouso ricevuto dal redirect di Spotify.
     * Viene cancellato immediatamente dopo lo scambio con il web token.
     */
    var pendingAuthCode: String?
        get() = prefs.getString("pending_auth_code", null)
        set(value) = prefs.edit().apply {
            if (value == null) remove("pending_auth_code") else putString("pending_auth_code", value)
        }.apply()

    /**
     * Web Access Token Bearer reale (~1 ora di validità).
     * È questo che va salvato e ricaricato tra le sessioni.
     */
    var savedWebToken: String?
        get() = prefs.getString("web_access_token", null)
        set(value) = prefs.edit().apply {
            if (value == null) remove("web_access_token") else putString("web_access_token", value)
        }.apply()

    /** Cancella tutto — chiamato su 401 o logout forzato. */
    fun clearAllTokens() {
        prefs.edit().remove("pending_auth_code").remove("web_access_token").apply()
        Log.d("SpotifyManager", "Tutti i token cancellati.")
    }

    fun getClientHeader(): String {
        // Blocco di sicurezza se le chiavi non sono ancora state impostate
        if (spotifyClientId.isBlank() || spotifyClientSecret.isBlank()) return ""

        val raw = "$spotifyClientId:$spotifyClientSecret"
        return "Basic ${Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)}"
    }

    fun requestToken(activity: Activity) {
        if (spotifyClientId.isBlank()) {
            Log.e("SpotifyManager", "Impossibile richiedere il token: Client ID mancante!")
            return
        }

        val scopes = "playlist-read-private playlist-read-collaborative " +
                "user-library-read user-library-modify user-follow-read user-modify-playback-state"
        val authUrl = "https://accounts.spotify.com/authorize" +
                "?client_id=$spotifyClientId" +
                "&response_type=code" +
                "&redirect_uri=${Uri.encode(redirectUri)}" +
                "&scope=${Uri.encode(scopes)}"
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
    }

    fun connect(onConnected: () -> Unit) {
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
                Log.d("SpotifyManager", "App Remote connesso!")
                subscribeToPlayerState()
                onConnected()
            }
            override fun onFailure(t: Throwable) {
                Log.e("SpotifyManager", "Errore connessione App Remote", t)
            }
        })
    }

    fun subscribeToPlayerState() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { state ->
            isShuffling = state.playbackOptions.isShuffling
            val track = state.track
            val imageUrl = "https://i.scdn.co/image/${track.imageUri.raw?.removePrefix("spotify:image:")}"
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

    // 🟢 Mantieni queste funzioni intatte in fondo al file
    fun getClientId(): String? {
        val prefs = context.getSharedPreferences("spotify_settings", Context.MODE_PRIVATE)
        return prefs.getString("client_id", null)
    }

    fun getClientSecret(): String? {
        val prefs = context.getSharedPreferences("spotify_settings", Context.MODE_PRIVATE)
        return prefs.getString("client_secret", null)
    }

    fun saveCredentials(clientId: String, clientSecret: String) {
        val prefs = context.getSharedPreferences("spotify_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("client_id", clientId)
            .putString("client_secret", clientSecret)
            .apply()
    }

    companion object { const val REQUEST_CODE = 1337 }
}