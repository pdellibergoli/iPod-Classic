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

class MainActivity : ComponentActivity() {

    private lateinit var themeManager: ThemeManager
    val viewModel: IPodViewModel by viewModels { IPodViewModel.Factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeManager = ThemeManager(this)

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
        val uri: Uri? = intent.data
        if (uri != null && uri.toString().startsWith(viewModel.spotifyManager.redirectUri)) {
            val authCode = uri.getQueryParameter("code")
            if (authCode != null) {
                viewModel.handleAuthCode(authCode, lifecycleScope = lifecycle)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.spotifyManager.spotifyAppRemote?.isConnected != true) {
            viewModel.spotifyManager.connect(onConnected = {
                Log.d("MainActivity", "App Remote riconnesso in onResume")
            })
        }
        if (viewModel.spotifyManager.savedWebToken != null) {
            viewModel.loadInitialDataIfReady()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.spotifyManager.disconnect()
    }
}
