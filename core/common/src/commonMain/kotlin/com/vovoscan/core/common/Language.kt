package com.vovoscan.core.common

/**
 * A language of the translations and episodes (the server's `core/languages.py`, ADR-0044): one translation per chapter
 * and language, shared by everyone, and an episode in it.
 */
data class Language(
    val code: String,
    /** English name. */
    val name: String,
    /** Own name ("Français"). */
    val native: String,
    val rtl: Boolean,
    /** ElevenLabs speaks it: an episode in it is dubbed; otherwise the original voices with captions in it. */
    val dub: Boolean,
    /** The MP4 export can draw its captions. */
    val mp4: Boolean,
    /** Low-resource: a less reliable translation. */
    val beta: Boolean,
) {
    companion object {
        fun of(code: String): Language? = ALL.firstOrNull { it.code == code }

        /** Mirror of the server's table, in its order. */
        val ALL: List<Language> =
            listOf(
                Language("en", "English", "English", rtl = false, dub = true, mp4 = true, beta = false),
                Language("fr", "French", "Français", rtl = false, dub = true, mp4 = true, beta = false),
                Language("es", "Spanish", "Español", rtl = false, dub = true, mp4 = true, beta = false),
                Language("pt", "Portuguese", "Português", rtl = false, dub = true, mp4 = true, beta = false),
                Language("de", "German", "Deutsch", rtl = false, dub = true, mp4 = true, beta = false),
                Language("it", "Italian", "Italiano", rtl = false, dub = true, mp4 = true, beta = false),
                Language("nl", "Dutch", "Nederlands", rtl = false, dub = true, mp4 = true, beta = false),
                Language("pl", "Polish", "Polski", rtl = false, dub = true, mp4 = true, beta = false),
                Language("ro", "Romanian", "Română", rtl = false, dub = true, mp4 = true, beta = false),
                Language("tr", "Turkish", "Türkçe", rtl = false, dub = true, mp4 = true, beta = false),
                Language("ru", "Russian", "Русский", rtl = false, dub = true, mp4 = true, beta = false),
                Language("uk", "Ukrainian", "Українська", rtl = false, dub = true, mp4 = true, beta = false),
                Language("el", "Greek", "Ελληνικά", rtl = false, dub = true, mp4 = true, beta = false),
                Language("ar", "Arabic", "العربية", rtl = true, dub = true, mp4 = false, beta = false),
                Language("fa", "Persian", "فارسی", rtl = true, dub = true, mp4 = false, beta = false),
                Language("he", "Hebrew", "עברית", rtl = true, dub = true, mp4 = false, beta = false),
                Language("ur", "Urdu", "اردو", rtl = true, dub = true, mp4 = false, beta = false),
                Language("hi", "Hindi", "हिन्दी", rtl = false, dub = true, mp4 = false, beta = false),
                Language("bn", "Bengali", "বাংলা", rtl = false, dub = true, mp4 = false, beta = false),
                Language("ta", "Tamil", "தமிழ்", rtl = false, dub = true, mp4 = false, beta = false),
                Language("th", "Thai", "ไทย", rtl = false, dub = true, mp4 = false, beta = false),
                Language("vi", "Vietnamese", "Tiếng Việt", rtl = false, dub = true, mp4 = true, beta = false),
                Language("id", "Indonesian", "Bahasa Indonesia", rtl = false, dub = true, mp4 = true, beta = false),
                Language("ms", "Malay", "Bahasa Melayu", rtl = false, dub = true, mp4 = true, beta = false),
                Language("fil", "Filipino", "Filipino", rtl = false, dub = true, mp4 = true, beta = false),
                Language("ja", "Japanese", "日本語", rtl = false, dub = true, mp4 = true, beta = false),
                Language("ko", "Korean", "한국어", rtl = false, dub = true, mp4 = true, beta = false),
                Language("zh-Hans", "Chinese (Simplified)", "简体中文", rtl = false, dub = true, mp4 = true, beta = false),
                Language("zh-Hant", "Chinese (Traditional)", "繁體中文", rtl = false, dub = true, mp4 = true, beta = false),
                Language("sw", "Swahili", "Kiswahili", rtl = false, dub = true, mp4 = true, beta = false),
                Language("ha", "Hausa", "Hausa", rtl = false, dub = true, mp4 = true, beta = true),
                Language("ln", "Lingala", "Lingála", rtl = false, dub = true, mp4 = true, beta = true),
                Language("so", "Somali", "Soomaali", rtl = false, dub = true, mp4 = true, beta = true),
                Language("ee", "Ewe", "Eʋegbe", rtl = false, dub = false, mp4 = true, beta = true),
                Language("fon", "Fon", "Fɔngbe", rtl = false, dub = false, mp4 = true, beta = true),
                Language("yo", "Yoruba", "Yorùbá", rtl = false, dub = false, mp4 = true, beta = true),
                Language("ig", "Igbo", "Igbo", rtl = false, dub = false, mp4 = true, beta = true),
                Language("tw", "Twi", "Twi", rtl = false, dub = false, mp4 = true, beta = true),
                Language("wo", "Wolof", "Wolof", rtl = false, dub = false, mp4 = true, beta = true),
                Language("bm", "Bambara", "Bamanankan", rtl = false, dub = false, mp4 = true, beta = true),
                Language("ff", "Fula", "Fulfulde", rtl = false, dub = false, mp4 = true, beta = true),
                Language("mos", "Mooré", "Mòoré", rtl = false, dub = false, mp4 = true, beta = true),
                Language("rw", "Kinyarwanda", "Ikinyarwanda", rtl = false, dub = false, mp4 = true, beta = true),
                Language("mg", "Malagasy", "Malagasy", rtl = false, dub = false, mp4 = true, beta = true),
                Language("zu", "Zulu", "isiZulu", rtl = false, dub = false, mp4 = true, beta = true),
                Language("xh", "Xhosa", "isiXhosa", rtl = false, dub = false, mp4 = true, beta = true),
                Language("am", "Amharic", "አማርኛ", rtl = false, dub = false, mp4 = false, beta = true),
                Language("ht", "Haitian Creole", "Kreyòl ayisyen", rtl = false, dub = false, mp4 = true, beta = true),
            )
    }
}
