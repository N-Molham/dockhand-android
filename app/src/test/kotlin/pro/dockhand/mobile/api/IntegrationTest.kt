package pro.dockhand.mobile.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class IntegrationTest {

    private fun integrationBaseUrl(): String? =
        System.getenv("DOCKHAND_INTEGRATION_URL")?.takeIf { it.isNotBlank() }

    @Test
    fun liveHealthAndEnvironmentsWhenConfigured() = runTest {
        val baseUrl = integrationBaseUrl()
        assumeTrue("Set DOCKHAND_INTEGRATION_URL to run against a live Dockhand server", baseUrl != null)

        val service = DockhandService(
            baseUrl = baseUrl!!,
            token = System.getenv("DOCKHAND_INTEGRATION_TOKEN").orEmpty(),
            allowCleartext = true
        )

        val health = service.fetchHealthStatus()
        assertTrue(health.isNotEmpty())

        val environments = service.fetchEnvironments()
        assertTrue(environments.isNotEmpty())
    }

    @Test
    fun liveContainerListWhenEnvironmentConfigured() = runTest {
        val baseUrl = integrationBaseUrl()
        assumeTrue("Set DOCKHAND_INTEGRATION_URL to run against a live Dockhand server", baseUrl != null)
        val environmentID = System.getenv("DOCKHAND_INTEGRATION_ENV")?.toIntOrNull() ?: 1

        val service = DockhandService(
            baseUrl = baseUrl!!,
            token = System.getenv("DOCKHAND_INTEGRATION_TOKEN").orEmpty(),
            allowCleartext = true
        )

        val containers = service.fetchContainers(environmentID)
        assertTrue(containers.isNotEmpty())
    }
}
