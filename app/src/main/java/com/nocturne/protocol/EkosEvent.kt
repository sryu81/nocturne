package com.nocturne.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Typed inbound pushes understood so far, decoded from an [Envelope]'s
 * `payload` once dispatched on `type` by [EkosEventCodec]. Not a polymorphic
 * hierarchy (no `@Serializable` on the sealed interface itself) — dispatch is
 * manual, keyed by the wire's `type` string, since the wire has no
 * discriminator field of its own.
 *
 * M3 confirms (against `EkosRemote-Command-Reference.md`, itself verified
 * against the real `message.cpp` source) that request/reply commands reuse
 * the *request's own type string* as the reply's `type` — e.g. `get_profiles`
 * replies are themselves typed `"get_profiles"`, `profile_add` triggers an
 * auto-reply also typed `"get_profiles"`. Only the M2 status-push family
 * below (`new_*_state`) uses a distinct push-side name.
 *
 * Anything still unmodeled — `capture_get_sequences`, align settings
 * payloads, the full ~230-command set — falls through to [Raw] rather than
 * being over-modeled before it's needed.
 */
sealed interface EkosEvent {
    @Serializable
    data class NewConnectionState(val connected: Boolean, val online: Boolean) : EkosEvent

    @Serializable
    data class NewCaptureState(val status: String) : EkosEvent

    @Serializable
    data class NewMountState(
        val status: String,
        val target: String,
        val slewRate: Int,
        val pierSide: Int,
    ) : EkosEvent

    @Serializable
    data class NewFocusState(val status: String) : EkosEvent

    @Serializable
    data class NewGuideState(val status: String) : EkosEvent

    @Serializable
    data class NewAlignState(val status: String) : EkosEvent

    @Serializable
    data class NewPolarState(val stage: String, val enabled: Boolean, val message: String) : EkosEvent

    // ── M3: profiles ────────────────────────────────────────────────────

    /** `get_profiles` reply — also the auto-reply after `profile_add`/`update`/`delete`. */
    @Serializable
    data class Profiles(val selectedProfile: String? = null, val profiles: List<WireProfile> = emptyList()) : EkosEvent

    // ── M3: devices / raw INDI properties ───────────────────────────────

    /** `get_devices` reply — bare array on the wire, wrapped here. */
    @Serializable
    data class Devices(val devices: List<WireDevice>) : EkosEvent

    /**
     * `device_get` reply — `{"device", "properties": [<property JSON>,...]}`,
     * one whole device's property vectors at once. Used to seed [DeviceRole]
     * property sheets right after connect.
     */
    data class DeviceProperties(val device: String, val properties: List<WireProperty>) : EkosEvent

    /**
     * `device_property_get` reply, **and** the same-tagged push
     * `device_property_subscribe` triggers per changed vector
     * (`sendPendingProperties`, always compact) — one property vector.
     */
    data class DeviceProperty(val property: WireProperty) : EkosEvent

    // ── M3: astro lookups ────────────────────────────────────────────────

    /** `astro_search_objects` reply — flat array of object name strings. */
    @Serializable
    data class AstroSearchResult(val names: List<String>) : EkosEvent

    /** `astro_get_objects_info` reply. */
    @Serializable
    data class AstroObjectsInfo(val objects: List<WireAstroObject>) : EkosEvent

    /** `astro_get_objects_riseset` reply. */
    @Serializable
    data class AstroObjectsRiseset(val entries: List<WireRiseset>) : EkosEvent

    // ── M3: scheduler ────────────────────────────────────────────────────

    /** `scheduler_get_jobs` reply. */
    @Serializable
    data class SchedulerJobs(val jobs: List<WireSchedulerJob>) : EkosEvent

    // ── M3: optical trains ───────────────────────────────────────────────

    /** `train_get_all` reply — bare array on the wire, wrapped here. Also the
     *  auto-push after every `train_add`/`update`/`delete`/`reset`. */
    @Serializable
    data class Trains(val trains: List<WireTrain>) : EkosEvent

    /** Anything not decoded above — unrecognized `type`, or a shape that failed to parse. */
    data class Raw(val type: String, val payload: JsonElement) : EkosEvent
}

/**
 * `ProfileInfo::toJson()` (`profileinfo.cpp:135`) — trimmed to the fields M3
 * actually reads/writes; the legacy per-role convenience fields
 * (`mount`/`ccd`/`guider`/...) and `remote`/`driver_source` are dropped
 * (`ignoreUnknownKeys` on decode, simply omitted on encode — profile_add/
 * update don't require them).
 */
