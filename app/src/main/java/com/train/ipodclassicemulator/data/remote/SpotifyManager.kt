package com.train.ipodclassicemulator.data.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.spotify.protocol.types.PlayerState as PlayerState

class SpotifyManager(private val context: Context) {
    // ⚠️ METTI IL TUO CLIENT ID QUI SOTTO:
    private val clientId = "052aa3ece4e846d9a110f5bdabd8c565"
    val clientSecret = "543095b721f1485e9fa5cae4c58d066a"
    val redirectUri = "ipodapp://spotify-callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null
    // 🚀 Callback per aggiornare l'interfaccia quando cambia qualcosa su Spotify
    var onPlayerStateChanged: ((PlayerState) -> Unit)? = null

    // Sottoscrizione in tempo reale allo stato del player
    fun subscribeToPlayerState() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            onPlayerStateChanged?.invoke(playerState)
        }
    }

    var accessToken by androidx.compose.runtime.mutableStateOf<String?>(null)

    fun getClientHeader(): String {
        val rawString = "$clientId:$clientSecret"
        val base64String = Base64.encodeToString(rawString.toByteArray(), Base64.NO_WRAP)
        return "Basic $base64String"
    }

    // 🚀 Richiesta Token Nativa senza librerie Auth esterne
    fun requestToken(activity: Activity) {
        // 🔑 user-library-modify serve per aggiungere/rimuovere brani dai preferiti
        val scopes = "playlist-read-private playlist-read-collaborative user-library-read user-library-modify"
        val authUrl = "https://accounts.spotify.com/authorize" +
                "?client_id=$clientId" +
                "&response_type=code" +
                "&redirect_uri=${Uri.encode(redirectUri)}" +
                "&scope=${Uri.encode(scopes)}"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
        activity.startActivity(intent)
    }

    fun connect(onConnected: () -> Unit) {
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d("SpotifyManager", "Connesso al Player di Spotify!")

                // 🚀 Attiva l'ascolto dei metadati reali all'avvio
                subscribeToPlayerState()

                onConnected()
            }

            override fun onFailure(throwable: Throwable) {
                Log.e("SpotifyManager", "Errore di connessione", throwable)
            }
        })
    }

    fun playSpotifyUri(uri: String) {
        spotifyAppRemote?.playerApi?.play(uri)
    }

    fun resumePlayback() {
        spotifyAppRemote?.playerApi?.resume()
    }

    fun disconnect() {
        SpotifyAppRemote.disconnect(spotifyAppRemote)
    }

    // 🚀 Funzioni per saltare e mettere in pausa le tracce
    fun skipNext() {
        spotifyAppRemote?.playerApi?.skipNext()
    }

    fun skipPrevious() {
        spotifyAppRemote?.playerApi?.skipPrevious()
    }

    fun pausePlayback() {
        spotifyAppRemote?.playerApi?.pause()
    }

    // 🔀 Attiva/disattiva davvero la riproduzione casuale tramite l'SDK ufficiale
    fun setShuffle(enabled: Boolean) {
        spotifyAppRemote?.playerApi?.setShuffle(enabled)
    }

    // 🚀 Controllo del volume nativo tramite l'hardware del telefono
    fun adjustVolume(up: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val direction = if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            direction,
            android.media.AudioManager.FLAG_SHOW_UI // Mostra la barra del volume classica sullo schermo
        )
    }

    companion object {
        const val REQUEST_CODE = 1337
    }
}