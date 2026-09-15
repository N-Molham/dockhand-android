package pro.dockhand.mobile.ui.screens

import org.junit.Assert.assertThrows
import org.junit.Test

class StackEditorValidatorTest {

    @Test
    fun validComposePasses() {
        val compose = """
            services:
              web:
                image: nginx:latest
                ports:
                  - "8080:80"
            """.trimIndent()

        StackEditorValidator.validateCompose(compose)
    }

    @Test
    fun emptyComposeFails() {
        assertThrows(StackEditorValidationException::class.java) {
            StackEditorValidator.validateCompose("   \n  ")
        }
    }

    @Test
    fun brokenComposeFails() {
        assertThrows(StackEditorValidationException::class.java) {
            StackEditorValidator.validateCompose("services:\n  web:\n    image: [nginx\n")
        }
    }

    @Test
    fun validEnvPasses() {
        val env = """
            # comment
            IMAGE_TAG=latest

            export PORT=8080
            DB_PASSWORD="secret"
            """.trimIndent()

        StackEditorValidator.validateEnv(env)
    }

    @Test
    fun badEnvKeyFails() {
        assertThrows(StackEditorValidationException::class.java) {
            StackEditorValidator.validateEnv("2BAD=value")
        }
    }

    @Test
    fun envLineWithoutSeparatorFails() {
        assertThrows(StackEditorValidationException::class.java) {
            StackEditorValidator.validateEnv("GOOD=1\nMISSING_SEPARATOR")
        }
    }
}
