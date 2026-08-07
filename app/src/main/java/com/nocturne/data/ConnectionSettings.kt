package com.nocturne.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    val useSimulator: Boolean = false,
    /** Companion reboot-daemon port/token (`pi-tools/reboot-daemon/`) — separate channel from
     *  the EkosRemote wire's own [port]. Token is null until the user pastes the one printed by
     *  the Pi-side install script. */
    val rebootPort: Int = 9001,
    val rebootToken: String? = null,
)

/**
 * Wraps DataStore Preferences for the connect screen and [SessionViewModel]'s
 * launch-time auto-reconnect decision. No saved host and `useSimulator ==
 * false` (the fresh-install default) means "show the connect screen" — the
 * simulator is an explicit opt-in via its escape-hatch link, not a fallback.
 */
class ConnectionRepository(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val LAST_CONNECTED_AT = longPreferencesKey("last_connected_at")
        val USE_SIMULATOR = booleanPreferencesKey("use_simulator")
        val REBOOT_PORT = intPreferencesKey("reboot_port")
        val REBOOT_TOKEN = stringPreferencesKey("reboot_token")
    }

    val settings: Flow<ConnectionSettings> = context.connectionDataStore.data.map { prefs ->
        ConnectionSettings(
            host = prefs[Keys.HOST],
            port = prefs[Keys.PORT] ?: 9000,
            lastConnectedAt = prefs[Keys.LAST_CONNECTED_AT],
            useSimulator = prefs[Keys.USE_SIMULATOR] ?: false,
            rebootPort = prefs[Keys.REBOOT_PORT] ?: 9001,
            rebootToken = prefs[Keys.REBOOT_TOKEN],
        )
    }

    suspend fun current(): ConnectionSettings = settings.first()

    suspend fun save(host: String, port: Int) {
        context.connectionDataStore.edit { prefs ->
            prefs[Keys.HOST] = host
            prefs[Keys.PORT] = port
            prefs[Keys.USE_SIMULATOR] = false
        }
    }

    suspend fun markConnectedNow() {
        context.connectionDataStore.edit { prefs -> prefs[Keys.LAST_CONNECTED_AT] = System.currentTimeMillis() }
    }

    suspend fun setUseSimulator(useSimulator: Boolean) {
        context.connectionDataStore.edit { prefs -> prefs[Keys.USE_SIMULATOR] = useSimulator }
    }

    suspend fun saveRebootConfig(port: Int, token: String) {
        context.connectionDataStore.edit { prefs ->
            prefs[Keys.REBOOT_PORT] = port
            prefs[Keys.REBOOT_TOKEN] = token
        }
    }
}
