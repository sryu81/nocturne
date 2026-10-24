package com.nocturne.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `/media/ekos` binary frame header: a fixed 512-byte null-padded JSON blob
 * (`Media::METADATA_PACKET`) before the JPEG bytes. [bpp] and [size] are JSON strings on the
 * wire (`ekosremote/media.cpp:upload()`). Three shapes, hence all-nullable: minimal (live video)
 * has only [resolution]/[ext] and no [uuid]; reduced (`+A` fast preview) omits
 * [min]/[max]/[shadows]/[midtones]/[highlights]/[hasWCS]/[hfr]/[view]; full (capture/preview)
 * has everything.
 */
@Serializable
data class MediaHeader(
    val resolution: String? = null,
    val size: String? = null,
    val channels: Int? = null,
    val mean: Double? = null,
    val median: Double? = null,
    val stddev: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val bin: String? = null,
    val bpp: String? = null,
    val uuid: String? = null,
    val exposure: String? = null,
    @SerialName("focal_length") val focalLength: String? = null,
    val aperture: String? = null,
    val gain: String? = null,
    @SerialName("pixel_size") val pixelSize: String? = null,
    val shadows: Double? = null,
    val midtones: Double? = null,
    val highlights: Double? = null,
    val hasWCS: Boolean? = null,
    val hfr: Double? = null,
    val view: String? = null,
    val ext: String? = null,
)

/** Module this frame came from, derived from [MediaHeader.uuid]'s tag convention. */
enum class MediaFrameType { CAPTURE, ALIGN, FOCUS, GUIDE, DARK, LIVE_VIDEO, OTHER }

/**
 * A capture frame's `uuid` is `data->objectName()`, a 32-hex-char per-image id
 * (`manager.cpp:updateCaptureProgress`), not an empty string. Only the module tags
 * `"+A"`/`"+F"`/`"+G"`/`"+D"` (`Media::sendModuleFrame`) and the HiPS sky tags
 * (`"skypoint_hips"`, `"hips_<md5>"`) are special, so any other uuid is a capture.
 */
val MediaHeader.frameType: MediaFrameType
    get() = when {
        uuid == null -> MediaFrameType.LIVE_VIDEO // minimal header — no uuid field at all
        uuid == "+A" -> MediaFrameType.ALIGN
        uuid == "+F" -> MediaFrameType.FOCUS
        uuid == "+G" -> MediaFrameType.GUIDE
        uuid == "+D" -> MediaFrameType.DARK
        uuid == "skypoint_hips" || uuid.startsWith("hips_") -> MediaFrameType.OTHER
        else -> MediaFrameType.CAPTURE
    }

/** One decoded `/media/ekos` binary frame: header + raw JPEG bytes ready for BitmapFactory. */
class MediaFrame(val header: MediaHeader, val jpeg: ByteArray) {
    companion object {
        private const val METADATA_PACKET = 512
        private val json = Json { ignoreUnknownKeys = true }

        /** Null on malformed input (too short, or header text isn't valid JSON) — caller drops it. */
        fun parse(bytes: ByteArray): MediaFrame? {
            if (bytes.size < METADATA_PACKET) return null
            var nullAt = METADATA_PACKET
            for (i in 0 until METADATA_PACKET) {
                if (bytes[i] == 0.toByte()) {
                    nullAt = i
                    break
                }
            }
            val headerText = String(bytes, 0, nullAt, Charsets.UTF_8)
            val header = runCatching { json.decodeFromString<MediaHeader>(headerText) }.getOrNull() ?: return null
            // Ekos sends HFR -1 (INVALID_STAR_MEASURE) when no star was measured: not a value.
            return MediaFrame(header.copy(hfr = header.hfr?.takeIf { it > 0.0 }), bytes.copyOfRange(METADATA_PACKET, bytes.size))
        }
    }
}
