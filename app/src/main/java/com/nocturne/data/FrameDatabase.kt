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
import com.nocturne.session.sanitizeFileToken
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

    /**
     * A real scheduler job was `BUSY` when this frame landed. [targetRA]/[targetDEC] are the
     * job's own real coordinates (`WireSchedulerJob.targetRA`/`targetDEC`) — **known ambiguity,
     * not fixed here**: the wire model defaults both to `0.0` when absent, so a real target
     * sitting exactly at RA/Dec 0° is indistinguishable from "no data" under this scheme.
     */
    data class Plan(
        val target: String,
        val filter: String?,
        val temperatureC: Double?,
        val targetRA: Double? = null,
        val targetDEC: Double? = null,
    ) : FrameSource
}

/**
 * Writes a real capture frame's JPEG to the app's own external-files storage under the real
 * folder templates specified 2026-08-23 (docs/M4.5-plan.md):
 * - `Preview/<date>/Prev_00000.jpg` (test captures — one dated folder per **day**, matching
 *   `Plan`'s own date granularity; multiple app sessions on the same day share the folder and
 *   keep counting up, they don't restart at 0 and clobber each other)
 * - `Plan/<date>/<target>/<target>_<date>_<filter>_<exposure>sec_<temp>C_<seq>.jpg` (scheduler
 *   captures — `<seq>` is 1-indexed, scoped per target+filter+exposure combination)
 *
 * [previewFile]'s counter is in-memory but seeded from what's already on disk the first time it's
 * needed — a relaunch on the same day continues numbering instead of overwriting what an earlier
 * session that day already wrote. [planFile]'s per-combo counters stay session-only (not
 * disk-seeded) — same accepted gap as before, unrelated to the change requested here.
 */
class FrameFileWriter(private val baseDir: File) {
    private var previewDir: File? = null
    private var previewCounter = 0
    private val planSequenceCounters = mutableMapOf<String, Int>()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

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
        val dir = previewDir ?: File(baseDir, "Preview/${dateFmt.format(Date(timestampMs))}").also {
            previewDir = it
            previewCounter = nextIndexIn(it, prefix = "Prev_", suffix = ".jpg")
        }
        return File(dir, "Prev_%05d.jpg".format(previewCounter++))
    }

    /** Highest existing `<prefix><N><suffix>` index in [dir], plus one — 0 if the folder is new/empty. */
    private fun nextIndexIn(dir: File, prefix: String, suffix: String): Int =
        (dir.listFiles()?.mapNotNull { f ->
            f.name.takeIf { it.startsWith(prefix) && it.endsWith(suffix) }
                ?.removePrefix(prefix)?.removeSuffix(suffix)?.toIntOrNull()
        }?.maxOrNull() ?: -1) + 1

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

    /** Real target/filter names can carry spaces/punctuation a filesystem path shouldn't — shared with [pushRealJob]'s `.esq` filename. */
    private fun sanitize(s: String): String = sanitizeFileToken(s)
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
    /** Null for a [FrameSource.Test] capture — real target/filter/coordinates only exist for [FrameSource.Plan] (M4.5 Part C). */
    val target: String? = null,
    val filter: String? = null,
    val targetRA: Double? = null,
    val targetDEC: Double? = null,
) {
    companion object {
        /** Real capture frame → persisted row. [id] is left at its default; Room assigns it on insert. */
        fun from(frame: MediaFrame, timestampMs: Long, filePath: String, source: FrameSource): FrameEntity = FrameEntity(
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
            target = (source as? FrameSource.Plan)?.target,
            filter = (source as? FrameSource.Plan)?.filter,
            targetRA = (source as? FrameSource.Plan)?.targetRA,
            targetDEC = (source as? FrameSource.Plan)?.targetDEC,
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

// Bumped 1->2 for M4.5 Part A's jpeg-blob -> filePath change, 2->3 for Part C's
// target/filter/targetRA/targetDEC columns. Pre-release schema churn only —
// fallbackToDestructiveMigration wipes old rows rather than needing a real migration written for
// data nothing depends on surviving yet.
@Database(entities = [FrameEntity::class], version = 3, exportSchema = false)
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
        return dao.insert(FrameEntity.from(frame, timestampMs, file.absolutePath, source))
    }

    fun observeAll(): Flow<List<FrameEntity>> = dao.observeAll()
    suspend fun setKeep(id: Long, keep: Boolean) = dao.setKeep(id, keep)
}
