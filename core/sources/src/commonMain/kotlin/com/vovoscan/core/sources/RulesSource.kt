package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RulesTitleSummary(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val kind: String,
    val format: String,
    val status: String,
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
    /** Dans l'ordre de lecture (numéros croissants). */
    val units: List<RulesUnit>,
)

data class RulesImage(
    val url: String,
    val width: Int,
    val height: Int,
)

/**
 * Une source à règles exécutée sur l'appareil : catalogue, fiche, pages d'un comic, fichier d'un livre.
 * Les erreurs de règles deviennent `Rejected("SOURCE_RULES")`, les coupures `Network`.
 */
class RulesSource(
    val manifest: SourceManifest,
    private val http: RulesHttp,
) {
    private val json: JsonObject get() = manifest.json

    private fun vars(vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf("settings" to manifest.settings, "page" to 1, "search" to "", "lang" to "") + extra

    private suspend fun load(
        request: JsonObject,
        variables: Map<String, Any?>,
    ): AppResult<RuleContext> {
        val url = Template.render(request.getValue("url").jsonPrimitive.content, variables)
        val query =
            (request["query"] as? JsonObject)
                .orEmpty()
                .mapValues { (_, v) -> Template.render(v.jsonPrimitive.content, variables) }
                .filterValues { it.isNotEmpty() }
        return when (val fetched = http.get(manifest, url, query, maxBytes = 5 * 1024 * 1024)) {
            is AppResult.Err -> fetched
            is AppResult.Ok -> {
                val type = request.getValue("type").jsonPrimitive.content
                rules {
                    RuleContext(
                        Rules.parse(fetched.value.body.decodeToString(), type, fetched.value.finalUrl),
                        fetched.value.finalUrl,
                        variables,
                    )
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
            AppResult.Err(AppError.Rejected("SOURCE_RULES", e.message ?: "Unreadable answer from ${manifest.name}."))
        } catch (e: IllegalArgumentException) {
            AppResult.Err(AppError.Rejected("SOURCE_RULES", e.message ?: "Unreadable answer from ${manifest.name}."))
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
        )
    }

    suspend fun catalog(
        page: Int,
        search: String = "",
    ): AppResult<RulesPage> {
        val section = json.getValue("catalog").jsonObject
        val loaded = load(section.getValue("request").jsonObject, vars("page" to page, "search" to search))
        val ctx = (loaded as? AppResult.Ok)?.value ?: return loaded as AppResult.Err
        return rules {
            val item = section.getValue("item").jsonObject
            val items =
                Rules.nodes(ctx, section.getValue("list")).mapNotNull { node ->
                    val child = ctx.child(node)
                    str(Rules.evaluate(child, item["id"]))?.let { summary(child, item, it) }
                }
            RulesPage(
                items = items,
                hasNext = Rules.evaluate(ctx, section["hasNext"]) == true,
                total = (Rules.evaluate(ctx, section["total"]) as? Double)?.toInt(),
            )
        }
    }

    suspend fun title(id: String): AppResult<RulesTitle> {
        val section = json.getValue("title").jsonObject
        val loaded = load(section.getValue("request").jsonObject, vars("id" to id))
        val ctx = (loaded as? AppResult.Ok)?.value ?: return loaded as AppResult.Err
        return rules {
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
                units = units(ctx, section.getValue("units").jsonObject, id),
            )
        }
    }

    private fun list(value: Any?): List<String> =
        when (value) {
            null -> emptyList()
            // Avant `List` : un JsonArray est aussi une List, dont les éléments garderaient leurs guillemets.
            is JsonElement -> JsonPath.select(value, "$[*]").mapNotNull { str(Rules.plain(it)) }
            is List<*> -> value.mapNotNull(::str)
            else -> listOfNotNull(str(value))
        }

    private fun units(
        ctx: RuleContext,
        rules: JsonObject,
        titleId: String,
    ): List<RulesUnit> {
        (rules["single"] as? JsonObject)?.let { single ->
            val unitId = Template.render(single.getValue("id").jsonPrimitive.content, ctx.variables + ("id" to titleId))
            return listOf(RulesUnit(unitId, 1.0, single["name"]?.jsonPrimitive?.contentOrNull))
        }
        val item = rules.getValue("item").jsonObject
        val seen = mutableSetOf<Double>()
        return Rules
            .nodes(ctx, rules.getValue("list"))
            .mapIndexedNotNull { index, node ->
                val child = ctx.child(node)
                val unitId = str(Rules.evaluate(child, item["id"])) ?: return@mapIndexedNotNull null
                val number = (Rules.evaluate(child, item["number"]) as? Double) ?: (index + 1).toDouble()
                // Deux unités au même numéro casseraient l'ordre de lecture : on garde la première.
                if (!seen.add(number)) return@mapIndexedNotNull null
                RulesUnit(unitId, number, str(Rules.evaluate(child, item["name"])))
            }.sortedBy { it.number }
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

    /** URL du fichier EPUB d'une unité de livre. */
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

    /** Fichier d'un livre ou d'une image, chez la source (hôtes autorisés seulement). */
    suspend fun download(url: String): AppResult<ByteArray> =
        when (val fetched = http.get(manifest, url)) {
            is AppResult.Err -> fetched
            is AppResult.Ok -> AppResult.Ok(fetched.value.body)
        }

    private fun <T> noContent(kind: String): AppResult<T> =
        AppResult.Err(AppError.Rejected("SOURCE_RULES", "${manifest.name} has no $kind content."))
}
