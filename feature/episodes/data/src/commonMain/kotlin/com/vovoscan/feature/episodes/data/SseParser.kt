package com.vovoscan.feature.episodes.data

/** One server-sent event: its `event:` name ("message" when none) and its `data:` lines joined by "\n". */
data class SseEvent(
    val type: String,
    val data: String,
)

/**
 * `text/event-stream`, line by line (WHATWG rules): `field: value` lines build an event, a blank line dispatches it,
 * lines starting with ":" are comments (the server's heartbeats every 15 s). `id` and `retry` are not used.
 */
class SseParser {
    private var type = ""
    private val data = StringBuilder()
    private var hasData = false

    /** Feeds one line (without its line ending); returns the event a blank line completes. */
    fun line(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> type = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        val event = if (hasData) SseEvent(type.ifEmpty { "message" }, data.toString()) else null
        type = ""
        data.clear()
        hasData = false
        return event
    }
}
