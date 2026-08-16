package com.relay

import androidx.compose.ui.window.ComposeUIViewController
import com.relay.di.ensureKoinStarted
import com.relay.push.SharedBridge

fun MainViewController() = ComposeUIViewController(
    configure = {
        ensureKoinStarted()
        SharedBridge.start()
    }
) { App() }
