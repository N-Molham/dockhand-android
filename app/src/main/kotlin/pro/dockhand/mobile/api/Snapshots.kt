@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package pro.dockhand.mobile.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class StackEditorDocument(
    var composeContent: String,
    var envContent: String,
    var composePath: String? = null,
    var envPath: String? = null,
    var suggestedEnvPath: String? = null,
    var needsFileLocation: Boolean = false,
    var noEnvFile: Boolean = false,
    var composeError: String? = null
)

@Serializable
data class ContainerLogsDocument(
    var logs: String
)

@Serializable
data class ImagePullProgressDetail(
    val current: Long? = null,
    val total: Long? = null
)

@Serializable
data class ImagePullProgressEvent(
    val id: String? = null,
    val status: String? = null,
    val progress: String? = null,
    val progressDetail: ImagePullProgressDetail? = null,
    val error: String? = null
)

@Serializable
data class ImagePullJobLine(
    val event: String? = null,
    val data: ImagePullProgressEvent
)

@Serializable
data class ImagePullJobSnapshot(
    val status: String,
    val lines: List<ImagePullJobLine> = emptyList(),
    val result: ImagePullProgressEvent? = null
)

sealed interface ImagePullStartResult {
    data class Job(val id: String) : ImagePullStartResult
    data class Completed(val event: ImagePullProgressEvent) : ImagePullStartResult
}

@Serializable
data class DashboardEnvironmentSnapshot(
    val id: Int,
    val name: String,
    val port: Int = 0,
    val icon: String = "globe",
    val socketPath: String = "",
    val collectActivity: Boolean = false,
    val collectMetrics: Boolean = false,
    val scannerEnabled: Boolean = false,
    val updateCheckEnabled: Boolean = false,
    val updateCheckAutoUpdate: Boolean = false,
    val connectionType: String = "unknown",
    val online: Boolean = false,
    val containers: Containers = Containers(),
    val images: Images = Images(),
    val volumes: Volumes = Volumes(),
    val containersSize: Long = 0,
    val buildCacheSize: Long = 0,
    val networks: Networks = Networks(),
    val stacks: Stacks = Stacks(),
    val metrics: Metrics = Metrics(),
    val events: Events = Events()
) {
    @Serializable
    data class Containers(
        val total: Int = 0,
        val running: Int = 0,
        val stopped: Int = 0,
        val paused: Int = 0,
        val restarting: Int = 0,
        val unhealthy: Int = 0,
        val pendingUpdates: Int = 0
    )

    @Serializable
    data class Images(val total: Int = 0, val totalSize: Long = 0)

    @Serializable
    data class Volumes(val total: Int = 0, val totalSize: Long = 0)

    @Serializable
    data class Networks(val total: Int = 0)

    @Serializable
    data class Stacks(
        val total: Int = 0,
        val running: Int = 0,
        val partial: Int = 0,
        val stopped: Int = 0
    )

    @Serializable
    data class Metrics(
        val cpuPercent: Double = 0.0,
        val memoryPercent: Double = 0.0,
        val memoryUsed: Long = 0,
        val memoryTotal: Long = 0
    )

    @Serializable
    data class Events(val total: Int = 0, val today: Int = 0)
}

@Serializable
data class DashboardHostSnapshot(
    val dockhand: Dockhand? = null,
    val docker: Docker,
    val host: Host
) {
    @Serializable
    data class Dockhand(
        val version: String? = null,
        val build: String? = null,
        val commit: String? = null,
        val runtime: String? = null,
        val database: String? = null
    )

    @Serializable
    data class Docker(
        val version: String = "Unknown",
        val apiVersion: String = "Unknown",
        val os: String = "Unknown",
        val arch: String = "Unknown",
        val kernelVersion: String = "Unknown",
        val serverVersion: String = "Unknown",
        val connectionType: String = "unknown",
        val socketPath: String? = null
    )

    @Serializable
    data class Host(
        val name: String = "Unknown",
        val cpus: Int = 0,
        val memory: Long = 0,
        val storageDriver: String = "Unknown"
    )
}

