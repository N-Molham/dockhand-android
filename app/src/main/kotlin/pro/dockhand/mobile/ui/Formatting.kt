package pro.dockhand.mobile.ui

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import pro.dockhand.mobile.api.Container
import pro.dockhand.mobile.api.Environment
import pro.dockhand.mobile.api.ImageSummary
import pro.dockhand.mobile.api.StackAction
import pro.dockhand.mobile.api.StackContainerDetail
import pro.dockhand.mobile.api.StackSummary

enum class ContainerAction {
    START,
    STOP,
    RESTART,
    PAUSE,
    UNPAUSE;

    val title: String
        get() = when (this) {
            START -> "Start"
            STOP -> "Stop"
            RESTART -> "Restart"
            PAUSE -> "Pause"
            UNPAUSE -> "Unpause"
        }
}

private enum class DockhandRuntimeState {
    RUNNING,
    EXITED,
    STOPPED,
    PAUSED,
    CREATED,
    RESTARTING,
    DEAD,
    REMOVING;

    companion object {
        fun from(rawState: String): DockhandRuntimeState =
            entries.firstOrNull { it.name.equals(rawState.lowercase(), ignoreCase = true) } ?: EXITED
    }
}

data class PublishedPortAccess(
    val label: String,
    val destinationUrl: String?
)

val String.normalizedDockhandState: String
    get() = trim().lowercase()

val String.localizedDockhandStateLabel: String
    get() = when (normalizedDockhandState) {
        "running" -> "Running"
        "restarting" -> "Restarting"
        "paused" -> "Paused"
        "created" -> "Created"
        "exited" -> "Exited"
        "stopped" -> "Stopped"
        "dead" -> "Dead"
        "removing" -> "Removing"
        else -> this
    }

val String.localizedConnectionTypeLabel: String
    get() = when (normalizedDockhandState) {
        "socket" -> "Socket"
        "http" -> "HTTP"
        "https" -> "HTTPS"
        "tcp" -> "TCP"
        else -> replace("-", " ")
    }

val String.localizedDockerRuntimeText: String
    get() {
        var text = this

        val patterns = listOf(
            Regex("""(\d+)\s+days?""", RegexOption.IGNORE_CASE) to ("day" to "days"),
            Regex("""(\d+)\s+hours?""", RegexOption.IGNORE_CASE) to ("hour" to "hours"),
            Regex("""(\d+)\s+minutes?""", RegexOption.IGNORE_CASE) to ("minute" to "minutes"),
            Regex("""(\d+)\s+seconds?""", RegexOption.IGNORE_CASE) to ("second" to "seconds"),
            Regex("""(\d+)\s+weeks?""", RegexOption.IGNORE_CASE) to ("week" to "weeks"),
            Regex("""(\d+)\s+months?""", RegexOption.IGNORE_CASE) to ("month" to "months")
        )
        patterns.forEach { (regex, labels) ->
            text = regex.replace(text) { match ->
                val count = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                "$count ${if (count == 1) labels.first else labels.second}"
            }
        }
        return text
    }

val String.dockhandStateRank: Int
    get() = when (normalizedDockhandState) {
        "running" -> 0
        "restarting" -> 1
        "paused" -> 2
        "created" -> 3
        "exited", "stopped" -> 4
        "dead" -> 5
        "removing" -> 6
        else -> 7
    }

sealed interface DockhandStateFilter {
    data object All : DockhandStateFilter
    data class State(val value: String) : DockhandStateFilter

    val title: String
        get() = when (this) {
            All -> "All states"
            is State -> value.localizedDockhandStateLabel
        }

    fun matches(state: String): Boolean = when (this) {
        All -> true
        is State -> state.normalizedDockhandState == value.normalizedDockhandState
    }
}

sealed interface ContainerListFilter {
    data object All : ContainerListFilter
    data class State(val value: String) : ContainerListFilter
    data object Stopped : ContainerListFilter
    data object Unhealthy : ContainerListFilter

    val title: String
        get() = when (this) {
            All -> "All states"
            is State -> value.localizedDockhandStateLabel
            Stopped -> "Stopped"
            Unhealthy -> "Unhealthy"
        }

