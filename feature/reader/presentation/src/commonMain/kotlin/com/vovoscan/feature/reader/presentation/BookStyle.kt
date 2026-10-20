package com.vovoscan.feature.reader.presentation

/**
 * HTML page for a book section, in brand colors (dark, paper-colored text). Shared by Android (WebView) and
 * iOS (WKWebView), shown with JavaScript off: the content is already sanitized, this is styling only.
 */
object BookStyle {
    fun document(
        title: String,
        body: String,
    ): String =
        """
        <!DOCTYPE html>
        <html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'">
        <title>${title.escapeHtml()}</title>
        <style>
          :root { color-scheme: dark; }
          html, body { margin: 0; background: #0b0b0e; color: #f3eee4; }
          body { font: 18px/1.7 "Space Grotesk", -apple-system, "Roboto", system-ui, sans-serif; padding: 24px 20px 96px;
                 max-width: 680px; margin: 0 auto; -webkit-text-size-adjust: 100%; overflow-wrap: break-word; }
          h1, h2, h3 { font-family: "Dela Gothic One", "Space Grotesk", system-ui, sans-serif; font-weight: 400;
                       line-height: 1.15; color: #f3eee4; }
          h1 { font-size: 28px; margin: 8px 0 24px; } h2 { font-size: 22px; } h3 { font-size: 19px; }
          p { margin: 0 0 1em; color: rgba(243, 238, 228, .92); }
          a { color: #ff2d55; text-decoration: none; }
          img { max-width: 100%; height: auto; display: block; margin: 16px auto; border-radius: 4px; }
          blockquote { margin: 1em 0; padding-left: 14px; border-left: 2px solid #ff2d55; color: #a39e94; }
          hr { border: 0; border-top: 1px solid #2a2a33; margin: 2em 0; }
          table { border-collapse: collapse; } td, th { border: 1px solid #2a2a33; padding: 4px 8px; }
        </style></head>
        <body>$body</body></html>
        """.trimIndent()

    private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
