package com.rknepp.parity.storage

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.KeyGenerator

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
        
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val alias = masterKeyUri.removePrefix("android-keystore://")

        fun ensureKeyExists() {
            if (!ks.containsAlias(alias)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        }

        try {
            ensureKeyExists()
        } catch (e: Exception) {
            // In case of Keystore corruption during check/generation
            ks.deleteEntry(alias)
            ensureKeyExists()
        }

        val client = AndroidKeystoreKmsClient()
        val masterAead = try {
            client.getAead(masterKeyUri)
        } catch (e: InvalidKeyException) {
            // The master key is permanently invalidated (e.g. OS upgrade, lock screen removal).
            ks.deleteEntry(alias)
            keysetFile.delete()
            ensureKeyExists()
            client.getAead(masterKeyUri)
        }

        val handle = if (keysetFile.exists()) {
            try {
                readHandle(masterAead)
            } catch (t: Throwable) {
                // The keyset is unreadable (likely because the master key changed).
                // We cannot recover the token. Delete the file and generate a new keyset.
                keysetFile.delete()
                val fresh = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                writeHandle(fresh, masterAead)
                fresh
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
        const val DEFAULT_MASTER_KEY_URI = "android-keystore://com.rknepp.parity.token_aead_v2"
        private val EMPTY_ASSOCIATED_DATA = ByteArray(0)
    }
}
