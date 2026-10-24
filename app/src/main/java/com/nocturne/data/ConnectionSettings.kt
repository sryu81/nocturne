package com.nocturne.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.connectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "connection_settings")

/** Last-used EkosRemote connection, persisted across launches. */
data class ConnectionSettings(
    val host: String? = null,
    val port: Int = 9000,
    val lastConnectedAt: Long? = null,
    /** Reboot-daemon port (`pi-tools/reboot-daemon/`) on the same host; separate from [port]. */
    val rebootPort: Int = 9001,
)

/**
 * DataStore Preferences for the connect screen's saved host/port, read by [SessionViewModel]
 * to pre-fill it.
 */
class ConnectionRepository(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val LAST_CONNECTED_AT = longPreferencesKey("last_connected_at")
        val REBOOT_PORT = intPreferencesKey("reboot_port")
        /** Left over from the removed daemon token — only read to delete it. */
        val LEGACY_REBOOT_TOKEN = stringPreferencesKey("reboot_token")
    }

    val settings: Flow<ConnectionSettings> = context.connectionDataStore.data.map { prefs ->
        ConnectionSettings(
            host = prefs[Keys.HOST],
            port = prefs[Keys.PORT] ?: 9000,
            lastConnectedAt = prefs[Keys.LAST_CONNECTED_AT],
            rebootPort = prefs[Keys.REBOOT_PORT] ?: 9001,
        )
    }

    suspend fun current(): ConnectionSettings = settings.first()

    suspend fun save(host: String, port: Int) {
        context.connectionDataStore.edit { prefs ->
            prefs[Keys.HOST] = host
            prefs[Keys.PORT] = port
        }
    }

    suspend fun markConnectedNow() {
        context.connectionDataStore.edit { prefs -> prefs[Keys.LAST_CONNECTED_AT] = System.currentTimeMillis() }
    }

    suspend fun saveRebootPort(port: Int) {
        context.connectionDataStore.edit { prefs ->
            prefs[Keys.REBOOT_PORT] = port
            prefs.remove(Keys.LEGACY_REBOOT_TOKEN)
        }
    }
}
