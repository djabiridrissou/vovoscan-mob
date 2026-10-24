package com.vovoscan.feature.groups.domain

/** The contract's limits, checked before sending (the server checks them again and has the last word). */
object GroupRules {
    const val NAME_MIN = 3
    const val NAME_MAX = 60
    const val HANDLE_MIN = 3
    const val HANDLE_MAX = 32
    const val ABOUT_MAX = 500
    const val NOTE_MAX = 300
    const val TEXT_MAX = 2000
    const val LINKS_MAX = 3
    const val ACCENTS = 8

    /** Edits are allowed this long after sending. */
    const val EDIT_WINDOW_MS = 15 * 60_000L
    const val BADGE_REASON_MIN = 30
    const val BADGE_REASON_MAX = 1000
    const val BADGE_LINKS_MAX = 5

    // The server's own list (`domain/rules.py: RESERVED`).
    private val RESERVED =
        setOf(
            "vovoscan",
            "admin",
            "admins",
            "staff",
            "support",
            "official",
            "moderator",
            "mod",
            "mods",
            "help",
            "new",
            "me",
            "settings",
            "search",
            "verified",
            "system",
            "root",
            "api",
            "sa",
            "groups",
            "g",
            "together",
            "team",
            "security",
        )
    private val HANDLE = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val LINK = Regex("""(?i)https?://|www\.""")

    /** Control and bidirectional characters dropped (the server does the same); line breaks kept when [lines]. */
    fun clean(
        text: String,
        lines: Boolean,
    ): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            val code = c.code
            val bidi = code in 0x200E..0x200F || code in 0x202A..0x202E || code in 0x2066..0x2069 || code == 0x061C
            when {
                c == '\n' && lines -> out.append(c)
                c == '\n' || c == '\t' -> out.append(' ')
                code < 0x20 || code == 0x7F || bidi -> Unit
                else -> out.append(c)
            }
        }
        // One blank line at most, no space at either end.
        return out
            .toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun nameOk(name: String): Boolean = clean(name, lines = false).length in NAME_MIN..NAME_MAX

    /** A share name as typed: `@` and case dropped, spaces and underscores as dashes. */
    fun normalizeHandle(text: String): String =
        text
            .trim()
            .removePrefix("@")
            .lowercase()
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("-{2,}"), "-")

    fun handleOk(handle: String): Boolean = handle.length in HANDLE_MIN..HANDLE_MAX && HANDLE.matches(handle) && handle !in RESERVED

    fun links(text: String): Int = LINK.findAll(text).count()

    /** Null when the message may go; else the problem's stable code (the server's own, so one catalogue phrases both). */
    fun textProblem(text: String): String? {
        val body = clean(text, lines = true)
        return when {
            body.isEmpty() -> "GROUP_EMPTY"
            body.length > TEXT_MAX -> "GROUP_TOO_LONG"
            links(body) > LINKS_MAX -> "GROUP_TOO_MANY_LINKS"
            else -> null
        }
    }

    fun editable(
        message: Message,
        userId: String?,
        nowMillis: Long,
    ): Boolean =
        message.kind == MessageKind.Text &&
            message.status == MessageStatus.Visible &&
            userId != null &&
            message.authorId == userId &&
            nowMillis - message.createdAtMillis <= EDIT_WINDOW_MS
}

/** Share links (`vovoscan://g/<handle>`, `https://<web>/g/<handle>`), what a QR code holds, and `@handle` in search. */
object GroupLinks {
    private val PATH = Regex("""^(?:vovoscan://g/|https?://[^/\s]+/g/)([A-Za-z0-9-]+)/?(?:[?#].*)?$""")

    /** The handle a link or a typed `@handle` points to, or null. */
    fun handleOf(text: String): String? {
        val trimmed = text.trim()
        PATH.find(trimmed)?.let { return GroupRules.normalizeHandle(it.groupValues[1]).takeIf(GroupRules::handleOk) }
        if (trimmed.startsWith("@")) return GroupRules.normalizeHandle(trimmed).takeIf(GroupRules::handleOk)
        return null
    }

    /** The full link a QR code encodes: the website's `/g/<handle>`, whose page hands over to the app. */
    fun shareUrl(
        webBaseUrl: String,
        handle: String,
    ): String = webBaseUrl.trimEnd('/') + "/g/" + handle

    fun appUrl(handle: String): String = "vovoscan://g/$handle"
}

/** A group's chat, kept as the history paged in plus every snapshot, merged by id, oldest first. */
object Chat {
    fun merge(
        kept: List<Message>,
        incoming: List<Message>,
    ): List<Message> {
        if (incoming.isEmpty()) return kept
        val byId = LinkedHashMap<String, Message>(kept.size + incoming.size)
        kept.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        return byId.values.sortedWith(compareBy<Message> { it.createdAtMillis }.thenBy { it.id })
    }
}
