package pro.dockhand.mobile.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

interface SecureStore {
    suspend fun readToken(profileId: String): String
    suspend fun writeToken(profileId: String, token: String)
    suspend fun readCustomHeaders(profileId: String): Map<String, String>
    suspend fun writeCustomHeaders(profileId: String, headers: Map<String, String>)
    suspend fun deleteProfileSecrets(profileId: String)
}

class InMemorySecureStore : SecureStore {
    private val tokens = mutableMapOf<String, String>()
    private val headers = mutableMapOf<String, Map<String, String>>()

    override suspend fun readToken(profileId: String): String = tokens[profileId].orEmpty()

    override suspend fun writeToken(profileId: String, token: String) {
        if (token.isEmpty()) tokens.remove(profileId) else tokens[profileId] = token
    }

    override suspend fun readCustomHeaders(profileId: String): Map<String, String> =
        headers[profileId].orEmpty()

    override suspend fun writeCustomHeaders(profileId: String, headers: Map<String, String>) {
        if (headers.isEmpty()) this.headers.remove(profileId) else this.headers[profileId] = headers
    }

    override suspend fun deleteProfileSecrets(profileId: String) {
        tokens.remove(profileId)
        headers.remove(profileId)
    }
}

class KeystoreSecureStore(
    private val dataStore: DataStore<Preferences>
) : SecureStore {

    override suspend fun readToken(profileId: String): String =
        readSecret(tokenKey(profileId)).orEmpty()

    override suspend fun writeToken(profileId: String, token: String) {
        writeSecret(tokenKey(profileId), token)
    }

    override suspend fun readCustomHeaders(profileId: String): Map<String, String> {
        val payload = readSecret(headersKey(profileId)) ?: return emptyMap()
        return try {
            decodeHeaders(payload)
        } catch (error: Exception) {
            emptyMap()
        }
    }

    override suspend fun writeCustomHeaders(profileId: String, headers: Map<String, String>) {
        writeSecret(headersKey(profileId), if (headers.isEmpty()) null else encodeHeaders(headers))
    }

    override suspend fun deleteProfileSecrets(profileId: String) {
        writeSecret(tokenKey(profileId), null)
        writeSecret(headersKey(profileId), null)
    }

    private suspend fun readSecret(key: String): String? {
        val blob = dataStore.data.first()[stringPreferencesKey(key)] ?: return null
        return try {
            decrypt(blob)
        } catch (error: Exception) {
            null
        }
    }

    private suspend fun writeSecret(key: String, value: String?) {
        val encrypted = value?.takeIf { it.isNotEmpty() }?.let { encrypt(it) }
        dataStore.edit { preferences ->
            if (encrypted == null) {
                preferences.remove(stringPreferencesKey(key))
            } else {
                preferences[stringPreferencesKey(key)] = encrypted
            }
        }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(combined)
        ciphertext.copyInto(combined, iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val combined = Base64.decode(blob, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encodeHeaders(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { (name, value) -> "$name\t$value" }

    private fun decodeHeaders(payload: String): Map<String, String> =
        payload.lines()
            .filter { it.isNotEmpty() }
            .associate { line ->
                val separator = line.indexOf('\t')
                if (separator < 0) line to "" else line.substring(0, separator) to line.substring(separator + 1)
            }

    private fun tokenKey(profileId: String) = "secure.token.$profileId"

    private fun headersKey(profileId: String) = "secure.headers.$profileId"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dockhand.secure.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
