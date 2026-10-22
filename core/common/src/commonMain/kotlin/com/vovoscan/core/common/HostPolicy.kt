package com.vovoscan.core.common

/**
 * Hosts a rule-based source may make the device contact: public names and public IPv4 addresses only. A source
 * (above all a third-party plugin, which nobody reviews) must never turn the phone into a door to the reader's own
 * network: loopback, private, link-local, carrier-grade NAT, multicast and reserved ranges, IPv6 literals, `localhost`
 * and local-only names (`.local`, `.lan`, `.internal`, `.home.arpa`…) are refused, in the manifest's `hosts`, in
 * url-type settings and on every redirect hop.
 *
 * A public name that resolves to a private address (DNS rebinding) can't be seen from here: the HTTP engines don't
 * expose the address they connect to.
 */
object HostPolicy {
    private val authorityOf = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]*)")
    private val localSuffixes = listOf(".localhost", ".local", ".lan", ".internal", ".intranet", ".home", ".corp", ".home.arpa")
    private val label = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    /**
     * The host of an absolute URL, lowercased. Null when there is none, and when the authority carries user info or a
     * backslash: `https://allowed.example:x@127.0.0.1/` must never pass for `allowed.example`.
     */
    fun hostOf(url: String): String? {
        val authority = authorityOf.find(url.trim())?.groupValues?.get(1) ?: return null
        if ('@' in authority || '\\' in authority) return null
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.lowercase().takeIf { it.isNotEmpty() }
    }

    /** [url]'s host, when it is one a source may contact. */
    fun isPublicUrl(url: String): Boolean = hostOf(url)?.let(::isPublic) == true

    fun isPublic(host: String): Boolean {
        val name = host.lowercase().removeSuffix(".")
        if (name.isEmpty() || name.length > 253 || name == "localhost" || localSuffixes.any { name.endsWith(it) }) return false
        val labels = name.split('.')
        // Letters, digits and hyphens only: no IPv6 literal, no userinfo, no percent-encoding, no single-label name.
        if (labels.size < 2 || labels.any { !label.matches(it) }) return false
        // A numeric last label is an address however it is written (127.1, 0x7f.0.0.1, 2130706433 parse as IPv4).
        val last = labels.last()
        if (last.all { it.isDigit() } || last.startsWith("0x")) return isPublicIpv4(labels)
        return true
    }

    /** Canonical dotted quads only (four decimal parts, no leading zero), outside every non-public range. */
    private fun isPublicIpv4(parts: List<String>): Boolean {
        if (parts.size != 4) return false
        val bytes =
            parts.map { part ->
                part.takeIf { it.length <= 3 && (it == "0" || !it.startsWith("0")) }?.toIntOrNull() ?: return false
            }
        if (bytes.any { it > 255 }) return false
        val (a, b, c) = bytes
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false // this network, private, loopback, multicast, reserved
            a == 100 && b in 64..127 -> false // carrier-grade NAT
            a == 169 && b == 254 -> false // link-local (cloud metadata)
            a == 172 && b in 16..31 -> false // private
            a == 192 && b == 168 -> false // private
            a == 192 && b == 0 && (c == 0 || c == 2) -> false // IETF protocol assignments, documentation
            a == 198 && (b == 18 || b == 19) -> false // benchmarking
            a == 198 && b == 51 && c == 100 -> false // documentation
            a == 203 && b == 0 && c == 113 -> false // documentation
            else -> true
        }
    }
}
