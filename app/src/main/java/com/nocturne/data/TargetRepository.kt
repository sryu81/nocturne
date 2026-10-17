package com.nocturne.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nocturne.session.Target
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.targetDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_targets")

private val targetJson = Json { ignoreUnknownKeys = true }

/**
 * The Plan tab's user catalogue ("My targets"), persisted across process death — previously
 * in-memory only, so every hand-entered target vanished on app restart. Same DataStore +
 * JSON-blob shape as [SequenceRepository]. Purely local: nothing here touches real Ekos.
 */
data class UserCatalogSnapshot(
    val name: String = "My targets",
    val targets: List<Target> = emptyList(),
    val targetSeq: Int = 1,
)

class TargetRepository(private val context: Context) {

    private object Keys {
        val NAME = stringPreferencesKey("catalog_name")
        val TARGETS_JSON = stringPreferencesKey("targets_json")
        val TARGET_SEQ = intPreferencesKey("target_seq")
    }

    val snapshot: Flow<UserCatalogSnapshot> = context.targetDataStore.data.map { prefs ->
        UserCatalogSnapshot(
            name = prefs[Keys.NAME] ?: "My targets",
            // Corrupt/unparseable blob falls back to empty rather than crashing on launch.
            targets = prefs[Keys.TARGETS_JSON]?.let {
                runCatching { targetJson.decodeFromString<List<Target>>(it) }.getOrNull()
            } ?: emptyList(),
            targetSeq = prefs[Keys.TARGET_SEQ] ?: 1,
        )
    }

    suspend fun current(): UserCatalogSnapshot = snapshot.first()

    suspend fun save(snapshot: UserCatalogSnapshot) {
        context.targetDataStore.edit { prefs ->
            prefs[Keys.NAME] = snapshot.name
            prefs[Keys.TARGETS_JSON] = targetJson.encodeToString(snapshot.targets)
            prefs[Keys.TARGET_SEQ] = snapshot.targetSeq
        }
    }
}
