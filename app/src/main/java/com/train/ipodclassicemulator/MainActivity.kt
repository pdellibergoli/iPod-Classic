package com.train.ipodclassicemulator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.train.ipodclassicemulator.ui.theme.IPodClassicEmulatorTheme
import com.train.ipodclassicemulator.ui.theme.ThemeManager
import android.os.Build

class MainActivity : ComponentActivity() {

    private lateinit var themeManager: ThemeManager
    val viewModel: IPodViewModel by viewModels { IPodViewModel.Factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeManager = ThemeManager(this)

        // Avvia e connette il service per la musica locale
        viewModel.bindMusicService()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }

        // ── Fullscreen immersivo: nascondi status bar e navigation bar di sistema ──
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.post { enableImmersiveFullscreen() }

        setContent {
            IPodClassicEmulatorTheme(themeType = themeManager.currentTheme) {
                IPodApp(
                    viewModel = viewModel,
                    themeManager = themeManager
                )
            }
        }
    }

    private fun enableImmersiveFullscreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        // Safety net: gestisce il callback OAuth anche se arriva tramite onResume
        // invece di onNewIntent (può succedere con alcuni launchMode o versioni Android)
        val currentIntent = intent
        if (currentIntent != null) {
            handleSpotifyCallback(currentIntent)
        }

        if (viewModel.spotifyManager.spotifyAppRemote?.isConnected != true) {
            viewModel.spotifyManager.connect(onConnected = {
                Log.d("MainActivity", "App Remote riconnesso in onResume")
            })
        }
        if (viewModel.spotifyManager.savedWebToken != null) {
            viewModel.loadInitialDataIfReady()
        }
    }

    /**
     * Estrae il codice OAuth dall'intent e lo consegna al ViewModel.
     * Dopo la gestione, pulisce i dati dell'intent per evitare doppi processing
     * in caso di onResume successivi.
     */
    private fun handleSpotifyCallback(intent: Intent) {
        val uri: Uri? = intent.data
        if (uri != null && uri.toString().startsWith(viewModel.spotifyManager.redirectUri)) {
            val authCode = uri.getQueryParameter("code")
            if (authCode != null) {
                Log.d("MainActivity", "Auth code ricevuto: gestisco il callback Spotify")
                viewModel.handleAuthCode(authCode, lifecycleScope = lifecycle)
                // Pulisce l'intent così un eventuale onResume successivo non riprocessa lo stesso codice
                setIntent(intent.apply { data = null })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.spotifyManager.disconnect()
        viewModel.unbindMusicService()
    }
}
