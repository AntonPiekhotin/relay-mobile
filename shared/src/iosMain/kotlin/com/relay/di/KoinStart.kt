package com.relay.di

private var koinStarted = false

fun ensureKoinStarted() {
    if (koinStarted) return
    koinStarted = true
    initKoin()
}
