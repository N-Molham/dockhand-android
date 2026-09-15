package pro.dockhand.mobile.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizeTest {

    @Test
    fun tokenNormalizationRemovesCopiedWhitespace() {
        assertEquals("dh_example", DockhandToken.normalized("  dh_example\n"))
        assertEquals("dh_example", DockhandToken.normalized("\tdh_example\r\n"))
        assertEquals("", DockhandToken.normalized(null))
    }

    @Test
    fun serverAddressAcceptsHttpAndHttpsWithPorts() {
        assertEquals("https://example.com:3000", DockhandServerAddress.normalized(" https://example.com:3000/ "))
        assertEquals("http://192.0.2.10:3230", DockhandServerAddress.normalized("http://192.0.2.10:3230"))
    }

    @Test
    fun serverAddressKeepsPathsWithoutTrailingSlash() {
        assertEquals("https://example.com/dockhand", DockhandServerAddress.normalized("https://example.com/dockhand/"))
        assertEquals("https://example.com", DockhandServerAddress.normalized("https://example.com/"))
    }

    @Test
    fun serverAddressRejectsUnsupportedOrAmbiguousUrls() {
        assertNull(DockhandServerAddress.normalized("example.com:3000"))
        assertNull(DockhandServerAddress.normalized("ftp://example.com"))
        assertNull(DockhandServerAddress.normalized("https://user@example.com"))
        assertNull(DockhandServerAddress.normalized("https://example.com?token=secret"))
        assertNull(DockhandServerAddress.normalized("https://example.com#fragment"))
        assertNull(DockhandServerAddress.normalized(""))
    }

    @Test
    fun webSocketUrlRewritesScheme() {
        assertEquals("ws://192.0.2.10:3230/api/containers/abc/exec", "http://192.0.2.10:3230/api/containers/abc/exec".dockhandWebSocketUrl())
        assertEquals("wss://example.com/api/containers/abc/exec", "https://example.com/api/containers/abc/exec".dockhandWebSocketUrl())
        assertNull("ftp://example.com/exec".dockhandWebSocketUrl())
    }

    @Test
    fun bestShellPrefersSelectionThenNameThenDefault() {
        val result = ContainerShellDetectionResult(
            shells = listOf("/bin/bash", "/bin/sh"),
            defaultShell = "/bin/bash",
            allShells = emptyList()
        )
        assertEquals("/bin/sh", result.bestShell("/bin/sh"))
        assertEquals("/bin/bash", result.bestShell("/usr/bin/bash"))
        assertTrue(result.hasAvailableShells)
        assertFalse(ContainerShellDetectionResult().hasAvailableShells)
    }
}
