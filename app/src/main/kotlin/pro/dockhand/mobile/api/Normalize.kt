package pro.dockhand.mobile.api

import java.util.Locale

object DockhandToken {
    fun normalized(raw: String?): String = raw?.trim().orEmpty()
}

object DockhandServerAddress {
    fun normalized(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val uri = try {
            java.net.URI(trimmed)
        } catch (error: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null) return null
        if (uri.rawQuery != null) return null
        if (uri.rawFragment != null) return null

        val host = uri.host ?: return null
        if (host.isEmpty()) return null

        val port = if (uri.port >= 0) ":${uri.port}" else ""

        val rawPath = uri.rawPath.orEmpty()
        val path = when {
            rawPath.isEmpty() || rawPath == "/" -> ""
            else -> {
                val trimmedPath = rawPath.trim('/')
                if (trimmedPath.isEmpty()) "" else "/$trimmedPath"
            }
        }

        return "$scheme://$host$port$path"
    }
}

fun String.dockhandWebSocketUrl(): String? {
    val normalized = DockhandServerAddress.normalized(this) ?: return null
    return when {
        normalized.startsWith("https://") -> "wss://" + normalized.removePrefix("https://")
        normalized.startsWith("http://") -> "ws://" + normalized.removePrefix("http://")
        else -> null
    }
}
