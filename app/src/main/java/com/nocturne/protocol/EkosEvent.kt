package com.nocturne.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Typed inbound pushes, decoded from an [Envelope]'s `payload` by [EkosEventCodec]. Dispatch is
 * manual on the envelope's `type` string because the wire has no discriminator of its own.
 *
 * Request/reply commands reuse the request's type string for the reply (`get_profiles` replies
 * are typed `"get_profiles"`; `profile_add` auto-replies with `"get_profiles"`). Only the status
 * pushes (`new_*_state`) use a distinct name. Anything unmodeled falls through to [Raw].
 *
 * Many pushes share one `type` across several independent payload shapes, so their fields
 * default to null and the controller merges the non-null ones. A required field there makes
 * every other shape fail to decode and silently degrade to [Raw].
 */
sealed interface EkosEvent {
    @Serializable
    data class NewConnectionState(val connected: Boolean, val online: Boolean) : EkosEvent

    @Serializable
    data class NewCaptureState(val status: String) : EkosEvent

    /**
     * `new_mount_state` push. Two disjoint shapes: `{status, target, slewRate, pierSide}` on a
     * mount status transition, and a ~1/s coordinate telemetry shape (`{at, az, de, de0, ha, ra,
     * ra0}`, ignored here). Fields default so the telemetry shape still decodes.
     */
    @Serializable
    data class NewMountState(
        val status: String? = null,
        val target: String? = null,
        val slewRate: Int? = null,
        val pierSide: Int? = null,
    ) : EkosEvent

    /**
     * `new_focus_state` push. Several senders share this name (`Manager::updateCurrentHFR`/
     * `setTitle`/`newLog`, `Message::sendStates`):
     * - `{status}`: module state text (`ekos.h` `focusStates`).
     * - per measured focus frame: `hfr` + `pos` (absolute focuser position, -1 on a relative
     *   focuser). HFR is sent even when Autofocus uses another star measure.
     * - `title`: the V-curve plot title (curve fit / R² / scan progress).
     */
    @Serializable
    data class NewFocusState(
        val status: String? = null,
        val hfr: Double? = null,
        val pos: Int? = null,
        val title: String? = null,
    ) : EkosEvent

    /**
     * `new_guide_state` push. Three senders share this name (fork `Message::updateGuideStatus`/
     * `sendGuideStats`/`sendGuideSigma`):
     * - `{status}`: module state text.
     * - per guide frame: `raError`/`decError` (arcsec), `raPulse`/`decPulse` (ms, signed),
     *   `snr`/`skyBg`/`numStars`. The last three are 0 with the plain SEP/Smart detectors and are
     *   only meaningful when > 0.
     * - running RMS: `rmsRA`/`rmsDE` (arcsec).
     */
    @Serializable
    data class NewGuideState(
        val status: String? = null,
        val raError: Double? = null,
        val decError: Double? = null,
        val raPulse: Int? = null,
        val decPulse: Int? = null,
        val snr: Double? = null,
        val skyBg: Double? = null,
        val numStars: Int? = null,
        val rmsRA: Double? = null,
        val rmsDE: Double? = null,
    ) : EkosEvent

    /**
     * `new_align_state` push. `Message::setAlignStatus` sends `{status}` and
     * `Message::setAlignSolution` sends `{solution}` (`message.cpp:942-969`), never combined.
     * The solution arrives on every successful solve, regardless of `rotator_control`.
     */
    @Serializable
    data class NewAlignState(
        val status: String? = null,
        val solution: WireAlignSolution? = null,
    ) : EkosEvent

    /**
     * `align_manual_rotator_status` push (`Message::sendManualRotatorStatus`, message.cpp:2731),
     * no request handler. The sender always includes all three fields; they default anyway.
     */
    @Serializable
    data class NewManualRotatorStatus(
        val currentPA: Double? = null,
        val targetPA: Double? = null,
        val threshold: Double? = null,
    ) : EkosEvent

    /**
     * `new_polar_state` push. Five senders in `message.cpp` (`Message::setPAHStage`/
     * `setPAHMessage`/`setPolarResults`/`setUpdatedErrors`/`setPAHEnabled`) each send one shape
     * alone: `{stage}`, `{message}`, `{vector: {...}}`, `{updatedError, updatedAZError,
     * updatedALTError}` (top-level, not under `vector`), or `{enabled}`.
     */
    @Serializable
    data class NewPolarState(
        val stage: String? = null,
        val enabled: Boolean? = null,
        val message: String? = null,
        val vector: WirePolarVector? = null,
        val updatedError: Double? = null,
        val updatedAZError: Double? = null,
        val updatedALTError: Double? = null,
    ) : EkosEvent

