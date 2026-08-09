package com.relay.network

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackoffTest {

    @Test
    fun delaysGrowExponentiallyAndCapAt30Seconds() {
        val noJitter = FixedRandom(0.5)
        assertEquals(1_000, backoffDelayMillis(0, noJitter))
        assertEquals(2_000, backoffDelayMillis(1, noJitter))
        assertEquals(4_000, backoffDelayMillis(2, noJitter))
        assertEquals(8_000, backoffDelayMillis(3, noJitter))
        assertEquals(16_000, backoffDelayMillis(4, noJitter))
        assertEquals(30_000, backoffDelayMillis(5, noJitter))
        assertEquals(30_000, backoffDelayMillis(50, noJitter))
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        repeat(200) {
            val delay = backoffDelayMillis(3)
            assertTrue(delay in 6_400..9_600, "delay $delay out of jitter bounds")
        }
    }

    @Test
    fun jitterActuallyVaries() {
        val samples = (1..50).map { backoffDelayMillis(5) }.toSet()
        assertTrue(samples.size > 1)
    }

    @Test
    fun negativeFailureCountIsTreatedAsZero() {
        assertEquals(1_000, backoffDelayMillis(-3, FixedRandom(0.5)))
    }

    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }
}
