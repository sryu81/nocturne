package com.nocturne.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nocturne.session.SequenceJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.sequenceDataStore: DataStore<Preferences> by preferencesDataStore(name = "sequence_jobs")

private val sequenceJson = Json { ignoreUnknownKeys = true }

/**
 * The app's local job queue, persisted across process death. Purely local: syncing with Ekos's
 * Scheduler is always an explicit action ([SessionController.pushJob]/`toggleScheduler`/
 * `removeJob`), never a side effect of loading this or of connecting.
 */
data class SequenceSnapshot(
    val jobs: List<SequenceJob> = emptyList(),
    val jobSeq: Int = 1,
    val lastActiveJobId: String? = null,
)

class SequenceRepository(private val context: Context) {

    private object Keys {
        val JOBS_JSON = stringPreferencesKey("jobs_json")
        val JOB_SEQ = intPreferencesKey("job_seq")
        val LAST_ACTIVE_JOB_ID = stringPreferencesKey("last_active_job_id")
    }

    val snapshot: Flow<SequenceSnapshot> = context.sequenceDataStore.data.map { prefs ->
        SequenceSnapshot(
            // A corrupt or incompatible blob falls back to an empty queue rather than crashing.
            jobs = prefs[Keys.JOBS_JSON]?.let {
                runCatching { sequenceJson.decodeFromString<List<SequenceJob>>(it) }.getOrNull()
            } ?: emptyList(),
            jobSeq = prefs[Keys.JOB_SEQ] ?: 1,
            lastActiveJobId = prefs[Keys.LAST_ACTIVE_JOB_ID],
        )
    }

    suspend fun current(): SequenceSnapshot = snapshot.first()

    suspend fun save(snapshot: SequenceSnapshot) {
        context.sequenceDataStore.edit { prefs ->
            prefs[Keys.JOBS_JSON] = sequenceJson.encodeToString(snapshot.jobs)
            prefs[Keys.JOB_SEQ] = snapshot.jobSeq
            if (snapshot.lastActiveJobId != null) {
                prefs[Keys.LAST_ACTIVE_JOB_ID] = snapshot.lastActiveJobId
            } else {
                prefs.remove(Keys.LAST_ACTIVE_JOB_ID)
            }
        }
    }
}