    /**
     * `new_scheduler_state` push: either `{status: Int}` (`Ekos::SchedulerState`, `ekos.h`:
     * 0=IDLE, 1=STARTUP, 2=RUNNING, 3=PAUSED, 4=SHUTDOWN, 5=ABORTED, 6=LOADING) or `{log: String}`
     * (the full Scheduler log, re-sent on every new line). Fires on every Scheduler transition,
     * including ones Ekos makes on its own, so it is the source of truth for whether the
     * Scheduler is running.
     */
    @Serializable
    data class NewSchedulerState(val status: Int? = null, val log: String? = null) : EkosEvent

    // ── Profiles ─────────────────────────────────────────────────────────

    /** `get_profiles` reply — also the auto-reply after `profile_add`/`update`/`delete`. */
    @Serializable
    data class Profiles(val selectedProfile: String? = null, val profiles: List<WireProfile> = emptyList()) : EkosEvent

    // ── Devices / INDI properties ────────────────────────────────────────

    /** `get_devices` reply — bare array on the wire, wrapped here. */
    @Serializable
    data class Devices(val devices: List<WireDevice>) : EkosEvent

    /** `device_get` reply — `{"device", "properties": [<property JSON>,...]}`, a whole device at once. */
    data class DeviceProperties(val device: String, val properties: List<WireProperty>) : EkosEvent

    /**
     * `device_property_get` reply, and the same-tagged push `device_property_subscribe` sends per
     * changed vector (`sendPendingProperties`, always compact).
     */
    data class DeviceProperty(val property: WireProperty) : EkosEvent

    // ── Astro lookups ────────────────────────────────────────────────────

    /** `astro_search_objects` reply — flat array of object name strings. */
    @Serializable
    data class AstroSearchResult(val names: List<String>) : EkosEvent

    /** `astro_get_names` reply — flat, sorted array of every name/long name KStars knows. */
    @Serializable
    data class AstroNames(val names: List<String>) : EkosEvent

    /**
     * `astro_get_object_info` reply — fuzzy name lookup across every catalog
     * (`SkyMapComposite::findByName`). [obj] is null when the server sends `"object": false`;
     * otherwise its `name` is the resolved catalog name, not what was typed.
     */
    data class AstroObjectInfo(val obj: WireAstroObject?) : EkosEvent

    /** `astro_get_objects_info` reply. */
    @Serializable
    data class AstroObjectsInfo(val objects: List<WireAstroObject>) : EkosEvent

    /** `astro_get_objects_riseset` reply. */
    @Serializable
    data class AstroObjectsRiseset(val entries: List<WireRiseset>) : EkosEvent

    /**
     * `astro_get_almanac` reply, computed for local midnight at the configured location.
     * `Dusk`/`Dawn` are signed fraction-of-day offsets from local midnight, not clock times
     * (`dusk: -0.0896` is 2h9m before midnight). Keys are capitalized on the wire.
     */
    @Serializable
    data class AstroAlmanac(
        @SerialName("Dusk") val dusk: Double = 0.0,
        @SerialName("Dawn") val dawn: Double = 0.0,
        /** Moon illuminated fraction, `[0.0, 1.0]` (`KSAlmanac::getMoonIllum()`). The reply's
         *  `MoonPhase` is only the 0-180° elongation and can't tell waxing from waning. */
        @SerialName("MoonIllum") val moonIllum: Double? = null,
    ) : EkosEvent

    /**
     * `astro_get_location` reply, curated to [tz]: signed hours from UTC (e.g. `-7` for PDT),
     * needed to turn [AstroAlmanac]'s offsets into instants.
     */
    @Serializable
    data class AstroLocation(val tz: Double = 0.0) : EkosEvent

    // ── Scheduler ────────────────────────────────────────────────────────

    /** `scheduler_get_jobs` reply. */
    @Serializable
    data class SchedulerJobs(val jobs: List<WireSchedulerJob>) : EkosEvent

    /**
     * `scheduler_save_sequence_file` reply. [path] is the input resolved server-side to an
     * absolute path (`"foo.esq"` → `"/home/<user>/foo.esq"`). `scheduler_set_all_settings`'s
     * `sequenceEdit` does not resolve a bare filename (it becomes `file:foo.esq`), so callers
     * must reuse this [path] verbatim rather than guess a home directory.
     */
    @Serializable
    data class SchedulerSaveSequenceFile(val result: Boolean = false, val path: String = "") : EkosEvent

