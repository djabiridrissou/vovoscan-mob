package com.vovoscan.core.sources

/**
 * Links typed or pasted by the user to add a source or a repository. Only `https://` (debug builds may also reach a
 * local server); GitHub pages are turned into the raw file they show.
 */
object PluginLinks {
    private val localHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")
    private val githubRepo = Regex("^https://(?:www\\.)?github\\.com/([^/?#]+)/([^/?#]+?)(?:\\.git)?/?(?:[?#].*)?$")
    private val githubTree = Regex("^https://(?:www\\.)?github\\.com/([^/?#]+)/([^/?#]+)/tree/([^/?#]+)(/[^?#]*)?(?:[?#].*)?$")
    private val githubBlob = Regex("^https://(?:www\\.)?github\\.com/([^/?#]+)/([^/?#]+)/blob/([^/?#]+)/([^?#]+)(?:[?#].*)?$")

    /** URL to fetch for what the user typed, or null when the link is not allowed. */
    fun resolve(
        input: String,
        allowLocal: Boolean,
    ): String? {
        val link = input.trim()
        if (!isAllowed(link, allowLocal)) return null
        githubBlob.find(link)?.destructured?.let { (owner, repo, branch, path) ->
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/${path.trim('/')}"
        }
        githubTree.find(link)?.destructured?.let { (owner, repo, branch, path) ->
            val folder = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/${folder}repo.json"
        }
        githubRepo.find(link)?.destructured?.let { (owner, repo) ->
            return "https://raw.githubusercontent.com/$owner/$repo/HEAD/repo.json"
        }
        return link
    }

    /** `https://` with a host; with [allowLocal], also `http://` to a local development server. */
    fun isAllowed(
        url: String,
        allowLocal: Boolean,
    ): Boolean {
        val host = hostOf(url) ?: return false
        if (url.any { it.isWhitespace() }) return false
        return when {
            url.startsWith("https://", ignoreCase = true) -> true
            url.startsWith("http://", ignoreCase = true) -> allowLocal && host in localHosts
            else -> false
        }
    }

    /** A path from `repo.json` (relative to it, or absolute) as an allowed absolute URL; null otherwise. */
    fun relativeTo(
        base: String,
        link: String,
        allowLocal: Boolean,
    ): String? {
        val trimmed = link.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.split('/').any { it == ".." }) {
            return trimmed.takeIf { isAllowed(it, allowLocal) }
        }
        return absoluteUrl(base, trimmed).takeIf { isAllowed(it, allowLocal) }
    }
}
