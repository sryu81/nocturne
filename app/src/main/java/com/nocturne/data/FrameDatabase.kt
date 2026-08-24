package com.nocturne.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nocturne.protocol.MediaFrame
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Which real capture context a frame came from (M4.5) — decides Preview/ vs Plan/ on disk. There
 * is no wire signal on the frame itself for this (confirmed: [com.nocturne.protocol.MediaHeader]
 * carries no target/filter) — [Test] vs [Plan] is decided by whatever's active in `AppState` at
 * the moment the frame arrives (see `AppState.activeFrameSource`), which is a real but fragile
 * correlation, not a guaranteed one — see docs/M4.5-plan.md.
 */
sealed interface FrameSource {
    /** Bench "Preview Main Cam"/"Preview Guide Cam" — real `JOBTYPE_PREVIEW` on the server side. */
    data object Test : FrameSource

    /** A real scheduler job was `BUSY` when this frame landed. */
    data class Plan(val target: String, val filter: String?, val temperatureC: Double?) : FrameSource
}

/**
 * Writes a real capture frame's JPEG to the app's own external-files storage under the real
 * folder templates specified 2026-08-23 (docs/M4.5-plan.md):
 * - `Preview/<session-datetime>/Prev_00000.jpg` (test captures — one dated folder per app
 *   session, counter reset per folder)
 * - `Plan/<date>/<target>/<target>_<date>_<filter>_<exposure>sec_<temp>C_<seq>.jpg` (scheduler
 *   captures — `<seq>` is 1-indexed, scoped per target+filter+exposure combination)
 *
 * Sequence counters are in-memory only, reset on app relaunch — an accepted gap, same class as
 * every other "session-scoped, not persisted" bookkeeping already in this app (matches
 * `EkosRemoteController`'s own pending-* fields); a relaunch mid-target just starts that
 * target+filter+exposure's counter over at 1, it does not overwrite the earlier files (the
 * timestamp-qualified path means a collision would only happen if two files also matched every
 * other tag, which `System.currentTimeMillis()`-derived date granularity makes exceedingly
 * unlikely within one calendar day at real sub-minute cadences — not treated as a real risk here).
 */
class FrameFileWriter(private val baseDir: File) {
    private var previewSessionDir: File? = null
    private var previewCounter = 0
    private val planSequenceCounters = mutableMapOf<String, Int>()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val sessionFmt = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US)

    /** Writes [frame]'s JPEG bytes to the real path for [source], creating parent folders as needed. */
    fun write(frame: MediaFrame, timestampMs: Long, source: FrameSource): File {
        val file = when (source) {
            is FrameSource.Test -> previewFile(timestampMs)
            is FrameSource.Plan -> planFile(frame, timestampMs, source)
        }
        file.parentFile?.mkdirs()
        file.writeBytes(frame.jpeg)
        return file
    }

    private fun previewFile(timestampMs: Long): File {
        val dir = previewSessionDir ?: File(baseDir, "Preview/${sessionFmt.format(Date(timestampMs))}").also {
            previewSessionDir = it
        }
        return File(dir, "Prev_%05d.jpg".format(previewCounter++))
    }

    private fun planFile(frame: MediaFrame, timestampMs: Long, source: FrameSource.Plan): File {
        val date = dateFmt.format(Date(timestampMs))
        val target = sanitize(source.target)
        val filter = source.filter?.let { sanitize(it) } ?: "NoFilter"
        val exposure = frame.header.exposure?.let { "${it}sec" } ?: "unknownExp"
        val temp = source.temperatureC?.let { "%.0fC".format(it) } ?: "unknownC"
        val key = "$target|$filter|$exposure"
        val seq = (planSequenceCounters[key] ?: 0) + 1
        planSequenceCounters[key] = seq
        return File(baseDir, "Plan/$date/$target/${target}_${date}_${filter}_${exposure}_${temp}_$seq.jpg")
    }

    /** Real target/filter names can carry spaces/punctuation a filesystem path shouldn't. */
    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
}

/**
 * One real capture frame (M4.3/M4.5), persisted so the Frames grid survives a relaunch — the
 * in-memory `AppState.latestCaptureFrame` (M4.2) only ever holds the *most recent* frame, no
 * history. Metadata fields are exactly [com.nocturne.protocol.MediaHeader]'s own real ones for a
 * full-shape capture frame — no per-sub index/name exists on the wire (confirmed against source,
 * docs/M4-plan.md), so [id] is minted client-side (arrival order), not a real Ekos-assigned
 * identifier. [filePath] is the real on-disk JPEG (M4.5, [FrameFileWriter]) — Room indexes
 * metadata + path only, it doesn't duplicate the image bytes into a blob column too.
 */
@Entity(tableName = "frames")
data class FrameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val hfr: Double?,
    val mean: Double?,
    val median: Double?,
    val stddev: Double?,
    val exposure: String?,
    val gain: String?,
    val bin: String?,
    val resolution: String?,
    val keep: Boolean = true,
    val filePath: String,
) {
    companion object {
        /** Real capture frame → persisted row. [id] is left at its default; Room assigns it on insert. */
        fun from(frame: MediaFrame, timestampMs: Long, filePath: String): FrameEntity = FrameEntity(
            timestampMs = timestampMs,
            hfr = frame.header.hfr,
            mean = frame.header.mean,
            median = frame.header.median,
            stddev = frame.header.stddev,
            exposure = frame.header.exposure,
            gain = frame.header.gain,
            bin = frame.header.bin,
            resolution = frame.header.resolution,
            filePath = filePath,
        )
    }
}

@Dao
interface FrameDao {
    @Insert
    suspend fun insert(frame: FrameEntity): Long

    /** Newest first — matches the Frames grid's own display order. */
    @Query("SELECT * FROM frames ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<FrameEntity>>

    @Query("UPDATE frames SET keep = :keep WHERE id = :id")
    suspend fun setKeep(id: Long, keep: Boolean)

    @Query("SELECT * FROM frames ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FrameEntity>>
}

// Bumped 1->2 for M4.5's jpeg-blob -> filePath column change. Pre-release schema churn only —
// fallbackToDestructiveMigration wipes old rows rather than needing a real migration written for
// data nothing depends on surviving yet.
@Database(entities = [FrameEntity::class], version = 2, exportSchema = false)
abstract class FrameDatabase : RoomDatabase() {
    abstract fun frameDao(): FrameDao
}

/**
 * Thin wrapper matching this package's existing [SequenceRepository]/[ConnectionRepository]
 * shape — [EkosRemoteController] depends on this, not on Room types directly, same "optional,
 * tests can omit it" convention as those two.
 */
class FrameRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = Room.databaseBuilder(appContext, FrameDatabase::class.java, "frames.db")
        .fallbackToDestructiveMigration(true)
        .build()
    private val dao = db.frameDao()
    private val fileWriter = FrameFileWriter(appContext.getExternalFilesDir(null) ?: appContext.filesDir)

    suspend fun insert(frame: MediaFrame, timestampMs: Long, source: FrameSource): Long {
        val file = fileWriter.write(frame, timestampMs, source)
        return dao.insert(FrameEntity.from(frame, timestampMs, file.absolutePath))
    }

    fun observeAll(): Flow<List<FrameEntity>> = dao.observeAll()
    suspend fun setKeep(id: Long, keep: Boolean) = dao.setKeep(id, keep)
}