    // ── Optical trains ───────────────────────────────────────────────────

    /** `train_get_all` reply — bare array on the wire, wrapped here. Also the
     *  auto-push after every `train_add`/`update`/`delete`/`reset`. */
    @Serializable
    data class Trains(val trains: List<WireTrain>) : EkosEvent

    /**
     * `train_get_profiles` reply (`ProfileSettings::getSettings()`): which train each module uses
     * in the active profile. Keyed by the stringified [ProfileTrainSetting] ordinal, valued by a
     * train ID (resolve against [WireTrain.id]).
     */
    @Serializable
    data class TrainProfiles(val assignments: Map<String, Int>) : EkosEvent

    // ── Scopes, drivers, options ─────────────────────────────────────────

    /** `get_scopes` reply — bare array, wrapped here. Also the auto-push after every `scope_add`/`update`/`delete`. */
    @Serializable
    data class Scopes(val scopes: List<WireScope>) : EkosEvent

    /** `get_drivers` reply — bare array of [WireDriver], wrapped here (`Message::sendDrivers`). */
    @Serializable
    data class Drivers(val drivers: List<WireDriver>) : EkosEvent

    /**
     * `option_get` reply — bare array, one entry per requested option in request order
     * (`message.cpp:1456-1470`). Typed `option_get`, like the request.
     */
    @Serializable
    data class OptionValues(val options: List<WireOption>) : EkosEvent

    // ── Per-module settings (curated subsets) ────────────────────────────
    // Each `*_get_all_settings` reply is a QVariantMap of the module's settings widgets keyed by
    // `objectName()`. Only the fields the app uses are modeled; `ignoreUnknownKeys` drops the rest.

    /** `mount_get_all_settings` reply (`Mount::getAllSettings`, 17 fields). */
    @Serializable
    data class MountSettings(val settings: WireMountSettings) : EkosEvent

    /** `capture_get_all_settings` reply (`Camera::getAllSettings`, 59 fields). */
    @Serializable
    data class CaptureSettings(val settings: WireCaptureSettings) : EkosEvent

    /** `align_get_all_settings` reply (`Align::getAllSettings`, 98 fields). */
    @Serializable
    data class AlignSettings(val settings: WireAlignSettings) : EkosEvent

    /** `guide_get_all_settings` reply (84 fields). */
    @Serializable
    data class GuideSettings(val settings: WireGuideSettings) : EkosEvent

    /** `focus_get_all_settings` reply (84 fields). */
    @Serializable
    data class FocusSettings(val settings: WireFocusSettings) : EkosEvent

    /**
     * `scheduler_get_all_settings` reply (~70 fields), curated to Scheduler-wide policy. Excluded:
     * per-job form fields the controller sends elsewhere (`nameEdit`/`sequenceEdit`/`raBox`/
     * `decBox`/`opticalTrainCombo`/...), weather fields (`kcfg_SchedulerWeather*`,
     * `kcfg_SchedulerSafetyMonitorConnectionString`), align-recovery/greedy toggles
     * (`kcfg_AlignCheck*`, `kcfg_*ResetMountModel*`, `kcfg_RealignAfterCalibrationFailure`,
     * `kcfg_GreedyScheduling`) and `executionSequenceLimit`/`schedulerProfileCombo`/`epochCB`/
     * `positionAngleSpin`/`groupEdit`/`fitsEdit`/`leadFollowerSelectionCB`.
     */
    @Serializable
    data class SchedulerSettings(val settings: WireSchedulerSettings) : EkosEvent

    /**
     * `new_camera_state` push (`Message::sendTemperature`, `message.cpp:438-449`): per-device
     * temperature. `new_temperature` is declared in `commands.h` but never sent.
     */
    @Serializable
    data class NewCameraState(val name: String, val temperature: Double) : EkosEvent

    /**
     * `new_notification` push (`Message::sendEvent`, `message.cpp:2721-2735`), no request handler.
     * Carries every `KSNotification::event()` in KStars (mount faults, module failures, scheduler
     * transitions, INDI messages), gated by `Options::ekosRemoteNotifications()` (default true).
     * Enum ints from `ksnotification.h`: source 0=General 1=INDI 2=Capture 3=Focus 4=Align
     * 5=Mount 6=Guide 7=Observatory 8=Scheduler; severity 0=Debug 1=Info 2=Warn 3=Alert.
     */
    @Serializable
    data class NewNotification(
        val source: Int? = null,
        val severity: Int? = null,
        val message: String? = null,
        val uuid: String? = null,
    ) : EkosEvent

