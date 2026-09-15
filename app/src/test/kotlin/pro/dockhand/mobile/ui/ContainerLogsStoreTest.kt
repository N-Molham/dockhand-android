package pro.dockhand.mobile.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.dockhand.mobile.ui.screens.ContainerLogFormatter
import pro.dockhand.mobile.ui.screens.ContainerLogsStore

@OptIn(ExperimentalCoroutinesApi::class)
class ContainerLogsStoreTest {

    @Test
    fun orderedLatestFirstGroupsContinuationLinesAndSortsDescending() {
        val logs = listOf(
            "2026-07-17T16:06:24.806084337Z timeout",
            "continuation for timeout",
            "2026-07-17T15:56:51.539909159Z cancelled",
            "2026-07-17T13:52:54.527709448Z older",
            "2026-07-17T17:43:17.961867978Z newest",
            "2026-07-17T17:43:17.750902313Z second newest"
        ).joinToString("\n")

        val expected = listOf(
            "2026-07-17T17:43:17.961867978Z newest",
            "2026-07-17T17:43:17.750902313Z second newest",
            "2026-07-17T16:06:24.806084337Z timeout",
            "continuation for timeout",
            "2026-07-17T15:56:51.539909159Z cancelled",
            "2026-07-17T13:52:54.527709448Z older"
        ).joinToString("\n")

        assertEquals(expected, ContainerLogFormatter.orderedLatestFirst(logs))
    }

    @Test
    fun orderedLatestFirstReversesWhenNoTimestampsArePresent() {
        assertEquals("second\nfirst", ContainerLogFormatter.orderedLatestFirst("first\nsecond"))
    }

    @Test
    fun liveLogsBatchFormattingAfterDelay() = runTest {
        val store = ContainerLogsStore(backgroundScope)

        store.appendLiveLog("2026-08-14T10:00:00Z older")
        store.appendLiveLog("2026-08-14T10:00:01Z latest")

        assertTrue(store.document.logs.contains("latest"))
        assertEquals("", store.formattedLogs)

        advanceTimeBy(101)
        runCurrent()

        assertEquals(
            "2026-08-14T10:00:01Z latest\n2026-08-14T10:00:00Z older",
            store.formattedLogs
        )
    }

    @Test
    fun pauseForBackgroundClearsStateAndPauses() {
        val store = ContainerLogsStore(CoroutineScope(Job()))
        store.isLoading = true
        store.error = "A stale stream error"
        store.streamStatus = "Connecting"

        store.pauseForBackground()

        assertFalse(store.isLoading)
        assertNull(store.error)
        assertEquals("Paused", store.streamStatus)
    }
}
