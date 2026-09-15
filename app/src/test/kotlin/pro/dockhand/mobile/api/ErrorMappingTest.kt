package pro.dockhand.mobile.api

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMappingTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun healthFailureIncludesStageDetail() {
        val error = DockhandConnectionStageException(
            DockhandConnectionStage.HEALTH,
            ConnectException("Connection refused")
        )
        val message = error.dockhandUserFacingMessage
        assertTrue(message.contains("Dockhand"))
        assertTrue(message.contains("Could not reach") || message.contains("Could not connect"))
        assertFalse(message.contains("Connection refused"))
    }

    @Test
    fun environmentFailureKeepsStatusMapping() {
        val error = DockhandConnectionStageException(
            DockhandConnectionStage.ENVIRONMENTS,
            DockhandServiceError.UnexpectedStatus(403)
        )
        val message = error.dockhandUserFacingMessage
        assertTrue(message.contains("token"))
        assertTrue(message.contains("Dockhand"))
    }

    @Test
    fun selectedEnvironmentTransportFailureMentionsEnvironment() {
        val error = DockhandConnectionStageException(
            DockhandConnectionStage.SELECTED_ENVIRONMENT,
            SocketTimeoutException("timeout")
        )
        val message = error.dockhandUserFacingMessage
        assertTrue(message.contains("selected Docker environment"))
        assertFalse(message.contains("SocketTimeoutException"))
    }

    @Test
    fun selectedEnvironmentServerErrorKeepsStatusMapping() {
        val error = DockhandConnectionStageException(
            DockhandConnectionStage.SELECTED_ENVIRONMENT,
            DockhandServiceError.UnexpectedStatus(500)
        )
        val message = error.dockhandUserFacingMessage
        assertTrue(message.contains("selected environment"))
        assertTrue(message.contains("server error"))
    }

    @Test
    fun authenticationStatusHidesCode() {
        val message = DockhandServiceError.UnexpectedStatus(401).dockhandUserFacingMessage
        assertTrue(message.contains("token"))
        assertFalse(message.contains("401"))
    }

    @Test
    fun transportErrorsHideTechnicalDetails() {
        val message = UnknownHostException("example.invalid").dockhandUserFacingMessage
        assertTrue(message.contains("Dockhand"))
        assertFalse(message.contains("UnknownHostException"))
    }

    @Test
    fun sslFailureMentionsSecureConnection() {
        val message = SSLHandshakeException("bad certificate").dockhandUserFacingMessage
        assertTrue(message.contains("secure connection"))
    }

    @Test
    fun genericIoFailureFallsBackToConnectionMessage() {
        val message = IOException("unexpected EOF").dockhandUserFacingMessage
        assertTrue(message.contains("Could not connect"))
        assertFalse(message.contains("unexpected EOF"))
    }

    @Test
    fun technicalTransportTextIsHidden() {
        val message = Exception("java.net.ProtocolException: unexpected status").dockhandUserFacingMessage
        assertTrue(message.contains("Could not connect"))
        assertFalse(message.contains("ProtocolException"))
    }

    @Test
    fun cancellationDetected() {
        assertTrue(CancellationException().isDockhandCancellation)
        assertTrue(IOException("Canceled").isDockhandCancellation)
        assertFalse(IllegalStateException("boom").isDockhandCancellation)
    }

    @Test
    fun logsUnavailableKeepsDriverDetail() {
        val error = DockhandServiceError.LogsUnavailable("configured logging driver does not support reading")
        val message = error.dockhandUserFacingMessage
        assertTrue(message.contains("logging driver"))
        assertTrue(message.contains("does not support reading"))
    }

    @Test
    fun spanishLocaleProducesSpanishMessages() {
        Locale.setDefault(Locale.forLanguageTag("es"))
        val message = DockhandServiceError.UnexpectedStatus(401).dockhandUserFacingMessage
        assertTrue(message.contains("token"))
        assertTrue(message.contains("caducado") || message.contains("rechazó"))
    }

    @Test
    fun emptyMessageFallsBackToGeneric() {
        val message = DockhandServiceError.Message("   ").dockhandUserFacingMessage
        assertTrue(message.contains("could not complete"))
    }
}