    /** Anything not decoded above — unrecognized `type`, or a shape that failed to parse. */
    data class Raw(val type: String, val payload: JsonElement) : EkosEvent
}

/**
 * `new_polar_state`'s `vector` (`Message::setPolarResults`, `message.cpp:1222-1243`): the
 * correction line Ekos overlays on the align image. `center_x`/`center_y` are its midpoint in
 * native align-frame pixels (matches [MediaHeader.resolution], not the on-screen size);
 * `mag`/`pa` are `QLineF::length()`/`angle()`, whose rotation convention is unverified.
 */
@Serializable
data class WirePolarVector(
    @SerialName("center_x") val centerX: Double? = null,
    @SerialName("center_y") val centerY: Double? = null,
    val mag: Double? = null,
    val pa: Double? = null,
    val error: Double? = null,
    val azError: Double? = null,
    val altError: Double? = null,
)

/**
 * `new_align_state`'s `solution` (`Message::setAlignSolution`, `align_solver.cpp:875-891`), sent
 * on every successful solve. [PA] is the solved position angle; [targetDiff] is the total
 * pointing error (arcsec) compared against `alignAccuracyThreshold`.
 */
@Serializable
data class WireAlignSolution(
    val camera: String? = null,
    val ra: String? = null,
    @SerialName("ra.Hours") val raHours: Double? = null,
    @SerialName("de.Degrees") val deDegrees: Double? = null,
    val de: String? = null,
    val dRA: Double? = null,
    val dDE: Double? = null,
    val dAZ: Double? = null,
    val dAL: Double? = null,
    val targetDiff: Double? = null,
    val pix: Double? = null,
    val PA: Double? = null,
    val fov: String? = null,
)

/**
 * `ProfileInfo::toJson()` (`profileinfo.cpp:135`), trimmed. The legacy per-role fields
 * (`mount`/`ccd`/...) and `remote`/`driver_source` are ignored on decode and omitted on encode.
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
     * The one legacy per-role field kept: a guide camera is just another entry in
     * `drivers["CCDs"]` (e.g. `["Sony DSLR","ZWO CCD"]`), and this is the only way to tell which
     * one is the guider.
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
 * INDI `DRIVER_INTERFACE` bitmask values (`libs/indidevice/basedevice.h`). A device ORs several
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
 * INDI property vector, shared by `device_get`'s `properties[]` and `device_property_get`'s
 * reply/push (see docs/PROTOCOL.md). The kind is identified by which element key is present,
 * not a discriminator, so [EkosEventCodec] sniffs the object and decodes the matching class.
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
 * `SchedulerJob::toJson()`. `state`/`stage` are raw enum ints; see [SchedulerJobStatus] and
 * [SchedulerJobStage].
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

/** `SchedulerJob::JOBStatus` (`schedulertypes.h`). */
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
 * States meaning Ekos has committed to a job: evaluating it, scheduled for a later window, or
 * running it. `EVALUATION`/`SCHEDULED` count as active, not just `BUSY`.
 */
val ACTIVE_SCHEDULER_STATES = setOf(SchedulerJobStatus.EVALUATION, SchedulerJobStatus.SCHEDULED, SchedulerJobStatus.BUSY)

/**
 * Raw `state` int → short UI label. Every known value is listed; `"raw $state"` covers a value a
 * future Ekos might add. `BUSY` reads "Imaging", matching the app header.
 */
val WireSchedulerJob.jobStatusLabel: String get() = when (state) {
    SchedulerJobStatus.IDLE -> "Idle"
    SchedulerJobStatus.EVALUATION -> "Evaluating"
    SchedulerJobStatus.SCHEDULED -> "Scheduled"
    SchedulerJobStatus.BUSY -> "Imaging"
    SchedulerJobStatus.ERROR -> "Error"
    SchedulerJobStatus.ABORTED -> "Aborted"
    SchedulerJobStatus.INVALID -> "Invalid"
    SchedulerJobStatus.COMPLETE -> "Complete"
    else -> "raw $state"
}

