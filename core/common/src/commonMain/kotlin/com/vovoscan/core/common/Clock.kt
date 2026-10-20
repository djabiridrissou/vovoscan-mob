package com.vovoscan.core.common

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.Clock as KotlinClock

/** Injected clock: offline writes are timestamped by the device (last-write-wins on the server). */
fun interface Clock {
    fun nowMillis(): Long
}

@OptIn(ExperimentalTime::class)
object SystemClock : Clock {
    override fun nowMillis(): Long = KotlinClock.System.now().toEpochMilliseconds()
}

/** ISO 8601 in UTC ("2026-10-20T10:00:00Z"), as the API expects. */
@OptIn(ExperimentalTime::class)
fun isoUtc(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()

/** Parses an API date; without a time zone, it is UTC. */
@OptIn(ExperimentalTime::class)
fun parseIsoMillis(value: String): Long? {
    val zoned = if (value.endsWith("Z") || value.contains('+') || value.lastIndexOf('-') > 9) value else value + "Z"
    return runCatching { Instant.parse(zoned).toEpochMilliseconds() }.getOrNull()
}