@Serializable
data class WireProfile(
    val name: String,
    val auto_connect: Boolean = true,
    val port_selector: Boolean = false,
    val mode: String = "local",
    val remote_host: String = "localhost",
    val remote_port: Int = 7624,
    val guiding: Int = 0,
    val remote_guiding_host: String = "localhost",
    val remote_guiding_port: Int = 4400,
    val use_web_manager: Boolean = false,
    val drivers: Map<String, List<String>> = emptyMap(),
    val scripts: String? = null,
    /**
     * Legacy per-role convenience field — real Ekos has no separate
     * "Guiders" driver family; a guide camera is just a second entry in
     * `drivers["CCDs"]`, disambiguated from the main imaging camera only by
     * this field (confirmed live: a real profile's `drivers["CCDs"]` held
     * both `["Sony DSLR","ZWO CCD"]` with `guider: "ZWO CCD"` naming which
     * one). Kept even though the rest of the legacy per-role fields
     * (`mount`/`ccd`/`focuser`/...) are dropped — there's no other way to
     * resolve which CCD is the guider.
     */
    val guider: String = "",
)

/** One `get_devices` entry — `interface` is a reserved Kotlin word, mapped via [SerialName]. */
@Serializable
data class WireDevice(
    val name: String,
    val connected: Boolean,
    val version: String = "",
    @SerialName("interface") val interfaceMask: Int = 0,
)

/**
 * INDI `DRIVER_INTERFACE` bitmask values, confirmed against
 * `~/cc/repo/indi/libs/indidevice/basedevice.h`. A device ORs several
 * together (e.g. a guide camera is `CCD or GUIDER`).
 */
enum class DeviceRole(val bit: Int) {
    TELESCOPE(1), CCD(2), GUIDER(4), FOCUSER(8), FILTER(16), DOME(32), GPS(64),
    WEATHER(128), AO(256), DUSTCAP(512), LIGHTBOX(1024), DETECTOR(2048),
    ROTATOR(4096), SPECTROGRAPH(8192), CORRELATOR(16384), AUX(32768),
}

/** Decodes a `get_devices` `interface` bitmask into the roles it ORs together. */
fun bitmaskToRoles(mask: Int): Set<DeviceRole> = DeviceRole.entries.filterTo(mutableSetOf()) { mask and it.bit != 0 }

/**
 * Shared INDI property-vector shape — `device_get`'s `properties[]` and
 * `device_property_get`'s reply/push both use this (`EkosRemote-Command-
 * Reference.md` §14 "Property JSON shapes"). Not a `@Serializable` sealed
 * hierarchy (Switch/Number/Text/Light shapes are distinguished by which key
 * is present, not a discriminator) — [EkosEventCodec] sniffs the JSON object
 * and decodes into the matching concrete class directly.
 */
sealed interface WireProperty {
    val device: String
    val name: String
    val state: Int
    val label: String?
    val group: String?

    @Serializable
    data class Switch(
        override val device: String, override val name: String, override val state: Int,
        val switches: List<WireSwitchElement> = emptyList(),
        override val label: String? = null, override val group: String? = null,
        val perm: Int? = null, val rule: Int? = null,
    ) : WireProperty

    @Serializable
    data class Number(
        override val device: String, override val name: String, override val state: Int,
        val numbers: List<WireNumberElement> = emptyList(),
        override val label: String? = null, override val group: String? = null, val perm: Int? = null,
    ) : WireProperty

    @Serializable
    data class Text(
        override val device: String, override val name: String, override val state: Int,
        val texts: List<WireTextElement> = emptyList(),
        override val label: String? = null, override val group: String? = null, val perm: Int? = null,
    ) : WireProperty

    @Serializable
    data class Light(
        override val device: String, override val name: String, override val state: Int,
        val lights: List<WireLightElement> = emptyList(),
        override val label: String? = null, override val group: String? = null,
    ) : WireProperty
}

@Serializable
data class WireSwitchElement(val name: String, val state: Int, val label: String? = null)

@Serializable
data class WireNumberElement(
    val name: String, val value: Double, val label: String? = null,
    val min: Double? = null, val max: Double? = null, val step: Double? = null, val format: String? = null,
)

