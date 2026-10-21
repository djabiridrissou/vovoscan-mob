package com.vovoscan.feature.episodes.domain.player

import com.vovoscan.feature.episodes.domain.Box
import com.vovoscan.feature.episodes.domain.BubbleSound
import com.vovoscan.feature.episodes.domain.Direction
import com.vovoscan.feature.episodes.domain.LoopCue
import com.vovoscan.feature.episodes.domain.PageSize
import com.vovoscan.feature.episodes.domain.Point
import com.vovoscan.feature.episodes.domain.Quad
import com.vovoscan.feature.episodes.domain.Timeline
import com.vovoscan.feature.episodes.domain.TimelineBubble
import com.vovoscan.feature.episodes.domain.TimelinePage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertTrue
import kotlin.test.fail

/** Reads the shared vectors (JSON trees, no serializers) into the player core's types. */
internal object VectorJson {
    fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}

internal fun JsonObject.cases(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }

internal fun JsonElement?.isNull(): Boolean = this == null || this is JsonNull

internal fun JsonObject.str(name: String): String? = this[name]?.takeIf { !it.isNull() }?.jsonPrimitive?.contentOrNull

internal fun JsonObject.num(name: String): Double? = this[name]?.takeIf { !it.isNull() }?.jsonPrimitive?.doubleOrNull

internal fun JsonObject.flag(name: String): Boolean? = this[name]?.takeIf { !it.isNull() }?.jsonPrimitive?.booleanOrNull

internal fun JsonElement.box(): Box = jsonArray.map { it.jsonPrimitive.double }.let { Box(it[0], it[1], it[2], it[3]) }

internal fun JsonObject.box(name: String): Box? = this[name]?.takeIf { !it.isNull() }?.box()

internal fun JsonObject.page(): PageSize =
    getValue("page").jsonArray.let {
        PageSize(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double)
    }

internal fun JsonElement.quad(): Quad =
    jsonArray
        .map { p ->
            p.jsonArray.let { Point(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
        }.let { Quad(it[0], it[1], it[2], it[3]) }

internal fun JsonObject.direction(): Direction = if (str("direction") == "ltr") Direction.Ltr else Direction.Rtl

internal fun JsonElement.take(): Take =
    jsonObject.let { Take(it.num("startMs")!!, it.num("totalMs")!!, it.num("leadMs")!!, it.flag("wide")!!) }

internal fun JsonObject.take(name: String): Take? = this[name]?.takeIf { !it.isNull() }?.take()

internal fun JsonElement.sound(): BubbleSound =
    jsonObject.let {
        BubbleSound(
            audioKey = it.str("audioKey") ?: "audio/aa/x.ogg",
            durationMs = it.num("durationMs")?.toInt() ?: 1000,
            kind = it.str("kind") ?: "sfx",
            spanMs = it.num("spanMs")?.toInt() ?: 2000,
            pan = it.num("pan") ?: 0.0,
            panEnd = it.num("panEnd"),
            impact = it.flag("impact"),
        )
    }

internal fun JsonElement.bubble(): TimelineBubble {
    val o = jsonObject
    val tags = o["tags"]?.takeIf { !it.isNull() }?.jsonObject
    return TimelineBubble(
        id = o.str("id") ?: "",
        page = o.num("page")?.toInt() ?: 0,
        panelBbox = o.box("panelBbox")!!,
        bubbleBbox = o.box("bubbleBbox")!!,
        text = o.str("text") ?: "",
        display = o.str("display"),
        shout = o.flag("shout") ?: false,
        tone = o.str("tone") ?: "normal",
        audioKey = o.str("audioKey"),
        durationMs = o.num("durationMs")!!.toInt(),
        leadMs = o.num("leadMs")?.toInt() ?: 0,
        onomatopoeia = tags?.get("onomatopoeia")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        panelShape = o["panelShape"]?.takeIf { !it.isNull() }?.quad(),
        detailBbox = o.box("detailBbox"),
        keepBbox = o.box("keepBbox"),
        sounds = (o["sounds"] as? JsonArray)?.map { it.sound() } ?: emptyList(),
        pan = o.num("pan") ?: 0.0,
    )
}

internal fun JsonObject.bubble(name: String): TimelineBubble? = this[name]?.takeIf { !it.isNull() }?.bubble()

internal fun JsonElement.loop(): LoopCue =
    jsonObject.let {
        LoopCue(
            it.str("audioKey") ?: "",
            it.num("durationMs")!!.toInt(),
            it.num("startMs")!!.toLong(),
            it.num("endMs")!!.toLong(),
            it.str("mood"),
        )
    }

internal fun JsonObject.timeline(): Timeline =
    Timeline(
        chapterHash = str("chapterHash") ?: "",
        renderKey = str("renderKey") ?: "",
        pages =
            getValue("pages").jsonArray.map {
                it.jsonObject.let { p -> TimelinePage(p.num("index")!!.toInt(), p.num("width")!!.toInt(), p.num("height")!!.toInt()) }
            },
        bubbles = getValue("bubbles").jsonArray.map { it.bubble() },
        direction = direction(),
        beds = (this["beds"] as? JsonArray)?.map { it.loop() } ?: emptyList(),
        music = (this["music"] as? JsonArray)?.map { it.loop() } ?: emptyList(),
    )

/** vitest's `toBeCloseTo(expected, digits)`: |actual − expected| < 10^−digits / 2. */
internal fun assertClose(
    expected: Double,
    actual: Double,
    digits: Int = 4,
    what: String = "",
) {
    val ok = abs(expected - actual) < 10.0.pow(-digits) / 2
    if (!ok) fail("$what: expected $expected, got $actual (to $digits digits)")
}

internal fun assertClose(
    expected: Box,
    actual: Box,
    digits: Int = 4,
    what: String = "",
) = (0..3).forEach { assertClose(expected[it], actual[it], digits, "$what[$it]") }

internal fun assertClose(
    expected: Quad,
    actual: Quad,
    digits: Int = 4,
    what: String = "",
) = expected.points.zip(actual.points).forEachIndexed { i, (e, a) ->
    assertClose(e.x, a.x, digits, "$what[$i].x")
    assertClose(e.y, a.y, digits, "$what[$i].y")
}

internal fun assertAtLeast(
    bound: Double,
    actual: Double,
    what: String = "",
) = assertTrue(actual >= bound, "$what: $actual < $bound")

internal fun assertAtMost(
    bound: Double,
    actual: Double,
    what: String = "",
) = assertTrue(actual <= bound, "$what: $actual > $bound")
