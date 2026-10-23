package com.vovoscan.feature.reader.presentation

import com.vovoscan.feature.reader.domain.BookFont
import com.vovoscan.feature.reader.domain.BookLineSpacing
import com.vovoscan.feature.reader.domain.BookTheme
import com.vovoscan.feature.reader.domain.BookTypography
import kotlin.math.roundToInt

/**
 * The book page's colours. [background] is also the web view's own colour (`0xRRGGBB`), so nothing flashes while a
 * section loads and the rubber band past an edge matches the page.
 */
data class BookPalette(
    val background: Long,
    val text: String,
    val body: String,
    val muted: String,
    val line: String,
    val accent: String,
    val scheme: String,
) {
    companion object {
        fun of(theme: BookTheme): BookPalette =
            when (theme) {
                // The brand's ink and paper (the page as it always was).
                BookTheme.Dark -> BookPalette(0x0B0B0E, "#f3eee4", "rgba(243, 238, 228, .92)", "#a39e94", "#2a2a33", "#ff2d55", "dark")
                BookTheme.Sepia -> BookPalette(0xF4ECD8, "#3b2f22", "#3b2f22", "#7a6a55", "#d9cbb0", "#b3261e", "light")
                BookTheme.Light -> BookPalette(0xFBFAF7, "#1b1b1f", "#1b1b1f", "#6b6b73", "#e2e0da", "#d81b45", "light")
            }
    }
}

/**
 * HTML page for a book section, set as the reader's typography says (size, line spacing, margins, font,
 * justification, page colours). Shared by Android (WebView) and iOS (WKWebView), shown with JavaScript off: the
 * content is already sanitized, this is styling only. Fonts are the system's (the web view has no network, and the
 * page's CSP loads nothing). Headings and spacing are in `em`, so the whole page grows with the text and a position
 * kept as a fraction of the section's height still points at about the same text after a change. Links are removed
 * (`EpubParser`), so what is left of an `<a>` reads as the text around it: a link colour would promise a tap that
 * does nothing (Gutenberg books wrap headings, and through `<a id="…"/>` whole chapters, in anchors).
 */
object BookStyle {
    fun document(
        title: String,
        body: String,
        typography: BookTypography = BookTypography(),
        systemScale: Float = 1f,
    ): String {
        val palette = BookPalette.of(typography.theme)
        val size = cssNumber(typography.textPx(systemScale))
        val align = if (typography.justify) "justify" else "start"
        return """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'">
            <title>${title.escapeHtml()}</title>
            <style>
              :root { color-scheme: ${palette.scheme}; }
              html, body { margin: 0; background: ${hex(palette.background)}; color: ${palette.text}; }
              body { font-family: ${fontStack(typography.font)}; font-size: ${size}px; line-height: ${lineHeight(typography.lineSpacing)};
                     padding: 24px ${typography.margins.px}px 96px; max-width: 680px; margin: 0 auto;
                     -webkit-text-size-adjust: 100%; text-size-adjust: 100%; overflow-wrap: break-word; }
              p, li, dd { text-align: $align; }
              h1, h2, h3, h4 { font-family: inherit; font-weight: 700; line-height: 1.2; color: ${palette.text}; text-align: start; }
              h1 { font-size: 1.55em; margin: .45em 0 1.3em; } h2 { font-size: 1.25em; } h3 { font-size: 1.08em; }
              p { margin: 0 0 1em; color: ${palette.body}; }
              a { color: inherit; text-decoration: none; }
              img { max-width: 100%; height: auto; display: block; margin: 16px auto; border-radius: 4px; }
              blockquote { margin: 1em 0; padding-left: 14px; border-left: 2px solid ${palette.accent}; color: ${palette.muted}; }
              hr { border: 0; border-top: 1px solid ${palette.line}; margin: 2em 0; }
              table { border-collapse: collapse; } td, th { border: 1px solid ${palette.line}; padding: 4px 8px; }
            </style></head>
            <body>$body</body></html>
            """.trimIndent()
    }

    /** System fonts only: offline, and the same family on every page. */
    internal fun fontStack(font: BookFont): String =
        when (font) {
            BookFont.Serif -> "ui-serif, Georgia, \"Noto Serif\", \"Droid Serif\", serif"
            BookFont.Sans -> "-apple-system, system-ui, Roboto, \"Helvetica Neue\", sans-serif"
        }

    private fun lineHeight(spacing: BookLineSpacing): String = cssNumber(spacing.factor)

    /** "18", "20.7", "1.45": two decimals at most, written the same on every platform (positive values). */
    internal fun cssNumber(value: Float): String {
        val hundredths = (value * 100).roundToInt().coerceAtLeast(0)
        val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
        return if (fraction.isEmpty()) "${hundredths / 100}" else "${hundredths / 100}.$fraction"
    }

    internal fun hex(rgb: Long): String = "#" + rgb.toString(16).padStart(6, '0')

    private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
