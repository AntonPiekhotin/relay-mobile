package com.relay.protocol

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
fun isoToEpochMillisOrNull(iso: String): Long? =
    try {
        Instant.parse(iso).toEpochMilliseconds()
    } catch (e: IllegalArgumentException) {
        null
    }

fun isoToEpochMillis(iso: String): Long = isoToEpochMillisOrNull(iso) ?: nowEpochMillis()
