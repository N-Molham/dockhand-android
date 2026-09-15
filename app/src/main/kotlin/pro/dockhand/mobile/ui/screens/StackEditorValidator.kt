package pro.dockhand.mobile.ui.screens

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class StackEditorValidationException(message: String) : Exception(message)

object StackEditorValidator {

    private val envKeyPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun validateCompose(text: String) {
        if (text.trim().isEmpty()) {
            throw StackEditorValidationException("Compose file cannot be empty.")
        }
        try {
            Load(LoadSettings.builder().build()).loadFromString(text)
        } catch (error: Exception) {
            throw StackEditorValidationException("Invalid YAML: ${error.message ?: "unknown error"}")
        }
    }

    fun validateEnv(text: String) {
        text.lines().forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed

            val body = if (trimmed.startsWith("export ")) {
                trimmed.removePrefix("export ")
            } else {
                trimmed
            }

            val separatorIndex = body.indexOf('=')
            if (separatorIndex < 0) {
                throw StackEditorValidationException(
                    "Invalid .env on line ${index + 1}: Missing '=' separator"
                )
            }

            val key = body.substring(0, separatorIndex).trim()
            if (!envKeyPattern.matches(key)) {
                throw StackEditorValidationException(
                    "Invalid .env on line ${index + 1}: Invalid variable name '$key'"
                )
            }
        }
    }
}
