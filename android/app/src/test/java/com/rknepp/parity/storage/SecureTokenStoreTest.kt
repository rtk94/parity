package com.rknepp.parity.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecureTokenStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var aeadProvider: TinkAeadProvider
    private lateinit var store: SecureTokenStore

    @Before
    fun setUp() {
        AeadConfig.register()
        val handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val aead = handle.getPrimitive(Aead::class.java)
        aeadProvider = object : TinkAeadProvider {
            override fun aead(): Aead = aead
        }
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("parity_secure.preferences_pb") },
        )
        store = SecureTokenStore.forTesting(dataStore, aeadProvider)
    }

    @After
    fun tearDown() {
        // PreferenceDataStoreFactory releases the file when the
        // process exits; nothing to close explicitly.
    }

    @Test
    fun setThenReadRoundTrips() = runBlocking {
        store.set("token-abc-123")
        val readBack = store.token.first()
        assertEquals("token-abc-123", readBack)
    }

    @Test
    fun clearRemovesTheToken() = runBlocking {
        store.set("token-abc-123")
        assertNotNull(store.token.first())
        store.clear()
        assertNull(store.token.first())
    }

    @Test
    fun corruptedCiphertextIsTreatedAsNoTokenAndCleared() = runBlocking {
        // Bypass `set()` to plant a garbage ciphertext directly.
        val key = stringPreferencesKey("token_ciphertext")
        dataStore.edit { prefs -> prefs[key] = "not-real-base64-or-tink-bytes!!!" }

        val readBack = store.token.first()
        assertNull(readBack)

        // And the bad row has been cleared.
        val plantedAfter = dataStore.data.first()[key]
        assertNull(plantedAfter)
    }

    @Test
    fun differentKeysetCannotDecryptCiphertext() = runBlocking {
        store.set("secret")

        // New keyset => new AEAD => existing ciphertext fails to decrypt.
        val otherHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val otherAead = otherHandle.getPrimitive(Aead::class.java)
        val otherProvider = object : TinkAeadProvider {
            override fun aead(): Aead = otherAead
        }
        val otherStore = SecureTokenStore.forTesting(dataStore, otherProvider)
        assertNull(otherStore.token.first())
    }
}