@Serializable
data class PendingContainerUpdate(
    val containerID: String,
    val containerName: String,
    val currentImage: String = "Unknown image",
    val checkedAt: String? = null
)

@Serializable
data class ContainerUpdateCheckProgress(
    val checked: Int? = null,
    val total: Int? = null
)

@Serializable
data class ContainerUpdateCheckResult(
    val total: Int = 0,
    val updatesFound: Int = 0
)

sealed interface ContainerUpdateCheckOperation {
    data class Job(val id: String) : ContainerUpdateCheckOperation
    data class Completed(val result: ContainerUpdateCheckResult) : ContainerUpdateCheckOperation
}

@Serializable
data class ContainerUpdateCheckJobLine(
    val event: String? = null,
    val data: ContainerUpdateCheckProgress
)

@Serializable
data class ContainerUpdateCheckJobSnapshot(
    val status: String,
    val lines: List<ContainerUpdateCheckJobLine> = emptyList(),
    val result: ContainerUpdateCheckResult? = null
)

@Serializable
data class ContainerBatchUpdateResult(
    @kotlinx.serialization.SerialName("containerId") val containerID: String,
    val containerName: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class ContainerBatchUpdateSummary(
    val total: Int = 0,
    val success: Int = 0,
    val failed: Int = 0
)

@Serializable
data class ContainerBatchUpdateResponse(
    val success: Boolean,
    val results: List<ContainerBatchUpdateResult> = emptyList(),
    val summary: ContainerBatchUpdateSummary = ContainerBatchUpdateSummary()
)

@Serializable
data class VolumeUsageSnapshot(
    val containerID: String,
    val containerName: String
)

@Serializable
data class VolumeSnapshot(
    val name: String,
    val driver: String = "Unknown",
    val scope: String = "Unknown",
    val usedBy: List<VolumeUsageSnapshot> = emptyList()
)

@Serializable
data class NetworkUsageSnapshot(
    val containerID: String,
    val containerName: String,
    val ipv4Address: String = ""
)

@Serializable
data class NetworkSnapshot(
    val id: String,
    val name: String,
    val driver: String = "Unknown",
    val scope: String = "Unknown",
    val isInternal: Boolean = false,
    val subnets: List<String> = emptyList(),
    val containers: List<NetworkUsageSnapshot> = emptyList()
)

@Serializable
data class ContainerEventSnapshot(
    val id: Int,
    val containerID: String,
    val containerName: String? = null,
    val image: String? = null,
    val action: String,
    val timestamp: String
)

@Serializable
data class ContainerActivitySnapshot(
    val events: List<ContainerEventSnapshot> = emptyList(),
    val total: Int = 0
)

@Serializable
data class StackDeployOptions(
    var pull: Boolean = true,
    var build: Boolean = false,
    var forceRecreate: Boolean = false
)

@Serializable
data class StackRedeployProgressEvent(
    val status: String? = null
)

@Serializable
data class StackRedeployJobLine(
    val event: String? = null,
    val data: StackRedeployProgressEvent
)

@Serializable
data class StackRedeployResult(
    val success: Boolean? = null,
    val status: String? = null,
    val output: String? = null,
    val error: String? = null
)

@Serializable
data class StackRedeployJobSnapshot(
    val status: String,
    val lines: List<StackRedeployJobLine> = emptyList(),
    val result: StackRedeployResult? = null
)

sealed interface StackRedeployStartResult {
    data class Job(val id: String) : StackRedeployStartResult
    data class Completed(val result: StackRedeployResult) : StackRedeployStartResult
}

@Serializable
data class ContainerShellInfo(
    val path: String,
    val label: String,
    val available: Boolean
)

@Serializable
data class ContainerShellDetectionResult(
    val shells: List<String> = emptyList(),
    val defaultShell: String? = null,
    val allShells: List<ContainerShellInfo> = emptyList(),
    val error: String? = null
) {
    val hasAvailableShells: Boolean get() = shells.isNotEmpty()

    fun bestShell(preferredShell: String): String? {
        if (preferredShell in shells) return preferredShell
        val preferredName = preferredShell.substringAfterLast('/')
        shells.firstOrNull { it.substringAfterLast('/') == preferredName }?.let { return it }
        return defaultShell ?: shells.firstOrNull()
    }
}

data class ImageScanDocument(
    var stage: String,
    var message: String,
    var progress: Int? = null,
    var results: List<String> = emptyList()
)

@Serializable
data class ContainerInspect(
    @JsonNames("Id") val id: String = "",
    @JsonNames("Name") val name: String = "",
    @JsonNames("Image") val image: String = "",
    @JsonNames("Created") val created: String? = null,
    @JsonNames("Platform") val platform: String? = null,
    @JsonNames("RestartCount") val restartCount: Int? = null,
    @JsonNames("State") val state: State? = null,
    @JsonNames("Config") val config: Config? = null,
    @JsonNames("HostConfig") val hostConfig: HostConfig? = null,
    @JsonNames("Mounts") val mounts: List<Mount> = emptyList(),
    @JsonNames("NetworkSettings") val networkSettings: NetworkSettings? = null,
    @JsonNames("Ports") val ports: Map<String, List<PortBinding>?>? = null
) {
    @Serializable
    data class State(
        @JsonNames("Status") val status: String? = null,
        @JsonNames("Running") val running: Boolean? = null,
        @JsonNames("Paused") val paused: Boolean? = null,
        @JsonNames("Restarting") val restarting: Boolean? = null,
        @JsonNames("Pid") val pid: Int? = null,
        @JsonNames("ExitCode") val exitCode: Int? = null,
        @JsonNames("StartedAt") val startedAt: String? = null,
        @JsonNames("FinishedAt") val finishedAt: String? = null,
        @JsonNames("Health") val health: Health? = null
    )

    @Serializable
    data class Health(
        @JsonNames("Status") val status: String? = null
    )

    @Serializable
    data class Config(
        @JsonNames("Image") val image: String? = null,
        @JsonNames("Hostname") val hostname: String? = null,
        @JsonNames("User") val user: String? = null,
        @JsonNames("WorkingDir") val workingDir: String? = null,
        @JsonNames("Entrypoint") val entrypoint: List<String>? = null,
        @JsonNames("Cmd") val cmd: List<String>? = null,
        @JsonNames("Env") val env: List<String>? = null,
        @JsonNames("Labels") val labels: Map<String, String> = emptyMap()
    )

    @Serializable
    data class HostConfig(
        @JsonNames("Binds") val binds: List<String>? = null,
        @JsonNames("NetworkMode") val networkMode: String? = null,
        @JsonNames("RestartPolicy") val restartPolicy: RestartPolicy? = null,
        @JsonNames("Privileged") val privileged: Boolean? = null,
        @JsonNames("Memory") val memory: Long? = null,
        @JsonNames("NanoCpus") val nanoCpus: Long? = null
    )

    @Serializable
    data class RestartPolicy(
        @JsonNames("Name") val name: String? = null
    )

    @Serializable
    data class Mount(
        @JsonNames("Type") val type: String? = null,
        @JsonNames("Source") val source: String? = null,
        @JsonNames("Destination") val destination: String? = null,
        @JsonNames("Mode") val mode: String? = null,
        @JsonNames("RW") val rw: Boolean? = null
    )

    @Serializable
    data class NetworkSettings(
        @JsonNames("IPAddress") val ipAddress: String? = null,
        @JsonNames("Networks") val networks: Map<String, Network> = emptyMap(),
        @JsonNames("Ports") val ports: Map<String, List<PortBinding>?>? = null
    )

    @Serializable
    data class Network(
        @JsonNames("IPAddress") val ipAddress: String? = null,
        @JsonNames("Gateway") val gateway: String? = null,
        @JsonNames("MacAddress") val macAddress: String? = null,
        @JsonNames("Aliases") val aliases: List<String>? = null,
        @JsonNames("NetworkID") val networkId: String? = null
    )

    @Serializable
    data class PortBinding(
        @JsonNames("HostIp") val hostIp: String? = null,
        @JsonNames("HostPort") val hostPort: String? = null
    )
}
