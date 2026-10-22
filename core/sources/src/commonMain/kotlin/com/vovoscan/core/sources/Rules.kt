package com.vovoscan.core.sources

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** A rule or a response the engine cannot read. */
class RuleException(
    message: String,
) : Exception(message)

/**
 * What a rule sees: the current node (JSON element or HTML element), the page URL (for `absUrl`) and the
 * template variables. No rule runs code: every transform is written here.
 */
class RuleContext(
    val node: Any,
    val baseUrl: String,
    val variables: Map<String, Any?>,
) {
    fun child(node: Any) = RuleContext(node, baseUrl, variables)
}

object Rules {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        body: String,
        type: String,
        baseUrl: String,
    ): Any =
        when (type) {
            "json" ->
                runCatching {
                    json.parseToJsonElement(
                        body,
                    )
                }.getOrElse { throw RuleException("The source answered with invalid JSON.") }
            "html" -> Ksoup.parse(body, baseUrl)
            "xml" -> Ksoup.parseXml(body, baseUrl)
            else -> throw RuleException("Unknown response type: $type")
        }

    /** Nodes of a list: a JSONPath (string), a CSS selector (`{"css": …}`), or JSON embedded in an element
     *  (`{"css": "script#data", "path": "$.items[*]"}` — e.g. a `application/json` script tag). */
    fun nodes(
        ctx: RuleContext,
        selector: JsonElement,
    ): List<Any> {
        if (selector is JsonPrimitive) return JsonPath.select(ctx.node as? JsonElement ?: return emptyList(), selector.content)
        val obj = selector as JsonObject
        val css = obj["css"]?.jsonPrimitive?.content ?: throw RuleException("Unknown list selector")
        val element = ctx.node as? Element ?: throw RuleException("A CSS selector needs an HTML or XML response.")
        val elements = if (css == "" || css == ":self") listOf(element) else element.select(css).toList()
        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return elements
        val first = elements.firstOrNull() ?: return emptyList()
        // A script tag's content is a data node: text() is empty there, html() carries the raw JSON.
        val raw = first.text().ifEmpty { first.html() }
        val parsed =
            runCatching { json.parseToJsonElement(raw) }
                .getOrElse { throw RuleException("The element matched by '$css' does not contain valid JSON.") }
        return JsonPath.select(parsed, path)
    }

    /** Value of a rule: a JSONPath string, or a path / css / const / template object, then `then`, then `default`. */
    fun evaluate(
        ctx: RuleContext,
        rule: JsonElement?,
    ): Any? {
        if (rule == null || rule is JsonNull) return null
        val spec: JsonObject = if (rule is JsonPrimitive) JsonObject(mapOf("path" to rule)) else rule as JsonObject
        val all = spec["all"]?.jsonPrimitive?.booleanOrNull == true
        var value: Any? =
            when {
                "const" in spec -> plain(spec.getValue("const"))
                "template" in spec -> Template.render(spec.getValue("template").jsonPrimitive.content, ctx.variables)
                "path" in spec -> {
                    val node = ctx.node as? JsonElement ?: throw RuleException("A JSONPath needs a JSON response.")
                    val found = JsonPath.select(node, spec.getValue("path").jsonPrimitive.content).map(::plain)
                    if (all) found else found.firstOrNull()
                }
                "css" in spec -> {
                    val element = ctx.node as? Element ?: throw RuleException("A CSS rule needs an HTML or XML response.")
                    val css = spec.getValue("css").jsonPrimitive.content
                    val elements = if (css == "" || css == ":self") listOf(element) else element.select(css).toList()
                    // `attrs` is a fallback list (first non-empty wins) for lazy-loaded markup where the
                    // first node holds its URL in `src` and the rest in `data-src`.
                    val attrs = spec["attrs"]?.jsonArray?.map { it.jsonPrimitive.content }
                    val attr = spec["attr"]?.jsonPrimitive?.contentOrNull
                    val values = elements.map { htmlValue(it, attr, attrs) }
                    if (all) values else values.firstOrNull()
                }
                else -> throw RuleException("Unknown rule: ${spec.keys}")
            }
        (spec["then"] as? JsonArray)?.forEach { step -> value = transform(value, step, ctx) }
        if ((value == null || value == "" || (value is List<*> && (value as List<*>).isEmpty())) && "default" in spec) {
            return plain(spec.getValue("default"))
        }
        return value
    }

    private fun htmlValue(
        element: Element,
        attr: String?,
        attrs: List<String>?,
    ): String? {
        val names = attrs ?: listOf(attr ?: "text")
        for (name in names) {
            when (name) {
                "text" -> return element.text()
                "html" -> return element.html()
                else -> element.attr(name).ifEmpty { null }?.let { return it }
            }
        }
        return null
    }

    /** JSON element → Kotlin value (string, number, boolean); objects and arrays stay JSON (for `pluck`). */
    fun plain(element: JsonElement): Any? =
        when (element) {
            is JsonNull -> null
            is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.doubleOrNull ?: element.content
            else -> element
        }

    private fun each(
        value: Any?,
        fn: (Any?) -> Any?,
    ): Any? = if (value is List<*>) value.map(fn) else fn(value)

    private fun number(value: Any?): Double? =
        when (value) {
            null -> null
            is Number -> value.toDouble()
            else ->
                value
                    .toString()
                    .replace(",", ".")
                    .trim()
                    .toDoubleOrNull()
        }

    private fun transform(
        value: Any?,
        step: JsonElement,
        ctx: RuleContext,
    ): Any? {
        if (step is JsonPrimitive) {
            return when (step.content) {
                "trim" -> each(value) { (it as? String)?.trim() ?: it }
                "collapseSpaces" -> each(value) { (it as? String)?.replace(Regex("\\s+"), " ")?.trim() ?: it }
                "string" ->
                    each(value) {
                        when (it) {
                            null -> null
                            is Double -> if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
                            else -> it.toString()
                        }
                    }
                "lower" -> each(value) { (it as? String)?.lowercase() ?: it }
                "number" -> each(value, ::number)
                "integer" -> each(value) { number(it)?.toLong() }
                "absUrl" -> each(value) { (it as? String)?.takeIf { s -> s.isNotEmpty() }?.let { s -> absoluteUrl(ctx.baseUrl, s) } ?: it }
                "md5" -> each(value) { (it as? String)?.let(::md5Hex) ?: it }
                "first" -> if (value is List<*>) value.firstOrNull() else value
                "notNull" -> value != null && value != ""
                else -> throw RuleException("Unknown transform: ${step.content}")
            }
        }
        val spec = step as JsonObject
        return when {
            "regex" in spec -> {
                val pattern = Regex(spec.getValue("regex").jsonPrimitive.content)
                val group = spec["group"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                each(value) { v ->
                    val found = (v as? String)?.let { pattern.find(it) } ?: return@each null
                    found.groupValues.getOrNull(group ?: if (found.groupValues.size > 1) 1 else 0)
                }
            }
            "replace" in spec -> {
                val (old, new) = (spec.getValue("replace") as JsonArray).map { it.jsonPrimitive.content }
                each(value) { (it as? String)?.replace(old, new) ?: it }
            }
            "join" in spec ->
                if (value is List<*>) {
                    value.filterNotNull().joinToString(
                        spec.getValue("join").jsonPrimitive.content,
                    )
                } else {
                    value
                }
            "map" in spec -> {
                val table = spec.getValue("map") as JsonObject
                each(value) { v -> table[v.toString()]?.let(::plain) ?: spec["default"]?.let(::plain) ?: v }
            }
            "format" in spec -> {
                val template = Template.render(spec.getValue("format").jsonPrimitive.content, ctx.variables)
                each(value) { v -> v?.let { template.replace("{value}", transform(it, JsonPrimitive("string"), ctx).toString()) } }
            }
            "pluck" in spec -> {
                val path = spec.getValue("pluck").jsonPrimitive.content
                each(value) { v -> (v as? JsonElement)?.let { JsonPath.select(it, path).firstOrNull()?.let(::plain) } }
            }
            else -> throw RuleException("Unknown transform: ${spec.keys}")
        }
    }
}

/** Absolute URL of a relative link (RFC 3986, common cases: scheme, //host, /path, relative path). */
internal fun absoluteUrl(
    base: String,
    link: String,
): String {
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link)) return link
    val origin = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*:)//[^/?#]*").find(base)?.value ?: return link
    val scheme = origin.substringBefore("//")
    return when {
        link.startsWith("//") -> scheme + link
        link.startsWith("/") -> origin + link
        else -> base.substringBefore('?').substringBefore('#').substringBeforeLast('/') + "/" + link
    }
}
