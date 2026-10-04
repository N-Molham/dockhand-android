package pro.dockhand.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraefikUrlResolverTest {

    @Test
    fun dockhandUrlWinsOverPortAndTraefikLabels() {
        val labels = mapOf(
            "dockhand.url" to "https://direct.example.com",
            "dockhand.port.8080.url" to "http://port.example.com:8080",
            "traefik.http.routers.web.rule" to "Host(`traefik.example.com`)",
            "traefik.http.routers.web.tls" to "true"
        )

        assertEquals(listOf("https://direct.example.com"), TraefikUrlResolver.resolveUrls(labels))
    }

    @Test
    fun portUrlUsedWhenDockhandUrlMissing() {
        val labels = mapOf(
            "dockhand.port.8080.url" to "http://app.example.com:8080",
            "traefik.http.routers.web.rule" to "Host(`traefik.example.com`)"
        )

        assertEquals(listOf("http://app.example.com:8080"), TraefikUrlResolver.resolveUrls(labels))
    }

    @Test
    fun malformedDockhandUrlFallsBackToTraefik() {
        val labels = mapOf(
            "dockhand.url" to "not a url",
            "traefik.http.routers.web.rule" to "Host(`app.example.com`)"
        )

        assertEquals(listOf("http://app.example.com"), TraefikUrlResolver.resolveUrls(labels))
    }

    @Test
    fun traefikTlsTrueUsesHttps() {
        val labels = mapOf(
            "traefik.http.routers.web.rule" to "Host(`app.example.com`)",
            "traefik.http.routers.web.tls" to "true"
        )

        assertEquals(listOf("https://app.example.com"), TraefikUrlResolver.resolveUrls(labels))
    }

    @Test
    fun websecureEntrypointUsesHttps() {
        val labels = mapOf(
            "traefik.http.routers.web.rule" to "Host(`app.example.com`)",
            "traefik.http.routers.web.entrypoints" to "websecure"
        )

        assertEquals(listOf("https://app.example.com"), TraefikUrlResolver.resolveUrls(labels))
    }

    @Test
    fun webEntrypointUsesHttp() {
        val labels = mapOf(
            "traefik.http.routers.web.rule" to "Host(`app.example.com`)",
            "traefik.http.routers.web.entrypoints" to "web"
        )

        assertEquals(listOf("http://app.example.com"), TraefikUrlResolver.resolveUrls(labels))
    }

    @Test
    fun pathPrefixAppendedToResolvedUrl() {
        val labels = mapOf(
            "traefik.http.routers.web.rule" to "Host(`app.example.com`) && PathPrefix(`/api`)"
        )

        assertEquals(listOf("http://app.example.com/api"), TraefikUrlResolver.resolveUrls(labels))
    }

    @Test
    fun hostRegexpRulesAreSkipped() {
        val labels = mapOf(
            "traefik.http.routers.web.rule" to "HostRegexp(`{subdomain:[a-z]+}.example.com`)",
            "traefik.http.routers.web.tls" to "true"
        )

        assertTrue(TraefikUrlResolver.resolveUrls(labels).isEmpty())
    }

    @Test
    fun urlsAreDistinctAndSorted() {
        val labels = mapOf(
            "traefik.http.routers.one.rule" to "Host(`b.example.com`)",
            "traefik.http.routers.two.rule" to "Host(`a.example.com`) && PathPrefix(`/app`)",
            "traefik.http.routers.three.rule" to "Host(`a.example.com`) && PathPrefix(`/app`)"
        )

        assertEquals(
            listOf("http://a.example.com/app", "http://b.example.com"),
            TraefikUrlResolver.resolveUrls(labels)
        )
    }

    @Test
    fun labelEntriesAreSortedByKey() {
        val labels = mapOf(
            "zeta" to "1",
            "Alpha" to "2",
            "beta" to "3"
        )

        assertEquals(
            listOf("Alpha" to "2", "beta" to "3", "zeta" to "1"),
            TraefikUrlResolver.labelEntries(labels)
        )
    }

    @Test
    fun isUrlLikeLabelDetectsHttpSchemes() {
        assertTrue(TraefikUrlResolver.isUrlLikeLabel("https://app.example.com"))
        assertTrue(TraefikUrlResolver.isUrlLikeLabel("http://app.example.com/path"))
        assertTrue(TraefikUrlResolver.isUrlLikeLabel("  https://app.example.com  "))
        assertFalse(TraefikUrlResolver.isUrlLikeLabel("app.example.com"))
        assertFalse(TraefikUrlResolver.isUrlLikeLabel("ftp://app.example.com"))
    }
}
