package com.relay.ui.format

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)

@OptIn(ExperimentalTime::class)
fun formatClockTime(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).time
    return "${time.hour.padded()}:${time.minute.padded()}"
}

fun formatListTimestamp(
    epochMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val moment = localDateTimeOf(epochMillis, zone)
    val daysApart = dayKeyOf(nowMillis, zone) - dayKeyOf(epochMillis, zone)
    return when {
        daysApart <= 0L -> formatClockTime(epochMillis, zone)
        daysApart == 1L -> "Yesterday"
        daysApart < 7L -> WEEKDAYS[moment.date.dayOfWeek.isoDayNumber - 1]
        else -> "${moment.date.day} ${MONTHS[moment.date.month.number - 1]}"
    }
}

fun formatDaySeparator(
    epochMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val date = localDateTimeOf(epochMillis, zone).date
    return when (dayKeyOf(nowMillis, zone) - dayKeyOf(epochMillis, zone)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> "${date.day} ${MONTHS[date.month.number - 1]} ${date.year}"
    }
}

fun formatFullDate(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val date = localDateTimeOf(epochMillis, zone).date
    return "${date.day} ${MONTHS[date.month.number - 1]} ${date.year}"
}

fun formatLastSeen(
    epochMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val moment = localDateTimeOf(epochMillis, zone)
    return when (val daysApart = dayKeyOf(nowMillis, zone) - dayKeyOf(epochMillis, zone)) {
        0L -> "at ${formatClockTime(epochMillis, zone)}"
        1L -> "yesterday at ${formatClockTime(epochMillis, zone)}"
        else -> if (daysApart in 2L..6L) {
            "on ${WEEKDAYS[moment.date.dayOfWeek.isoDayNumber - 1]}"
        } else {
            "on ${moment.date.day} ${MONTHS[moment.date.month.number - 1]}"
        }
    }
}

fun formatDuration(elapsedMillis: Long): String {
    val totalSeconds = (if (elapsedMillis > 0) elapsedMillis else 0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.padded()}:${seconds.padded()}"
    } else {
        "${minutes.padded()}:${seconds.padded()}"
    }
}

fun dayKeyOf(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
    localDateTimeOf(epochMillis, zone).date.toEpochDays().toLong()

@OptIn(ExperimentalTime::class)
private fun localDateTimeOf(epochMillis: Long, zone: TimeZone) =
    Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)

private fun Int.padded(): String = if (this < 10) "0$this" else toString()

private fun Long.padded(): String = if (this < 10) "0$this" else toString()
