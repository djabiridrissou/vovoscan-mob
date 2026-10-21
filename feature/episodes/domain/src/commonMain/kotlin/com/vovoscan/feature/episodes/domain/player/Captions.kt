package com.vovoscan.feature.episodes.domain.player

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * Caption text (ADR-0037, ADR-0039): premium-subtitle rules, a line-for-line port of the old web player's
 * captions.ts (itself a port of the worker's captions.py and of `tidy`/`speakable` in voice/direction.py).
 * The shared vectors (fixtures/captions.json) keep the players and the MP4 export identical.
 *
 * JavaScript regular expressions are replaced by explicit character tests: `\s` is JavaScript's whitespace set,
 * scripts are code point ranges, and lookbehinds are written out, so the JVM and Kotlin/Native behave the same.
 */

/** Two subtitle lines of 42 characters. */
internal const val CAPTION_CHARS = 84

/** Two lines of 13 full-width characters (Netflix Japanese). */
internal const val CJK_CAPTION_CHARS = 26

/** Two lines of 16 characters (Netflix Korean). */
internal const val HANGUL_CAPTION_CHARS = 32

/** Every card weighs at least this many characters when sharing the bubble's time. */
private const val CARD_FLOOR = 12

/** No card stays up longer than this (Netflix: 7 s per subtitle event). */
internal const val MAX_CARD_MS = 7000.0

/** A blank between consecutive captions so the change is seen (two frames at 24 or 30 fps). */
internal const val CARD_GAP_MS = 80.0

/** JavaScript's `\s` (WhiteSpace and LineTerminator), as code points: written as numbers so no tool rewrites them. */
private val JS_SPACES: Set<Int> =
    (0x09..0x0D).toSet() + (0x2000..0x200A) + setOf(0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)

internal fun isJsSpace(c: Char): Boolean = c.code in JS_SPACES

private val WS = "[" + JS_SPACES.joinToString("") { Char(it).toString() } + "]"
private val DOTS = Regex("(?:\\.$WS*){2,}|…+")
private val BANGS = Regex("[!?]+")
private val SPACE_BEFORE = Regex("$WS+([.!?,;:])")
private val LEADING = Regex("^[.… ]+")

/** String.prototype.trim. */
internal fun jsTrim(text: String): String = text.trim { isJsSpace(it) }

/** `text.split(/\s+/).filter(Boolean)`. */
internal fun jsWords(text: String): List<String> {
    val out = mutableListOf<String>()
    var start = -1
    text.forEachIndexed { i, c ->
        if (isJsSpace(c)) {
            if (start >= 0) out += text.substring(start, i)
            start = -1
        } else if (start < 0) {
            start = i
        }
    }
    if (start >= 0) out += text.substring(start)
    return out
}

private fun collapse(text: String) = jsWords(text).joinToString(" ")

/** Code points of [text] (`[...text]`), each as a string. */
internal fun codePoints(text: String): List<String> {
    val out = ArrayList<String>(text.length)
    var i = 0
    while (i < text.length) {
        val n = if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        out += text.substring(i, i + n)
        i += n
    }
    return out
}

/** Code point values of [text]. */
private fun codes(text: String): List<Int> =
    codePoints(text).map { if (it.length == 2) 0x10000 + ((it[0].code - 0xD800) shl 10) + (it[1].code - 0xDC00) else it[0].code }

/** `[...text].length`. */
internal fun chars(text: String): Int {
    var n = 0
    var i = 0
    while (i < text.length) {
        i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        n += 1
    }
    return n
}

private fun inRanges(
    code: Int,
    ranges: IntArray,
): Boolean {
    var i = 0
    while (i < ranges.size) {
        if (code >= ranges[i] && code <= ranges[i + 1]) return true
        i += 2
    }
    return false
}

/** Kana, CJK ideographs and full-width punctuation: written without spaces between words. */
private val WIDE = intArrayOf(0x3000, 0x30ff, 0x31f0, 0x31ff, 0x3400, 0x4dbf, 0x4e00, 0x9fff, 0xf900, 0xfaff, 0xff01, 0xff9f)
private val HANGUL = intArrayOf(0x1100, 0x11ff, 0x3130, 0x318f, 0xac00, 0xd7a3)
private val KOREAN = intArrayOf(0x1100, 0x11ff, 0x3130, 0x318f, 0xa960, 0xa97f, 0xac00, 0xd7af)
private val KANA = intArrayOf(0x3040, 0x30ff, 0x31f0, 0x31ff, 0xff66, 0xff9f)
private val HAN =
    intArrayOf(
        0x2e80,
        0x2fdf,
        0x3000,
        0x303f,
        0x3400,
        0x4dbf,
        0x4e00,
        0x9fff,
        0xf900,
        0xfaff,
        0xfe30,
        0xfe4f,
        0xff00,
        0xff65,
        0xffe0,
        0xffee,
        0x20000,
        0x3134f,
    )

private fun has(
    text: String,
    ranges: IntArray,
): Boolean = codes(text).any { inRanges(it, ranges) }

private fun isWide(piece: String): Boolean = has(piece, WIDE)

/** `/[\p{L}\p{N}]/u`: a letter or a number. */
private fun hasAlnum(text: String): Boolean =
    codes(text).any { code ->
        if (code >= 0x10000) {
            inRanges(code, intArrayOf(0x20000, 0x3134f))
        } else {
            val c = code.toChar()
            c.isLetter() ||
                c.category == CharCategory.DECIMAL_DIGIT_NUMBER ||
                c.category == CharCategory.LETTER_NUMBER ||
                c.category == CharCategory.OTHER_NUMBER
        }
    }

private fun isCased(c: String) = c.uppercase() != c.lowercase()

private fun isAllCaps(text: String): Boolean {
    val cased = codePoints(text).filter(::isCased)
    return cased.size >= 2 && cased.all { it == it.uppercase() }
}

/** tidy() in voice/direction.py: tidy dots and stacked punctuation, the case left alone. */
private fun tidy(raw: String): String {
    var text = DOTS.replace(raw, "... ")
    text = BANGS.replace(text) { m -> if ('?' in m.value && '!' in m.value) "?!" else m.value.substring(0, 1) }
    text = SPACE_BEFORE.replace(collapse(text)) { it.groupValues[1] }
    return jsTrim(LEADING.replace(text, ""))
}

private fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

/** `/(^|[!?]\s+|(?<!\.)\.\s+)([a-z])/g` → the letter in capitals. */
private fun sentenceCase(text: String): String {
    val out = StringBuilder(text)
    for (i in text.indices) {
        if (text[i] !in 'a'..'z') continue
        var starts = i == 0
        if (!starts && isJsSpace(text[i - 1])) {
            var j = i - 1
            while (j >= 0 && isJsSpace(text[j])) j -= 1
            if (j >= 0) {
                val p = text[j]
                starts = p == '!' || p == '?' || (p == '.' && (j == 0 || text[j - 1] != '.'))
            }
        }
        if (starts) out[i] = text[i].uppercaseChar()
    }
    return out.toString()
}

/** `/\bi\b/g` → "I". */
private fun capitalI(text: String): String {
    val out = StringBuilder(text)
    for (i in text.indices) {
        if (text[i] != 'i') continue
        val before = i > 0 && isWordChar(text[i - 1])
        val after = i + 1 < text.length && isWordChar(text[i + 1])
        if (!before && !after) out[i] = 'I'
    }
    return out.toString()
}

/** speakable() in voice/direction.py: tidy, then sentence case for lines in capitals. */
private fun speakable(raw: String): String {
    var text = tidy(raw)
    if (isAllCaps(text)) text = capitalI(sentenceCase(text.lowercase()))
    return text
}

/**
 * The line as a subtitle: the natural-case `display` (proper nouns kept) when there is one, tidied, else the
 * lettering read like the voice reads it; a silent reaction ("……") is shown as lettered.
 */
internal fun captionText(
    text: String,
    display: String? = null,
): String {
    val line = collapse(if (display.isNullOrEmpty()) text else display)
    if (!hasAlnum(line)) return line
    return if (!display.isNullOrEmpty()) tidy(line) else speakable(line)
}

/** Characters on two subtitle lines in the line's script. */
internal fun captionLimit(line: String): Int =
    when {
        has(line, WIDE) -> CJK_CAPTION_CHARS
        has(line, HANGUL) -> HANGUL_CAPTION_CHARS
        else -> CAPTION_CHARS
    }

/**
 * The caption's language, so the renderer picks the region's CJK font and glyph forms: Hangul is Korean, kana
 * Japanese, other ideographs or full-width punctuation Chinese; null for other scripts.
 */
internal fun captionLang(line: String): String? =
    when {
        has(line, KOREAN) -> "ko"
        has(line, KANA) -> "ja"
        has(line, HAN) -> "zh"
        else -> null
    }

/** Two pieces of a line: a space between words, none next to kana, kanji or full-width marks. */
private fun join(
    a: String,
    b: String,
): String {
    if (a.isEmpty() || b.isEmpty()) return a + b
    return if (isWide(codePoints(a).last()) || isWide(codePoints(b).first())) a + b else "$a $b"
}

private fun isComma(c: String) = c == "、" || c == "，" || c == ","

/** `/[^、，,]+[、，,]*|[、，,]+/gu`: clauses, each with the commas that close it. */
private fun clauses(sentence: String): List<String> {
    val points = codePoints(sentence)
    val out = mutableListOf<String>()
    var i = 0
    while (i < points.size) {
        val start = i
        if (!isComma(points[i])) {
            while (i < points.size && !isComma(points[i])) i += 1
        }
        while (i < points.size && isComma(points[i])) i += 1
        out += points.subList(start, i).joinToString("")
    }
    return out
}

/**
 * Words; for a sentence written without spaces (Japanese, Chinese), its clauses (cut after a comma), a clause
 * longer than `limit` taken character by character.
 */
private fun tokens(
    sentence: String,
    limit: Int,
): List<String> {
    if (sentence.contains(' ') || !isWide(sentence)) return sentence.split(' ')
    return clauses(sentence).flatMap { if (chars(it) > limit) codePoints(it) else listOf(it) }
}

/** A sentence longer than `limit` cut at word boundaries into even pieces. */
private fun balanced(
    sentence: String,
    limit: Int,
): List<String> {
    val n = ceil(chars(sentence).toDouble() / limit)
    val target = chars(sentence) / n
    val pieces = mutableListOf<String>()
    var cur = ""
    for (word in tokens(sentence, limit)) {
        val cand = join(cur, word)
        if (cur.isNotEmpty() && (chars(cand) > limit || chars(cur) >= target)) {
            pieces += cur
            cur = word
        } else {
            cur = cand
        }
    }
    if (cur.isNotEmpty()) pieces += cur
    return pieces
}

/** Sentences: `line.split(SENTENCE_END)` of captions.ts (after . ! ? … and spaces, or after 。！？), JavaScript's split rules. */
private fun sentences(line: String): List<String> {
    fun matchEnd(q: Int): Int {
        if (q == 0) return -1
        val prev = line[q - 1]
        val ends = (prev in ".!?…" && isJsSpace(line[q])) || prev in "。！？"
        if (!ends) return -1
        var e = q
        while (e < line.length && isJsSpace(line[e])) e += 1
        return e
    }
    val out = mutableListOf<String>()
    var p = 0
    var q = 0
    while (q < line.length) {
        val e = matchEnd(q)
        if (e < 0 || e == p) {
            q += 1
            continue
        }
        out += line.substring(p, q)
        p = e
        q = p
    }
    out += line.substring(p)
    return out
}

private fun splitCards(
    line: String,
    limit: Int,
): List<String> {
    if (line.isEmpty()) return emptyList()
    if (chars(line) <= limit) return listOf(line)
    val cards = mutableListOf<String>()
    var cur = ""
    for (sentence in sentences(line).filter { it.isNotEmpty() }) {
        for (piece in if (chars(sentence) > limit) balanced(sentence, limit) else listOf(sentence)) {
            val cand = join(cur, piece)
            if (cur.isNotEmpty() && chars(cand) > limit) {
                cards += cur
                cur = piece
            } else {
                cur = cand
            }
        }
    }
    if (cur.isNotEmpty()) cards += cur
    return cards
}

/**
 * The caption cards of a line, in order; none for an empty line. Over `durationMs`, enough cards that none needs
 * more than MAX_CARD_MS (while a card can be split).
 */
internal fun captionCards(
    text: String,
    durationMs: Int? = null,
    limit: Int? = null,
    display: String? = null,
): List<String> {
    val line = captionText(text, display)
    val cards = splitCards(line, limit ?: captionLimit(line)).toMutableList()
    val need = if (durationMs != null && durationMs != 0) ceil(durationMs / MAX_CARD_MS).toInt() else 1
    while (cards.size < need) {
        var i = -1
        cards.forEachIndexed { k, c -> if (i < 0 || chars(c) > chars(cards[i])) i = k }
        val half = if (i >= 0) ceil(chars(cards[i]) / 2.0).toInt() else 0
        if (i < 0 || tokens(cards[i], half).size < 2) break
        val pieces = balanced(cards[i], half)
        cards.removeAt(i)
        cards.addAll(i, pieces)
    }
    return cards
}

/**
 * Index of the card shown at `elapsed` ms of a `duration` ms bubble; -1 for none (the blank between two cards, or a
 * card past MAX_CARD_MS). `after`: the previous bubble had a caption, so the first card waits for the blank too.
 */
internal fun cardAt(
    cards: List<String>,
    elapsed: Double,
    duration: Double,
    after: Boolean = false,
): Int {
    if (cards.isEmpty()) return -1
    val weights = cards.map { max(CARD_FLOOR, chars(it)) }
    val total = weights.sum()
    val at = min(1.0, max(0.0, elapsed / duration)) * total
    var index = 0
    var acc = 0
    while (index < cards.size - 1 && at >= acc + weights[index]) {
        acc += weights[index]
        index += 1
    }
    val since = elapsed - (acc.toDouble() / total) * duration
    if (((index > 0 || after) && since < CARD_GAP_MS) || since >= MAX_CARD_MS) return -1
    return index
}
