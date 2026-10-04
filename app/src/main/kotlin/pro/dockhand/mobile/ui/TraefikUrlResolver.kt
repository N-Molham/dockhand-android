package pro.dockhand.mobile.ui

import java.util.Locale

object TraefikUrlResolver {

    private val portUrlLabel = Regex("""^dockhand\.port\.\d+\.url$""")
    private val routerRuleLabel = Regex("""^traefik\.http\.routers\.(.+)\.rule$""")
    private val hostValue = Regex("""Host\(\s*[`"]([^`"]+)[`"]\s*\)""")
    private val hostRegexp = Regex("""HostRegexp\s*\(""")
    private val pathPrefixValue = Regex("""PathPrefix\(\s*[`"]([^`"]+)[`"]\s*\)""")
    private val httpUrl = Regex("""^https?://[^\s/?#]+(?:[/?#]\S*)?$""", RegexOption.IGNORE_CASE)

    fun resolveUrls(labels: Map<String, String>): List<String> {
        labels["dockhand.url"]?.let { value ->
            normalizedUrl(value)?.let { return listOf(it) }
        }

        val portUrls = labels.entries
            .mapNotNull { (key, value) ->
                if (!portUrlLabel.matches(key)) return@mapNotNull null
                normalizedUrl(value)
            }
            .distinct()
            .sorted()
        if (portUrls.isNotEmpty()) return portUrls

        return labels.entries
            .mapNotNull { (key, value) ->
                val router = routerRuleLabel.matchEntire(key)?.groupValues?.get(1) ?: return@mapNotNull null
                deriveRouterUrl(router, value, labels)
            }
            .distinct()
            .sorted()
    }

    fun labelEntries(labels: Map<String, String>): List<Pair<String, String>> =
        labels.entries
            .map { it.key to it.value }
            .sortedBy { it.first.lowercase(Locale.US) }

    fun isUrlLikeLabel(value: String): Boolean {
        val trimmed = value.trim().lowercase(Locale.US)
        return trimmed.startsWith("http://") || trimmed.startsWith("https://")
    }

    private fun deriveRouterUrl(
        router: String,
        rule: String,
        labels: Map<String, String>
    ): String? {
        if (hostRegexp.containsMatchIn(rule)) return null
        val host = hostValue.find(rule)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (host.any { it.isWhitespace() }) return null

        val prefix = "traefik.http.routers.$router."
        val tls = labels["${prefix}tls"]
        val entrypoints = labels["${prefix}entrypoints"] ?: labels["${prefix}entryPoints"]
        val https = tls.equals("true", ignoreCase = true) ||
            entrypoints
                ?.split(',')
                ?.any { it.trim().equals("websecure", ignoreCase = true) } == true

        val path = pathPrefixValue.find(rule)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val scheme = if (https) "https" else "http"
        val base = "$scheme://$host"
        return path?.let { if (it.startsWith("/")) "$base$it" else "$base/$it" } ?: base
    }

    private fun normalizedUrl(value: String): String? {
        val trimmed = value.trim()
        return trimmed.takeIf { httpUrl.matches(it) }
    }
}
