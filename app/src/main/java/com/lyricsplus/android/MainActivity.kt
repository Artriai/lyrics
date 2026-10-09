package com.lyricsplus.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.ui.platform.ComposeView
import com.lyricsplus.android.analytics.AnonymousStats
import com.lyricsplus.android.ui.LyricsPlusApp
import com.lyricsplus.android.ui.LyricsWebController

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var webController: LyricsWebController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The simplified standalone app has no analytics controls or analytics collection.
        AnonymousStats.setEnabled(this, false)
        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        webController = LyricsWebController(this)
        webController.onOpenLibrary = viewModel::openLibrary
        webController.onOpenSearch = viewModel::openSearch
        webController.onBeginScrub = viewModel::beginScrub
        webController.onSeekCue = viewModel::seekCue
        webController.onEndScrub = viewModel::endScrub
        setContentView(ComposeView(this).apply {
            setContent { LyricsPlusApp(viewModel, webController) }
        })
    }

    override fun onDestroy() {
        if (::webController.isInitialized) webController.destroy()
        super.onDestroy()
    }
}
