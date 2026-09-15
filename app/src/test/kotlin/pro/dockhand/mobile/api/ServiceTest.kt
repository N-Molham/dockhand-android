package pro.dockhand.mobile.api

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun service(
        token: String = "",
        allowCleartext: Boolean = true,
        customHeaders: Map<String, String> = emptyMap()
    ): DockhandService = DockhandService(
        baseUrl = server.url("/").toString(),
        token = token,
        allowCleartext = allowCleartext,
        customHeaders = customHeaders
    )

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    @Test
    fun healthSuccessReturnsStatus() = runTest {
        enqueue(200, """{"status":"ok","timestamp":"2026-07-05T00:00:00Z"}""")

        assertEquals("ok", service().fetchHealthStatus())

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/health", recorded.url.encodedPath)
    }

    @Test
    fun environmentsDecodeTolerantly() = runTest {
        enqueue(
            200,
            """[{"id":1,"name":"Lab","port":2375,"protocol":"tcp","icon":"server","collectActivity":false,"collectMetrics":true,"highlightChanges":false,"labels":[],"connectionType":"socket","socketPath":"/var/run/docker.sock","createdAt":"2026-07-05T00:00:00Z","unknownField":{"nested":true}}]"""
        )

        val environments = service().fetchEnvironments()

        assertEquals(1, environments.size)
        assertEquals("Lab", environments.first().name)
        assertEquals("tcp", environments.first().protocolName)
        assertEquals("/api/environments", server.takeRequest().url.encodedPath)
    }

    @Test
    fun containersRequestCarriesEnvQueryParameter() = runTest {
        enqueue(
            200,
            """[{"id":"abc","name":"web","image":"nginx:latest","state":"running","status":"Up","created":0}]"""
        )

        val containers = service().fetchContainers(7)

        assertEquals(1, containers.size)
        assertEquals("abc", containers.first().id)
        val recorded = server.takeRequest()
        assertEquals("/api/containers", recorded.url.encodedPath)
        assertEquals("7", recorded.url.queryParameter("env"))
    }

    @Test
    fun startContainerPostsWithEnvAndFalseSuccessRaisesError() = runTest {
        enqueue(200, """{"success":false,"error":"boom"}""")

        try {
            service().startContainer("abc", 3)
            fail("Expected a message error")
        } catch (expected: DockhandServiceError.Message) {
            assertEquals("boom", expected.text)
        }

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/containers/abc/start", recorded.url.encodedPath)
        assertEquals("3", recorded.url.queryParameter("env"))
    }

    @Test
    fun stopContainerPostsAndTrueSuccessCompletes() = runTest {
        enqueue(200, """{"success":true}""")

        service().stopContainer("abc", 3)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/containers/abc/stop", recorded.url.encodedPath)
        assertEquals("3", recorded.url.queryParameter("env"))
    }

    @Test
    fun missingTokenOmitsAuthorizationHeader() = runTest {
        enqueue(200, """{"status":"ok","timestamp":"now"}""")

        service(token = "").fetchHealthStatus()

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun presentTokenIsNormalizedIntoBearerHeader() = runTest {
        enqueue(200, """{"status":"ok","timestamp":"now"}""")

        service(token = "  dh_secret\n").fetchHealthStatus()

        assertEquals("Bearer dh_secret", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun customHeadersAndAcceptAreApplied() = runTest {
        enqueue(200, """{"status":"ok","timestamp":"now"}""")

        service(customHeaders = mapOf("X-Client" to "android")).fetchHealthStatus()

        val recorded = server.takeRequest()
        assertEquals("android", recorded.headers["X-Client"])
        assertEquals("application/json", recorded.headers["Accept"])
    }

    @Test
    fun cleartextIsBlockedUnlessExplicitlyAllowed() = runTest {
        val blocked = DockhandService(
            baseUrl = server.url("/").toString(),
            token = "",
            allowCleartext = false
        )
        try {
            blocked.fetchHealthStatus()
            fail("Expected cleartext rejection")
        } catch (expected: DockhandServiceError.Message) {
            assertEquals("Cleartext HTTP is disabled for this server.", expected.text)
        }

        enqueue(200, """{"status":"ok","timestamp":"now"}""")
        val allowed = DockhandService(
            baseUrl = server.url("/").toString(),
            token = "",
            allowCleartext = true
        )
        assertEquals("ok", allowed.fetchHealthStatus())
    }

    @Test
    fun logsUnsupportedDriverPayloadMapsToLogsUnavailable() = runTest {
        enqueue(500, """{"error":"driver unsupported","details":"json-file only"}""")

        try {
            service().fetchContainerLogs("abc", 2)
            fail("Expected logs unavailable")
        } catch (expected: DockhandServiceError.LogsUnavailable) {
            assertEquals("json-file only", expected.reason)
        }

        val recorded = server.takeRequest()
        assertEquals("/api/containers/abc/logs", recorded.url.encodedPath)
        assertEquals("2", recorded.url.queryParameter("env"))
        assertEquals("200", recorded.url.queryParameter("tail"))
    }

    @Test
    fun logsNonServerFailureKeepsStatusCode() = runTest {
        enqueue(404, """{"error":"not found"}""")

        try {
            service().fetchContainerLogs("abc", 2)
            fail("Expected unexpected status")
        } catch (expected: DockhandServiceError.UnexpectedStatus) {
            assertEquals(404, expected.code)
        }
    }

    @Test
    fun updateCheckStartReturnsJobWhenJobIdPresent() = runTest {
        enqueue(200, """{"jobId":"job-9","status":"queued"}""")

        val result = service().startContainerUpdateCheck(1)

        assertTrue(result is ContainerUpdateCheckOperation.Job)
        assertEquals("job-9", (result as ContainerUpdateCheckOperation.Job).id)
        assertEquals("1", server.takeRequest().url.queryParameter("env"))
    }

    @Test
    fun updateCheckJobPollingDecodesSnapshot() = runTest {
        enqueue(
            200,
            """{"status":"done","lines":[{"event":"progress","data":{"checked":3,"total":5}}],"result":{"total":5,"updatesFound":2}}"""
        )

        val snapshot = service().fetchContainerUpdateCheckJob("job-1")

        assertEquals("done", snapshot.status)
        assertEquals(3, snapshot.lines.first().data.checked)
        assertEquals(5, snapshot.lines.first().data.total)
        assertEquals(2, snapshot.result?.updatesFound)
        assertEquals("/api/jobs/job-1", server.takeRequest().url.encodedPath)
    }
}
