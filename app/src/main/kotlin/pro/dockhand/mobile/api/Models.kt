package pro.dockhand.mobile.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Health(
    val status: String,
    val timestamp: String
)

@Serializable
data class Environment(
    val id: Int,
    val name: String,
    val host: String? = null,
    val port: Int,
    @SerialName("protocol") val protocolName: String,
    val icon: String,
    val collectActivity: Boolean,
    val collectMetrics: Boolean,
    val highlightChanges: Boolean,
    val labels: List<JsonObject> = emptyList(),
    val connectionType: String,
    val socketPath: String,
    val publicIp: String? = null,
    val timezone: String? = null,
    val updateCheckEnabled: Boolean? = null,
    val updateCheckAutoUpdate: Boolean? = null,
    val imagePruneEnabled: Boolean? = null,
    val createdAt: String,
    val updatedAt: String? = null
)

@Serializable
data class NetworkInfo(
    val ipAddress: String? = null
)

@Serializable
data class ContainerPort(
    @SerialName("IP") val ip: String? = null,
    @SerialName("PrivatePort") val privatePort: Int,
    @SerialName("PublicPort") val publicPort: Int? = null,
    @SerialName("Type") val type: String
)

@Serializable
data class MountInfo(
    @SerialName("type") val type: String? = null,
    val source: String? = null,
    val destination: String? = null,
    val mode: String? = null,
    val rw: Boolean? = null
)

@Serializable
data class Container(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val created: Long,
    val health: String? = null,
    val restartCount: Int? = null,
    val exitCode: Int? = null,
    val command: String? = null,
    val systemContainer: String? = null,
    val ports: List<ContainerPort> = emptyList(),
    val networks: Map<String, NetworkInfo> = emptyMap(),
    val mounts: List<MountInfo> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val cpuPercent: Double? = null,
    val memoryUsed: Long? = null,
    val memoryLimit: Long? = null,
    val networkRx: Long? = null,
    val networkTx: Long? = null,
    val diskRead: Long? = null,
    val diskWrite: Long? = null,
    val pids: Int? = null
)

@Serializable
data class ImageSummary(
    val id: String,
    val repoTags: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val repoDigests: List<String> = emptyList(),
    val size: Long,
    val virtualSize: Long,
    val created: Long,
    val labels: Map<String, String> = emptyMap(),
    val containers: Int
)

@Serializable
data class StackPort(
    val publicPort: Int? = null,
    val privatePort: Int? = null,
    val type: String? = null,
    val display: String? = null
)

@Serializable
data class StackNetwork(
    val name: String? = null,
    val ipAddress: String? = null
)

@Serializable
data class StackContainerDetail(
    val id: String,
    val name: String,
    val service: String,
    val state: String,
    val status: String,
    val image: String,
    val health: String? = null,
    val created: Long? = null,
    val ports: List<StackPort> = emptyList(),
    val networks: List<StackNetwork> = emptyList(),
    val labels: Map<String, String> = emptyMap()
)

@Serializable
data class StackSummary(
    val name: String,
    val containers: List<String> = emptyList(),
    val containerDetails: List<StackContainerDetail> = emptyList(),
    val status: String,
    val sourceType: String? = null
)

@Serializable
data class StackComposeDocument(
    val content: String? = null,
    val stackDir: String? = null,
    val composePath: String? = null,
    val envPath: String? = null,
    val suggestedEnvPath: String? = null,
    val needsFileLocation: Boolean? = null,
    val error: String? = null
)

@Serializable
data class RawEnvDocument(
    val content: String,
    val noEnvFile: Boolean? = null
)

@Serializable
data class UpdateStackComposeRequest(
    val content: String,
    val restart: Boolean? = null,
    val composePath: String? = null,
    val envPath: String? = null,
    val moveFromDir: String? = null,
    val oldComposePath: String? = null,
    val oldEnvPath: String? = null
)

@Serializable
data class UpdateRawEnvRequest(
    val content: String
)

@Serializable
data class ActionResponse(
    val success: Boolean? = null,
    val deleted: Boolean? = null,
    val noEnvFile: Boolean? = null,
    val error: String? = null
)