/**
 * `SchedulerJob::JOBStage` (`schedulertypes.h`), all 15 values. There is no error/aborted stage;
 * those live on [SchedulerJobStatus].
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
 * `OpticalTrainManager::getOpticalTrains()` entry (`train_get_all`). `profile` and `reducer` are
 * numbers on the wire, and `adaptiveoptics` can be an explicit `null`. A type mismatch here makes
 * every reply fall back to [EkosEvent.Raw] with no visible error.
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
 * `ProfileSettings::Settings` ordinals (`profilesettings.h`), the keys of a
 * [EkosEvent.TrainProfiles] reply. `PRIMARY` is not assignable via `train_set`
 * (`processTrainCommands` accepts only capture/focus/mount/guide/align/darklibrary); it is the
 * fallback new trains default the other modules to.
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

/** The `train_set` module string (`message.cpp`'s `processTrainCommands`) for each [ProfileTrainSetting] — `PRIMARY` has none. */
val MODULE_KEY_BY_TRAIN_SETTING: Map<String, String> = mapOf(
    ProfileTrainSetting.CAPTURE to "capture",
    ProfileTrainSetting.FOCUS to "focus",
    ProfileTrainSetting.MOUNT to "mount",
    ProfileTrainSetting.GUIDE to "guide",
    ProfileTrainSetting.ALIGN to "align",
    ProfileTrainSetting.DARK_LIBRARY to "darklibrary",
)

/**
 * One entry of KStars' INDI driver catalog (`get_drivers`, `DriverInfo::toJson`): every driver
 * described by the XML files under `/usr/share/indi`. [label] is what a profile stores under
 * `drivers[family]`; [family] uses the same labels ("Telescopes", "CCDs", "Filter Wheels",
 * "Focusers", ...). [manufacturer] can be empty.
 */
@Serializable
data class WireDriver(
    val name: String = "",
    val label: String = "",
    val binary: String = "",
    val version: String = "",
    val manufacturer: String = "",
    val family: String = "",
)

/**
 * `OAL::Scope::toJson()` (`get_scopes`/`scope_add`/`scope_update`/`scope_delete`). The Scopes
 * catalog is separate from optical trains; a train's `scope` references one by [name].
 * `focal_length`/`aperture` are doubles on the wire.
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
 * One `option_get` entry (`message.cpp:1464-1467`): `{"name", "value": <any QVariant>}`. [value]
 * stays raw because `Options` properties span every Qt type; callers decode the type they asked for.
 */
@Serializable
data class WireOption(val name: String, val value: JsonElement)

/**
 * Curated `mount_get_all_settings` fields: meridian flip, altitude/HA limits, auto-park. Names
 * are the wire keys. Excluded: `locationSource`/`timeSource`/`useGeographicUpdate`/
 * `useTimeUpdate`/`leftRightCheckObject`/`upDownCheckObject` (desktop-only conveniences).
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
 * Curated Scheduler-wide policy settings (see [EkosEvent.SchedulerSettings] for exclusions).
 * Names are the wire keys (`Scheduler::getAllSettings` widget object names). Radio buttons are
 * reflected as one bool each, exactly one `true` per group.
 *
 * - Startup: [asapConditionR]/[startupTimeConditionR] (radio pair), [startupTimeEdit] (ISO
 *   datetime, used with [startupTimeConditionR]), [kcfg_LeadTime]/[kcfg_PreDawnTime] (minutes).
 * - Constraints: altitude, moon separation/altitude, [schedulerTwilight], [schedulerHorizon];
 *   [kcfg_DawnOffset]/[kcfg_DuskOffset] are signed hours added to astronomical dawn/dusk;
 *   [schedulerTrackStep]/[schedulerFocusStep]/[schedulerAlignStep]/[schedulerGuideStep] are
 *   step defaults for newly created jobs only — existing jobs keep their own pipeline.
 * - Completion (radio group): [schedulerCompleteSequences], [schedulerRepeatSequences]/
 *   [schedulerRepeatSequencesLimit], [schedulerRepeatEverything], [schedulerUntilTerminated],
 *   [schedulerUntil]/[schedulerUntilValue].
 * - Observatory startup/shutdown scripts and preemptive shutdown.
 * - Aborted jobs: the `errorHandling*Button` radio group, [errorHandlingRescheduleErrorsCB],
 *   [errorHandlingStrategyDelay] (minutes).
 */
