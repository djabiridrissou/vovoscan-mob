package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.SOURCE_RULES
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RulesTitleSummary(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val kind: String,
    val format: String,
    val status: String,
    /** `safe`, `suggestive`, `erotica`, `pornographic`, or null when the source doesn't say (unknown). */
    val contentRating: String? = null,
)

data class RulesPage(
    val items: List<RulesTitleSummary>,
    val hasNext: Boolean,
    val total: Int?,
)

data class RulesUnit(
    val id: String,
    val number: Double,
    val name: String?,
)

data class RulesTitle(
    val summary: RulesTitleSummary,
    val description: String,
    val authors: List<String>,
    val genres: List<String>,
    /** In reading order (ascending numbers). */
    val units: List<RulesUnit>,
)

/** A category of the site (`categories` in the manifest): its id is `{{category}}` in the catalog request. */
data class RulesCategory(
    val id: String,
    val name: String,
)

data class RulesImage(
    val url: String,
    val width: Int,
    val height: Int,
)

/**
 * A rule-based source run on the device: catalog, title details, comic pages, book file.
 * Rule errors become `Rejected(SOURCE_RULES)`; a source that doesn't answer `SourceUnavailable`, no network `Network`.
 */
class RulesSource(
    val manifest: SourceManifest,
    private val http: RulesHttp,
) {
    private val json: JsonObject get() = manifest.json

    /**
     * The catalog request sends `{{search}}` somewhere: the site does the matching. A manifest without it lists the
     * same titles whatever is typed, so its search keeps only the titles whose name contains the query.
     */
    val searchable: Boolean
        get() =
            json["catalog"]
                ?.jsonObject
                ?.get("request")
                ?.toString()
                ?.let { SEARCH_PLACEHOLDER.containsMatchIn(it) } == true

    private fun vars(vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf("settings" to manifest.settings, "page" to 1, "search" to "", "lang" to deviceLanguage()) + extra

    private suspend fun load(
        request: JsonObject,
        variables: Map<String, Any?>,
    ): AppResult<RuleContext> {
        val url = Template.render(request.getValue("url").jsonPrimitive.content, variables)
        val method = request["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val body = request["body"]?.jsonPrimitive?.contentOrNull?.let { Template.render(it, variables) }
        val contentType = request["contentType"]?.jsonPrimitive?.contentOrNull
        // Query values are usually plain templates; an object is a rule evaluated before the request is
        // sent (no response yet), e.g. a signed token: {"_t": {"template": "{{unix}}…", "then": ["md5"]}}.
        val query = linkedMapOf<String, String>()
        (request["query"] as? JsonObject).orEmpty().forEach { (key, value) ->
            val rendered =
                when (value) {
                    is JsonObject ->
                        str(Rules.evaluate(RuleContext(JsonObject(emptyMap()), url, variables), value))
                    else -> Template.render(value.jsonPrimitive.content, variables)
                }
            if (!rendered.isNullOrEmpty()) query[key] = rendered
        }
        (request["pagination"] as? JsonObject)
            ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "startIndex" }
            ?.let { pagination ->
                val page = (variables["page"] as? Number)?.toInt() ?: 1
                val pageSize = pagination.getValue("pageSize").jsonPrimitive.int
                val base = pagination["base"]?.jsonPrimitive?.intOrNull ?: 1
                val start = base + (page - 1) * pageSize
                if (start != base) query[pagination.getValue("param").jsonPrimitive.content] = start.toString()
            }
        return when (val fetched = http.get(manifest, url, query, Transfer.Page, method = method, body = body, contentType = contentType)) {
            is AppResult.Err -> fetched
            is AppResult.Ok -> {
                val type = request.getValue("type").jsonPrimitive.content
                rules {
                    val loaded =
                        RuleContext(
                            Rules.parse(fetched.value.body.decodeToString(), type, fetched.value.finalUrl),
                            fetched.value.finalUrl,
                            variables,
                        )
                    // `let` evaluates rules on the whole response and names the results for the section's
                    // templates (e.g. an image host and a chapter hash used to build every page URL).
                    val declared = (request["let"] as? JsonObject).orEmpty()
                    if (declared.isEmpty()) {
                        loaded
                    } else {
                        val named =
                            declared
                                .mapValues { (_, rule) -> str(Rules.evaluate(loaded, rule)) }
                                .filterValues { it != null }
                        RuleContext(loaded.node, loaded.baseUrl, loaded.variables + named)
                    }
                }
            }
        }
    }

    private inline fun <T> rules(block: () -> T): AppResult<T> =
        try {
            if (manifest.contractVersion != RULES_CONTRACT_VERSION) {
                AppResult.Err(AppError.Rejected("CONTRACT_UNSUPPORTED", "Update the app to read ${manifest.name}."))
            } else {
                AppResult.Ok(block())
            }
        } catch (e: RuleException) {
            AppResult.Err(AppError.Rejected(SOURCE_RULES, e.message ?: "Unreadable answer from ${manifest.name}."))
        } catch (e: IllegalArgumentException) {
            AppResult.Err(AppError.Rejected(SOURCE_RULES, e.message ?: "Unreadable answer from ${manifest.name}."))
        }

    private fun str(value: Any?): String? =
        when (value) {
            null -> null
            is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            else -> value.toString().takeIf { it.isNotEmpty() }
        }

    private fun defaultKind() = manifest.kinds.firstOrNull() ?: "comic"

    private fun defaultFormat(kind: String) = if (kind == "book") "text" else "manga"

    private fun summary(
        ctx: RuleContext,
        item: JsonObject,
        id: String,
    ): RulesTitleSummary? {
        val name = str(Rules.evaluate(ctx, item["name"])) ?: return null
        val kind = str(Rules.evaluate(ctx, item["kind"])) ?: defaultKind()
        return RulesTitleSummary(
            id = id,
            name = name,
            coverUrl = str(Rules.evaluate(ctx, item["cover"])),
            kind = kind,
            format = str(Rules.evaluate(ctx, item["format"])) ?: defaultFormat(kind),
            status = str(Rules.evaluate(ctx, item["status"])) ?: "ongoing",
            contentRating = rating(str(Rules.evaluate(ctx, item["contentRating"]))),
        )
    }

    /**
     * The value the item rule read, mapped to the shared scale by the manifest's `ratings.map` (the source's own words,
     * "R18" → "pornographic"), else taken as it is when it already is one, else unknown (null); nothing read:
     * `ratings.default`, else unknown.
     */
    private fun rating(read: String?): String? {
        val ratings = json["ratings"] as? JsonObject
        val scale = setOf("safe", "suggestive", "erotica", "pornographic")
        val value = read?.trim()?.takeIf { it.isNotEmpty() }
        val mapped =
            value?.let { v ->
                (ratings?.get("map") as? JsonObject)
                    ?.entries
                    ?.firstOrNull { it.key.lowercase() == v.lowercase() }
                    ?.value
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: v.lowercase().takeIf { it in scale }
            }
        // A value the manifest doesn't map stays unknown: the default is only for titles the site doesn't rate.
        val rating = if (value != null) mapped else ratings?.get("default")?.jsonPrimitive?.contentOrNull
        return rating?.lowercase()?.takeIf { it in scale }
    }

    /**
     * The site's categories: the manifest's fixed list, or read from its own request (`list` + `item {id, name}`).
     * None when the manifest declares no categories.
     */
    suspend fun categories(): AppResult<List<RulesCategory>> {
        val section = json["categories"] ?: return AppResult.Ok(emptyList())
        if (section is kotlinx.serialization.json.JsonArray) {
            return rules {
                section
                    .mapNotNull { entry ->
                        val item = entry as? JsonObject ?: return@mapNotNull null
                        val id = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        val name =
                            item["name"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        RulesCategory(id, name)
                    }.distinctBy { it.id }
            }
        }
        val request = section.jsonObject.getValue("request").jsonObject
        val loaded = load(request, vars())
        val ctx = (loaded as? AppResult.Ok)?.value ?: return loaded as AppResult.Err
        return rules {
            val item = section.jsonObject.getValue("item").jsonObject
            Rules
                .nodes(ctx, section.jsonObject.getValue("list"))
                .mapNotNull { node ->
                    val child = ctx.child(node)
                    val id = str(Rules.evaluate(child, item["id"])) ?: return@mapNotNull null
                    val name = str(Rules.evaluate(child, item["name"]))?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    RulesCategory(id, name)
                }.distinctBy { it.id }
        }
    }

    /** `category`: a category id from [categories], empty for everything (the empty query value is not sent). */
    suspend fun catalog(
        page: Int,
        search: String = "",
        category: String = "",
    ): AppResult<RulesPage> {
        val section = json.getValue("catalog").jsonObject
        val request = section.getValue("request").jsonObject
        val cursor =
            (request["pagination"] as? JsonObject)
                ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "cursor" }
        var variables = vars("page" to page, "search" to search, "category" to category)
        var finalCtx: RuleContext? = null
        var stoppedEarly = false
        for (p in 1..page) {
            val loaded = load(request, variables)
            val loadedCtx = (loaded as? AppResult.Ok)?.value ?: return loaded as AppResult.Err
            finalCtx = loadedCtx
            if (p == page || cursor == null) break
            val next = rules { str(Rules.evaluate(loadedCtx, cursor.getValue("from"))) }
            val cursorValue = (next as? AppResult.Ok)?.value
            if (cursorValue.isNullOrEmpty()) {
                stoppedEarly = true
                break
            }
            variables = vars("page" to p + 1, "search" to search, "category" to category) + ("cursor" to cursorValue)
        }
        val ctx = finalCtx ?: return AppResult.Err(AppError.Rejected(SOURCE_RULES, "${manifest.name} returned no page."))
        return rules {
            val hasNext =
                when {
                    cursor == null -> null
                    stoppedEarly -> false
                    else -> str(Rules.evaluate(ctx, cursor.getValue("from"))) != null
                }
            val found = page(ctx, section, hasNext)
            if (search.isBlank() || searchable) found else found.copy(items = found.items.filter { matches(it.name, search) }, total = null)
        }
    }

    private fun matches(
        name: String,
        query: String,
    ): Boolean {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        val haystack = name.lowercase()
        return words.all { haystack.contains(it) }
    }

    private fun page(
        ctx: RuleContext,
        section: JsonObject,
        hasNextOverride: Boolean?,
    ): RulesPage {
        val item = section.getValue("item").jsonObject
        val items =
            Rules.nodes(ctx, section.getValue("list")).mapNotNull { node ->
                val child = ctx.child(node)
                // The item's own id is visible to its other rules: covers often need it (e.g. /covers/{{id}}/…).
                str(Rules.evaluate(child, item["id"]))?.let { id ->
                    summary(RuleContext(child.node, child.baseUrl, child.variables + ("id" to id)), item, id)
                }
            }
        return RulesPage(
            items = items,
            hasNext = hasNextOverride ?: (Rules.evaluate(ctx, section["hasNext"]) == true),
            total = (Rules.evaluate(ctx, section["total"]) as? Double)?.toInt(),
        )
    }

    suspend fun title(id: String): AppResult<RulesTitle> {
        val section = json.getValue("title").jsonObject
        val loaded = load(section.getValue("request").jsonObject, vars("id" to id))
        val rawCtx = (loaded as? AppResult.Ok)?.value ?: return loaded as AppResult.Err
        // `title.let` names values read from the title's own response and makes them template variables
        // for the units request and the content rules (e.g. the site's numeric id of the series).
        val named = (section["let"] as? JsonObject).orEmpty().mapValues { (_, rule) -> str(Rules.evaluate(rawCtx, rule)) }
        val ctx =
            if (named.isEmpty()) {
                rawCtx
            } else {
                RuleContext(rawCtx.node, rawCtx.baseUrl, rawCtx.variables + named)
            }
        return when (val titleUnits = units(ctx, section.getValue("units").jsonObject, id)) {
            is AppResult.Err -> titleUnits
            is AppResult.Ok ->
                rules {
                    val fields = section.getValue("fields").jsonObject
                    val kind = defaultKind()
                    val head =
                        summary(ctx, fields, id)?.copy(kind = kind)
                            ?: throw RuleException("The title has no name.")
                    RulesTitle(
                        summary = head,
                        description = str(Rules.evaluate(ctx, fields["description"])).orEmpty(),
                        authors = list(Rules.evaluate(ctx, fields["authors"])),
                        genres = list(Rules.evaluate(ctx, fields["genres"])),
                        units = titleUnits.value,
                    )
                }
        }
    }

    private fun list(value: Any?): List<String> =
        when (value) {
            null -> emptyList()
            // Before `List`: a JsonArray is also a List, whose elements would keep their quotes.
            is JsonElement -> JsonPath.select(value, "$[*]").mapNotNull { str(Rules.plain(it)) }
            is List<*> -> value.mapNotNull(::str)
            else -> listOfNotNull(str(value))
        }

    /**
     * Units of a title. From the title's own response by default; a `request` in the units section fetches a
     * separate list instead (e.g. an API endpoint for chapters), with `{{id}}` and `{{lang}}` available.
     */
    private suspend fun units(
        ctx: RuleContext,
        section: JsonObject,
        titleId: String,
    ): AppResult<List<RulesUnit>> {
        (section["single"] as? JsonObject)?.let { single ->
            val unitId = Template.render(single.getValue("id").jsonPrimitive.content, ctx.variables + ("id" to titleId))
            return AppResult.Ok(listOf(RulesUnit(unitId, 1.0, single["name"]?.jsonPrimitive?.contentOrNull)))
        }
        val listCtx =
            (section["request"] as? JsonObject)?.let { request ->
                when (val loaded = load(request, ctx.variables + ("id" to titleId))) {
                    is AppResult.Err -> return loaded
                    is AppResult.Ok -> loaded.value
                }
            } ?: ctx
        return rules {
            val item = section.getValue("item").jsonObject
            val seen = mutableSetOf<Double>()
            Rules
                .nodes(listCtx, section.getValue("list"))
                .mapIndexedNotNull { index, node ->
                    val child = listCtx.child(node)
                    val unitId = str(Rules.evaluate(child, item["id"])) ?: return@mapIndexedNotNull null
                    // The unit's own id is visible to its other rules (e.g. a pages URL built from {{id}}).
                    val unit = RuleContext(child.node, child.baseUrl, child.variables + ("id" to unitId))
                    val number = (Rules.evaluate(unit, item["number"]) as? Double) ?: (index + 1).toDouble()
                    // Two units with the same number would break the reading order: keep the first one.
                    if (!seen.add(number)) return@mapIndexedNotNull null
                    RulesUnit(unitId, number, str(Rules.evaluate(unit, item["name"])))
                }.sortedBy { it.number }
        }
    }

    suspend fun comicPages(
        titleId: String,
        unitId: String,
    ): AppResult<List<RulesImage>> {
        val section = json["content"]?.jsonObject?.get("comic")?.jsonObject ?: return noContent("comic")
        val loaded = load(section.getValue("request").jsonObject, vars("id" to titleId, "unitId" to unitId))
        val ctx = (loaded as? AppResult.Ok)?.value ?: return loaded as AppResult.Err
        return rules {
            val item = section.getValue("item").jsonObject
            Rules.nodes(ctx, section.getValue("list")).mapNotNull { node ->
                val child = ctx.child(node)
                val url = str(Rules.evaluate(child, item["url"])) ?: return@mapNotNull null
                RulesImage(
                    url = url,
                    width = (Rules.evaluate(child, item["width"]) as? Number)?.toInt() ?: 0,
                    height = (Rules.evaluate(child, item["height"]) as? Number)?.toInt() ?: 0,
                )
            }
        }
    }

    /** URL of the EPUB file for a book unit. */
    suspend fun bookUrl(
        titleId: String,
        unitId: String,
    ): AppResult<String> {
        val section = json["content"]?.jsonObject?.get("book")?.jsonObject ?: return noContent("book")
        val variables = vars("id" to titleId, "unitId" to unitId)
        val request = section["request"] as? JsonObject
        val ctx =
            if (request != null) {
                when (val loaded = load(request, variables)) {
                    is AppResult.Err -> return loaded
                    is AppResult.Ok -> loaded.value
                }
            } else {
                RuleContext(JsonObject(emptyMap()), "", variables)
            }
        return rules {
            str(Rules.evaluate(ctx, section.getValue("document").jsonObject["url"])) ?: throw RuleException("No book file for this title.")
        }
    }

    /** A book file, fetched from the source (allowed hosts only). */
    suspend fun download(url: String): AppResult<ByteArray> =
        when (val fetched = http.get(manifest, url, transfer = Transfer.File)) {
            is AppResult.Err -> fetched
            is AppResult.Ok -> AppResult.Ok(fetched.value.body)
        }

    /** A page image, fetched from the source (allowed hosts only) with its Referer when the manifest declares one. */
    suspend fun downloadImage(url: String): AppResult<ByteArray> =
        when (val fetched = http.get(manifest, url, transfer = Transfer.Image, referer = manifest.imageReferer)) {
            is AppResult.Err -> fetched
            is AppResult.Ok -> AppResult.Ok(fetched.value.body)
        }

    private fun <T> noContent(kind: String): AppResult<T> =
        AppResult.Err(AppError.Rejected(SOURCE_RULES, "${manifest.name} has no $kind content."))
}

/** `{{search}}` in a request (braces escaped: Android's ICU engine rejects a bare `}`). */
private val SEARCH_PLACEHOLDER = Regex("\\{\\{\\s*search\\s*\\}\\}")
