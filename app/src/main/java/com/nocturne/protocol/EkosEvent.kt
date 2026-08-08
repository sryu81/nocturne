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

    /**
     * `new_polar_state` push. Real pushes arrive as independent **partial** shapes "scattered
     * across several functions" per the protocol reference — `{"stage"}` alone, `{"message"}`
     * alone, `{"vector": {...}}`, `{"updatedError",...}`, `{"enabled"}` alone, never all three of
     * these fields at once. All three fields need defaults for that reason — without them, every
     * partial real payload fails to decode (missing required field) and silently degrades to
     * [Raw], meaning `wirePolarStage` never actually updates on a real rig. Caught before ever
     * shipping real Polar Alignment wiring, not found via a live bug — same category of mistake
     * as [WireAlignSettings]'s `alignBinning` type fix, just caught by inspection this time.
     */
    @Serializable
    data class NewPolarState(val stage: String? = null, val enabled: Boolean? = null, val message: String? = null) : EkosEvent

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

    /**
     * `train_get_profiles` reply — `ProfileSettings::getSettings()`, the real
     * per-*active-profile* mapping of which train each Ekos module currently
     * uses (confirmed against `profilesettings.cpp`/`opticaltrainmanager.cpp`
     * — not guessed). Bare flat object on the wire, keyed by the *stringified
     * ordinal* of `ProfileSettings::Settings` (`profilesettings.h`) — `"0"`
     * Primary, `"1"` Capture, `"2"` Focus, `"3"` Mount, `"4"` Guide, `"5"`
     * Align, `"6"` DarkLibrary — valued by a train **ID** (resolve against
     * [WireTrain.id] to get a name; this reply carries IDs, not names).
     */
    @Serializable
    data class TrainProfiles(val assignments: Map<String, Int>) : EkosEvent

    // ── M3.1: Scopes catalog ─────────────────────────────────────────────

    /** `get_scopes` reply — bare array, wrapped here. Also the auto-push after every `scope_add`/`update`/`delete`. */
    @Serializable
    data class Scopes(val scopes: List<WireScope>) : EkosEvent

    // ── M3.3: per-module settings (curated subset, see docs/M3.3-plan.md) ─

    /**
     * `mount_get_all_settings` reply — real Ekos's Mount module reports 17
     * fields (`Mount::getAllSettings`, a QVariantMap dump of the whole
     * settings dialog); [WireMountSettings] models only the 10 curated as
     * session-relevant (meridian flip, altitude/HA limits, auto-park).
     * `ignoreUnknownKeys` (see `protocolJson`) drops the other 7 rather than
     * failing to decode.
     */
    @Serializable
    data class MountSettings(val settings: WireMountSettings) : EkosEvent

    /**
     * `capture_get_all_settings` reply — real Ekos's Camera module reports 59 fields
     * (`Camera::getAllSettings`, reflects over every settings-dialog widget by `objectName()`);
     * [WireCaptureSettings] models only the 7 curated as session-relevant (save path, guide-
     * deviation abort guard, start-of-job guide-drift guard, per-job dither) — exposure/bin/
     * gain/offset are already live via the Sequence block editor, cooler temp already live via
     * Bench (see docs/M3.3-plan.md). `ignoreUnknownKeys` drops the other 52.
     */
    @Serializable
    data class CaptureSettings(val settings: WireCaptureSettings) : EkosEvent

    /**
     * `align_get_all_settings` reply — real Ekos's Align module reports 98 fields
     * (`Align::getAllSettings`, reflects over every settings-dialog widget by `objectName()`);
     * [WireAlignSettings] models only the 5 curated as session-relevant (exposure, gain,
     * filter, binning, solver accuracy threshold) — the ~50 astrometry index-file booleans and
     * PAH-star/solver internals are one-time-calibrate-and-forget, not per-session (see
     * docs/M3.3-plan.md). `ignoreUnknownKeys` drops the other 93.
     */
    @Serializable
    data class AlignSettings(val settings: WireAlignSettings) : EkosEvent

    /**
     * `guide_get_all_settings` reply — real Ekos's Guide module reports 84 fields;
     * [WireGuideSettings] models 8 curated (exposure/gain/binning for Bench "Snap guide", plus
     * accuracy threshold/dither for the Guide settings sheet, M3.3 phase 4) — see that class's
     * own doc. `ignoreUnknownKeys` drops the other 76.
     */
    @Serializable
    data class GuideSettings(val settings: WireGuideSettings) : EkosEvent

    /**
     * `focus_get_all_settings` reply — real Ekos's Focus module reports 84 fields;
     * [WireFocusSettings] currently models only `absTicksSpin` — see that class's own doc for
     * why (fixes Bench Focuser's stale-vs-Ekos initial position) and the phase-6 extension plan.
     * `ignoreUnknownKeys` drops the other 83.
     */
    @Serializable
    data class FocusSettings(val settings: WireFocusSettings) : EkosEvent

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
 * *names* were verified live-test output; *types* are now confirmed against
 * a real live capture too (a real Pi's reply held `"profile":5, "reducer":1,
 * "adaptiveoptics":null` — `profile`/`reducer` are numbers, not strings, and
 * `adaptiveoptics` can be an explicit JSON `null`, not just an absent/blank
 * string like the other unassigned-role fields). Getting these wrong isn't
 * cosmetic: a type mismatch throws during `decodeFromJsonElement`, which
 * [EkosEventCodec] catches and silently downgrades to [EkosEvent.Raw] — the
 * Optical Train tab would get no live data at all on real rigs, every time,
 * with no visible error.
 */
@Serializable
data class WireTrain(
    val id: Int = 0,
    val name: String = "",
    val profile: Int = 0,
    val mount: String = "",
    val camera: String = "",
    val guider: String = "",
    val focuser: String = "",
    val filterwheel: String = "",
    val rotator: String = "",
    val reducer: Double = 1.0,
    val dustcap: String = "",
    val lightbox: String = "",
    val scope: String = "",
    val adaptiveoptics: String? = null,
)

/**
 * `ProfileSettings::Settings` enum ordinals (`profilesettings.h`) — keys of a
 * [EkosEvent.TrainProfiles] reply. `PRIMARY` isn't a real assignable module
 * (there's no `train_set {module: "primary"}` — confirmed against
 * `message.cpp`'s `processTrainCommands`, only capture/focus/mount/guide/
 * align/darklibrary are accepted), it's just the fallback ID new trains
 * default everything else to (`opticaltrainmanager.cpp`).
 */
object ProfileTrainSetting {
    const val PRIMARY = "0"
    const val CAPTURE = "1"
    const val FOCUS = "2"
    const val MOUNT = "3"
    const val GUIDE = "4"
    const val ALIGN = "5"
    const val DARK_LIBRARY = "6"
}

/** The real `train_set` module strings (`message.cpp`'s `processTrainCommands`) each [ProfileTrainSetting] ordinal maps to — `PRIMARY` has none. */
val MODULE_KEY_BY_TRAIN_SETTING: Map<String, String> = mapOf(
    ProfileTrainSetting.CAPTURE to "capture",
    ProfileTrainSetting.FOCUS to "focus",
    ProfileTrainSetting.MOUNT to "mount",
    ProfileTrainSetting.GUIDE to "guide",
    ProfileTrainSetting.ALIGN to "align",
    ProfileTrainSetting.DARK_LIBRARY to "darklibrary",
)

/**
 * `OAL::Scope::toJson()` (`get_scopes`/`scope_add`/`scope_update`/
 * `scope_delete` — `EkosRemote-Command-Reference.md` §4, message.cpp:204/
 * 1469/1474/1479): `{id, model, vendor, type, name, focal_length, aperture}`.
 * Real Ekos's Scopes catalog is entirely separate from Optical Trains — a
 * train's `scope` field (on [WireTrain]) just references one of these by
 * [name]. `focal_length`/`aperture` are wire doubles; Nocturne's own
 * [com.nocturne.session.ScopeDef] rounds them to Int mm for its UI.
 */
@Serializable
data class WireScope(
    val id: String = "",
    val model: String = "",
    val vendor: String = "",
    val type: String = "",
    val name: String = "",
    val focal_length: Double = 0.0,
    val aperture: Double = 0.0,
)

/**
 * Curated subset of `mount_get_all_settings`'s real 17 fields (see
 * docs/M3.3-plan.md) — meridian flip, altitude/HA slew limits, auto-park.
 * Field names match the wire verbatim (`Mount::getAllSettings`'s Qt widget
 * object names), confirmed live against the real rig rather than guessed
 * from docs. Excluded: `locationSource`/`timeSource`/`useGeographicUpdate`/
 * `useTimeUpdate`/`leftRightCheckObject`/`upDownCheckObject` — KStars
 * desktop-only conveniences, meaningless from a remote client.
 */
@Serializable
data class WireMountSettings(
    val executeMeridianFlip: Boolean = false,
    val meridianFlipOffsetDegrees: Double = 0.0,
    val enableAltitudeLimits: Boolean = false,
    val minimumAltLimit: Double = 0.0,
    val maximumAltLimit: Double = 90.0,
    val enableAltitudeLimitsTrackingOnly: Boolean = false,
    val enableHaLimit: Boolean = false,
    val maximumHaLimit: Double = 0.0,
    val parkEveryDay: Boolean = false,
    val autoParkTime: String = "00:00:00",
)

/**
 * Curated subset (12 of 59 real fields, docs/M3.3-plan.md) of real Ekos's Camera module
 * settings — save path, guide-deviation abort guard, start-of-job guide-drift guard, per-job
 * dither, plus the live preview capture parameters and cooler setpoint (see below). Field names
 * match the wire verbatim (`Camera::getAllSettings`'s Qt widget object names), confirmed live
 * against the real rig (real values seen: `fileDirectoryT "/home/soo/Pictures"`,
 * `guideDeviation 2`, `startGuideDeviation 2`, `guideDitherPerJobFrequency 0`,
 * `captureExposureN 1`, `captureGainN 99`, `captureBinHN/VN 1`, `cameraTemperatureN -1`).
 *
 * **`captureExposureN`/`captureGainN`/`captureBinHN`/`captureBinVN` were originally excluded**
 * as "already live via the Sequence block editor" — that was wrong: the Sequence block editor's
 * `Block.exposureSec`/`gain`/`binning` only take effect once that block's *job* actually runs
 * through the scheduler. A bare `capture_preview` (Bench check's "Snap main") has no exposure/
 * gain/bin parameter of its own — real Ekos fires it using whatever the Capture module's *own*
 * currently-loaded values are, i.e. exactly these four fields. Included now so Bench's Snap
 * main can actually configure what a preview shoots, not just fire blind.
 *
 * **`cameraTemperatureN` was also wrongly excluded** as "already live via Bench's cooler card" —
 * only the *live sensor reading* was live there (`CCD_TEMPERATURE`, read directly), never the
 * *setpoint*: `state.coolTarget` was a plain client-side value, seeded from a fixture default
 * and never reconciled against this real field on connect. Included now so `coolTarget` can be
 * seeded from the real Capture module's actual setpoint the moment this reply arrives (see
 * `EkosRemoteController.applyEvent`'s `CaptureSettings` arm) — fixes cooler setpoint not
 * matching real Ekos at the start of a session.
 *
 * Still excluded: capture count/offset/frame/format fields (Sequence-job concepts, not a
 * preview's), autofocus/refocus/calibration/script fields (one-time setup, not per-session).
 */
@Serializable
data class WireCaptureSettings(
    val fileDirectoryT: String = "",
    val enforceGuideDeviation: Boolean = false,
    val guideDeviation: Double = 2.0,
    val enforceStartGuiderDrift: Boolean = false,
    val startGuideDeviation: Double = 2.0,
    val enableDitherPerJob: Boolean = false,
    val guideDitherPerJobFrequency: Int = 0,
    val captureExposureN: Double = 1.0,
    val captureGainN: Double = 99.0,
    val captureBinHN: Int = 1,
    val captureBinVN: Int = 1,
    val cameraTemperatureN: Double = -10.0,
)

/**
 * Curated subset (5 of 98 real fields, docs/M3.3-plan.md) of real Ekos's Align module
 * settings — exposure, gain, filter, binning, solver accuracy threshold. Field names match the
 * wire verbatim (`Align::getAllSettings`'s Qt widget object names), confirmed live against the
 * real rig (real values seen: `alignExposure 3`, `alignGain 99`, `alignFilter "L"`,
 * `alignBinning "1x1"`, `alignAccuracyThreshold 30`). **`alignBinning` is a string** (a combo-box
 * selection like `"1x1"`/`"2x2"`, not a number) — confirmed live, don't assume it matches
 * `Block.binning`'s `Int` shape in the Sequence block editor, a completely separate field.
 * Excluded: astrometry index-file booleans (~50 `index_*`/`kcfg_Astrometry*` fields) and
 * PAH-star/solver internals — one-time calibration, not per-session.
 */
/**
 * Curated subset of real Ekos's Guide module settings (8 of 84 real fields — see
 * docs/M3.3-plan.md) — the 3 preview-capture fields Bench check's "Snap guide" needs (same
 * reasoning as `WireCaptureSettings.captureExposureN` etc — `guide_capture` has no parameter of
 * its own, real Ekos fires it using whatever the Guide module's own currently-loaded values are)
 * plus the 5 M3.3 phase 4 fields (solver accuracy threshold, dither). Field names match the wire
 * verbatim, confirmed live against the real rig (real values seen: `guideExposure 3.5`,
 * `guideGain 99`, `guideBinning "1x1"`, `guiderAccuracyThreshold 2`, `kcfg_DitherEnabled false`,
 * `kcfg_DitherPixels 2`, `kcfg_DitherThreshold 1`, `kcfg_ReuseGuideCalibration true`).
 * **`guideBinning` is a string**, same as `WireAlignSettings.alignBinning` — confirmed live, not
 * a number. **Corrects `docs/M3.3-plan.md`'s own field list**, which was flagged unverified
 * (`EkosRemote-Command-Reference.md` explicitly says Guide's field list is "not live-verified,
 * unlike Capture/Focus") and turned out to name 3 of its 5 dither/calibration fields incorrectly
 * relative to what the reference doc's own static analysis had found — this probe confirms the
 * plan's `kcfg_`-prefixed names are correct as-is (all 5 present verbatim in the live reply,
 * including `kcfg_ReuseGuideCalibration` under `guide_get_all_settings` itself, not needing the
 * separate `guide_set_calibration_settings` command as briefly suspected before probing).
 */
/**
 * Curated subset of real Ekos's Focus module settings — currently just `absTicksSpin`, the
 * Focus tab's own tracked absolute-position widget. **This is a real, different number from the
 * focuser's raw INDI `ABS_FOCUS_POSITION` property** (confirmed live: `absTicksSpin` read 29535
 * while the same focuser's raw INDI position read 29465, a real ~70-step gap, not a rounding
 * artifact) — Bench check's Focuser card was reading the raw INDI value, which live-updates
 * correctly on every jog but never matched what real Ekos's own Focus tab actually displays,
 * since Ekos's Focus tab shows *this* field, not the raw hardware property. Fixed by seeding
 * `SimState.focPos` from this value the moment it arrives (see
 * `EkosRemoteController.applyEvent`'s `FocusSettings` arm) rather than switching the display
 * over to this field permanently — `focPos` already live-tracks jogs via local optimistic
 * increment (`jogFocus`), so seeding it once here gets both properties right: matches Ekos's own
 * number at connect, keeps live-updating afterward exactly like it already did.
 *
 * Deliberately partial, same growth plan as `WireGuideSettings` — M3.3 phase 6 (Focus settings
 * sheet) adds the remaining curated fields (exposure, gain, filter, backlash, algorithm — see
 * docs/M3.3-plan.md) to this same struct/decode point later.
 */
@Serializable
data class WireFocusSettings(
    val absTicksSpin: Int = 18422,
)

@Serializable
data class WireGuideSettings(
    val guideExposure: Double = 1.0,
    val guideGain: Double = 99.0,
    val guideBinning: String = "1x1",
    val guiderAccuracyThreshold: Double = 2.0,
    val kcfg_DitherEnabled: Boolean = false,
    val kcfg_DitherPixels: Int = 2,
    val kcfg_DitherThreshold: Double = 1.0,
    val kcfg_ReuseGuideCalibration: Boolean = true,
)

@Serializable
data class WireAlignSettings(
    val alignExposure: Double = 3.0,
    val alignGain: Double = 99.0,
    val alignFilter: String = "L",
    val alignBinning: String = "1x1",
    val alignAccuracyThreshold: Double = 30.0,
)
