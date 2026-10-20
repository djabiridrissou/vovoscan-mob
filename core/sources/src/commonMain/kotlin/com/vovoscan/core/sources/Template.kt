package com.vovoscan.core.sources

/** Modèles « {{nom}} » et « {{settings.nom}} » : substitution simple, sans logique. */
object Template {
    private val placeholder = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.]*)\s*}}""")

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
