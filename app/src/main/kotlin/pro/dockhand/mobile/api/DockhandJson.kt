package pro.dockhand.mobile.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

val dockhandJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

object DockhandDecoding {

    fun decodeEnvironments(text: String): List<Environment> = try {
        dockhandJson.decodeFromString<List<Environment>>(text)
    } catch (error: Exception) {
        throw DockhandServiceError.InvalidResponse
    }

    fun decodeDashboardStats(text: String): DashboardEnvironmentSnapshot {
        val root = dockhandJson.parseToJsonElement(text) as? JsonObject
            ?: throw DockhandServiceError.InvalidResponse
        val id = root.int("id") ?: throw DockhandServiceError.InvalidResponse
        val name = root.string("name") ?: throw DockhandServiceError.InvalidResponse

        val containers = root.obj("containers")
        val images = root.obj("images")
        val volumes = root.obj("volumes")
        val networks = root.obj("networks")
        val stacks = root.obj("stacks")
        val metrics = root.obj("metrics")
        val events = root.obj("events")

        return DashboardEnvironmentSnapshot(
            id = id,
            name = name,
            port = root.int("port") ?: 0,
            icon = root.string("icon") ?: "globe",
            socketPath = root.string("socketPath") ?: "",
            collectActivity = root.bool("collectActivity") ?: false,
            collectMetrics = root.bool("collectMetrics") ?: false,
            scannerEnabled = root.bool("scannerEnabled") ?: false,
            updateCheckEnabled = root.bool("updateCheckEnabled") ?: false,
            updateCheckAutoUpdate = root.bool("updateCheckAutoUpdate") ?: false,
            connectionType = root.string("connectionType") ?: "unknown",
            online = root.bool("online") ?: false,
            containers = DashboardEnvironmentSnapshot.Containers(
                total = containers.int("total") ?: 0,
                running = containers.int("running") ?: 0,
                stopped = containers.int("stopped") ?: 0,
                paused = containers.int("paused") ?: 0,
                restarting = containers.int("restarting") ?: 0,
                unhealthy = containers.int("unhealthy") ?: 0,
                pendingUpdates = containers.int("pendingUpdates") ?: 0
            ),
            images = DashboardEnvironmentSnapshot.Images(
                total = images.int("total") ?: 0,
                totalSize = images.long("totalSize") ?: 0
            ),
            volumes = DashboardEnvironmentSnapshot.Volumes(
                total = volumes.int("total") ?: 0,
                totalSize = volumes.long("totalSize") ?: 0
            ),
            containersSize = root.long("containersSize") ?: 0,
            buildCacheSize = root.long("buildCacheSize") ?: 0,
            networks = DashboardEnvironmentSnapshot.Networks(total = networks.int("total") ?: 0),
            stacks = DashboardEnvironmentSnapshot.Stacks(
                total = stacks.int("total") ?: 0,
                running = stacks.int("running") ?: 0,
                partial = stacks.int("partial") ?: 0,
                stopped = stacks.int("stopped") ?: 0
            ),
            metrics = DashboardEnvironmentSnapshot.Metrics(
                cpuPercent = metrics.double("cpuPercent") ?: 0.0,
                memoryPercent = metrics.double("memoryPercent") ?: 0.0,
                memoryUsed = metrics.long("memoryUsed") ?: 0,
                memoryTotal = metrics.long("memoryTotal") ?: 0
            ),
            events = DashboardEnvironmentSnapshot.Events(
                total = events.int("total") ?: 0,
                today = events.int("today") ?: 0
            )
        )
    }

    fun decodeDashboardHost(text: String): DashboardHostSnapshot {
        val root = dockhandJson.parseToJsonElement(text) as? JsonObject
            ?: throw DockhandServiceError.InvalidResponse
        val docker = root.obj("docker") ?: throw DockhandServiceError.InvalidResponse
        val host = root.obj("host") ?: throw DockhandServiceError.InvalidResponse
        val connection = docker.obj("connection")
        val dockhand = root.obj("dockhand") ?: root.obj("app") ?: root.obj("server")

        return DashboardHostSnapshot(
            dockhand = DashboardHostSnapshot.Dockhand(
                version = dockhand.string("version") ?: root.nonBlankString("dockhandVersion") ?: root.nonBlankString("version"),
                build = dockhand.string("build") ?: root.nonBlankString("build"),
                commit = dockhand.string("commit") ?: dockhand.string("gitCommit") ?: root.nonBlankString("commit"),
                runtime = dockhand.string("runtime") ?: root.nonBlankString("runtime"),
                database = dockhand.string("database") ?: root.nonBlankString("database")
            ),
            docker = DashboardHostSnapshot.Docker(
                version = docker.string("version") ?: "Unknown",
                apiVersion = docker.string("apiVersion") ?: "Unknown",
                os = docker.string("os") ?: "Unknown",
                arch = docker.string("arch") ?: "Unknown",
                kernelVersion = docker.string("kernelVersion") ?: "Unknown",
                serverVersion = docker.string("serverVersion") ?: "Unknown",
                connectionType = connection.string("type") ?: "unknown",
                socketPath = connection.string("socketPath")
            ),
            host = DashboardHostSnapshot.Host(
                name = host.string("name") ?: "Unknown",
                cpus = host.int("cpus") ?: 0,
                memory = host.long("memory") ?: 0,
                storageDriver = host.string("storageDriver") ?: "Unknown"
            )
        )
    }

