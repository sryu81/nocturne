package com.nocturne.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nocturne.session.LogLevel
import com.nocturne.session.LogLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One stored log line; [raw] with [source] is unique, so re-sent history is ignored. */
@Entity(
    tableName = "log_entries",
    indices = [Index(value = ["source", "raw"], unique = true), Index("timeMs"), Index("nightKey")],
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val nightKey: String,
    val source: String,
    val level: Int,
    val text: String,
    val raw: String,
)

/** A stored line as the UI sees it. */
data class LogEntry(val id: Long, val timeMs: Long, val nightKey: String, val source: String, val level: LogLevel, val text: String)

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<LogEntity>)

    @Query(
        "SELECT * FROM log_entries WHERE (:source IS NULL OR source = :source) AND level >= :minLevel " +
            "AND (:night IS NULL OR nightKey = :night) AND (:query = '' OR text LIKE '%' || :query || '%') " +
            "ORDER BY timeMs DESC, id DESC LIMIT :limit",
    )
    fun observe(source: String?, minLevel: Int, night: String?, query: String, limit: Int): Flow<List<LogEntity>>

    @Query("SELECT DISTINCT nightKey FROM log_entries ORDER BY nightKey DESC")
    fun nights(): Flow<List<String>>

    @Query("SELECT DISTINCT source FROM log_entries ORDER BY source")
    fun sources(): Flow<List<String>>

    @Query("DELETE FROM log_entries WHERE nightKey = :night")
    suspend fun deleteNight(night: String)

    @Query("DELETE FROM log_entries")
    suspend fun clear()
}

@Database(entities = [LogEntity::class], version = 1, exportSchema = false)
abstract class LogDatabase : RoomDatabase() {
    abstract fun dao(): LogDao
}

/**
 * The phone's copy of what Ekos, the app and the rig said, kept until the user deletes it, so a
 * night's history survives an app restart, a Pi reboot or a power cut. A separate database from
 * the frames, so it can change without touching them.
 */
class LogRepository(context: Context) {
    private val dao = Room.databaseBuilder(context.applicationContext, LogDatabase::class.java, "log.db").build().dao()

    suspend fun add(lines: List<LogLine>, nightOf: (Long) -> String) {
        if (lines.isEmpty()) return
        dao.insertAll(lines.map { LogEntity(timeMs = it.timeMs, nightKey = nightOf(it.timeMs), source = it.source, level = it.level.ordinal, text = it.text, raw = it.raw) })
    }

    fun observe(source: String? = null, minLevel: LogLevel = LogLevel.INFO, night: String? = null, query: String = "", limit: Int = 500): Flow<List<LogEntry>> =
        dao.observe(source, minLevel.ordinal, night, query, limit).map { rows ->
            rows.map { LogEntry(it.id, it.timeMs, it.nightKey, it.source, LogLevel.entries[it.level], it.text) }
        }

    fun nights(): Flow<List<String>> = dao.nights()
    fun sources(): Flow<List<String>> = dao.sources()
    suspend fun deleteNight(night: String) = dao.deleteNight(night)
    suspend fun clear() = dao.clear()
}
