package com.relay

import androidx.compose.ui.window.ComposeUIViewController
import com.relay.di.initKoin

private var koinStarted = false

fun MainViewController() = ComposeUIViewController(
    configure = {
        if (!koinStarted) {
            initKoin()
            koinStarted = true
        }
    }
) { App() }
