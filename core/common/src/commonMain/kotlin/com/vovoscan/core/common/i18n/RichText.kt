package com.vovoscan.core.common.i18n

/** One run of a catalogue value with tags (SPEC §5: `<b>…</b>`): the native side draws [bold] runs in bold. */
data class RichRun(
    val text: String,
    val bold: Boolean,
)

/** Catalogue values carry no HTML, only `<b>` tags mapped here; anything else stays as text. */
object RichText {
    fun runs(value: String): List<RichRun> {
        val runs = mutableListOf<RichRun>()
        var rest = value
        while (rest.isNotEmpty()) {
            val open = rest.indexOf("<b>")
            val close = if (open < 0) -1 else rest.indexOf("</b>", open + 3)
            if (open < 0 || close < 0) {
                runs += RichRun(rest, false)
                break
            }
            if (open > 0) runs += RichRun(rest.substring(0, open), false)
            runs += RichRun(rest.substring(open + 3, close), true)
            rest = rest.substring(close + 4)
        }
        return runs.filter { it.text.isNotEmpty() }
    }

    /** The same value without its tags (accessibility labels, plain text). */
    fun plain(value: String): String = runs(value).joinToString("") { it.text }
}
