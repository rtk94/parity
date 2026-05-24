package com.rknepp.parity.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.serverConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "parity_config",
)

class ServerUrlStore(private val context: Context) {

    private val dataStore = context.serverConfigDataStore

    val serverUrl: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL]?.takeIf { it.isNotBlank() }
    }

    suspend fun set(url: String) {
        val trimmed = url.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Server URL cannot be empty." }
        require(trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)) {
            "Server URL must include http:// or https://"
        }
        dataStore.edit { prefs -> prefs[KEY_SERVER_URL] = trimmed }
    }

    suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(KEY_SERVER_URL) }
    }

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
    }
}
