package com.vovoscan.core.sources

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** "{{name}}" and "{{settings.name}}" templates: plain substitution, no logic. */
@OptIn(ExperimentalTime::class)
object Template {
    // Closing braces escaped too: Android's ICU regex engine rejects a bare "}" (the JVM and iOS accept it).
    // The name may carry a ":pattern" ({{utc:yyyyMMddHH}}), hence the colon in the character class.
    private val placeholder = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.:]*)\s*\}\}""")

    fun render(
        template: String,
        variables: Map<String, Any?>,
    ): String =
        placeholder.replace(template) { match ->
            val name = match.groupValues[1]
            when {
                // Clock variables, for request signing (e.g. a token of the current time): the same values
                // expand everywhere in one template, and `{{utc:…}}` is UTC so every device signs identically.
                name == "unix" ->
                    Clock.System
                        .now()
                        .epochSeconds
                        .toString()
                name.startsWith("utc:") -> UtcFormat.format(name.removePrefix("utc:"), Clock.System.now())
                else -> lookup(name, variables)
            }
        }

    private fun lookup(
        name: String,
        variables: Map<String, Any?>,
    ): String {
        var current: Any? = variables
        for (part in name.split(".")) current = (current as? Map<*, *>)?.get(part)
        return current?.toString() ?: ""
    }
}
