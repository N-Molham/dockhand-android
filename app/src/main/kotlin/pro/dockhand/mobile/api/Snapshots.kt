package pro.dockhand.mobile.api

import kotlinx.serialization.Serializable

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