@Serializable
data class WireSchedulerSettings(
    // Startup condition
    val asapConditionR: Boolean = true,
    val startupTimeConditionR: Boolean = false,
    val startupTimeEdit: String = "",
    val kcfg_LeadTime: Double = 5.0,
    val kcfg_PreDawnTime: Double = 30.0,
    // Constraints
    val schedulerAltitude: Boolean = false,
    val schedulerAltitudeValue: Double = 0.0,
    val schedulerMoonSeparation: Boolean = false,
    val schedulerMoonSeparationValue: Double = 0.0,
    val schedulerMoonAltitude: Boolean = false,
    val schedulerMoonAltitudeMaxValue: Double = 90.0,
    val schedulerTwilight: Boolean = false,
    val schedulerHorizon: Boolean = false,
    val kcfg_DawnOffset: Double = 0.0,
    val kcfg_DuskOffset: Double = 0.0,
    val schedulerTrackStep: Boolean = true,
    val schedulerFocusStep: Boolean = true,
    val schedulerAlignStep: Boolean = true,
    val schedulerGuideStep: Boolean = true,
    /**
     * `Options::forceAlignmentBeforeJob()` (default false): align before starting or restarting
     * each job. Still gated by the job's own Align step (`schedulerprocess.cpp:306,364,417`); it
     * never adds alignment to a job without one.
     */
    val kcfg_ForceAlignmentBeforeJob: Boolean = false,
    // Completion condition
    val schedulerCompleteSequences: Boolean = true,
    val schedulerRepeatSequences: Boolean = false,
    val schedulerRepeatSequencesLimit: Int = 1,
    val schedulerRepeatEverything: Boolean = false,
    val schedulerUntilTerminated: Boolean = false,
    val schedulerUntil: Boolean = false,
    val schedulerUntilValue: String = "",
    // Observatory startup/shutdown procedure
    val schedulerStartupEnabled: Boolean = false,
    val schedulerPreStartupScript: String = "",
    val schedulerPostStartupScript: String = "",
    val schedulerShutdownEnabled: Boolean = false,
    val schedulerPreShutdownScript: String = "",
    val schedulerPostShutdownScript: String = "",
    val kcfg_PreemptiveShutdown: Boolean = false,
    val kcfg_PreemptiveShutdownTime: Double = 2.0,
    val kcfg_StopEkosAfterShutdown: Boolean = false,
    val kcfg_ShutdownScriptTerminatesINDI: Boolean = false,
    // Aborted-job handling
    val errorHandlingDontRestartButton: Boolean = true,
    val errorHandlingRestartImmediatelyButton: Boolean = false,
    val errorHandlingRestartQueueButton: Boolean = false,
    val errorHandlingRescheduleErrorsCB: Boolean = false,
    val errorHandlingStrategyDelay: Int = 0,
    /**
     * When true, Ekos resumes a partly completed queued job after its own restart. This is why a
     * stale job can start again on its own.
     */
    val kcfg_RememberJobProgress: Boolean = true,
)

/**
 * Curated `capture_get_all_settings` fields. Names are the wire keys (`Camera::getAllSettings`
 * widget object names).
 *
 * `capture_preview` has no exposure/gain/bin/filter parameters: it shoots with whatever the
 * Capture module currently has loaded, i.e. `captureExposureN`/`captureGainN`/`captureBinHN`/
 * `captureBinVN`/[FilterPosCombo]. Sequence blocks only apply their own values when their job
 * runs. Likewise [cameraTemperatureN] is the cooler setpoint, distinct from the live
 * `CCD_TEMPERATURE` reading.
 */
@Serializable
data class WireCaptureSettings(
    val fileDirectoryT: String = "",
    /**
     * Save-path template appended to [fileDirectoryT] (`camera_config.cpp:1076`), e.g.
     * `/%t/%T/%F/%t_%T_%F_...` where `%t`=target, `%T`=frame type, `%F`=filter.
     */
    val placeholderFormatT: String = "",
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
    /** Ekos's capture frame (subframe) in unbinned sensor pixels, applied to the camera's
     *  CCD_FRAME before every exposure. Null when absent. A new optical train can inherit another
     *  camera's frame here (e.g. the CCD Simulator's 1280×1024) and silently crop every capture. */
    val captureFrameXN: Int? = null,
    val captureFrameYN: Int? = null,
    val captureFrameWN: Int? = null,
    val captureFrameHN: Int? = null,
    val cameraTemperatureN: Double = -10.0,
    /** Capture's "enforce temperature" checkbox: when on, every capture (previews included) waits
     *  in "Setting Temperature" until the sensor is within MaxTemperatureDiff of
     *  [cameraTemperatureN] — a set point the cooler can't reach holds previews indefinitely. */
    val cameraTemperatureS: Boolean = false,
    /**
     * "Refocus every N minutes" (`limits.ui`, `sequencequeue.cpp:245`). Global, not per job:
     * set through `capture_set_all_settings` with no job index (`message.cpp:542,546`).
     */
    val enforceRefocusEveryN: Boolean = true,
    val refocusEveryN: Int = 60,
    /** "Refocus if ΔT° >" trigger, same mechanism as [enforceRefocusEveryN]. */
    val enforceAutofocusOnTemperature: Boolean = false,
    val maxFocusTemperatureDelta: Double = 1.0,
    /**
     * The Capture tab's current filter (`camera.ui` `FilterPosCombo`, e.g. `"L"`). Named exactly
     * like the wire key, capital included, so it stays greppable.
     */
    val FilterPosCombo: String = "",
)

