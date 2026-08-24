package com.nocturne.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `/media/ekos` binary frame header — a fixed 512-byte null-padded JSON blob
 * (`Media::METADATA_PACKET`) preceding raw JPEG bytes. Field shapes confirmed against a REAL
 * live capture this session (not the docs, which had two real mistakes — see below) after the
 * app connected fine but showed no image at all: [bpp] and [size] are both real JSON **strings**
 * (`QString::number(imageData->bpp())`/`KFormat().formatByteSize(...)`,
 * `~/cc/repo/kstars/kstars/ekos/ekosremote/media.cpp:upload()`), not the numeric types the docs'
 * field list implied — every real frame was silently decode-failing to `null` before this fix.
 * Real server sends three distinct shapes depending on frame type — modeled here as one
 * all-nullable data class rather than three sealed variants, since the only thing that actually
 * varies is *which* fields are present, not their meaning: minimal (live video) carries only
 * [resolution]/[ext] and no [uuid] at all; reduced (`+A` fast-preview) adds most fields but omits
 * [min]/[max]/[shadows]/[midtones]/[highlights]/[hasWCS]/[hfr]/[view]; full (capture/preview)
 * carries everything.
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

/** Real device this frame came from, derived from [MediaHeader.uuid]'s tag convention. */
enum class MediaFrameType { CAPTURE, ALIGN, FOCUS, GUIDE, DARK, LIVE_VIDEO, OTHER }

/**
 * A second real mistake the docs' summary led to: a genuine capture frame's `uuid` is NOT an
 * empty string — it's `data->objectName()`, a real per-image identifier
 * (`manager.cpp:updateCaptureProgress`, `media()->sendData(data, data->objectName())`), confirmed
 * live as a 32-hex-char value (e.g. `"5d7fe6d1d36a4378a4238bad2d054535"`). Only the module tags
 * `"+A"`/`"+F"`/`"+G"`/`"+D"` (`Media::sendModuleFrame`, confirmed source-side) and the two
 * sky-lookup tags (`"skypoint_hips"`, `"hips_<md5>"` — an unrelated feature, not a device frame)
 * are ever literal/prefixed like that; everything else — any other uuid string at all — is a
 * real capture frame, so CAPTURE is the default case here, not a `""`-only special case.
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
