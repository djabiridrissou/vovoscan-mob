package com.vovoscan.core.common

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.Clock as KotlinClock

/** Horloge injectée : les écritures hors ligne sont datées par l'appareil (last-write-wins côté serveur). */
fun interface Clock {
    fun nowMillis(): Long
}

@OptIn(ExperimentalTime::class)
object SystemClock : Clock {
    override fun nowMillis(): Long = KotlinClock.System.now().toEpochMilliseconds()
}

/** ISO 8601 en UTC (« 2026-10-20T10:00:00Z »), comme l'attend l'API. */
@OptIn(ExperimentalTime::class)
fun isoUtc(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()

/** Lit une date de l'API ; sans fuseau, elle est en UTC. */
@OptIn(ExperimentalTime::class)
fun parseIsoMillis(value: String): Long? {
    val zoned = if (value.endsWith("Z") || value.contains('+') || value.lastIndexOf('-') > 9) value else value + "Z"
    return runCatching { Instant.parse(zoned).toEpochMilliseconds() }.getOrNull()
}