/**
 * Curated `focus_get_all_settings` fields. Names are the wire keys.
 *
 * [absTicksSpin] is the position the Focus tab shows, and can differ from the focuser's raw INDI
 * `ABS_FOCUS_POSITION` (by ~70 steps on the rig). The app seeds its focuser position from it on
 * connect so it matches Ekos. `focusAlgorithm` and the combo-box fields are strings (combo text),
 * not enum indices.
 */
@Serializable
data class WireFocusSettings(
    val absTicksSpin: Int = 18422,
    val focusExposure: Double = 2.0,
    val focusGain: Double = 99.0,
    val focusFilter: String = "L",
    val focusBacklash: Int = 0,
    val focusAlgorithm: String = "Linear 1 Pass",
    /**
     * "Initial Step Size" (`focusTicks`). `focus_in`/`focus_out` move by `steps`, or by this
     * amount when `steps <= 0`; the app sends `steps=0` so a tap moves like Ekos's own buttons.
     */
    val focusTicks: Int = 200,
    /**
     * Max travel (ticks) of one autofocus run's search from its start position (`focus.cpp`
     * `searchMin`/`searchMax`). A policy limit, not the focuser's hardware range.
     */
    val focusMaxTravel: Int = 20000,
    /**
     * "Out Step Multiple": multiplies [focusTicks] for the Classic walk's opening outward move
     * (`setupLinearFocuser`). Unused by Fixed Steps/CFZ Shuffle; see [focusNumSteps].
     */
    val focusOutSteps: Double = 5.0,
    /** "Number Steps": total sample count for the Fixed Steps/CFZ Shuffle walks (see [focusWalk]). */
    val focusNumSteps: Int = 11,
    /** `"Classic"` (Linear only), `"Fixed Steps"` or `"CFZ Shuffle"`. */
    val focusWalk: String = "Fixed Steps",
    /**
     * "AF Overscan": backlash ticks added to every outward move the Focus module makes, autofocus
     * or manual jog, then corrected back (`adjustLinearPosition`/`autoFocusProcessPositionChange`).
     * This turns a `focus_out` jog into an out-then-back-in double move. Stock default 100.
     */
    val focusAFOverscan: Int = 100,
    /** Seconds between [focusAFOverscan]'s outward extension and its correction move back in. */
    val focusOverscanDelay: Double = 0.0,
    /** Seconds to wait for the focuser to report motion complete before declaring it stuck. */
    val focusMotionTimeout: Int = 30,
    /** Seconds to wait for a focus-frame capture before timing out. */
    val focusCaptureTimeout: Int = 30,
    /** Seconds to wait after a focuser move before the next autofocus frame. */
    val focusSettleTime: Double = 1.0,
    /** `"Gradient"`, `"Centroid"`, `"Threshold"`, `"SEP"` or `"Bahtinov"`. */
    val focusDetection: String = "SEP",
    /** `"Quadratic"`, `"Hyperbola"`, `"Parabola"` or `"Gaussian"`. */
    val focusCurveFit: String = "Hyperbola",
    /** `"HFR"`, `"HFR Adj"`, `"FWHM"`, `"# Stars"`, `"Fourier"`, `"StdDev"`, `"Sobel"`,
     *  `"Laplassian"` or `"Canny"`. */
    val focusStarMeasure: String = "HFR",
    /** Percent change in the star measure considered in tolerance once a fit is found. */
    val focusTolerance: Double = 1.0,
    /** Minimum curve-fit R² to accept a Linear 1 Pass result instead of aborting/retrying. */
    val focusR2Limit: Double = 0.8,
    /** Frames captured and averaged per focus measurement point. */
    val focusFramesCount: Int = 1,
    /** Focus-camera binning, a string like `guideBinning`/`alignBinning` (e.g. `"1x1"`). */
    val focusBinning: String = "1x1",
)