@Serializable
data class WireTextElement(val name: String, val text: String, val label: String? = null)

@Serializable
data class WireLightElement(val name: String, val state: Int, val label: String? = null)

/** `astro_get_object(s)_info` entry. `a`/`b`/`pa` only present for DSO catalog objects. */
@Serializable
data class WireAstroObject(
    val name: String,
    val designations: List<String> = emptyList(),
    val magnitude: Double? = null,
    val ra0: Double = 0.0,
    val de0: Double = 0.0,
    val ra: Double = 0.0,
    val de: Double = 0.0,
    val a: Double? = null,
    val b: Double? = null,
    val pa: Double? = null,
)

/** One `astro_get_objects_riseset` entry, minus the optional trailing `days` field. */
@Serializable
data class WireRisesetDay(
    val date: String,
    val rise: String,
    val set: String,
    val transit: String,
    val altitudes: List<Double> = emptyList(),
)

@Serializable
data class WireRiseset(
    val name: String,
    val date: String,
    val rise: String,
    val set: String,
    val transit: String,
    val altitudes: List<Double> = emptyList(),
    val days: List<WireRisesetDay>? = null,
)

/**
 * `SchedulerJob::toJson()`. `state`/`stage` are the raw enum ints —
 * [SchedulerJobStatus]/[SchedulerJobStage] below name the values M3's UI
 * actually branches on (confirmed against `schedulertypes.h`).
 */
@Serializable
data class WireSchedulerJob(
    val name: String,
    val pa: Double = 0.0,
    val targetRA: Double = 0.0,
    val targetDEC: Double = 0.0,
    val state: Int = 0,
    val stage: Int = 0,
    val sequenceCount: Int = 0,
    val completedCount: Int = 0,
    val minAltitude: Double = 0.0,
    val minMoonSeparation: Double = 0.0,
    val maxMoonAltitude: Double = 0.0,
    val repeatsRequired: Int = 0,
    val repeatsRemaining: Int = 0,
    val inSequenceFocus: Boolean = false,
    val startupTime: String = "--",
    val completionTime: String = "--",
    val stopTime: String = "--",
    val altitude: Double = 0.0,
    val altitudeFormatted: String = "",
    val startupFormatted: String = "",
    val endFormatted: String = "",
    val sequence: String = "",
)

/** `SchedulerJob::JOBStatus` (`schedulertypes.h`) — only the values M3's UI branches on. */
object SchedulerJobStatus {
    const val IDLE = 0
    const val EVALUATION = 1
    const val SCHEDULED = 2
    const val BUSY = 3
    const val ERROR = 4
    const val ABORTED = 5
    const val INVALID = 6
    const val COMPLETE = 7
}

/**
 * `SchedulerJob::JOBStage` (`schedulertypes.h`) — full 15-value enum (there's
 * no separate ERROR/ABORTED stage; those live on [SchedulerJobStatus]
 * instead). M3's UI only branches on [IDLE]/[CAPTURING]/[COMPLETE].
 */
object SchedulerJobStage {
    const val IDLE = 0
    const val SLEWING = 1
    const val SLEW_COMPLETE = 2
    const val FOCUSING = 3
    const val FOCUS_COMPLETE = 4
    const val ALIGNING = 5
    const val ALIGN_COMPLETE = 6
    const val RESLEWING = 7
    const val RESLEWING_COMPLETE = 8
    const val POSTALIGN_FOCUSING = 9
    const val POSTALIGN_FOCUSING_COMPLETE = 10
    const val GUIDING = 11
    const val GUIDING_COMPLETE = 12
    const val CAPTURING = 13
    const val COMPLETE = 14
}

/**
 * `OpticalTrainManager::getOpticalTrains()` entry (`train_get_all`). Field
 * *names* are verified live-test output; exact wire *types* beyond `id` are
 * not traced in the docs, so every role/name field defaults to `""` rather
 * than assuming non-null.
 */
@Serializable
data class WireTrain(
    val id: Int = 0,
    val name: String = "",
    val profile: String = "",
    val mount: String = "",
    val camera: String = "",
    val guider: String = "",
    val focuser: String = "",
    val filterwheel: String = "",
    val rotator: String = "",
    val reducer: String = "",
    val dustcap: String = "",
    val lightbox: String = "",
    val scope: String = "",
    val adaptiveoptics: String = "",
)
