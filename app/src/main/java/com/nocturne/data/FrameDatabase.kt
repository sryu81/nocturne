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
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nocturne.protocol.MediaFrame
import com.nocturne.session.sanitizeFileToken
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Which capture context a frame came from; decides Preview/ vs Plan/ on disk. The frame itself
 * carries no target/filter ([com.nocturne.protocol.MediaHeader]), so this is inferred from what
 * is active when it arrives (`AppState.activeFrameSource`), a correlation rather than a guarantee.
 */
sealed interface FrameSource {
    /** Bench "Preview Main Cam"/"Preview Guide Cam" (`JOBTYPE_PREVIEW` in Ekos). */
    data object Test : FrameSource

    /**
     * A scheduler job was `BUSY` when this frame landed. [targetRA]/[targetDEC] come from
     * `WireSchedulerJob`, which defaults them to `0.0` when absent, so a target at exactly
     * RA/Dec 0° is indistinguishable from "no data".
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
 * Writes capture JPEGs to the app's external-files storage:
 * - `Preview/<date>/Prev_00000.jpg` for test captures, one folder per day
 * - `Plan/<date>/<target>/<target>_<date>_<filter>_<exposure>sec_<temp>C_<seq>.jpg` for
 *   scheduler captures, `<seq>` 1-indexed per target+filter+exposure
 *
 * [previewFile]'s counter is seeded from disk so a same-day relaunch doesn't overwrite earlier
 * frames. [planFile]'s per-combo counters are session-only (not disk-seeded).
 */
class FrameFileWriter(private val baseDir: File) {
    private var previewDir: File? = null
    private var previewCounter = 0
    private val planSequenceCounters = mutableMapOf<String, Int>()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Writes [frame]'s JPEG bytes to the path for [source], creating parent folders as needed. */
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

    /** Target/filter names can contain characters unsafe in a path; same rule as the `.esq` filename. */
    private fun sanitize(s: String): String = sanitizeFileToken(s)
}

/**
 * One persisted capture frame, so the Frames grid survives a relaunch. Fields mirror
 * [com.nocturne.protocol.MediaHeader]; the wire has no per-sub id, so [id] is assigned locally
 * in arrival order. Room stores metadata and [filePath] only, not the image bytes.
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
    /** Null for a [FrameSource.Test] capture; target/filter/coordinates exist only for [FrameSource.Plan]. */
    val target: String? = null,
    val filter: String? = null,
    val targetRA: Double? = null,
    val targetDEC: Double? = null,
) {
    companion object {
        /** Capture frame → row. [id] is left at its default; Room assigns it on insert. */
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

    /** Newest first, the Frames grid's display order. */
    @Query("SELECT * FROM frames ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<FrameEntity>>

    @Query("UPDATE frames SET keep = :keep WHERE id = :id")
    suspend fun setKeep(id: Long, keep: Boolean)

    @Query("SELECT * FROM frames ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FrameEntity>>
}

// fallbackToDestructiveMigration: frames are a convenience cache of files on disk, so a schema
// change wipes rows instead of needing a migration.
@Database(entities = [FrameEntity::class], version = 3, exportSchema = false)
abstract class FrameDatabase : RoomDatabase() {
    abstract fun frameDao(): FrameDao
}

/**
 * Keeps Room types out of [EkosRemoteController], like [SequenceRepository]/[ConnectionRepository];
 * optional, so tests can omit it.
 */
class FrameRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = Room.databaseBuilder(appContext, FrameDatabase::class.java, "frames.db")
        .fallbackToDestructiveMigration(true)
        .addCallback(object : RoomDatabase.Callback() {
            // Ekos sends HFR -1 for "no star measured"; clear any such values stored as numbers.
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE frames SET hfr = NULL WHERE hfr <= 0")
            }
        })
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
