package pro.dockhand.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pro.dockhand.mobile.ui.screens.headerNameError
import pro.dockhand.mobile.ui.screens.headerValueError

class SettingsValidationTest {

    @Test
    fun acceptsRfc7230TokenHeaderNames() {
        assertNull(headerNameError("X-Api-Key"))
        assertNull(headerNameError("X_Custom.Header"))
        assertNull(headerNameError("x!#$%&'*+-.^_`|~9"))
        assertNull(headerNameError("  X-Trimmed  "))
    }

    @Test
    fun rejectsEmptyHeaderNames() {
        assertEquals("Header name is required.", headerNameError(""))
        assertEquals("Header name is required.", headerNameError("   "))
    }

    @Test
    fun rejectsHeaderNamesWithInvalidCharacters() {
        assertNotNull(headerNameError("X Api Key"))
        assertNotNull(headerNameError("X-Api-Key:"))
        assertNotNull(headerNameError("X-Api-Key\nInjected"))
        assertNotNull(headerNameError("Café"))
    }

    @Test
    fun rejectsReservedHeaderNamesCaseInsensitively() {
        val reserved = listOf(
            "Authorization",
            "authorization",
            "AUTHORIZATION",
            "Accept",
            "Content-Type",
            "content-length",
            "Host",
            "Connection",
            "Upgrade"
        )
        reserved.forEach { name ->
            assertNotNull(name, headerNameError(name))
        }
    }

    @Test
    fun acceptsEmptyAndPlainHeaderValues() {
        assertNull(headerValueError(""))
        assertNull(headerValueError("Bearer abc.def"))
        assertNull(headerValueError("   spaced   "))
    }

    @Test
    fun rejectsHeaderValuesWithLineBreaks() {
        assertNotNull(headerValueError("value\r\nInjected: true"))
        assertNotNull(headerValueError("value\nInjected: true"))
        assertNotNull(headerValueError("value\rInjected: true"))
    }
}
