package pro.dockhand.mobile.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.dockhand.mobile.api.Container
import pro.dockhand.mobile.api.ContainerActivitySnapshot
import pro.dockhand.mobile.api.ContainerBatchUpdateResponse
import pro.dockhand.mobile.api.ContainerLogEvent
import pro.dockhand.mobile.api.ContainerLogsDocument
import pro.dockhand.mobile.api.ContainerShellDetectionResult
import pro.dockhand.mobile.api.ContainerShellInfo
import pro.dockhand.mobile.api.ContainerUpdateCheckJobSnapshot
import pro.dockhand.mobile.api.ContainerUpdateCheckOperation
import pro.dockhand.mobile.api.DashboardEnvironmentSnapshot
import pro.dockhand.mobile.api.DashboardHostSnapshot
import pro.dockhand.mobile.api.DockhandApi
import pro.dockhand.mobile.api.Environment
import pro.dockhand.mobile.api.ImagePullJobSnapshot
import pro.dockhand.mobile.api.ImagePullStartResult
import pro.dockhand.mobile.api.ImageScanDocument
import pro.dockhand.mobile.api.ImageSummary
import pro.dockhand.mobile.api.NetworkSnapshot
import pro.dockhand.mobile.api.PendingContainerUpdate
import pro.dockhand.mobile.api.ShellProtocol
import pro.dockhand.mobile.api.StackAction
import pro.dockhand.mobile.api.StackDeployOptions
import pro.dockhand.mobile.api.StackEditorDocument
import pro.dockhand.mobile.api.StackRedeployJobSnapshot
import pro.dockhand.mobile.api.StackRedeployStartResult
import pro.dockhand.mobile.api.StackSummary
import pro.dockhand.mobile.api.UpdateRawEnvRequest
import pro.dockhand.mobile.api.UpdateStackComposeRequest
import pro.dockhand.mobile.api.VolumeSnapshot
import pro.dockhand.mobile.ui.screens.ContainerShellStatus
import pro.dockhand.mobile.ui.screens.ContainerShellStore
import pro.dockhand.mobile.ui.screens.KeyValueStore
import pro.dockhand.mobile.ui.screens.ShellWebSocket
import pro.dockhand.mobile.ui.screens.ShellWebSocketListener

@OptIn(ExperimentalCoroutinesApi::class)
class ContainerShellStoreTest {