    fun decodePendingUpdates(root: JsonObject): List<PendingContainerUpdate> {
        val updates = root.array("pendingUpdates") ?: return emptyList()
        return updates.mapNotNull { element ->
            val update = element as? JsonObject ?: return@mapNotNull null
            val containerID = update.string("containerId") ?: return@mapNotNull null
            val containerName = update.string("containerName") ?: return@mapNotNull null
            PendingContainerUpdate(
                containerID = containerID,
                containerName = containerName,
                currentImage = update.string("currentImage") ?: "Unknown image",
                checkedAt = update.string("checkedAt")
            )
        }
    }

    fun decodePendingUpdates(text: String): List<PendingContainerUpdate> {
        val root = dockhandJson.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        return decodePendingUpdates(root)
    }

    fun decodeVolumes(text: String): List<VolumeSnapshot> {
        val elements = dockhandJson.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        return elements.mapNotNull { element ->
            val volume = element as? JsonObject ?: return@mapNotNull null
            val name = volume.string("name") ?: return@mapNotNull null
            val usedBy = (volume.array("usedBy") ?: emptyList()).mapNotNull { usageElement ->
                val usage = usageElement as? JsonObject ?: return@mapNotNull null
                val containerID = usage.string("containerId") ?: return@mapNotNull null
                val containerName = usage.string("containerName") ?: return@mapNotNull null
                VolumeUsageSnapshot(containerID = containerID, containerName = containerName)
            }
            VolumeSnapshot(
                name = name,
                driver = volume.string("driver") ?: "Unknown",
                scope = volume.string("scope") ?: "Unknown",
                usedBy = usedBy
            )
        }
    }

    fun decodeNetworks(text: String): List<NetworkSnapshot> {
        val elements = dockhandJson.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        return elements.mapNotNull { element ->
            val network = element as? JsonObject ?: return@mapNotNull null
            val id = network.string("id") ?: return@mapNotNull null
            val name = network.string("name") ?: return@mapNotNull null

            val containerObjects = network.obj("containers")
            val containers = (containerObjects?.entries ?: emptySet())
                .mapNotNull { (containerID, value) ->
                    val container = value as? JsonObject ?: return@mapNotNull null
                    NetworkUsageSnapshot(
                        containerID = containerID,
                        containerName = container.string("name") ?: "Unknown container",
                        ipv4Address = container.string("ipv4Address") ?: ""
                    )
                }
                .sortedBy { it.containerName.lowercase() }

            val ipam = network.obj("ipam")
            val configurations = ipam.array("config") ?: emptyList()
            val subnets = configurations.mapNotNull { (it as? JsonObject)?.string("subnet") }

            NetworkSnapshot(
                id = id,
                name = name,
                driver = network.string("driver") ?: "Unknown",
                scope = network.string("scope") ?: "Unknown",
                isInternal = network.bool("internal") ?: false,
                subnets = subnets,
                containers = containers
            )
        }
    }

    fun decodeActivity(text: String): ContainerActivitySnapshot {
        val root = dockhandJson.parseToJsonElement(text) as? JsonObject
            ?: throw DockhandServiceError.InvalidResponse
        val events = (root.array("events") ?: emptyList()).mapNotNull { element ->
            val event = element as? JsonObject ?: return@mapNotNull null
            val id = event.int("id") ?: return@mapNotNull null
            val containerID = event.string("containerId") ?: return@mapNotNull null
            val action = event.string("action") ?: return@mapNotNull null
            val timestamp = event.string("timestamp") ?: return@mapNotNull null
            ContainerEventSnapshot(
                id = id,
                containerID = containerID,
                containerName = event.string("containerName"),
                image = event.string("image"),
                action = action,
                timestamp = timestamp
            )
        }
        return ContainerActivitySnapshot(
            events = events,
            total = root.int("total") ?: events.size
        )
    }
}

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.value(key: String): JsonElement? {
    val value = this[key] ?: return null
    return if (value is JsonNull) null else value
}

internal fun JsonObject?.int(key: String): Int? =
    (this?.value(key) as? JsonPrimitive)?.intOrNull

internal fun JsonObject?.long(key: String): Long? =
    (this?.value(key) as? JsonPrimitive)?.longOrNull

internal fun JsonObject?.double(key: String): Double? =
    (this?.value(key) as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject?.bool(key: String): Boolean? =
    (this?.value(key) as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject?.string(key: String): String? =
    (this?.value(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject?.nonBlankString(key: String): String? =
    string(key)?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject?.obj(key: String): JsonObject? =
    (this?.value(key) as? JsonObject)

internal fun JsonObject?.array(key: String): List<JsonElement>? =
    (this?.value(key) as? JsonArray)
