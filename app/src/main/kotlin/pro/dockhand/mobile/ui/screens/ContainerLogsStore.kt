package pro.dockhand.mobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pro.dockhand.mobile.api.ContainerLogEvent
import pro.dockhand.mobile.api.ContainerLogsDocument
import pro.dockhand.mobile.api.DockhandServiceError
import pro.dockhand.mobile.api.dockhandUserFacingMessage
import pro.dockhand.mobile.api.isDockhandCancellation
import pro.dockhand.mobile.app.AppViewModel

class ContainerLogsStore(
    private val scope: CoroutineScope,
    private val liveFormattingDelayMillis: Long = 100L
) {
    var document by mutableStateOf(ContainerLogsDocument(logs = ""))
        private set
    var formattedLogs by mutableStateOf("")
        private set
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var tail by mutableIntStateOf(200)
    var follow by mutableStateOf(false)
    var streamStatus by mutableStateOf("Idle")

    private var runGeneration = 0
    private var streamHasReceivedLog = false
    private var liveFormattingJob: Job? = null

    suspend fun run(containerID: String, viewModel: AppViewModel) {
        runGeneration += 1
        val generation = runGeneration
        val selectedTail = tail
        val shouldFollow = follow

        if (shouldFollow) {
            loadSnapshot(containerID, viewModel, selectedTail, generation)
            if (!isCurrent(generation)) return
            stream(containerID, viewModel, selectedTail, generation)
        } else {
            loadSnapshot(containerID, viewModel, selectedTail, generation)
        }
    }

    fun cancelCurrentRun() {
        runGeneration += 1
        cancelPendingLiveFormatting()
        isLoading = false
        streamStatus = "Stopped"
    }

    fun pauseForBackground() {
        runGeneration += 1
        cancelPendingLiveFormatting()
        isLoading = false
        error = null
        streamStatus = "Paused"
    }

    fun appendLiveLog(log: String) {
        if (log.isEmpty()) return

        val current = document.logs
        val separator = if (current.isNotEmpty() && !current.endsWith("\n") && !log.startsWith("\n")) "\n" else ""
        var updated = current + separator + log
        if (updated.length > MAX_LOG_LENGTH) {
            updated = updated.takeLast(MAX_LOG_LENGTH)
        }
        document = ContainerLogsDocument(logs = updated)

        if (liveFormattingJob != null) return
        liveFormattingJob = scope.launch {
            delay(liveFormattingDelayMillis)
            formattedLogs = ContainerLogFormatter.orderedLatestFirst(document.logs)
            liveFormattingJob = null
        }
    }

    private suspend fun loadSnapshot(
        containerID: String,
        viewModel: AppViewModel,
        tail: Int,
        generation: Int
    ) {
        val service = viewModel.service()
        val environmentID = viewModel.selectedEnvironment?.id
        if (service == null || environmentID == null) {
            if (isCurrent(generation)) {
                replaceLogs("")
                isLoading = false
            }
            return
        }

        isLoading = true
        error = null
        streamStatus = "Loading"
        try {
            val loadedDocument = service.fetchContainerLogs(containerID, environmentID, tail)
            if (!isCurrent(generation)) return
            replaceLogs(loadedDocument.logs)
            streamStatus = "Snapshot"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (loadError: Throwable) {
            if (!isCurrent(generation)) return
            if (loadError.isDockhandCancellation) {
                streamStatus = "Stopped"
                return
            }
            error = loadError.dockhandUserFacingMessage
            streamStatus = "Error"
        } finally {
            if (isCurrent(generation)) {
                isLoading = false
            }
        }
    }

    private suspend fun stream(
        containerID: String,
        viewModel: AppViewModel,
        tail: Int,
        generation: Int
    ) {
        val service = viewModel.service()
        val environmentID = viewModel.selectedEnvironment?.id
        if (service == null || environmentID == null) return

        isLoading = true
        error = null
        streamHasReceivedLog = false
        streamStatus = "Connecting"
        try {
            service.streamContainerLogs(containerID, environmentID, tail).collect { event ->
                if (!isCurrent(generation)) return@collect
                when (event) {
                    ContainerLogEvent.Connected -> {
                        isLoading = false
                        streamStatus = "Live"
                    }
                    is ContainerLogEvent.Log -> {
                        if (!streamHasReceivedLog) {
                            replaceLogs("")
                            streamHasReceivedLog = true
                        }
                        appendLiveLog(event.text)
                        isLoading = false
                        streamStatus = "Live"
                    }
                    is ContainerLogEvent.ServerError -> {
                        flushLiveFormatting()
                        error = DockhandServiceError.LogsUnavailable(event.message).dockhandUserFacingMessage
                        isLoading = false
                        streamStatus = "Error"
                    }
                    ContainerLogEvent.Ended -> {
                        flushLiveFormatting()
                        isLoading = false
                        streamStatus = "Stopped"
                    }
                }
            }
            if (!isCurrent(generation) || error != null) return
            flushLiveFormatting()
            streamStatus = "Stopped"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (streamError: Throwable) {
            if (!isCurrent(generation)) return
            if (streamError.isDockhandCancellation) {
                streamStatus = "Stopped"
                return
            }
            flushLiveFormatting()
            error = streamError.dockhandUserFacingMessage
            streamStatus = "Error"
        } finally {
            if (isCurrent(generation)) {
                isLoading = false
            }
        }
    }

    private fun isCurrent(generation: Int): Boolean = generation == runGeneration

    private fun replaceLogs(logs: String) {
        cancelPendingLiveFormatting()
        document = ContainerLogsDocument(logs = logs)
        formattedLogs = ContainerLogFormatter.orderedLatestFirst(logs)
    }

    private fun flushLiveFormatting() {
        if (liveFormattingJob == null) return
        liveFormattingJob?.cancel()
        liveFormattingJob = null
        formattedLogs = ContainerLogFormatter.orderedLatestFirst(document.logs)
    }

    private fun cancelPendingLiveFormatting() {
        liveFormattingJob?.cancel()
        liveFormattingJob = null
    }

    private companion object {
        const val MAX_LOG_LENGTH = 200_000
    }
}

object ContainerLogFormatter {
    private data class Entry(
        val timestamp: Instant?,
        val originalIndex: Int,
        val lines: MutableList<String>
    )

    private val lineSplitRegex = Regex("\r\n|\r|\n")
    private val leadingTimestampRegex = Regex(
        """^\[?(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:Z| ?[+-]\d{2}:?\d{2})?)\]?"""
    )
    private val spacedOffsetRegex = Regex(""" (?=[+-]\d{2}:?\d{2}$)""")
    private val compactOffsetRegex = Regex("""[+-]\d{4}$""")
    private val offsetRegex = Regex("""(Z|[+-]\d{2}:\d{2})$""")

    private val timestampDescending = Comparator<Entry> { lhs, rhs ->
        val lhsTimestamp = lhs.timestamp
        val rhsTimestamp = rhs.timestamp
        when {
            lhsTimestamp != null && rhsTimestamp != null -> {
                val compared = rhsTimestamp.compareTo(lhsTimestamp)
                if (compared != 0) compared else lhs.originalIndex.compareTo(rhs.originalIndex)
            }
            lhsTimestamp != null -> -1
            rhsTimestamp != null -> 1
            else -> lhs.originalIndex.compareTo(rhs.originalIndex)
        }
    }

    fun orderedLatestFirst(rawLogs: String): String {
        val lines = rawLogs.split(lineSplitRegex).dropLastWhile { it.isEmpty() }

        val entries = mutableListOf<Entry>()
        for (line in lines) {
            val timestamp = timestampIn(line)
            if (timestamp != null) {
                entries.add(Entry(timestamp, entries.size, mutableListOf(line)))
            } else if (entries.isEmpty()) {
                entries.add(Entry(null, entries.size, mutableListOf(line)))
            } else {
                entries[entries.size - 1].lines.add(line)
            }
        }

        if (entries.none { it.timestamp != null }) {
            return lines.asReversed().joinToString("\n")
        }

        return entries
            .sortedWith(timestampDescending)
            .flatMap { it.lines }
            .joinToString("\n")
    }

    private fun timestampIn(line: String): Instant? {
        val match = leadingTimestampRegex.find(line) ?: return null
        if (match.groupValues.size < 2) return null

        var timestamp = match.groupValues[1].replace(',', '.')
        if (timestamp.length > 10 && timestamp[10] == ' ') {
            timestamp = timestamp.substring(0, 10) + "T" + timestamp.substring(11)
        }
        timestamp = spacedOffsetRegex.replace(timestamp, "")
        if (compactOffsetRegex.containsMatchIn(timestamp)) {
            timestamp = timestamp.dropLast(2) + ":" + timestamp.takeLast(2)
        }
        val normalized = if (offsetRegex.containsMatchIn(timestamp)) timestamp else timestamp + "Z"

        return try {
            OffsetDateTime.parse(normalized).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}
