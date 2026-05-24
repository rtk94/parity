package com.rknepp.parity.storage

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.io.File

/**
 * Abstraction over the Tink keyset bootstrap so JVM unit tests can
 * substitute a non-keystore master AEAD. Production wiring (see
 * [AndroidKeystoreTinkAeadProvider]) wraps the keyset with a master
 * AEAD backed by the Android Keystore.
 */
interface TinkAeadProvider {
    /** Returns a token-scoped AEAD ready for encrypt/decrypt. */
    fun aead(): Aead
}

class AndroidKeystoreTinkAeadProvider(
    private val keysetFile: File,
    private val masterKeyUri: String = DEFAULT_MASTER_KEY_URI,
) : TinkAeadProvider {

    private val aead: Aead by lazy { loadOrCreate() }

    override fun aead(): Aead = aead

    private fun loadOrCreate(): Aead {
        AeadConfig.register()
        val client = AndroidKeystoreKmsClient()
        val masterAead = client.getAead(masterKeyUri)
        val handle = if (keysetFile.exists()) {
            try {
                readHandle(masterAead)
            } catch (t: Throwable) {
                // Corrupted keyset — caller will see decrypt failures
                // and clear ciphertext; replacing the keyset here
                // would silently shred any still-recoverable token.
                throw t
            }
        } else {
            val fresh = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            writeHandle(fresh, masterAead)
            fresh
        }
        return handle.getPrimitive(Aead::class.java)
    }

    private fun readHandle(masterAead: Aead): KeysetHandle {
        keysetFile.inputStream().use { input ->
            return KeysetHandle.readWithAssociatedData(
                com.google.crypto.tink.JsonKeysetReader.withInputStream(input),
                masterAead,
                EMPTY_ASSOCIATED_DATA,
            )
        }
    }

    private fun writeHandle(handle: KeysetHandle, masterAead: Aead) {
        keysetFile.parentFile?.mkdirs()
        keysetFile.outputStream().use { output ->
            handle.writeWithAssociatedData(
                com.google.crypto.tink.JsonKeysetWriter.withOutputStream(output),
                masterAead,
                EMPTY_ASSOCIATED_DATA,
            )
        }
    }

    companion object {
        const val DEFAULT_MASTER_KEY_URI = "android-keystore://com.rknepp.parity.token_aead"
        private val EMPTY_ASSOCIATED_DATA = ByteArray(0)
    }
}
