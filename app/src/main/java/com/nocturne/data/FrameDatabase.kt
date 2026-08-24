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

/**
 * One real capture frame (M4.3), persisted so the Frames grid survives a relaunch — the in-memory
 * `SimState.latestCaptureFrame` (M4.2) only ever holds the *most recent* frame, no history.
 * Fields are exactly [com.nocturne.protocol.MediaHeader]'s own real ones for a full-shape capture
 * frame — no per-sub index/name exists on the wire (confirmed against source, docs/M4-plan.md),
 * so [id] is minted client-side (arrival order), not a real Ekos-assigned identifier. [jpeg] is
 * stored so the grid's thumbnails come from Room directly, not a re-fetch — the real frame is
 * gone the moment a newer one arrives on the Media channel.
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
    val jpeg: ByteArray,
) {
    // Room needs no equals/hashCode override (rows are compared by id in practice, never this
    // data class's own generated one), but overriding avoids a default ByteArray-identity
    // equals silently breaking DiffUtil/LazyColumn key stability if this is ever compared by value.
    override fun equals(other: Any?): Boolean =
        other is FrameEntity && id == other.id && timestampMs == other.timestampMs && keep == other.keep &&
            hfr == other.hfr && mean == other.mean && median == other.median && stddev == other.stddev &&
            exposure == other.exposure && gain == other.gain && bin == other.bin && resolution == other.resolution &&
            jpeg.contentEquals(other.jpeg)

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** Real capture frame → persisted row. [id] is left at its default; Room assigns it on insert. */
        fun from(frame: MediaFrame, timestampMs: Long): FrameEntity = FrameEntity(
            timestampMs = timestampMs,
            hfr = frame.header.hfr,
            mean = frame.header.mean,
            median = frame.header.median,
            stddev = frame.header.stddev,
            exposure = frame.header.exposure,
            gain = frame.header.gain,
            bin = frame.header.bin,
            resolution = frame.header.resolution,
            jpeg = frame.jpeg,
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

@Database(entities = [FrameEntity::class], version = 1, exportSchema = false)
abstract class FrameDatabase : RoomDatabase() {
    abstract fun frameDao(): FrameDao
}

/**
 * Thin wrapper matching this package's existing [SequenceRepository]/[ConnectionRepository]
 * shape — [EkosRemoteController] depends on this, not on Room types directly, same "optional,
 * tests can omit it" convention as those two.
 */
class FrameRepository(context: Context) {
    private val db = Room.databaseBuilder(context.applicationContext, FrameDatabase::class.java, "frames.db").build()
    private val dao = db.frameDao()

    suspend fun insert(frame: MediaFrame, timestampMs: Long): Long = dao.insert(FrameEntity.from(frame, timestampMs))
    fun observeAll(): Flow<List<FrameEntity>> = dao.observeAll()
    suspend fun setKeep(id: Long, keep: Boolean) = dao.setKeep(id, keep)
}
