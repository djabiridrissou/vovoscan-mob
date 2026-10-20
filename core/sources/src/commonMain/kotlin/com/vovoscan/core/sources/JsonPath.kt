package com.vovoscan.core.sources

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Subset of JSONPath, the same as the contract's: `$`, `.key`, `['key with / or .']`, `[n]`, `[*]`.
 * No filters or expressions: nothing that looks like code.
 */
object JsonPath {
    private val token = Regex("""\.([A-Za-z_][A-Za-z0-9_-]*)|\[\*]|\[(-?\d+)]|\['((?:[^'\\]|\\.)*)']|\["((?:[^"\\]|\\.)*)"]""")

    private sealed interface Step {
        data class Key(
            val name: String,
        ) : Step

        data class Index(
            val index: Int,
        ) : Step

        data object All : Step
    }

    private fun compile(path: String): List<Step> {
        require(path.startsWith("$")) { "JSONPath must start with $: $path" }
        val steps = mutableListOf<Step>()
        var rest = path.drop(1)
        while (rest.isNotEmpty()) {
            val match = token.matchAt(rest, 0) ?: throw IllegalArgumentException("Unsupported JSONPath near '$rest' in $path")
            val (name, index, quoted, double) = match.destructured
            steps +=
                when {
                    name.isNotEmpty() -> Step.Key(name)
                    index.isNotEmpty() -> Step.Index(index.toInt())
                    quoted.isNotEmpty() -> Step.Key(quoted.replace("\\'", "'"))
                    double.isNotEmpty() -> Step.Key(double.replace("\\\"", "\""))
                    else -> Step.All
                }
            rest = rest.substring(match.range.last + 1)
        }
        return steps
    }

    fun select(
        data: JsonElement,
        path: String,
    ): List<JsonElement> {
        var current = listOf(data)
        for (step in compile(path)) {
            current =
                current.flatMap { value ->
                    when (step) {
                        is Step.Key -> listOfNotNull((value as? JsonObject)?.get(step.name))
                        is Step.Index -> {
                            val array = value as? JsonArray ?: return@flatMap emptyList()
                            val at = if (step.index < 0) array.size + step.index else step.index
                            listOfNotNull(array.getOrNull(at))
                        }
                        Step.All ->
                            when (value) {
                                is JsonArray -> value
                                is JsonObject -> value.values.toList()
                                else -> emptyList()
                            }
                    }
                }
        }
        return current
    }
}
