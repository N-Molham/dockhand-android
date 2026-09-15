package pro.dockhand.mobile.api

import kotlinx.coroutines.flow.Flow
import okhttp3.Request

interface DockhandApi {
    suspend fun fetchHealthStatus(): String
    suspend fun fetchEnvironments(): List<Environment>
    suspend fun fetchContainers(environmentID: Int): List<Container>
    suspend fun fetchImages(environmentID: Int): List<ImageSummary>
    suspend fun fetchStacks(environmentID: Int): List<StackSummary>
    suspend fun fetchDashboardStats(environmentID: Int): DashboardEnvironmentSnapshot
    suspend fun fetchDashboardHost(environmentID: Int): DashboardHostSnapshot
    suspend fun fetchPendingContainerUpdates(environmentID: Int): List<PendingContainerUpdate>
    suspend fun startContainerUpdateCheck(environmentID: Int): ContainerUpdateCheckOperation
    suspend fun fetchContainerUpdateCheckJob(id: String): ContainerUpdateCheckJobSnapshot
    suspend fun updateContainers(ids: List<String>, environmentID: Int): ContainerBatchUpdateResponse
    suspend fun fetchVolumes(environmentID: Int): List<VolumeSnapshot>
    suspend fun fetchNetworks(environmentID: Int): List<NetworkSnapshot>
    suspend fun fetchContainerActivity(environmentID: Int, limit: Int = 100): ContainerActivitySnapshot
    suspend fun clearPendingContainerUpdate(containerID: String, environmentID: Int)
    suspend fun fetchStackEditorDocument(name: String, environmentID: Int): StackEditorDocument
    suspend fun fetchContainerLogs(containerID: String, environmentID: Int, tail: Int = 200): ContainerLogsDocument
    suspend fun fetchContainerShells(containerID: String, environmentID: Int): ContainerShellDetectionResult
    fun streamContainerLogs(containerID: String, environmentID: Int, tail: Int = 200): Flow<ContainerLogEvent>
    fun makeContainerShellRequest(containerID: String, environmentID: Int, shell: String, user: String): Request
    suspend fun startImagePull(imageName: String, environmentID: Int, tag: String? = null): ImagePullStartResult
    suspend fun fetchImagePullJob(id: String): ImagePullJobSnapshot
    suspend fun pruneImages(environmentID: Int, danglingOnly: Boolean)
    suspend fun tagImage(imageID: String, environmentID: Int, repo: String, tag: String)
    suspend fun deleteImage(imageReference: String, environmentID: Int)
    suspend fun deleteImageTag(imageTag: String, environmentID: Int)
    suspend fun scanImage(imageName: String, environmentID: Int): ImageScanDocument
    suspend fun startContainer(containerID: String, environmentID: Int)
    suspend fun stopContainer(containerID: String, environmentID: Int)
    suspend fun restartContainer(containerID: String, environmentID: Int)
    suspend fun pauseContainer(containerID: String, environmentID: Int)
    suspend fun unpauseContainer(containerID: String, environmentID: Int)
    suspend fun stackAction(action: StackAction, stackName: String, environmentID: Int)
    suspend fun redeployStack(stackName: String, environmentID: Int, options: StackDeployOptions)
    suspend fun startStackRedeploy(stackName: String, environmentID: Int, options: StackDeployOptions): StackRedeployStartResult
    suspend fun fetchStackRedeployJob(id: String): StackRedeployJobSnapshot
    suspend fun deleteStack(stackName: String, environmentID: Int, deleteVolumes: Boolean)
    suspend fun updateStackCompose(name: String, environmentID: Int, request: UpdateStackComposeRequest)
    suspend fun updateStackEnvFile(name: String, environmentID: Int, request: UpdateRawEnvRequest)
}
