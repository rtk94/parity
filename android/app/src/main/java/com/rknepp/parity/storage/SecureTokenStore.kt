package com.rknepp.parity.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Base64

private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "parity_secure",
)

/**
 * Stores the bearer token encrypted with a Tink AEAD primitive whose
 * keyset is itself wrapped by an Android Keystore master AEAD. The
 * ciphertext lives in a Preferences DataStore as Base64. The plaintext
 * token never lands on disk.
 */
class SecureTokenStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val aeadProvider: TinkAeadProvider,
) {

    constructor(context: Context, aeadProvider: TinkAeadProvider) :
        this(context.secureDataStore, aeadProvider)

    val token: Flow<String?> = dataStore.data.map { prefs ->
        val ciphertext = prefs[KEY_TOKEN_CIPHERTEXT]?.takeIf { it.isNotBlank() }
            ?: return@map null
        decrypt(ciphertext)
    }

    suspend fun set(plaintext: String) {
        require(plaintext.isNotEmpty()) { "Token cannot be empty." }
        val ciphertext = encrypt(plaintext)
        dataStore.edit { prefs -> prefs[KEY_TOKEN_CIPHERTEXT] = ciphertext }
    }

    suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(KEY_TOKEN_CIPHERTEXT) }
    }

    private fun encrypt(plaintext: String): String {
        val bytes = aeadProvider.aead().encrypt(plaintext.toByteArray(Charsets.UTF_8), EMPTY_AD)
        return ENCODER.encodeToString(bytes)
    }

    private suspend fun decrypt(ciphertext: String): String? {
        return try {
            val raw = DECODER.decode(ciphertext)
            val plain = aeadProvider.aead().decrypt(raw, EMPTY_AD)
            String(plain, Charsets.UTF_8)
        } catch (t: Throwable) {
            // Corrupted ciphertext (rotated keyset, partial wipe,
            // mangled storage) — clear so the next read is clean and
            // the user is routed back to login.
            dataStore.edit { it.remove(KEY_TOKEN_CIPHERTEXT) }
            null
        }
    }

    companion object {
        private val KEY_TOKEN_CIPHERTEXT = stringPreferencesKey("token_ciphertext")
        private val EMPTY_AD = ByteArray(0)
        private val ENCODER: Base64.Encoder = Base64.getEncoder()
        private val DECODER: Base64.Decoder = Base64.getDecoder()

        /** Test seam: construct with a caller-supplied DataStore. */
        internal fun forTesting(
            dataStore: DataStore<Preferences>,
            aeadProvider: TinkAeadProvider,
        ): SecureTokenStore = SecureTokenStore(dataStore, aeadProvider)
    }
}
