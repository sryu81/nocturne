package com.nocturne.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `/media/ekos` binary frame header — a fixed 512-byte null-padded JSON blob
 * (`Media::METADATA_PACKET`) preceding raw JPEG bytes, confirmed against
 * EkosRemote-Client-Guide.md/EkosRemote-Command-Reference.md (docs/M4-plan.md
 * "Media channel"). Real server sends three distinct shapes depending on
 * frame type — modeled here as one all-nullable data class rather than three
 * sealed variants, since the only thing that actually varies is *which*
 * fields are present, not their meaning: minimal (live video) carries only
 * [resolution]/[ext] and no [uuid] at all; reduced (`+A` fast-preview) adds
 * most fields but omits [min]/[max]/[shadows]/[midtones]/[highlights]/
 * [hasWCS]/[hfr]/[view]; full (capture/preview) carries everything.
 */
@Serializable
data class MediaHeader(
    val resolution: String? = null,
    val size: Long? = null,
    val channels: Int? = null,
    val mean: Double? = null,
    val median: Double? = null,
    val stddev: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val bin: String? = null,
    val bpp: Int? = null,
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

/** Real device this frame came from, derived from [MediaHeader.uuid]'s tag convention. */
enum class MediaFrameType { CAPTURE, ALIGN, FOCUS, GUIDE, DARK, LIVE_VIDEO, OTHER }

val MediaHeader.frameType: MediaFrameType
    get() = when (uuid) {
        null -> MediaFrameType.LIVE_VIDEO // minimal header — no uuid field at all
        "" -> MediaFrameType.CAPTURE
        "+A" -> MediaFrameType.ALIGN
        "+F" -> MediaFrameType.FOCUS
        "+G" -> MediaFrameType.GUIDE
        "+D" -> MediaFrameType.DARK
        else -> MediaFrameType.OTHER // hips_<md5>/skypoint_hips sky-image lookups, not a device frame
    }

/** One decoded `/media/ekos` binary frame: real header + raw JPEG bytes ready for BitmapFactory. */
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
            return MediaFrame(header, bytes.copyOfRange(METADATA_PACKET, bytes.size))
        }
    }
}
