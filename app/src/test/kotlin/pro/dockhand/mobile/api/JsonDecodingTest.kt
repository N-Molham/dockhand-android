package pro.dockhand.mobile.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonDecodingTest {

    @Test
    fun environmentDecodingKeepsRequiredFieldsAndIgnoresExtras() {
        val payload = """
            [
              {
                "id": 1,
                "name": "Lab",
                "port": 2375,
                "protocol": "tcp",
                "icon": "server",
                "collectActivity": false,
                "collectMetrics": true,
                "highlightChanges": false,
                "labels": [],
                "connectionType": "socket",
                "socketPath": "/var/run/docker.sock",
                "publicIp": "10.0.0.24",
                "createdAt": "2026-07-05T00:00:00Z",
                "unknownField": {"nested": true}
              }
            ]
        """.trimIndent()

        val environments = DockhandDecoding.decodeEnvironments(payload)

        assertEquals(1, environments.size)
        assertEquals(1, environments.first().id)
        assertEquals("Lab", environments.first().name)
        assertEquals("tcp", environments.first().protocolName)
        assertEquals("10.0.0.24", environments.first().publicIp)
        assertNull(environments.first().updatedAt)
    }

    @Test
    fun environmentDecodingFailsWhenRequiredFieldMissing() {
        val payload = """[{"id":1,"name":"Lab"}]"""
        try {
            DockhandDecoding.decodeEnvironments(payload)
            error("Expected InvalidResponse")
        } catch (expected: DockhandServiceError.InvalidResponse) {
            // expected
        }
    }

    @Test
    fun dashboardStatsDecodingFillsDefaultsForMissingSections() {
        val payload = """{"id":3,"name":"Prod","online":true}"""
        val snapshot = DockhandDecoding.decodeDashboardStats(payload)

        assertEquals(3, snapshot.id)
        assertEquals("Prod", snapshot.name)
        assertTrue(snapshot.online)
        assertEquals(0, snapshot.containers.total)
        assertEquals(0.0, snapshot.metrics.cpuPercent, 0.0)
    }

    @Test
    fun dashboardHostDecodingHandlesDockhandAppAlias() {
        val payload = """
            {
              "app": {"version": "1.2.3", "database": "sqlite"},
              "docker": {
                "version": "27.0",
                "apiVersion": "1.46",
                "os": "linux",
                "arch": "x86_64",
                "kernelVersion": "6.6.0",
                "serverVersion": "27.0",
                "connection": {"type": "socket", "socketPath": "/var/run/docker.sock"}
              },
              "host": {"name": "lab-host", "cpus": 8, "memory": 17179869184, "storageDriver": "overlay2"}
            }
        """.trimIndent()

        val snapshot = DockhandDecoding.decodeDashboardHost(payload)

        assertEquals("1.2.3", snapshot.dockhand?.version)
        assertEquals("sqlite", snapshot.dockhand?.database)
        assertEquals("socket", snapshot.docker.connectionType)
        assertEquals("lab-host", snapshot.host.name)
        assertEquals(8, snapshot.host.cpus)
    }

    @Test
    fun dashboardHostDecodingFailsWithoutDockerOrHost() {
        try {
            DockhandDecoding.decodeDashboardHost("""{"host": {}}""")
            error("Expected InvalidResponse")
        } catch (expected: DockhandServiceError.InvalidResponse) {
            // expected
        }
    }

    @Test
    fun pendingUpdateDecodingKeepsContainerIdentity() {
        val updates = DockhandDecoding.decodePendingUpdates(
            """
            {
              "pendingUpdates": [
                {
                  "containerId": "container-1",
                  "containerName": "web",
                  "currentImage": "nginx:latest",
                  "checkedAt": "2026-07-17T10:00:00Z"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, updates.size)
        assertEquals("container-1", updates.first().containerID)
        assertEquals("web", updates.first().containerName)
        assertEquals("nginx:latest", updates.first().currentImage)
    }

    @Test
    fun volumeDecodingKeepsContainerUsage() {
        val volumes = DockhandDecoding.decodeVolumes(
            """
            [
              {
                "name": "app-data",
                "driver": "local",
                "scope": "local",
                "usedBy": [
                  {"containerId": "container-1", "containerName": "web"}
                ]
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, volumes.size)
        assertEquals("app-data", volumes.first().name)
        assertEquals("container-1", volumes.first().usedBy.first().containerID)
    }

    @Test
    fun networkDecodingKeepsConnectedContainersAndSubnets() {
        val networks = DockhandDecoding.decodeNetworks(
            """
            [
              {
                "id": "network-1",
                "name": "frontend",
                "driver": "bridge",
                "scope": "local",
                "internal": false,
                "ipam": {"config": [{"subnet": "172.20.0.0/16"}]},
                "containers": {
                  "container-1": {"name": "web", "ipv4Address": "172.20.0.2"}
                }
              }
            ]
            """.trimIndent()
        )

        assertEquals("frontend", networks.first().name)
        assertEquals(listOf("172.20.0.0/16"), networks.first().subnets)
        assertEquals("container-1", networks.first().containers.first().containerID)
        assertEquals("web", networks.first().containers.first().containerName)
    }

    @Test
    fun activityDecodingKeepsContainerAndAction() {
        val activity = DockhandDecoding.decodeActivity(
            """
            {
              "events": [
                {
                  "id": 7,
                  "containerId": "container-1",
                  "containerName": "web",
                  "image": "nginx:latest",
                  "action": "restart",
                  "timestamp": "2026-07-17T10:00:00Z"
                }
              ],
              "total": 42
            }
            """.trimIndent()
        )

        assertEquals(42, activity.total)
        assertEquals("container-1", activity.events.first().containerID)
        assertEquals("restart", activity.events.first().action)
    }

    @Test
    fun updateCheckJobDecodesProgressAndResult() {
        val snapshot = dockhandJson.decodeFromString<ContainerUpdateCheckJobSnapshot>(
            """{"status":"done","lines":[{"event":"progress","data":{"checked":3,"total":5}}],"result":{"total":5,"updatesFound":2,"results":[]}}"""
        )

        assertEquals("done", snapshot.status)
        assertEquals(3, snapshot.lines.first().data.checked)
        assertEquals(5, snapshot.lines.first().data.total)
        assertEquals(2, snapshot.result?.updatesFound)
    }

    @Test
    fun batchUpdateResponseDecodesFailures() {
        val response = dockhandJson.decodeFromString<ContainerBatchUpdateResponse>(
            """{"success":false,"results":[{"containerId":"abc","containerName":"web","success":false,"error":"Pull failed"}],"summary":{"total":1,"success":0,"failed":1}}"""
        )

        assertEquals(false, response.success)
        assertEquals(1, response.summary.failed)
        assertEquals("abc", response.results.first().containerID)
        assertEquals("Pull failed", response.results.first().error)
    }

    @Test
    fun containerDecodingMapsDockerPortWireNames() {
        val payload = """
            [
              {
                "id": "abc",
                "name": "web",
                "image": "nginx:latest",
                "state": "running",
                "status": "Up",
                "created": 0,
                "ports": [
                  {"IP": "0.0.0.0", "PrivatePort": 80, "PublicPort": 8080, "Type": "tcp"}
                ],
                "networks": {"bridge": {"ipAddress": "172.17.0.2"}},
                "labels": {}
              }
            ]
        """.trimIndent()

        val containers = dockhandJson.decodeFromString<List<Container>>(payload)

        assertEquals(80, containers.first().ports.first().privatePort)
        assertEquals(8080, containers.first().ports.first().publicPort)
        assertEquals("tcp", containers.first().ports.first().type)
        assertEquals("172.17.0.2", containers.first().networks["bridge"]?.ipAddress)
    }
}