    @Test
    fun connectSendsInitialResize() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)

        store.connect()

        assertTrue(store.isConnected)
        assertEquals(ContainerShellStatus.CONNECTED, store.status)
        assertEquals(listOf(ShellProtocol.encodeResize(80, 24)), socket.sent)
        assertNull(store.error)
    }

    @Test
    fun requestResizeIsDebouncedAndSentWhenConnected() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()
        socket.sent.clear()

        store.requestResize(120, 40)
        store.requestResize(100, 30)
        advanceTimeBy(301)
        runCurrent()

        assertEquals(listOf(ShellProtocol.encodeResize(100, 30)), socket.sent)
    }

    @Test
    fun requestResizeWhileDisconnectedIsUsedByNextConnect() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()
        store.disconnect()
        socket.sent.clear()

        store.requestResize(140, 44)
        advanceTimeBy(301)
        runCurrent()
        assertTrue(socket.sent.isEmpty())

        store.connect()

        assertEquals(listOf(ShellProtocol.encodeResize(140, 44)), socket.sent)
    }

    @Test
    fun sendCommandEncodesInputWithNewline() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()
        socket.sent.clear()

        store.sendCommand("ls -la")

        assertEquals(listOf(ShellProtocol.encodeInput("ls -la\n")), socket.sent)
    }

    @Test
    fun sendInputEncodesControlSequences() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()
        socket.sent.clear()

        store.sendInput("\u0003")
        store.sendInput("\u001B[A")

        assertEquals(
            listOf(
                ShellProtocol.encodeInput("\u0003"),
                ShellProtocol.encodeInput("\u001B[A")
            ),
            socket.sent
        )
    }

    @Test
    fun inputIsIgnoredWhenNotConnected() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)

        store.sendInput("ls")
        store.sendCommand("ls")

        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun outputFramesAppendToScrollback() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()

        socket.emit("""{"type":"output","data":"hello\n"}""")

        assertEquals("hello\n", store.feedEvents.last().text)
        assertEquals(ContainerShellStatus.CONNECTED, store.status)
    }

    @Test
    fun rawPayloadIsAppendedWhenNotStructured() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()

        socket.emit("plain output")

        assertEquals("plain output", store.feedEvents.last().text)
    }

    @Test
    fun errorFrameSetsErrorStatusAndAppendsLine() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()

        socket.emit("""{"type":"error","message":"boom"}""")

        assertEquals(ContainerShellStatus.ERROR, store.status)
        assertEquals("boom", store.error)
        assertTrue(store.feedEvents.last().text.contains("boom"))
    }

    @Test
    fun exitFrameEndsTheSession() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()

        socket.emit("""{"type":"exit"}""")

        assertEquals(ContainerShellStatus.ENDED, store.status)
        assertFalse(store.isConnected)
        assertTrue(socket.cancelled)
        assertTrue(store.feedEvents.last().text.contains("Session ended."))
    }

    @Test
    fun socketFailureSetsErrorStatus() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()

        socket.fail(RuntimeException("socket dropped"))

        assertEquals(ContainerShellStatus.ERROR, store.status)
        assertEquals("socket dropped", store.error)
        assertFalse(store.isConnected)
    }

    @Test
    fun socketCloseMarksDisconnected() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()

        socket.close()

        assertEquals(ContainerShellStatus.DISCONNECTED, store.status)
        assertFalse(store.isConnected)
    }

    @Test
    fun detectShellsSelectsBestShell() = runTest {
        val api = FakeDockhandApi(
            detection = ContainerShellDetectionResult(
                shells = listOf("/bin/bash"),
                defaultShell = "/bin/bash",
                allShells = listOf(ContainerShellInfo("/bin/bash", "Bash", true))
            )
        )
        val store = store(backgroundScope, FakeShellWebSocket(), api = api)

        store.detectShells()

        assertEquals("/bin/bash", store.selectedShell)
        assertEquals(ContainerShellStatus.IDLE, store.status)
        assertFalse(store.isDetectingShells)
    }

    @Test
    fun detectShellsKeepsPreferredShellWhenAvailable() = runTest {
        val api = FakeDockhandApi(
            detection = ContainerShellDetectionResult(
                shells = listOf("/bin/sh", "/bin/bash"),
                defaultShell = "/bin/bash"
            )
        )
        val store = store(backgroundScope, FakeShellWebSocket(), api = api)

        store.detectShells()

        assertEquals("/bin/sh", store.selectedShell)
    }

    @Test
    fun detectShellsFailureSetsErrorStatus() = runTest {
        val api = FakeDockhandApi(detectionFailure = RuntimeException("detection failed"))
        val store = store(backgroundScope, FakeShellWebSocket(), api = api)

        store.detectShells()

        assertEquals(ContainerShellStatus.ERROR, store.status)
        assertEquals("detection failed", store.error)
        assertFalse(store.isDetectingShells)
    }

    @Test
    fun storedPreferencesAreLoadedOnCreate() = runTest {
        val keyValueStore = FakeKeyValueStore(
            mutableMapOf(
                "dockhand.shell.profile-1.7.container-1.shell" to "/bin/zsh",
                "dockhand.shell.profile-1.7.container-1.user" to "nobody"
            )
        )
        val store = store(backgroundScope, FakeShellWebSocket(), keyValueStore = keyValueStore)

        assertEquals("/bin/zsh", store.selectedShell)
        assertEquals("nobody", store.selectedUser)
    }

    @Test
    fun connectPersistsShellAndUserSelection() = runTest {
        val keyValueStore = FakeKeyValueStore()
        val store = store(backgroundScope, FakeShellWebSocket(), keyValueStore = keyValueStore)
        store.selectShell("/bin/bash")
        store.selectUser("nobody")

        store.connect()

        assertEquals("/bin/bash", keyValueStore.get("dockhand.shell.profile-1.7.container-1.shell"))
        assertEquals("nobody", keyValueStore.get("dockhand.shell.profile-1.7.container-1.user"))
    }

    @Test
    fun commitCustomUserSelectsAndPersists() = runTest {
        val keyValueStore = FakeKeyValueStore()
        val store = store(backgroundScope, FakeShellWebSocket(), keyValueStore = keyValueStore)
        store.customUserInput = "dev-user"

        store.commitCustomUser()

        assertEquals("dev-user", store.selectedUser)
        assertTrue(store.customUsers.contains("dev-user"))
        assertEquals("dev-user", keyValueStore.get("dockhand.shell.customUsers"))
    }

    @Test
    fun clearRemovesScrollback() = runTest {
        val socket = FakeShellWebSocket()
        val store = store(backgroundScope, socket)
        store.connect()
        socket.emit("""{"type":"output","data":"hello"}""")
        assertTrue(store.feedEvents.isNotEmpty())

        store.clear()

        assertTrue(store.feedEvents.isEmpty())
    }

    private fun store(
        scope: CoroutineScope,
        socket: FakeShellWebSocket,
        api: DockhandApi = FakeDockhandApi(),
        keyValueStore: KeyValueStore = FakeKeyValueStore()
    ): ContainerShellStore = ContainerShellStore(
        api = api,
        environmentID = 7,
        containerID = "container-1",
        containerName = "web",
        profileID = "profile-1",
        webSocketFactory = { socket },
        scope = scope,
        keyValueStore = keyValueStore
    )
}

private class FakeShellWebSocket : ShellWebSocket {
    override var listener: ShellWebSocketListener? = null
    val sent = mutableListOf<String>()
    var cancelled = false

    override fun send(text: String) {
        sent.add(text)
    }

    override fun cancel() {
        cancelled = true
    }

    fun emit(text: String) {
        listener?.onText(text)
    }

    fun fail(error: Throwable) {
        listener?.onFailure(error)
    }

