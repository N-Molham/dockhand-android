package pro.dockhand.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.dockhand.mobile.api.Container
import pro.dockhand.mobile.api.ContainerPort
import pro.dockhand.mobile.api.Environment
import pro.dockhand.mobile.api.ImageSummary
import pro.dockhand.mobile.api.StackSummary

class FormattingTest {

    private fun makeEnvironment(publicIp: String?): Environment = Environment(
        id = 1,
        name = "Lab",
        port = 2375,
        protocolName = "tcp",
        icon = "server",
        collectActivity = false,
        collectMetrics = false,
        highlightChanges = false,
        connectionType = "socket",
        socketPath = "/var/run/docker.sock",
        publicIp = publicIp,
        createdAt = "2026-07-05T00:00:00Z"
    )

    @Test
    fun byteFormattingUsesBinaryUnits() {
        assertTrue(1_048_576L.dockhandByteCount.contains("MB"))
        assertTrue(1_073_741_824L.dockhandByteCount.contains("GB"))
    }

    @Test
    fun publishedPortUrlUsesPublicIpAndTlsPorts() {
        assertEquals("http://10.0.0.24:8080", makeEnvironment("10.0.0.24").publishedPortUrl(8080))
        assertEquals("https://10.0.0.24:8443", makeEnvironment("10.0.0.24").publishedPortUrl(8443))
        assertEquals("http://[2001:db8::20]:8080", makeEnvironment("[2001:db8::20]").publishedPortUrl(8080))
    }

    @Test
    fun containerFilterMatchesHealthAndState() {
        val unhealthy = Container(
            id = "abc",
            name = "web",
            image = "nginx:latest",
            state = "running",
            status = "Up",
            created = 0,
            health = "unhealthy",
            ports = emptyList()
        )

        assertTrue(ContainerListFilter.State("running").matches(unhealthy))
        assertTrue(ContainerListFilter.Unhealthy.matches(unhealthy))
        assertFalse(ContainerListFilter.Stopped.matches(unhealthy))
        assertTrue(ContainerListFilter.State("paused").matches(unhealthy.copy(state = "paused")))
        assertTrue(ContainerListFilter.Stopped.matches(unhealthy.copy(state = "exited")))
    }

    @Test
    fun containerPortAccessUsesPublishedPortAndPublicIp() {
        val container = Container(
            id = "abc",
            name = "web",
            image = "nginx:latest",
            state = "running",
            status = "Up",
            created = 0,
            ports = listOf(ContainerPort(ip = "0.0.0.0", privatePort = 80, publicPort = 8080, type = "tcp"))
        )

        val accesses = container.publishedPortAccesses(makeEnvironment("192.168.1.50"))
        assertEquals(listOf("8080:80"), accesses.map { it.label })
        assertEquals("http://192.168.1.50:8080", accesses.first().destinationUrl)
        assertTrue(container.canPerform(ContainerAction.STOP))
        assertFalse(container.canPerform(ContainerAction.START))
        assertTrue(container.canOpenShell)
    }

    @Test
    fun imageHelpersDeriveNamesAndKeys() {
        val image = ImageSummary(
            id = "sha256:abcdef0123456789",
            repoTags = listOf("ghcr.io/example/app:1.2.3"),
            size = 1_000,
            virtualSize = 1_000,
            created = 0,
            containers = 0
        )

        assertEquals("ghcr.io/example/app:1.2.3", image.displayName)
        assertEquals("abcdef012345", image.shortId)
        assertEquals("ghcr.io/example/app", image.repositoryKey)
        assertTrue(image.isUnused)
    }

    @Test
    fun stackCapabilitiesRespectActiveContainers() {
        val stack = StackSummary(name = "web", status = "stopped", sourceType = "compose")
        assertTrue(stack.canPerform(pro.dockhand.mobile.api.StackAction.START))
        assertFalse(stack.canPerform(pro.dockhand.mobile.api.StackAction.STOP))
        assertTrue(stack.supportsRedeploy)
    }
}
