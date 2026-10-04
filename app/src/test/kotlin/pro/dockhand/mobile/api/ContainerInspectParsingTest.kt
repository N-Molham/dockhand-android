package pro.dockhand.mobile.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerInspectParsingTest {

    @Test
    fun decodesDockerInspectPayload() {
        val payload = """
            {
              "Id": "abc123",
              "Name": "/web",
              "Image": "sha256:deadbeef",
              "Created": "2026-01-01T00:00:00Z",
              "Platform": "linux",
              "RestartCount": 2,
              "State": {
                "Status": "running",
                "Running": true,
                "Paused": false,
                "Restarting": false,
                "Pid": 4321,
                "ExitCode": 0,
                "StartedAt": "2026-01-01T00:00:01Z",
                "FinishedAt": "0001-01-01T00:00:00Z",
                "Health": {"Status": "healthy"}
              },
              "Config": {
                "Image": "nginx:latest",
                "Hostname": "web",
                "User": "root",
                "WorkingDir": "/app",
                "Entrypoint": ["/docker-entrypoint.sh"],
                "Cmd": ["nginx", "-g", "daemon off;"],
                "Env": ["A=B"],
                "Labels": {
                  "traefik.http.routers.web.rule": "Host(`web.example.com`)",
                  "traefik.http.routers.web.tls": "true"
                }
              },
              "HostConfig": {
                "Binds": ["/data:/usr/share/nginx/html:ro"],
                "NetworkMode": "bridge",
                "RestartPolicy": {"Name": "unless-stopped", "MaximumRetryCount": 0},
                "Privileged": false,
                "Memory": 536870912,
                "NanoCpus": 1000000000
              },
              "Mounts": [
                {
                  "Type": "bind",
                  "Source": "/data",
                  "Destination": "/usr/share/nginx/html",
                  "Mode": "ro",
                  "RW": false
                }
              ],
              "NetworkSettings": {
                "IPAddress": "172.17.0.2",
                "Networks": {
                  "bridge": {
                    "IPAddress": "172.17.0.2",
                    "Gateway": "172.17.0.1",
                    "MacAddress": "02:42:ac:11:00:02",
                    "Aliases": ["web"],
                    "NetworkID": "net1"
                  }
                },
                "Ports": {
                  "80/tcp": [{"HostIp": "0.0.0.0", "HostPort": "8080"}],
                  "443/tcp": null
                }
              }
            }
        """.trimIndent()

        val inspect = dockhandJson.decodeFromString<ContainerInspect>(payload)

        assertEquals("abc123", inspect.id)
        assertEquals("/web", inspect.name)
        assertEquals("sha256:deadbeef", inspect.image)
        assertEquals("linux", inspect.platform)
        assertEquals(2, inspect.restartCount)
        assertEquals("running", inspect.state?.status)
        assertEquals(true, inspect.state?.running)
        assertEquals(4321, inspect.state?.pid)
        assertEquals("healthy", inspect.state?.health?.status)
        assertEquals("nginx:latest", inspect.config?.image)
        assertEquals("root", inspect.config?.user)
        assertEquals("/app", inspect.config?.workingDir)
        assertEquals(listOf("/docker-entrypoint.sh"), inspect.config?.entrypoint)
        assertEquals(listOf("nginx", "-g", "daemon off;"), inspect.config?.cmd)
        assertEquals("Host(`web.example.com`)", inspect.config?.labels?.get("traefik.http.routers.web.rule"))
        assertEquals(listOf("/data:/usr/share/nginx/html:ro"), inspect.hostConfig?.binds)
        assertEquals("bridge", inspect.hostConfig?.networkMode)
        assertEquals("unless-stopped", inspect.hostConfig?.restartPolicy?.name)
        assertEquals(536870912L, inspect.hostConfig?.memory)
        assertEquals(1000000000L, inspect.hostConfig?.nanoCpus)
        assertEquals(1, inspect.mounts.size)
        assertEquals("bind", inspect.mounts.first().type)
        assertEquals("/data", inspect.mounts.first().source)
        assertEquals("/usr/share/nginx/html", inspect.mounts.first().destination)
        assertEquals("ro", inspect.mounts.first().mode)
        assertEquals(false, inspect.mounts.first().rw)
        assertEquals("172.17.0.2", inspect.networkSettings?.ipAddress)
        assertEquals("172.17.0.2", inspect.networkSettings?.networks?.get("bridge")?.ipAddress)
        assertEquals("172.17.0.1", inspect.networkSettings?.networks?.get("bridge")?.gateway)
        assertEquals("02:42:ac:11:00:02", inspect.networkSettings?.networks?.get("bridge")?.macAddress)
        assertEquals("net1", inspect.networkSettings?.networks?.get("bridge")?.networkId)
        assertEquals(listOf("web"), inspect.networkSettings?.networks?.get("bridge")?.aliases)
        assertEquals(
            "8080",
            inspect.networkSettings?.ports?.get("80/tcp")?.firstOrNull()?.hostPort
        )
        assertEquals("0.0.0.0", inspect.networkSettings?.ports?.get("80/tcp")?.firstOrNull()?.hostIp)
        assertNull(inspect.networkSettings?.ports?.get("443/tcp"))
    }

    @Test
    fun decodesLowercaseDockhandShape() {
        val payload = """
            {
              "id": "abc123",
              "name": "web",
              "config": {
                "labels": {"app": "web"},
                "entrypoint": ["/entry.sh"],
                "cmd": ["serve"]
              },
              "mounts": [
                {"type": "volume", "source": "app-data", "destination": "/data", "rw": true}
              ],
              "networkSettings": {
                "networks": {"bridge": {"ipAddress": "172.17.0.3", "networkId": "net2"}}
              }
            }
        """.trimIndent()

        val inspect = dockhandJson.decodeFromString<ContainerInspect>(payload)

        assertEquals("abc123", inspect.id)
        assertEquals("web", inspect.config?.labels?.get("app"))
        assertEquals("volume", inspect.mounts.first().type)
        assertEquals(true, inspect.mounts.first().rw)
        assertEquals("172.17.0.3", inspect.networkSettings?.networks?.get("bridge")?.ipAddress)
        assertEquals("net2", inspect.networkSettings?.networks?.get("bridge")?.networkId)
    }

    @Test
    fun missingSectionsStillParse() {
        val inspect = dockhandJson.decodeFromString<ContainerInspect>("""{"Id":"abc"}""")

        assertEquals("abc", inspect.id)
        assertEquals("", inspect.name)
        assertNull(inspect.created)
        assertNull(inspect.state)
        assertNull(inspect.config)
        assertNull(inspect.hostConfig)
        assertNull(inspect.networkSettings)
        assertTrue(inspect.mounts.isEmpty())
    }
}