    fun matches(container: Container): Boolean = when (this) {
        All -> true
        is State -> container.state.normalizedDockhandState == value.normalizedDockhandState
        Stopped -> container.state.normalizedDockhandState in setOf("exited", "stopped")
        Unhealthy -> (container.health ?: "").normalizedDockhandState == "unhealthy"
    }
}

val Long.dockhandByteCount: String
    get() {
        if (this < 1024) return "$this B"
        val units = listOf("KB", "MB", "GB", "TB", "PB")
        var value = this.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return if (value >= 100) {
            String.format(Locale.US, "%.0f %s", value, units[unitIndex])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

val Int.dockhandByteCount: String get() = toLong().dockhandByteCount

val Int.localizedServicesCountText: String get() = if (this == 1) "$this service" else "$this services"
val Int.localizedContainersCountText: String get() = if (this == 1) "$this container" else "$this containers"
val Int.localizedCoresCountText: String get() = if (this == 1) "$this core" else "$this cores"

private val Environment.trimmedPublicIp: String?
    get() = publicIp?.trim()?.takeIf { it.isNotEmpty() }

fun Environment.publishedPortUrl(port: Int): String? {
    val ip = trimmedPublicIp ?: return null
    val scheme = if (port in setOf(443, 8443, 9443)) "https" else "http"
    val host = ip.trim('[', ']')
    val hostPart = if (host.contains(":")) "[$host]" else host
    return "$scheme://$hostPart:$port"
}

val Environment.hostSummary: String
    get() = when {
        connectionType == "socket" -> socketPath
        !host.isNullOrEmpty() -> "$host:$port"
        else -> socketPath
    }

val Environment.metadataChips: List<String>
    get() = buildList {
        add(hostSummary)
        add(connectionType.replace("-", " "))
        publicIp?.takeIf { it.isNotEmpty() }?.let(::add)
        timezone?.takeIf { it.isNotEmpty() }?.let(::add)
    }

fun Container.canPerform(action: ContainerAction): Boolean {
    val runtimeState = DockhandRuntimeState.from(state)
    return when (action) {
        ContainerAction.START -> runtimeState != DockhandRuntimeState.RUNNING && runtimeState != DockhandRuntimeState.RESTARTING
        ContainerAction.STOP -> runtimeState in setOf(
            DockhandRuntimeState.RUNNING,
            DockhandRuntimeState.PAUSED,
            DockhandRuntimeState.RESTARTING
        )
        ContainerAction.RESTART -> runtimeState in setOf(
            DockhandRuntimeState.RUNNING,
            DockhandRuntimeState.PAUSED,
            DockhandRuntimeState.RESTARTING
        )
        ContainerAction.PAUSE -> runtimeState == DockhandRuntimeState.RUNNING
        ContainerAction.UNPAUSE -> runtimeState == DockhandRuntimeState.PAUSED
    }
}

val Container.canOpenShell: Boolean get() = state.normalizedDockhandState == "running"

val Container.primaryPortLabel: String
    get() = publishedPortAccesses(null).firstOrNull()?.label ?: "No ports"

fun Container.publishedPortAccesses(environment: Environment?): List<PublishedPortAccess> =
    ports.mapNotNull { port ->
        val publicPort = port.publicPort ?: return@mapNotNull null
        PublishedPortAccess(
            label = "$publicPort:${port.privatePort}",
            destinationUrl = environment?.publishedPortUrl(publicPort)
        )
    }

val Container.networkSummary: String
    get() = networks.entries
        .map { (name, info) -> info.ipAddress?.takeIf { it.isNotEmpty() }?.let { "$name $it" } ?: name }
        .sorted()
        .joinToString(" · ")

val Container.stateRank: Int get() = state.dockhandStateRank

val StackSummary.servicesCount: Int get() = containerDetails.size
val StackSummary.localizedStatusText: String get() = status.localizedDockhandStateLabel
val StackSummary.statusRank: Int get() = status.dockhandStateRank
val StackSummary.supportsRedeploy: Boolean get() = sourceType?.lowercase() != "git"

fun StackSummary.canPerform(action: StackAction): Boolean {
    val hasActiveContainers = containerDetails.any {
        DockhandRuntimeState.from(it.state) in setOf(
            DockhandRuntimeState.RUNNING,
            DockhandRuntimeState.PAUSED,
            DockhandRuntimeState.RESTARTING
        )
    }
    val normalizedStatus = status.trim().lowercase()
    return when (action) {
        StackAction.START -> !hasActiveContainers && normalizedStatus != "running"
        StackAction.STOP -> hasActiveContainers
        StackAction.RESTART -> hasActiveContainers
        StackAction.DOWN -> normalizedStatus in setOf("running", "stopped", "exited")
        StackAction.REDEPLOY -> supportsRedeploy
    }
}

fun StackContainerDetail.canPerform(action: ContainerAction): Boolean {
    val runtimeState = DockhandRuntimeState.from(state)
    return when (action) {
        ContainerAction.START -> runtimeState != DockhandRuntimeState.RUNNING && runtimeState != DockhandRuntimeState.RESTARTING
        ContainerAction.STOP -> runtimeState in setOf(
            DockhandRuntimeState.RUNNING,
            DockhandRuntimeState.PAUSED,
            DockhandRuntimeState.RESTARTING
        )
        ContainerAction.RESTART -> runtimeState in setOf(
            DockhandRuntimeState.RUNNING,
            DockhandRuntimeState.PAUSED,
            DockhandRuntimeState.RESTARTING
        )
        ContainerAction.PAUSE -> runtimeState == DockhandRuntimeState.RUNNING
        ContainerAction.UNPAUSE -> runtimeState == DockhandRuntimeState.PAUSED
    }
}

val StackContainerDetail.canOpenShell: Boolean get() = state.normalizedDockhandState == "running"

val StackContainerDetail.primaryPortLabel: String
    get() = publishedPortAccesses(null).firstOrNull()?.label ?: "No ports"

fun StackContainerDetail.publishedPortAccesses(environment: Environment?): List<PublishedPortAccess> =
    ports.mapNotNull { port ->
        val publicPort = port.publicPort ?: return@mapNotNull null
        val label = port.display?.trim()?.takeIf { it.isNotEmpty() }
            ?: port.privatePort?.let { "$publicPort:$it" }
            ?: publicPort.toString()
        PublishedPortAccess(
            label = label,
            destinationUrl = environment?.publishedPortUrl(publicPort)
        )
    }

val StackContainerDetail.networkSummary: String
    get() = networks.mapNotNull { network ->
        val name = network.name?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        network.ipAddress?.takeIf { it.isNotEmpty() }?.let { "$name $it" } ?: name
    }
        .sorted()
        .joinToString(" · ")

val StackContainerDetail.localizedStatusText: String get() = status.localizedDockerRuntimeText

val ImageSummary.displayName: String get() = repoTags.firstOrNull() ?: tags.firstOrNull() ?: id
val ImageSummary.shortId: String get() = id.replace("sha256:", "").take(12)
val ImageSummary.createdAtText: String
    get() {
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return format.format(Date(created * 1000))
    }
val ImageSummary.isUnused: Boolean get() = containers == 0
val ImageSummary.labelPairs: List<Pair<String, String>>
    get() = labels.entries.map { it.key to it.value }.sortedBy { it.first.lowercase() }
val ImageSummary.allTags: List<String> get() = (repoTags.ifEmpty { tags }).sorted()
val ImageSummary.allDigests: List<String> get() = repoDigests.sorted()
val ImageSummary.repositoryKey: String
    get() {
        allTags.firstOrNull()?.let { return it.dockhandRepositoryName }
        allDigests.firstOrNull()?.let { digest ->
            val atIndex = digest.indexOf('@')
            if (atIndex >= 0) return digest.substring(0, atIndex)
        }
        return id
    }

private val String.dockhandRepositoryName: String
    get() {
        val slashIndex = lastIndexOf('/')
        val colonIndex = lastIndexOf(':')
        return if (colonIndex >= 0 && (slashIndex < 0 || colonIndex > slashIndex)) {
            substring(0, colonIndex)
        } else {
            this
        }
    }
