package pro.dockhand.mobile.api

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class StackAction(val endpoint: String) {
    START("start"),
    STOP("stop"),
    RESTART("restart"),
    DOWN("down"),
    REDEPLOY("deploy")
}

class DockhandService(
    baseUrl: String,
    token: String,
    private val allowCleartext: Boolean = false,
    private val customHeaders: Map<String, String> = emptyMap(),
    client: OkHttpClient = defaultClient
) {
    val token: String = DockhandToken.normalized(token)

    private val baseHttpUrl: HttpUrl = baseUrl.trim().let { trimmed ->
        if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }.toHttpUrlOrNull() ?: throw DockhandServiceError.InvalidResponse

    private val httpClient: OkHttpClient
    private val streamingClient: OkHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    init {
        val headers = customHeaders.toMap()
        val normalizedToken = this.token
        val headerInterceptor = Interceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            headers.forEach { (name, value) -> builder.header(name, value) }
            if (original.header("Accept") == null) {
                builder.header("Accept", "application/json")
            }
            if (normalizedToken.isNotEmpty()) {
                builder.header("Authorization", "Bearer $normalizedToken")
            }
            chain.proceed(builder.build())
        }
        httpClient = client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .addInterceptor(headerInterceptor)
            .build()
        streamingClient = httpClient.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchHealthStatus(): String {
        val request = Request.Builder().url(requestUrl("api/health")).get().build()
        val (status, text) = executeText(request)
        if (status != 200) throw DockhandServiceError.UnexpectedStatus(status)
        return dockhandJson.decodeFromString<Health>(text).status
    }

    suspend fun fetchEnvironments(): List<Environment> {
        val request = Request.Builder().url(requestUrl("api/environments")).get().build()
        val (status, text) = executeText(request)
        if (status !in 200..299) throw DockhandServiceError.UnexpectedStatus(status)
        return DockhandDecoding.decodeEnvironments(text)
    }

    suspend fun fetchContainers(environmentID: Int): List<Container> =
        fetchList("api/containers", environmentID)

    suspend fun fetchImages(environmentID: Int): List<ImageSummary> =
        fetchList("api/images", environmentID)

    suspend fun fetchStacks(environmentID: Int): List<StackSummary> =
        fetchList("api/stacks", environmentID)

    suspend fun fetchDashboardStats(environmentID: Int): DashboardEnvironmentSnapshot =
        DockhandDecoding.decodeDashboardStats(
            performJsonObject("api/dashboard/stats", "GET", environmentID).toString()
        )

    suspend fun fetchDashboardHost(environmentID: Int): DashboardHostSnapshot =
        DockhandDecoding.decodeDashboardHost(
            performJsonObject("api/system", "GET", environmentID).toString()
        )

    suspend fun fetchPendingContainerUpdates(environmentID: Int): List<PendingContainerUpdate> =
        DockhandDecoding.decodePendingUpdates(
            performJsonObject("api/containers/pending-updates", "GET", environmentID).toString()
        )

    suspend fun startContainerUpdateCheck(environmentID: Int): ContainerUpdateCheckOperation {
        val response = performJsonObject("api/containers/check-updates", "POST", environmentID)
        val jobID = response.string("jobId")
        if (!jobID.isNullOrEmpty()) return ContainerUpdateCheckOperation.Job(jobID)
        return ContainerUpdateCheckOperation.Completed(
            dockhandJson.decodeFromString<ContainerUpdateCheckResult>(response.toString())
        )
    }

    suspend fun fetchContainerUpdateCheckJob(id: String): ContainerUpdateCheckJobSnapshot {
        val request = Request.Builder().url(requestUrl("api/jobs/$id")).get().build()
        val (status, text) = executeText(request)
        validateResponse(status, text)
        return dockhandJson.decodeFromString<ContainerUpdateCheckJobSnapshot>(text)
    }

    suspend fun updateContainers(ids: List<String>, environmentID: Int): ContainerBatchUpdateResponse {
        if (ids.isEmpty()) throw DockhandServiceError.InvalidResponse
        val body = buildJsonObject {
            putJsonArray("containerIds") { ids.forEach { add(it) } }
        }
        val response = performJsonObject(
            "api/containers/batch-update",
            "POST",
            environmentID,
            body = body.toString()
        )
        return dockhandJson.decodeFromString<ContainerBatchUpdateResponse>(response.toString())
    }

    suspend fun fetchVolumes(environmentID: Int): List<VolumeSnapshot> =
        DockhandDecoding.decodeVolumes(performJsonArrayText("api/volumes", "GET", environmentID))

    suspend fun fetchNetworks(environmentID: Int): List<NetworkSnapshot> =
        DockhandDecoding.decodeNetworks(performJsonArrayText("api/networks", "GET", environmentID))

    suspend fun fetchContainerActivity(
        environmentID: Int,
        limit: Int = 100
    ): ContainerActivitySnapshot = DockhandDecoding.decodeActivity(
        performJsonObject(
            "api/activity",
            "GET",
            environmentID,
            additionalQuery = listOf(
                "environmentId" to environmentID.toString(),
                "limit" to limit.toString()
            )
        ).toString()
    )

    suspend fun clearPendingContainerUpdate(containerID: String, environmentID: Int) {
        val response = performJsonObject(
            "api/containers/pending-updates",
            "DELETE",
            environmentID,
            additionalQuery = listOf("containerId" to containerID)
        )
        if (response.bool("success") != true) throw DockhandServiceError.InvalidResponse
    }

    suspend fun fetchStackEditorDocument(name: String, environmentID: Int): StackEditorDocument =
        coroutineScope {
            val composeDocument = async { fetchStackComposeDocument(name, environmentID) }
            val envDocument = async { fetchStackEnvDocument(name, environmentID) }
            val compose = composeDocument.await()
            val env = envDocument.await()
            StackEditorDocument(
                composeContent = compose.content.orEmpty(),
                envContent = env.content,
                composePath = compose.composePath,
                envPath = compose.envPath,
                suggestedEnvPath = compose.suggestedEnvPath,
                needsFileLocation = compose.needsFileLocation ?: false,
                noEnvFile = env.noEnvFile ?: false,
                composeError = compose.error
            )
        }

    suspend fun fetchContainerLogs(
        containerID: String,
        environmentID: Int,
        tail: Int = 200
    ): ContainerLogsDocument {
        val request = jsonRequest(
            "api/containers/$containerID/logs",
            "GET",
            environmentID,
            additionalQuery = listOf("tail" to tail.toString())
        )
        val (status, text) = executeText(request)
        if (status != 200) throw containerLogError(status, text)
        return ContainerLogsDocument(dockhandJson.decodeFromString<ContainerLogsResponse>(text).logs)
    }

    suspend fun fetchContainerShells(
        containerID: String,
        environmentID: Int
    ): ContainerShellDetectionResult {
        val request = jsonRequest("api/containers/$containerID/shells", "GET", environmentID)
        val (status, text) = executeText(request)
        if (status !in 200..299) throw DockhandServiceError.UnexpectedStatus(status)
        val response = dockhandJson.decodeFromString<ContainerShellDetectionResponse>(text)
        return ContainerShellDetectionResult(
            shells = response.shells,
            defaultShell = response.defaultShell,
            allShells = response.allShells,
            error = response.error
        )
    }

    fun makeContainerShellRequest(
        containerID: String,
        environmentID: Int,
        shell: String,
        user: String
    ): Request {
        val url = requestUrl(
            "api/containers/$containerID/exec",
            listOf(
                "shell" to shell,
                "user" to user,
                "envId" to environmentID.toString()
            )
        )
        val builder = Request.Builder().url(url).get()
        if (token.isNotEmpty()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    fun streamContainerLogs(
        containerID: String,
        environmentID: Int,
        tail: Int = 200
    ): Flow<ContainerLogEvent> = channelFlow {
        val producer = this
        val request = jsonRequest(
            "api/containers/$containerID/logs/stream",
            "GET",
            environmentID,
            additionalQuery = listOf("tail" to tail.toString())
        ).newBuilder().header("Accept", "text/event-stream").build()
        withContext(Dispatchers.IO) {
            streamingClient.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    throw containerLogError(response.code, response.body.string())
                }
                val decoder = ContainerLogSSEDecoder()
                response.body.byteStream().use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        for (event in decoder.consume(chunk.copyOf(count))) {
                            producer.trySend(event)
                        }
                    }
                }
                for (event in decoder.finish()) {
                    producer.trySend(event)
                }
            }
        }
    }

    suspend fun startImagePull(
        imageName: String,
        environmentID: Int,
        tag: String? = null
    ): ImagePullStartResult {
        val body = buildJsonObject {
            put("image", imageName)
            put("scanAfterPull", false)
            val normalizedTag = tag?.trim().orEmpty()
            if (normalizedTag.isNotEmpty()) put("tag", normalizedTag)
        }
        val (status, text) = executeText(
            jsonRequest("api/images/pull", "POST", environmentID, body = body.toString())
        )
        validateResponse(status, text)
        val startPayload = runCatching {
            dockhandJson.decodeFromString<ImagePullJobStartPayload>(text)
        }.getOrNull()
        val jobID = startPayload?.jobId
        if (!jobID.isNullOrEmpty()) return ImagePullStartResult.Job(jobID)
        val completed = dockhandJson.decodeFromString<ImagePullProgressEvent>(text)
        val error = completed.error
        if (!error.isNullOrEmpty()) throw DockhandServiceError.Message(error)
        if (completed.status == "complete") return ImagePullStartResult.Completed(completed)
        throw DockhandServiceError.InvalidResponse
    }

    suspend fun fetchImagePullJob(id: String): ImagePullJobSnapshot {
        val request = Request.Builder().url(requestUrl("api/jobs/$id")).get().build()
        val (status, text) = executeText(request)
        validateResponse(status, text)
        return dockhandJson.decodeFromString<ImagePullJobSnapshot>(text)
    }

    suspend fun pruneImages(environmentID: Int, danglingOnly: Boolean) {
        val additionalQuery = if (danglingOnly) emptyList() else listOf("dangling" to "false")
        val response = performJsonObject(
            "api/prune/images",
            "POST",
            environmentID,
            additionalQuery = additionalQuery
        )
        if (response.bool("success") == true) return
        val error = response.string("error")
        if (!error.isNullOrEmpty()) throw DockhandServiceError.Message(error)
        throw DockhandServiceError.InvalidResponse
    }

    suspend fun tagImage(imageID: String, environmentID: Int, repo: String, tag: String) {
        val body = buildJsonObject {
            put("repo", repo)
            put("tag", tag)
        }
        val response = performJsonObject(
            "api/images/$imageID/tag",
            "POST",
            environmentID,
            body = body.toString()
        )
        if (response.bool("success") == true) return
        throw DockhandServiceError.Message(response.string("error") ?: "Failed to tag image")
    }

    suspend fun deleteImage(imageReference: String, environmentID: Int) {
        val response = performJsonObject("api/images/$imageReference", "DELETE", environmentID)
        if (response.bool("success") == true || response.string("status") == "complete") return
        val error = response.string("error")
        if (error != null) throw DockhandServiceError.Message(error)
        throw DockhandServiceError.InvalidResponse
    }

    suspend fun deleteImageTag(imageTag: String, environmentID: Int) {
        val body = buildJsonObject {
            put("operation", "remove")
            put("entityType", "images")
            putJsonArray("items") {
                add(
                    buildJsonObject {
                        put("id", imageTag)
                        put("name", imageTag)
                    }
                )
            }
        }
        val response = performJsonObject("api/batch", "POST", environmentID, body = body.toString())
        if (response.obj("summary")?.int("failed") == 0) return
        if (response.string("type") == "complete") return
        val error = response.string("error")
        if (error != null) throw DockhandServiceError.Message(error)
        throw DockhandServiceError.InvalidResponse
    }

    suspend fun scanImage(imageName: String, environmentID: Int): ImageScanDocument {
        val body = buildJsonObject { put("imageName", imageName) }
        val response = performJsonObject("api/images/scan", "POST", environmentID, body = body.toString())
        val error = response.string("error")
        if (!error.isNullOrEmpty()) throw DockhandServiceError.Message(error)
        val results = (response.array("results") ?: emptyList()).mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val target = entry.string("target")
            val vulnerability = entry.string("vulnerability")
            if (target != null && vulnerability != null) "$target: $vulnerability" else entry.toString()
        }
        return ImageScanDocument(
            stage = response.string("stage") ?: "complete",
            message = response.string("message") ?: "Scan complete",
            progress = response.int("progress"),
            results = results
        )
    }

    suspend fun startContainer(containerID: String, environmentID: Int) =
        containerAction("start", containerID, environmentID)

    suspend fun stopContainer(containerID: String, environmentID: Int) =
        containerAction("stop", containerID, environmentID)

    suspend fun restartContainer(containerID: String, environmentID: Int) =
        containerAction("restart", containerID, environmentID)

    suspend fun pauseContainer(containerID: String, environmentID: Int) =
        containerAction("pause", containerID, environmentID)

    suspend fun unpauseContainer(containerID: String, environmentID: Int) =
        containerAction("unpause", containerID, environmentID)

    suspend fun stackAction(action: StackAction, stackName: String, environmentID: Int) {
        val response = performJsonObject(
            "api/stacks/$stackName/${action.endpoint}",
            "POST",
            environmentID
        )
        stackResultOrThrow(response)
    }

    suspend fun redeployStack(
        stackName: String,
        environmentID: Int,
        options: StackDeployOptions
    ) {
        val response = performJsonObject(
            "api/stacks/$stackName/deploy",
            "POST",
            environmentID,
            body = deployOptionsBody(options).toString()
        )
        stackResultOrThrow(response)
    }

    suspend fun startStackRedeploy(
        stackName: String,
        environmentID: Int,
        options: StackDeployOptions
    ): StackRedeployStartResult {
        val (status, text) = executeText(
            jsonRequest(
                "api/stacks/$stackName/deploy",
                "POST",
                environmentID,
                body = deployOptionsBody(options).toString()
            )
        )
        validateResponse(status, text)
        val jobID = runCatching {
            dockhandJson.decodeFromString<ImagePullJobStartPayload>(text)
        }.getOrNull()?.jobId
        if (!jobID.isNullOrEmpty()) return StackRedeployStartResult.Job(jobID)
        val result = dockhandJson.decodeFromString<StackRedeployResult>(text)
        if (result.success == false || result.error != null) {
            throw DockhandServiceError.Message(result.error ?: "Stack redeploy failed")
        }
        return StackRedeployStartResult.Completed(result)
    }

    suspend fun fetchStackRedeployJob(id: String): StackRedeployJobSnapshot {
        val request = Request.Builder().url(requestUrl("api/jobs/$id")).get().build()
        val (status, text) = executeText(request)
        validateResponse(status, text)
        return dockhandJson.decodeFromString<StackRedeployJobSnapshot>(text)
    }

    suspend fun deleteStack(stackName: String, environmentID: Int, deleteVolumes: Boolean) {
        val additionalQuery = buildList {
            add("force" to "true")
            if (deleteVolumes) add("volumes" to "true")
        }
        val response = performJsonObject(
            "api/stacks/$stackName",
            "DELETE",
            environmentID,
            additionalQuery = additionalQuery
        )
        stackResultOrThrow(response)
    }

    suspend fun updateStackCompose(
        name: String,
        environmentID: Int,
        request: UpdateStackComposeRequest
    ) {
        val (status, text) = executeText(
            jsonRequest(
                "api/stacks/$name/compose",
                "PUT",
                environmentID,
                body = dockhandJson.encodeToString(request)
            )
        )
        if (status != 200) throw DockhandServiceError.UnexpectedStatus(status)
        val response = dockhandJson.decodeFromString<ActionResponse>(text)
        if (response.success == false) {
            throw DockhandServiceError.Message(response.error ?: "Failed to save compose file")
        }
    }

    suspend fun updateStackEnvFile(
        name: String,
        environmentID: Int,
        request: UpdateRawEnvRequest
    ) {
        val (status, text) = executeText(
            jsonRequest(
                "api/stacks/$name/env/raw",
                "PUT",
                environmentID,
                body = dockhandJson.encodeToString(request)
            )
        )
        if (status != 200) throw DockhandServiceError.UnexpectedStatus(status)
        val response = dockhandJson.decodeFromString<ActionResponse>(text)
        if (response.success == false) {
            throw DockhandServiceError.Message(response.error ?: "Failed to save .env")
        }
    }

    private suspend fun containerAction(
        action: String,
        containerID: String,
        environmentID: Int
    ) {
        val (status, text) = executeText(
            jsonRequest("api/containers/$containerID/$action", "POST", environmentID)
        )
        if (status != 200) throw DockhandServiceError.UnexpectedStatus(status)
        val response = dockhandJson.decodeFromString<ActionResponse>(text)
        if (response.success == false) {
            throw DockhandServiceError.Message(response.error ?: "Action failed")
        }
    }

    private suspend fun fetchStackComposeDocument(
        name: String,
        environmentID: Int
    ): StackComposeDocument {
        val (status, text) = executeText(jsonRequest("api/stacks/$name/compose", "GET", environmentID))
        if (status != 200 && status != 404) throw DockhandServiceError.UnexpectedStatus(status)
        return dockhandJson.decodeFromString<StackComposeDocument>(text)
    }

    private suspend fun fetchStackEnvDocument(name: String, environmentID: Int): RawEnvDocument {
        val (status, text) = executeText(jsonRequest("api/stacks/$name/env/raw", "GET", environmentID))
        if (status != 200) throw DockhandServiceError.UnexpectedStatus(status)
        return dockhandJson.decodeFromString<RawEnvDocument>(text)
    }

    private suspend inline fun <reified T> fetchList(path: String, environmentID: Int): List<T> {
        val (status, text) = executeText(jsonRequest(path, "GET", environmentID))
        if (status != 200) throw DockhandServiceError.UnexpectedStatus(status)
        return dockhandJson.decodeFromString(text)
    }

    private suspend fun performJsonObject(
        path: String,
        method: String,
        environmentID: Int,
        additionalQuery: List<Pair<String, String>> = emptyList(),
        body: String? = null
    ): JsonObject {
        val (status, text) = executeText(
            jsonRequest(path, method, environmentID, additionalQuery, body)
        )
        validateResponse(status, text)
        return dockhandJson.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun performJsonArrayText(
        path: String,
        method: String,
        environmentID: Int
    ): String {
        val (status, text) = executeText(jsonRequest(path, method, environmentID))
        validateResponse(status, text)
        if (dockhandJson.parseToJsonElement(text) !is JsonArray) {
            throw DockhandServiceError.InvalidResponse
        }
        return text
    }

    private fun jsonRequest(
        path: String,
        method: String,
        environmentID: Int? = null,
        additionalQuery: List<Pair<String, String>> = emptyList(),
        body: String? = null
    ): Request {
        val query = ArrayList<Pair<String, String>>()
        if (environmentID != null) query.add("env" to environmentID.toString())
        query.addAll(additionalQuery)
        val builder = Request.Builder().url(requestUrl(path, query))
        val requestBody = body?.toRequestBody(jsonMediaType)
            ?: if (method == "GET" || method == "HEAD") null else ByteArray(0).toRequestBody(null)
        return builder.method(method, requestBody).build()
    }

    private fun requestUrl(
        path: String,
        query: List<Pair<String, String>> = emptyList()
    ): HttpUrl {
        ensureCleartextAllowed()
        val builder = baseHttpUrl.newBuilder().addPathSegments(path)
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private suspend fun executeText(request: Request): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                response.code to response.body.string()
            }
        }

    private fun ensureCleartextAllowed() {
        if (baseHttpUrl.scheme == "http" && !allowCleartext) {
            throw DockhandServiceError.Message("Cleartext HTTP is disabled for this server.")
        }
    }

    private fun validateResponse(status: Int, text: String) {
        if (status in 200..299) return
        val error = runCatching {
            dockhandJson.parseToJsonElement(text) as? JsonObject
        }.getOrNull()?.string("error")
        if (error != null) throw DockhandServiceError.Message(error)
        throw DockhandServiceError.UnexpectedStatus(status)
    }

    private fun stackResultOrThrow(response: JsonObject) {
        if (response.bool("success") == true || response.string("status") == "complete") return
        val error = response.string("error")
        if (!error.isNullOrEmpty()) throw DockhandServiceError.Message(error)
        throw DockhandServiceError.InvalidResponse
    }

    private fun deployOptionsBody(options: StackDeployOptions): JsonObject = buildJsonObject {
        put("pull", options.pull)
        put("build", options.build)
        put("forceRecreate", options.forceRecreate)
    }

    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .build()

        fun containerLogError(statusCode: Int, data: ByteArray): DockhandServiceError =
            containerLogError(statusCode, String(data, Charsets.UTF_8))

        fun containerLogError(statusCode: Int, data: String): DockhandServiceError {
            if (statusCode < 500) return DockhandServiceError.UnexpectedStatus(statusCode)
            val payload = runCatching {
                dockhandJson.decodeFromString<ContainerLogErrorPayload>(data)
            }.getOrNull()
            return DockhandServiceError.LogsUnavailable(payload?.details ?: payload?.error)
        }
    }
}

@Serializable
private data class ImagePullJobStartPayload(val jobId: String? = null)

@Serializable
private data class ContainerLogsResponse(val logs: String)

@Serializable
private data class ContainerShellDetectionResponse(
    val shells: List<String> = emptyList(),
    val defaultShell: String? = null,
    val allShells: List<ContainerShellInfo> = emptyList(),
    val error: String? = null
)

@Serializable
private data class ContainerLogErrorPayload(
    val error: String? = null,
    val details: String? = null
)