    fun close() {
        listener?.onClosed()
    }
}

private class FakeKeyValueStore(
    private val values: MutableMap<String, String> = mutableMapOf()
) : KeyValueStore {
    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }
}

private class FakeDockhandApi(
    private val detection: ContainerShellDetectionResult = ContainerShellDetectionResult(),
    private val detectionFailure: Throwable? = null
) : DockhandApi {

    override suspend fun fetchContainerShells(
        containerID: String,
        environmentID: Int
    ): ContainerShellDetectionResult {
        detectionFailure?.let { throw it }
        return detection
    }

    override fun makeContainerShellRequest(
        containerID: String,
        environmentID: Int,
        shell: String,
        user: String
    ): Request = Request.Builder()
        .url("http://dockhand.test/api/containers/$containerID/exec?shell=$shell&user=$user&envId=$environmentID")
        .build()

    override suspend fun fetchHealthStatus(): String = fail()

    override suspend fun fetchEnvironments(): List<Environment> = fail()

    override suspend fun fetchContainers(environmentID: Int): List<Container> = fail()

    override suspend fun fetchImages(environmentID: Int): List<ImageSummary> = fail()

    override suspend fun fetchStacks(environmentID: Int): List<StackSummary> = fail()

    override suspend fun fetchDashboardStats(environmentID: Int): DashboardEnvironmentSnapshot = fail()

    override suspend fun fetchDashboardHost(environmentID: Int): DashboardHostSnapshot = fail()

    override suspend fun fetchPendingContainerUpdates(environmentID: Int): List<PendingContainerUpdate> = fail()

    override suspend fun startContainerUpdateCheck(environmentID: Int): ContainerUpdateCheckOperation = fail()

    override suspend fun fetchContainerUpdateCheckJob(id: String): ContainerUpdateCheckJobSnapshot = fail()

    override suspend fun updateContainers(
        ids: List<String>,
        environmentID: Int
    ): ContainerBatchUpdateResponse = fail()

    override suspend fun fetchVolumes(environmentID: Int): List<VolumeSnapshot> = fail()

    override suspend fun fetchNetworks(environmentID: Int): List<NetworkSnapshot> = fail()

    override suspend fun fetchContainerActivity(
        environmentID: Int,
        limit: Int
    ): ContainerActivitySnapshot = fail()

    override suspend fun clearPendingContainerUpdate(containerID: String, environmentID: Int): Unit = fail()

    override suspend fun fetchStackEditorDocument(
        name: String,
        environmentID: Int
    ): StackEditorDocument = fail()

    override suspend fun fetchContainerLogs(
        containerID: String,
        environmentID: Int,
        tail: Int
    ): ContainerLogsDocument = fail()

    override fun streamContainerLogs(
        containerID: String,
        environmentID: Int,
        tail: Int
    ): Flow<ContainerLogEvent> = fail()

    override suspend fun startImagePull(
        imageName: String,
        environmentID: Int,
        tag: String?
    ): ImagePullStartResult = fail()

    override suspend fun fetchImagePullJob(id: String): ImagePullJobSnapshot = fail()

    override suspend fun pruneImages(environmentID: Int, danglingOnly: Boolean): Unit = fail()

    override suspend fun tagImage(
        imageID: String,
        environmentID: Int,
        repo: String,
        tag: String
    ): Unit = fail()

    override suspend fun deleteImage(imageReference: String, environmentID: Int): Unit = fail()

    override suspend fun deleteImageTag(imageTag: String, environmentID: Int): Unit = fail()

    override suspend fun scanImage(imageName: String, environmentID: Int): ImageScanDocument = fail()

    override suspend fun startContainer(containerID: String, environmentID: Int): Unit = fail()

    override suspend fun stopContainer(containerID: String, environmentID: Int): Unit = fail()

    override suspend fun restartContainer(containerID: String, environmentID: Int): Unit = fail()

    override suspend fun pauseContainer(containerID: String, environmentID: Int): Unit = fail()

    override suspend fun unpauseContainer(containerID: String, environmentID: Int): Unit = fail()

    override suspend fun stackAction(
        action: StackAction,
        stackName: String,
        environmentID: Int
    ): Unit = fail()

    override suspend fun redeployStack(
        stackName: String,
        environmentID: Int,
        options: StackDeployOptions
    ): Unit = fail()

    override suspend fun startStackRedeploy(
        stackName: String,
        environmentID: Int,
        options: StackDeployOptions
    ): StackRedeployStartResult = fail()

    override suspend fun fetchStackRedeployJob(id: String): StackRedeployJobSnapshot = fail()

    override suspend fun deleteStack(
        stackName: String,
        environmentID: Int,
        deleteVolumes: Boolean
    ): Unit = fail()

    override suspend fun updateStackCompose(
        name: String,
        environmentID: Int,
        request: UpdateStackComposeRequest
    ): Unit = fail()

    override suspend fun updateStackEnvFile(
        name: String,
        environmentID: Int,
        request: UpdateRawEnvRequest
    ): Unit = fail()

    private fun fail(): Nothing = throw NotImplementedError()
}
