package com.relay.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone

private val UTC = TimeZone.UTC

private const val NOON_2026_08_06 = 1_786_017_600_000L
private const val DAY = 24L * 60 * 60 * 1000

class TimeFormatTest {

    @Test
    fun clockTimeIsZeroPadded() {
        assertEquals("12:00", formatClockTime(NOON_2026_08_06, UTC))
        assertEquals("09:05", formatClockTime(NOON_2026_08_06 - 2 * 60 * 60 * 1000 - 55 * 60 * 1000, UTC))
    }

    @Test
    fun todayShowsClockTime() {
        assertEquals("12:00", formatListTimestamp(NOON_2026_08_06, NOON_2026_08_06, UTC))
    }

    @Test
    fun yesterdayIsNamed() {
        assertEquals("Yesterday", formatListTimestamp(NOON_2026_08_06 - DAY, NOON_2026_08_06, UTC))
    }

    @Test
    fun withinTheLastWeekShowsAWeekday() {
        assertEquals("Mon", formatListTimestamp(NOON_2026_08_06 - 3 * DAY, NOON_2026_08_06, UTC))
    }

    @Test
    fun olderThanAWeekShowsDayAndMonth() {
        assertEquals("7 Jul", formatListTimestamp(NOON_2026_08_06 - 30 * DAY, NOON_2026_08_06, UTC))
    }

    @Test
    fun daySeparatorsNameTodayAndYesterday() {
        assertEquals("Today", formatDaySeparator(NOON_2026_08_06, NOON_2026_08_06, UTC))
        assertEquals("Yesterday", formatDaySeparator(NOON_2026_08_06 - DAY, NOON_2026_08_06, UTC))
    }

    @Test
    fun olderDaySeparatorsCarryTheYear() {
        assertEquals("7 Jul 2026", formatDaySeparator(NOON_2026_08_06 - 30 * DAY, NOON_2026_08_06, UTC))
    }

    @Test
    fun theDayKeyChangesAtMidnightNotAfterTwentyFourHours() {
        val lateEvening = NOON_2026_08_06 + 11 * 60 * 60 * 1000
        val earlyMorning = NOON_2026_08_06 + 13 * 60 * 60 * 1000
        assertEquals(dayKeyOf(NOON_2026_08_06, UTC), dayKeyOf(lateEvening, UTC))
        assertEquals(dayKeyOf(NOON_2026_08_06, UTC) + 1, dayKeyOf(earlyMorning, UTC))
    }

    @Test
    fun callDurationsAreMinutesAndSecondsUntilAnHour() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:09", formatDuration(9_400))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun longerCallsGrowAnHoursField() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("2:03:04", formatDuration(2 * 3_600_000 + 3 * 60_000 + 4_000))
    }

    @Test
    fun aClockSkewedBackwardsShowsZeroRatherThanANegativeDuration() {
        assertEquals("00:00", formatDuration(-5_000))
    }
}