/** `guide.ui` `guideSquareSize` combo items (px); Ekos sets the combo by text. */
val GUIDE_BOX_SIZES = listOf("8", "16", "32", "64", "128")

/**
 * Curated `guide_get_all_settings` fields. Names are the wire keys; defaults from `kstars.kcfg`.
 * Like `capture_preview`, `guide_capture` has no parameters and shoots with the Guide module's
 * loaded exposure/gain/binning. `guideBinning` is a string (`"1x1"`).
 */
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
    // guide.ui checkboxes:
    val guideDarkFrame: Boolean = false,
    val guideSubframe: Boolean = false,
    val guideAutoStar: Boolean = false,
    /** Tracking box size, px — `guide.ui` QComboBox, its text is one of [GUIDE_BOX_SIZES]. */
    val guideSquareSize: String = "16",
    // opsdither.ui (all QSpinBox — ints):
    val kcfg_DitherSettle: Int = 0,
    val kcfg_DitherTimeout: Int = 45,
    val kcfg_DitherMaxIterations: Int = 10,
    val kcfg_DitherFailAbortsAutoGuide: Boolean = false,
    // opscalibration.ui:
    val kcfg_CalibrationPulseDuration: Int = 1000,
    val kcfg_CalibrationMaxMove: Int = 15,
    val kcfg_TwoAxisEnabled: Boolean = true,
    val kcfg_GuideCalibrationBacklash: Boolean = true,
    val kcfg_ResetGuideCalibration: Boolean = true,
    val kcfg_ReverseDecOnPierSideChange: Boolean = false,
    // opsguide.ui:
    val kcfg_GuideLostStarTimeout: Int = 60,
)

/**
 * Curated `align_get_all_settings` fields. Names are the wire keys. `alignBinning` is a combo
 * string (`"1x1"`), not an Int like a sequence block's binning. Astrometry index-file and solver
 * internals are excluded.
 */
@Serializable
data class WireAlignSettings(
    val alignExposure: Double = 3.0,
    val alignGain: Double = 99.0,
    val alignFilter: String = "L",
    val alignBinning: String = "1x1",
    val alignAccuracyThreshold: Double = 30.0,
    /**
     * `align.ui:739`, see `Align::checkFilter` (`align_devices.cpp:583/590`). When true, solves use
     * whatever filter the wheel is on and `alignFilter` is ignored. When false, every solve
     * switches to `alignFilter`, even mid-sequence.
     */
    val alignUseCurrentFilter: Boolean = false,
    /**
     * Solver-action radio group, what Ekos does after a successful `align_solve`: `nothingR`
     * (report only, default), `slewR` (re-slew onto the solved position, i.e. goto & center),
     * `syncR` (sync the mount model). Modeled so goto-and-center can restore the user's choice.
     */
    val nothingR: Boolean = true,
    val slewR: Boolean = false,
    val syncR: Boolean = false,
    /**
     * `kcfg_AstrometryRotatorThreshold`, arcminutes (`kstars.kcfg` default 30). Readable and
     * writable through this command, unlike `rotator_control` (a `QGroupBox` the reflection
     * misses). The rotator push sends the same threshold already converted to degrees
     * (`align_goto.cpp`: `/ 60.0`).
     */
    val kcfg_AstrometryRotatorThreshold: Double = 30.0,
    /**
     * Polar Alignment Assistant settings (`polaralignmentassistant.ui`, a child widget of Align, so
     * the same reflection covers them). Null until a reply carries them. [pAHDirection]
     * "West"/"East"; [pAHRotation] 15..60 step 15; [pAHMountSpeed] one of the mount's
     * `TELESCOPE_SLEW_RATE` labels; [pAHExposure] refresh exposure, 1..30 s; [pAHRefreshAlgorithm]
     * "Plate Solve"/"Move Star"/"Move Star & Calc Error" (combos are set by text). Set them here,
     * not via `polar_set_algorithm`: that handler looks up a stale widget name
     * (`PAHRefreshAlgorithmCombo`) and does nothing.
     */
    val pAHDirection: String? = null,
    val pAHRotation: Int? = null,
    val pAHMountSpeed: String? = null,
    val pAHManualSlew: Boolean? = null,
    val pAHExposure: Double? = null,
    val pAHRefreshAlgorithm: String? = null,
)
