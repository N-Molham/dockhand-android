package pro.dockhand.mobile.api

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

val Throwable.isDockhandCancellation: Boolean
    get() = DockhandUserFacingErrorFormatter.isCancellation(this)

val Throwable.dockhandUserFacingMessage: String
    get() = DockhandUserFacingErrorFormatter.message(this)

object DockhandUserFacingErrorFormatter {

    fun isCancellation(error: Throwable): Boolean {
        if (error is CancellationException) return true
        val text = errorText(error)
        return text.contains("canceled", ignoreCase = true) || text.contains("cancelled", ignoreCase = true)
    }

    fun message(error: Throwable): String {
        if (error is DockhandConnectionStageException) {
            val detail = message(error.underlying)
            return when (error.stage) {
                DockhandConnectionStage.HEALTH -> localized(
                    "Could not validate Dockhand at this address. $detail",
                    "No se pudo validar Dockhand en esta dirección. $detail"
                )
                DockhandConnectionStage.ENVIRONMENTS -> localized(
                    "Dockhand is online, but its environments could not be loaded. $detail",
                    "Dockhand está en línea, pero no se pudieron cargar sus entornos. $detail"
                )
                DockhandConnectionStage.SELECTED_ENVIRONMENT -> {
                    if (isTransportFailure(error.underlying)) {
                        localized(
                            "Dockhand is online, but the selected Docker environment could not be reached. Check the environment or Hawser connection.",
                            "Dockhand está en línea, pero no se pudo acceder al entorno Docker seleccionado. Revisa la conexión del entorno o de Hawser."
                        )
                    } else {
                        localized(
                            "Dockhand is online, but data could not be loaded from the selected environment. $detail",
                            "Dockhand está en línea, pero no se pudieron cargar los datos del entorno seleccionado. $detail"
                        )
                    }
                }
            }
        }

        if (error is DockhandServiceError) {
            return message(error)
        }

        transportMessage(error)?.let { return it }

        if (error is IOException) {
            return localized(
                "Could not connect to Dockhand. Check that the server is online and that your network or VPN is connected.",
                "No se pudo conectar con Dockhand. Comprueba que el servidor esté encendido y que estés en la red o VPN correcta."
            )
        }

        val rawMessage = error.message?.trim().orEmpty()
        if (rawMessage.isEmpty()) {
            return localized(
                "Something went wrong. Try again in a moment.",
                "Algo salió mal. Inténtalo de nuevo en un momento."
            )
        }

        if (looksLikeTechnicalTransportError(rawMessage)) {
            return localized(
                "Could not connect to Dockhand. Check that the server is online and that your network or VPN is connected.",
                "No se pudo conectar con Dockhand. Comprueba que el servidor esté encendido y que estés en la red o VPN correcta."
            )
        }

        return rawMessage
    }

    private fun message(error: DockhandServiceError): String = when (error) {
        is DockhandServiceError.InvalidResponse -> localized(
            "Dockhand sent a response the app could not read. Try again or update the server.",
            "Dockhand envió una respuesta que la app no pudo leer. Inténtalo de nuevo o actualiza el servidor."
        )
        is DockhandServiceError.LogsUnavailable -> {
            val suffix = error.reason?.let { " ($it)" } ?: ""
            localized(
                "Dockhand cannot read logs for this container. Its logging driver may not support reading.$suffix",
                "Dockhand no puede leer los logs de este contenedor. Es posible que su driver de logs no permita la lectura.$suffix"
            )
        }
        is DockhandServiceError.Message -> {
            val trimmed = error.text.trim()
            if (trimmed.isEmpty()) {
                localized(
                    "Dockhand could not complete the request.",
                    "Dockhand no pudo completar la solicitud."
                )
            } else {
                trimmed
            }
        }
        is DockhandServiceError.UnexpectedStatus -> when (error.code) {
            401, 403 -> localized(
                "Dockhand rejected the token. It may be expired, revoked, or missing the required permissions.",
                "Dockhand rechazó el token. Puede haber caducado, estar revocado o no tener los permisos necesarios."
            )
            404 -> localized(
                "Dockhand could not find the requested resource. Refresh and try again.",
                "Dockhand no encontró el recurso solicitado. Actualiza e inténtalo de nuevo."
            )
            408, 504 -> localized(
                "Dockhand took too long to respond. Check the server connection and try again.",
                "Dockhand tardó demasiado en responder. Revisa la conexión del servidor e inténtalo de nuevo."
            )
            in 500..599 -> localized(
                "Dockhand reported a server error. Try again in a moment.",
                "Dockhand informó de un error del servidor. Inténtalo de nuevo en un momento."
            )
            else -> localized(
                "Dockhand could not complete the request. Try again in a moment.",
                "Dockhand no pudo completar la solicitud. Inténtalo de nuevo en un momento."
            )
        }
    }

    private fun transportMessage(error: Throwable): String? = when (error) {
        is SocketTimeoutException -> localized(
            "Could not connect to Dockhand. Check that the server is online and that your network or VPN is connected.",
            "No se pudo conectar con Dockhand. Comprueba que el servidor esté encendido y que estés en la red o VPN correcta."
        )
        is UnknownHostException -> localized(
            "Could not find the Dockhand server. Check the server address in Settings.",
            "No se encontró el servidor Dockhand. Revisa la dirección del servidor en Ajustes."
        )
        is ConnectException -> localized(
            "Could not reach the Dockhand server. Check that it is online and reachable from this network.",
            "No se pudo acceder al servidor Dockhand. Comprueba que esté encendido y accesible desde esta red."
        )
        is SSLException -> localized(
            "The secure connection to Dockhand failed. Check the server certificate or URL.",
            "Falló la conexión segura con Dockhand. Revisa el certificado o la URL del servidor."
        )
        is CancellationException -> localized(
            "The request was cancelled.",
            "La solicitud se canceló."
        )
        else -> null
    }

    private fun isTransportFailure(error: Throwable): Boolean {
        if (transportMessage(error) != null && error !is CancellationException) return true
        return looksLikeTechnicalTransportError(errorText(error))
    }

    private fun looksLikeTechnicalTransportError(message: String): Boolean {
        val patterns = listOf(
            "okhttp3",
            "java.net",
            "javax.net.ssl",
            "NSURLErrorDomain",
            "Transport threw an error",
            "Client encountered an error",
            "OpenAPIRuntime",
            "URLSessionTask"
        )
        return patterns.any { message.contains(it, ignoreCase = true) }
    }

    private fun errorText(error: Throwable): String = buildString {
        append(error::class.java.name)
        append('\n')
        append(error.message.orEmpty())
        append('\n')
        error.cause?.let { append(it::class.java.name).append(' ').append(it.message.orEmpty()) }
    }

    private fun localized(english: String, spanish: String): String {
        val language = Locale.getDefault().language
        return if (language == "es") spanish else english
    }
}
