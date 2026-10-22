package com.vovoscan.core.sources

/** "{{name}}" and "{{settings.name}}" templates: plain substitution, no logic. */
object Template {
    // Closing braces escaped too: Android's ICU regex engine rejects a bare "}" (the JVM and iOS accept it).
    private val placeholder = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\}\}""")

    fun render(
        template: String,
        variables: Map<String, Any?>,
    ): String =
        placeholder.replace(template) { match ->
            var current: Any? = variables
            for (part in match.groupValues[1].split(".")) current = (current as? Map<*, *>)?.get(part)
            current?.toString() ?: ""
        }
}
