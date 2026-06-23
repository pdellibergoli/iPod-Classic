package com.train.ipodclassicemulator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.train.ipodclassicemulator.ui.theme.IPodClassicEmulatorTheme
import com.train.ipodclassicemulator.ui.theme.ThemeManager

class MainActivity : ComponentActivity() {

    private lateinit var themeManager: ThemeManager
    val viewModel: IPodViewModel by viewModels { IPodViewModel.Factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeManager = ThemeManager(this)

        setContent {
            IPodClassicEmulatorTheme(themeType = themeManager.currentTheme) {
                IPodApp(
                    viewModel = viewModel,
                    themeManager = themeManager
                )
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
        viewModel.spotifyManager.disconnect()
    }
}