package com.relay.network

import kotlin.math.min
import kotlin.random.Random

private const val BASE_DELAY_MILLIS = 1_000L
private const val MAX_DELAY_MILLIS = 30_000L
private const val MAX_EXPONENT = 5
private const val JITTER_FLOOR = 0.8
private const val JITTER_SPAN = 0.4

fun backoffDelayMillis(consecutiveFailures: Int, random: Random = Random.Default): Long {
    val exponent = min(consecutiveFailures.coerceAtLeast(0), MAX_EXPONENT)
    val base = min(BASE_DELAY_MILLIS shl exponent, MAX_DELAY_MILLIS)
    val jitter = JITTER_FLOOR + random.nextDouble() * JITTER_SPAN
    return (base * jitter).toLong()
}

private const val SEND_MAX_DELAY_MILLIS = 60_000L
private const val SEND_MAX_EXPONENT = 6

fun sendBackoffMillis(attemptCount: Int, random: Random = Random.Default): Long {
    val exponent = min(attemptCount.coerceAtLeast(0), SEND_MAX_EXPONENT)
    val base = min(BASE_DELAY_MILLIS shl exponent, SEND_MAX_DELAY_MILLIS)
    val jitter = JITTER_FLOOR + random.nextDouble() * JITTER_SPAN
    return (base * jitter).toLong()
}
