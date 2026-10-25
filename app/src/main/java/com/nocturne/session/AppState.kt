package com.nocturne.session

import com.nocturne.data.FrameEntity
import com.nocturne.data.FrameSource
import com.nocturne.protocol.ACTIVE_SCHEDULER_STATES
import com.nocturne.protocol.DeviceRole
import com.nocturne.protocol.WireAlignSettings
import com.nocturne.protocol.WireAlignSolution
import com.nocturne.protocol.WireCaptureSettings
import com.nocturne.protocol.WireFocusSettings
import com.nocturne.protocol.WireGuideSettings
import com.nocturne.protocol.WireMountSettings
import com.nocturne.protocol.WireRiseset
import com.nocturne.protocol.SchedulerJobStatus
import com.nocturne.protocol.WireSchedulerJob
import com.nocturne.protocol.WireSchedulerSettings
import com.nocturne.protocol.WireTrain
import com.nocturne.protocol.WirePolarVector
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin

/** Which detail sheet is open. */
enum class SheetType {
    GUIDE, FOCUS, ALERTS, PREFS, SETUP, PA, DEVICE, SUMMARY,
    OPTICAL_TRAIN, SCOPES, MODULE_ASSIGNMENTS, MAINTENANCE, MOUNT_SETTINGS, CAMERA_SETTINGS, ALIGN_SETTINGS,
    GUIDE_SETTINGS, FOCUS_SETTINGS, SCHEDULER_SETTINGS,
}

/**
 * Progress of a [SessionController.rebootRig] request. Reboot goes over a separate HTTP daemon
 * (`pi-tools/reboot-daemon/`); the EkosRemote wire has no OS-level reboot command.
 */
enum class RigRebootState { IDLE, SENDING, SENT, FAILED }

/**
 * Offline sky atlas ([com.nocturne.hips.SkyAtlas]): DSS2 colour HiPS tiles orders 0..5 copied
 * from the rig. [completeOrder] is the deepest order fully on the phone (-1: none), which is
 * what framing can render from; [rigTiles] is null until the rig has been asked.
 */
data class SkyAtlasStatus(
    val localTiles: Int = 0,
    val completeOrder: Int = -1,
    val totalTiles: Int = com.nocturne.hips.SkyAtlas.TOTAL_TILES,
    val rigTiles: Int? = null,
    val copying: Boolean = false,
    val copyDone: Int = 0,
    /** Tiles the rig didn't have yet at the end of the last copy (its own mirror still running). */
    val lastMissing: Int? = null,
    val error: String? = null,
)

/**
 * `new_guide_state.status` strings for which Ekos's Guide module is idle: the same group
 * `Guide::checkCamera` (guide.cpp) switches on. Every other `guideStates` value (`ekos.h`) means busy.
 */
val GUIDE_IDLE_STATES = setOf("Idle", "Aborted", "Connected", "Disconnected", "Calibration error")

/**
 * `new_focus_state.status` strings for which no autofocus run is going (`ekos.h` `focusStates`).
 * "Framing" is the plain focus loop, not an autofocus run, so it counts as idle.
 */
val FOCUS_IDLE_STATES = setOf("Idle", "Complete", "Failed", "Aborted", "Framing")

/**
 * `new_polar_state.stage` strings: `PolarAlignmentAssistant::PAHStages`
 * (polaralignmentassistant.cpp:26-43, English locale), in run order.
 */
const val PAH_STAGE_IDLE = "Idle"
const val PAH_STAGE_SELECT_STAR = "Select Star"
const val PAH_STAGE_REFRESHING = "Refreshing"
val PAH_ROTATE_STAGES = setOf("First Rotation", "Second Rotation")
/** Measurement stages → which of the 3 images it belongs to. */
val PAH_MEASURE_IMAGE = mapOf(
    "First Capture" to 1, "First Solve" to 1, "Finding CP" to 1, "First Rotation" to 1, "First Settle" to 1,
    "Second Capture" to 2, "Second Solve" to 2, "Second Rotation" to 2, "Second Settle" to 2,
    "Third Capture" to 3, "Third Solve" to 3,
)
val PAH_DIRECTIONS = listOf("West", "East")
val PAH_ROTATIONS = listOf(15, 30, 45, 60)
val PAH_REFRESH_ALGORITHMS = listOf("Plate Solve", "Move Star", "Move Star & Calc Error")

/** Which meridian-flip action is awaiting confirmation. */
enum class FlipConfirm { DEFER, NOW }

/** Frames tab's top-level category picker; see [AppState.frameCategory]. */
enum class FrameCategory { PREVIEW, PLAN }

/** The whole UI state: local UI fields plus the `wire*` mirrors of what Ekos pushes. */
data class AppState(
    val t: Int = 0,
    val sheet: SheetType? = null,
    /** Session tab's sub preview expanded to a full-screen overlay. */
    val subPreviewExpanded: Boolean = false,
    /** Frames tab: id of the frame expanded to a full-screen overlay, if any. */
    val expandedFrameId: String? = null,
    /**
     * Frames tab navigation (local only). Null [frameCategory] = Preview/Plan picker. Under
     * [FrameCategory.PLAN], null [frameTarget] shows the target list, non-null that target's frames.
     */
    val frameCategory: FrameCategory? = null,
    val frameTarget: String? = null,
    /** Cumulative seconds the meridian flip countdown has been pushed back via DEFER. */
    val flipDeferSec: Int = 0,
    /** Flip/defer action awaiting user confirmation on the Session tab. */
    val pendingFlipConfirm: FlipConfirm? = null,
    val jobs: List<SequenceJob> = emptyList(),
    /** Which job's block editor is drilled into on the Sequence tab; null = job-list view. */
    val activeJobId: String? = null,
    /** Monotonic job id counter — survives removals, unlike jobs.size + 1. */
    val jobSeq: Int = 1,
    /**
     * Last job that was started: the Session tab's fallback when nothing is running, so pausing
     * a job doesn't jump to another one by list position. Only [contractJob] should read this.
     */
    val lastActiveJobId: String? = null,
    /** Which block card is expanded — global scalar is fine, only one job is ever drilled into at a time. */
    val openBlockId: String? = null,
    /** The job [endSession] stopped, pending a Back-to-session/Next-job/Finish choice on the Summary sheet. */
    val lastEndedJobId: String? = null,
    val mountParked: Boolean = false,
    /** Local default only; a connected rig reads [mountTrackingOn] (INDI `TELESCOPE_TRACK_STATE`). */
    val mountTracking: Boolean? = null,
    /**
     * Optimistic "routine running" flags for Autofocus/Guide/Polar Align, flipped on tap. The
     * status/stage push overrides them (see [FOCUS_IDLE_STATES], [GUIDE_IDLE_STATES],
     * [PAH_STAGE_IDLE]). [guiding] means "Guide module busy" (guiding, calibrating, looping...),
     * like Ekos's own Stop button being enabled.
     */
    val focusRunning: Boolean = false,
    val guiding: Boolean = false,
    val polarRunning: Boolean = false,
    val deviceKey: String = "mount",
    /** Sheet to return to when the device sheet closes, e.g. SETUP when opened from the rig wizard's device list. */
    val deviceOrigin: SheetType? = null,
    val focPos: Int? = null,
    val coolTarget: Double? = null,
    val coolNow: Double? = null,
    val snappedMain: Boolean = false,
    val snappedGuide: Boolean = false,
    val rate: Int? = null,
    val slewDir: String? = null,
    val paStep: Int = 0,
    val paAlt: Double? = null,
    val paAz: Double? = null,
    val paRate: Int = 1,
    val query: String = "",
    val chips: List<Int> = listOf(0),
    /** `astro_search_objects`' `type`: a `SkyObject::TYPE` value, one of [SEARCH_TYPES].
     *  Null = no type: nothing listed until the user types, then names from every catalog. */
    val searchType: Int? = SEARCH_TYPES.first().skyType,
    /** `astro_search_objects`' `maxMagnitude`; the server only honours it for DSO types. */
    val searchMaxMag: Int = 10,
    /** Empty = nothing picked yet; Plan tab shows a "pick a target" hint instead of the target card. */
    val targetId: String = "",
    /**
     * The target as it was when [targetId] was picked, so it survives the next search replacing
     * [wireSearchResults]; goto, add-to-sequence and the target card need its coords. See [findTarget].
     */
    val targetSnapshot: Target? = null,
    /** The single user catalogue; only its name and its targets are editable. */
    val userCatalogName: String = "My targets",
    val userTargets: List<Target> = emptyList(),
    /** Monotonic id counter for custom targets — survives removals. */
    val userTargetSeq: Int = 1,
    val editingUserTargetId: String? = null,
    val addingUserTarget: Boolean = false,
    /**
     * Scopes catalog. Ekos keeps telescopes/lenses separate from profiles and trains
     * (`get_scopes`/`scope_add`, docs/PROTOCOL.md); a train's Scope role references one by name.
     */
    val scopes: List<ScopeDef> = emptyList(),
    /** Monotonic id counter for user-added scopes; survives removals. */
    val scopeSeq: Int = 1,
    val editingScopeId: String? = null,
    val addingScope: Boolean = false,
    val profileName: String = "",
    val ekosRunning: Boolean = false,
    val activeProfile: String? = null,
    val profiles: List<RigProfile> = emptyList(),
    val selectedProfile: String? = null,
    val setupEditingName: String? = null,
    val rotatorAngle: Double = 0.0,
    val domeOpen: Boolean = false,
    /** INDI properties per device name, from `device_get` and live property pushes. */
    val indiProps: Map<String, List<IndiProperty>> = emptyMap(),
    /**
     * Ekos options via `option_get`/`option_set`: the master gate for the `new_notification`
     * stream, and whether Ekos plays a sound on the Pi's speaker for one. Null until
     * `ensurePrefsLoaded` gets a reply.
     */
    val wireEkosRemoteNotifications: Boolean? = null,
    val wireEkosRemoteSound: Boolean? = null,
    /**
     * Persisted capture frames, newest first, from [FrameRepository] via
     * [com.nocturne.session.EkosRemoteController]'s `frameRepo.observeAll()`. Each row's
     * [FrameEntity.keep] is its keep/cut state.
     */
    val frameRows: List<FrameEntity> = emptyList(),
    val devOff: Set<String> = DEVICES.map { it.key }.toSet(),
    /** Which catalog entry is assigned per device category — key -> chosen name from that [Device.catalog]. */
    val selectedDeviceNames: Map<String, String> = DEVICES.associate { it.key to "None" },
    val primaryTrain: TrainAssignment = TrainAssignment(),
    val secondaryTrain: TrainAssignment = TrainAssignment(),
    /** Focus sheet: bookkeeping from the last "Run autofocus now" tap; feeds TEMP Δ. */
    val focusLastAfAt: Int = 0,
    val focusTempAtLastAf: Double? = null,
    val quietHoursEnabled: Boolean = true,
    /** Bench sheet mount pointing. `new_mount_state` has no alt/az, so these keep their defaults. */
    val mountAlt: Double? = null,
    val mountAz: Double? = null,
    /** Cleared whenever the mount slews — a solve is only valid until the mount moves again. */
    val mountSolved: Boolean = false,
    /**
     * Wire-mirror fields: raw `new_*_state` payload fields, set only by [EkosRemoteController].
     * Null until the corresponding push has arrived at least once.
     */
    val wireCaptureStatus: String? = null,
    val wireMountStatus: String? = null,
    val wireMountTarget: String? = null,
    val wireMountSlewRate: Int? = null,
    val wireMountPierSide: Int? = null,
    val wireFocusStatus: String? = null,
    /** Per-frame focus measurements (position, HFR), oldest first, bounded at
     *  [MAX_FOCUS_SAMPLES]; cleared when an autofocus run starts. See [EkosEvent.NewFocusState]. */
    val focusSamples: List<FocusSample> = emptyList(),
    /** Ekos's own V-curve plot title (curve fit, R², scan progress); cleared with [focusSamples]. */
    val focusCurveTitle: String? = null,
    val wireGuideStatus: String? = null,
    /** Per-frame guide error/pulse, oldest first, bounded at [MAX_GUIDE_SAMPLES]; cleared when a
     *  new guide run starts (status idle → busy). See [EkosEvent.NewGuideState]. */
    val guideSamples: List<GuideSample> = emptyList(),
    /** Ekos's running RMS (arcsec) per axis — same numbers as the desktop's RMS label. */
    val guideRmsRA: Double? = null,
    val guideRmsDE: Double? = null,
    /** Latest SNR / star count; null when the detector doesn't report them (wire sends 0). */
    val guideSnr: Double? = null,
    val guideStarCount: Int? = null,
    /** Vocabulary (`ekos.h`): `Idle`/`Complete`/`Failed`/`Aborted`/`In Progress`/
     * `Successful`/`Syncing`/`Slewing`/`Rotating`/`Suspended`. */
    val wireAlignStatus: String? = null,
    /** Latest successful solve. The primary source for the current camera angle, unlike the
     * rotator-gated [wireRotatorCurrentPA]; see [com.nocturne.protocol.WireAlignSolution]. */
    val wireAlignSolution: WireAlignSolution? = null,
    /**
     * Pointing-accuracy trend: each solve's `targetDiff` (total pointing error, arcsec),
     * oldest first, bounded at [MAX_ACCURACY_HISTORY].
     */
    val alignAccuracyHistory: List<Double> = emptyList(),
    /** `new_notification` stream, newest first, capped at [MAX_NOTIFICATIONS] (an event stream,
     * not a current-value push). Deduplicated by `uuid` in case a reconnect replays events. */
    val wireNotifications: List<Alert> = emptyList(),
    val wirePolarStage: String? = null,
    /** `new_polar_state`'s `enabled`/`message` fields. */
    val wirePolarEnabled: Boolean? = null,
    val wirePolarMessage: String? = null,
    /**
     * `new_polar_state`'s correction vector and errors. The vector's `pa`/`mag` aren't drawn as an
     * arrow: Qt's `QLineF::angle()` convention is unverified (see [WirePolarVector]).
     * [wirePolarUpdatedError]/Az/Alt come from a separate push (`setUpdatedErrors`, no `vector`
     * wrapper) that fires after a correction slew.
     */
    val wirePolarVector: WirePolarVector? = null,
    val wirePolarUpdatedError: Double? = null,
    val wirePolarUpdatedAzError: Double? = null,
    val wirePolarUpdatedAltError: Double? = null,
    /**
     * `align_manual_rotator_status`: Ekos's current-vs-target camera position angle, for a manual
     * turn or a rotator device. Only pushed once [rotatorAutoControl] is on and a solve has run
     * (`align_goto.cpp` `checkIfRotationRequired()`); null until then.
     */
    val wireRotatorCurrentPA: Double? = null,
    val wireRotatorTargetPA: Double? = null,
    val wireRotatorThreshold: Double? = null,
    /** Optimistic mirror of `align_set_astrometry_settings`' `rotator_control`: the master gate
     * for the rotator feature, needed with or without a rotator device. The device only decides
     * whether Ekos drives it or reports a manual-turn diff. */
    val rotatorAutoControl: Boolean = false,
    /**
     * Server value of [rotatorAutoControl]: `option_get`'s `astrometryUseRotator`, fetched on
     * connect (`ensurePrefsLoaded`). Authoritative once it arrives and seeds [rotatorAutoControl];
     * the Align commands have no getter for it.
     */
    val wireAstrometryUseRotator: Boolean? = null,
    /** `get_devices`, translated. Null until the first reply; the default [DEVICES] catalog shows until then. */
    val wireDevices: List<LiveDevice>? = null,
    /**
     * Each camera's `CCD_INFO` (sensor size, pixel size) by device name, for the Framing card's
     * pixel scale/FOV. Parsed directly because the generic decode
     * (`WireProperty.Number.toIndiProperty`) keeps only the first element of a vector.
     */
    val wireCcdInfoByDevice: Map<String, CcdInfo> = emptyMap(),
    /** Each camera's gain range: `CCD_GAIN`, or the "gain" element of `CCD_CONTROLS`, same lookup
     *  as Ekos's `Camera::getGainMinMaxStep`. See [gainSpecialValue]. */
    val wireGainRangeByDevice: Map<String, GainRange> = emptyMap(),
    /** `train_get_all`, translated; `OpticalTrainCard` prefers it over [trainRolePool]. */
    val wireTrains: List<WireTrain>? = null,
    /**
     * Train name per Ekos module (`train_get_profiles`/`train_set`, stored per profile
     * server-side). Keys are `train_set`'s module strings: `"capture"`, `"focus"`, `"mount"`,
     * `"guide"`, `"align"`, `"darklibrary"`. Null until the first reply.
     */
    val moduleTrainAssignments: Map<String, String>? = null,
    /**
     * Every driver label saved in any profile on the Pi, unioned from `get_profiles` and keyed by
     * [CATEGORY_TO_DRIVER_FAMILY] families; see [realDeviceOptions]. Null until the first reply.
     */
    val wireKnownDrivers: Map<String, List<String>>? = null,
    /** Every INDI driver installed on the Pi (`get_drivers`); see [installedDriverOptions]. */
    val wireDriverCatalog: List<com.nocturne.protocol.WireDriver>? = null,
    /**
     * `get_scopes`, translated (message.cpp:204/1469/1474/1479). [ScopesCard]/[trainRolePool]
     * prefer it over [scopes]. Null until the first reply (sent with `get_profiles`/`train_get_all`).
     */
    val wireScopes: List<ScopeDef>? = null,
    /**
     * Name of a refused `profile_delete`. Ekos silently refuses to delete `"Simulators"` or the
     * active profile; [EkosRemoteController] detects it by diffing the next `get_profiles` push.
     */
    val profileDeleteRefused: String? = null,
    /**
     * Result of `astro_search_objects` → `astro_get_objects_info`/`astro_get_objects_riseset`,
     * shown by `PlanScreen`. Null = no reply yet (a search runs once Ekos is online).
     */
    val wireSearchResults: List<Target>? = null,
    /**
     * Names the last search matched before the [SEARCH_RESULT_CAP] cut, so the list can tell
     * "still resolving" from "no matches" and say when it is truncated. Null = no reply yet.
     */
    val wireSearchMatchCount: Int? = null,
    /** `scheduler_get_jobs`, translated; cross-referenced for progress, see [SequenceJob.synced]. */
    val wireSchedulerJobs: List<WireSchedulerJob>? = null,
    /**
     * Whether the Scheduler is on. Set only from the `new_scheduler_state` push, never
     * optimistically from a tap: an optimistic flip races the push and can show the wrong state.
     */
    val schedulerRunning: Boolean = false,
    /**
     * Target name of a refused "Push to Ekos". Ekos refuses duplicate job names
     * (`"A job with name '...' already exists"`), so [EkosRemoteController.pushJob] checks
     * [wireSchedulerJobs] before sending.
     */
    val jobPushRefused: String? = null,
    /**
     * Target name of a "Remove from Ekos" refused before sending: Ekos refuses
     * `scheduler_remove_jobs` while the Scheduler is running (`"Cannot delete currently running
     * job"`). Gate on [schedulerRunning], not the job's `state`: a freshly pushed job can read
     * `SCHEDULED` while the Scheduler is idle, and removing it then is fine.
     */
    val jobRemoveRefused: String? = null,
    /**
     * Latest `/media/ekos` binary frame per module, routed by the header's `uuid` tag
     * ([com.nocturne.protocol.frameType]). No history; [FramesScreen]'s grid is Room-backed.
     */
    val latestCaptureFrame: com.nocturne.protocol.MediaFrame? = null,
    val latestAlignFrame: com.nocturne.protocol.MediaFrame? = null,
    val latestFocusFrame: com.nocturne.protocol.MediaFrame? = null,
    val latestGuideFrame: com.nocturne.protocol.MediaFrame? = null,
    /** `mount_get_all_settings` (curated subset). Null until the first reply. */
    val wireMountSettings: WireMountSettings? = null,
    /** `capture_get_all_settings` (curated subset), fetched on connect. Null until the first reply. */
    val wireCaptureSettings: WireCaptureSettings? = null,
    /** `align_get_all_settings` (curated subset), fetched on connect. Null until the first reply. */
    val wireAlignSettings: WireAlignSettings? = null,
    /**
     * `guide_get_all_settings` (partial, see [WireGuideSettings]), fetched on connect. Gates
     * Bench "Snap guide"'s exposure/gain/binning controls.
     */
    val wireGuideSettings: WireGuideSettings? = null,
    /**
     * `focus_get_all_settings` (partial, see [WireFocusSettings]), fetched on connect. Its
     * `absTicksSpin` seeds [focPos] once on arrival; kept so [benchFocPos] can tell "seeded"
     * from "still on the raw-INDI fallback".
     */
    val wireFocusSettings: WireFocusSettings? = null,
    /** `scheduler_get_all_settings` (curated subset, see [WireSchedulerSettings]), fetched on connect. */
    val wireSchedulerSettings: WireSchedulerSettings? = null,
    /**
     * `astro_get_almanac`'s `Dusk`/`Dawn`: signed fraction-of-day offsets from local midnight
     * (sign convention in [EkosEvent.AstroAlmanac]). Fetched on connect; drives the night arc.
     */
    val wireDusk: Double? = null,
    val wireDawn: Double? = null,
    /** Moon illuminated fraction, `astro_get_almanac`'s `MoonIllum`. */
    val wireMoonIllum: Double? = null,
    /** `astro_get_location`'s `tz`: signed UTC hour offset, needed to turn [wireDusk]/[wireDawn] into instants. */
    val wireSiteTz: Double? = null,
    /**
     * `astro_get_objects_riseset` for the framed target, which may be a custom target that
     * search results don't cover. Fetched on target change
     * ([EkosRemoteController.ensureTargetRiseset]); check `.name` matches before trusting it.
     */
    val wireTargetRiseset: WireRiseset? = null,
    /**
     * Moon altitude curve. "Moon" rides along in the [wireTargetRiseset] request; kept across
     * target switches since it doesn't depend on the target.
     */
    val wireMoonRiseset: WireRiseset? = null,
    /**
     * DSS reference image centred on the framed target, for the Framing card. Comes from the
     * offline atlas or CDS `hips2fits` (`transport/ReferenceImageClient.kt`, the app's only
     * internet call that isn't to the Pi). [referenceImageForTargetId] guards against a stale image
     * after a target switch; null shows a placeholder.
     */
    val referenceImageJpeg: ByteArray? = null,
    /** Field of view (degrees, square) the cutout was rendered at; sizes the camera's FOV box on it. */
    val referenceImageFovDeg: Double? = null,
    val referenceImageForTargetId: String? = null,
    /** Where [referenceImageJpeg] came from: the offline sky atlas on the phone, or CDS online. */
    val referenceImageOffline: Boolean = false,
    /** No atlas tiles for this field and the online cutout was skipped (rig/metered network). */
    val referenceImageOnlineSkipped: Boolean = false,
    /** Offline sky atlas copied from the rig (see [com.nocturne.hips.SkyAtlas]). */
    val skyAtlas: SkyAtlasStatus = SkyAtlasStatus(),
    /** Reboot daemon's port on the Pi, separate from the EkosRemote port (`pi-tools/reboot-daemon/`). */
    val rigRebootPort: Int = 9001,
    val rigRebootState: RigRebootState = RigRebootState.IDLE,
    val rigRebootError: String? = null,
    /** [SessionController.syncTimeToRig]'s progress. Separate from [rigRebootState] (same daemon)
     *  so one action's result doesn't show under the other button. */
    val piTimeSyncState: RigRebootState = RigRebootState.IDLE,
    val piTimeSyncError: String? = null,
)

/** One `get_devices` entry, decoded to the roles its `interface` bitmask ORs together. */
data class LiveDevice(val name: String, val connected: Boolean, val roles: Set<DeviceRole>)

/**
 * What the main camera will actually capture: Ekos's capture frame and binning against the
 * sensor's full size ([CcdInfo]). [cropped] is true when the frame is smaller than the sensor.
 */
data class PrimaryImageSize(
    val frameW: Int, val frameH: Int, val x: Int, val y: Int,
    val binH: Int, val binV: Int,
    val sensorW: Int?, val sensorH: Int?,
) {
    val outW get() = frameW / binH.coerceAtLeast(1)
    val outH get() = frameH / binV.coerceAtLeast(1)
    val cropped get() = sensorW != null && sensorH != null && (frameW < sensorW || frameH < sensorH)
}

fun AppState.primaryImageSize(): PrimaryImageSize? {
    val cs = wireCaptureSettings ?: return null
    val w = cs.captureFrameWN?.takeIf { it > 0 } ?: return null
    val h = cs.captureFrameHN?.takeIf { it > 0 } ?: return null
    val sensor = wireCcdInfoByDevice[primaryTrain.camera]
    return PrimaryImageSize(
        w, h, cs.captureFrameXN ?: 0, cs.captureFrameYN ?: 0, cs.captureBinHN, cs.captureBinVN,
        sensor?.maxX?.takeIf { it > 0 }, sensor?.maxY?.takeIf { it > 0 },
    )
}

/** A camera's `CCD_INFO` vector; see [AppState.wireCcdInfoByDevice]. */
data class CcdInfo(val maxX: Int, val maxY: Int, val pixelUm: Double)

/** A camera's gain range; see [AppState.wireGainRangeByDevice]. */
data class GainRange(val min: Double, val max: Double, val step: Double)

// ── Catalog data ───────────────────────────────────────────────────────────

val PA_SECS = listOf(1, 2, 5, 10)
val PLAN_CHIPS = listOf("Up tonight", "Alt > 40°", "Fits FOV")

/** One `astro_search_objects` object-type choice; [skyType] is the `SkyObject::TYPE` value. */
data class SearchType(val label: String, val skyType: Int, val usesMagnitude: Boolean = true)

/**
 * Types the fork's `astro_search_objects` handles (`message.cpp`'s type switch). Supernovae and
 * satellites are left out because they turn on KStars display options as a side effect; comets and
 * asteroids are huge, mostly faint lists. Planets ignore `maxMagnitude` (not in the DSO database).
 */
val SEARCH_TYPES = listOf(
    SearchType("Galaxies", 8),
    SearchType("Emission neb.", 5),
    SearchType("Planetary neb.", 6),
    SearchType("Open clusters", 3),
    SearchType("Globulars", 4),
    SearchType("Planets", 2, usesMagnitude = false),
)

/** Max-magnitude stepper bounds for [AppState.searchMaxMag]. */
val SEARCH_MAG_RANGE = 6..16

/**
 * Most names one search resolves via `astro_get_objects_info`/`riseset`. The DSO database
 * returns names brightest-first (`ORDER BY magnitude`), so this keeps the brightest slice — a
 * blank query at mag 14 can otherwise ask the Pi for riseset curves on thousands of objects.
 */
const val SEARCH_RESULT_CAP = 150

/**
 * [size]/[max]/[peak]/[usable] come from `astro_*` replies for search results; user-catalogue
 * [custom] targets only have [id]/[common]/[coords]. [band]/[fov] are unused and always null.
 * Serializable for [com.nocturne.data.TargetRepository].
 */
@Serializable
data class Target(
    val id: String,
    val common: String,
    val coords: String,
    val size: String? = null,
    val band: String? = null,
    val max: Int? = null,
    val peak: String? = null,
    val usable: String? = null,
    val fov: Int? = null,
    val custom: Boolean = false,
    /**
     * Resolved J2000 coords/magnitude from `astro_get_objects_info`, set only for search results
     * and carried to [addToSequence]/`SequenceJob.targetRA/DEC`.
     */
    val ra0: Double? = null,
    val de0: Double? = null,
    val magnitude: Double? = null,
)

/** Sensor spec used to derive pixel scale + FOV from a focal length. */
private data class SensorSpec(val pixelUm: Double, val widthMm: Double, val heightMm: Double)

// ASI2600MM Pro: 3.76 µm, 6248×4176 px.
private val MAIN_SENSOR = SensorSpec(3.76, 23.5, 15.7)

// ASI174MM mini: 5.86 µm, 1280×960 px.
private val GUIDE_SENSOR = SensorSpec(5.86, 7.5, 5.6)

private fun pixelScaleArcsec(focalMm: Int, sensor: SensorSpec): Double =
    206.265 * sensor.pixelUm / focalMm

private fun fovDeg(dimMm: Double, focalMm: Int): Double =
    Math.toDegrees(2 * kotlin.math.atan(dimMm / (2.0 * focalMm)))

/** "1.24 ″/px · 2.4° × 1.6° FOV" for the main imaging optic. */
fun opticNote(focalMm: Int): String {
    val px = pixelScaleArcsec(focalMm, MAIN_SENSOR)
    val fovW = fovDeg(MAIN_SENSOR.widthMm, focalMm)
    val fovH = fovDeg(MAIN_SENSOR.heightMm, focalMm)
    return "${"%.2f".format(px)} ″/px · ${"%.1f".format(fovW)}° × ${"%.1f".format(fovH)}° FOV"
}

/** Same, for the guide optic (smaller guide-cam sensor). */
fun guideOpticNote(focalMm: Int): String {
    val px = pixelScaleArcsec(focalMm, GUIDE_SENSOR)
    val fovW = fovDeg(GUIDE_SENSOR.widthMm, focalMm)
    val fovH = fovDeg(GUIDE_SENSOR.heightMm, focalMm)
    return "${"%.2f".format(px)} ″/px · ${"%.1f".format(fovW)}° × ${"%.1f".format(fovH)}° FOV"
}

/**
 * A saved Ekos profile (`get_profiles`/`profile_add`): name, drivers, connection mode. It carries
 * no optics; those live in the Scopes catalog ([ScopeDef]) referenced by [TrainAssignment.scope].
 */
data class RigProfile(
    val name: String,
    val deviceKeys: List<String>,
    /**
     * `get_profiles`' `drivers` map (device family → driver labels). [editProfile]'s per-category
     * picker needs it; the flat [deviceKeys] lose the family.
     */
    val drivers: Map<String, List<String>> = emptyMap(),
    /** Which entry in `drivers["CCDs"]` (if any) is the guide camera — see [WireProfile.guider]. */
    val guider: String = "",
)

val AppState.activeRigProfile: RigProfile? get() = profiles.firstOrNull { it.name == activeProfile }

/** Looks up a [ScopeDef] by name in [AppState.wireScopes], falling back to [AppState.scopes]. */
fun AppState.findScope(name: String): ScopeDef? = (wireScopes ?: scopes).firstOrNull { it.name == name }

/** "1160 mm · f/8.9" — formats the F-ratio from a [ScopeDef]'s focal length + aperture. */
fun fRatio(focalMm: Int, apertureMm: Int): String =
    if (apertureMm <= 0) "—" else "f/${"%.1f".format(focalMm.toDouble() / apertureMm)}"

/**
 * One Scopes catalog entry, Ekos's `OAL::Scope::toJson()`: `{id, model, vendor, type, name,
 * focal_length, aperture}` (docs/PROTOCOL.md). Trains reference it by [name]. `id` is
 * server-assigned on the wire; locally it is `"scope_<seq>"`.
 */
data class ScopeDef(
    val id: String,
    val name: String,
    val vendor: String = "",
    val type: String = "",
    val focalMm: Int,
    val apertureMm: Int,
)

/** Which Optical Train slot — Ekos only ever has these two roles. */
enum class TrainSlot { PRIMARY, SECONDARY }

/** One assignable role within a train — everything but [TrainAssignment.reducer]. */
enum class TrainRole { MOUNT, CAMERA, ROTATOR, GUIDE_VIA, DUST_CAP, SCOPE, FILTER_WHEEL, FOCUSER, LIGHT_BOX, ADAPTIVE_OPTICS }

/**
 * One Optical Train's role assignments, mirroring `train_get_all`'s `mount`/`camera`/`guider`/
 * `focuser`/`filterwheel`/`rotator`/`reducer`/`dustcap`/`lightbox`/`scope` (message.cpp:236).
 * Dust cap and Light box have no device category yet, so they stay "None".
 */
data class TrainAssignment(
    val mount: String = "None",
    val camera: String = "None",
    val rotator: String = "None",
    val guideVia: String = "None",
    val dustCap: String = "None",
    val scope: String = "None",
    val filterWheel: String = "None",
    val focuser: String = "None",
    val reducer: Double = 1.0,
    val lightBox: String = "None",
    val adaptiveOptics: String = "None",
)

fun TrainAssignment.get(role: TrainRole): String = when (role) {
    TrainRole.MOUNT -> mount
    TrainRole.CAMERA -> camera
    TrainRole.ROTATOR -> rotator
    TrainRole.GUIDE_VIA -> guideVia
    TrainRole.DUST_CAP -> dustCap
    TrainRole.SCOPE -> scope
    TrainRole.FILTER_WHEEL -> filterWheel
    TrainRole.FOCUSER -> focuser
    TrainRole.LIGHT_BOX -> lightBox
    TrainRole.ADAPTIVE_OPTICS -> adaptiveOptics
}

fun TrainAssignment.with(role: TrainRole, value: String): TrainAssignment = when (role) {
    TrainRole.MOUNT -> copy(mount = value)
    TrainRole.CAMERA -> copy(camera = value)
    TrainRole.ROTATOR -> copy(rotator = value)
    TrainRole.GUIDE_VIA -> copy(guideVia = value)
    TrainRole.DUST_CAP -> copy(dustCap = value)
    TrainRole.SCOPE -> copy(scope = value)
    TrainRole.FILTER_WHEEL -> copy(filterWheel = value)
    TrainRole.FOCUSER -> copy(focuser = value)
    TrainRole.LIGHT_BOX -> copy(lightBox = value)
    TrainRole.ADAPTIVE_OPTICS -> copy(adaptiveOptics = value)
}

fun AppState.train(slot: TrainSlot): TrainAssignment = when (slot) {
    TrainSlot.PRIMARY -> primaryTrain
    TrainSlot.SECONDARY -> secondaryTrain
}

fun AppState.withTrain(slot: TrainSlot, train: TrainAssignment): AppState = when (slot) {
    TrainSlot.PRIMARY -> copy(primaryTrain = train)
    TrainSlot.SECONDARY -> copy(secondaryTrain = train)
}

/** [DeviceRole] bits each train role's device pool draws from. [TrainRole.SCOPE] has none: it is the Scopes catalog. */
private val TRAIN_ROLE_DEVICE_ROLES: Map<TrainRole, Set<DeviceRole>> = mapOf(
    TrainRole.MOUNT to setOf(DeviceRole.TELESCOPE),
    TrainRole.CAMERA to setOf(DeviceRole.CCD, DeviceRole.GUIDER),
    TrainRole.ROTATOR to setOf(DeviceRole.ROTATOR),
    TrainRole.GUIDE_VIA to setOf(DeviceRole.TELESCOPE),
    TrainRole.DUST_CAP to setOf(DeviceRole.DUSTCAP),
    TrainRole.FILTER_WHEEL to setOf(DeviceRole.FILTER),
    TrainRole.FOCUSER to setOf(DeviceRole.FOCUSER),
    TrainRole.LIGHT_BOX to setOf(DeviceRole.LIGHTBOX),
    TrainRole.ADAPTIVE_OPTICS to setOf(DeviceRole.AO),
)

/**
 * Picker pool for one train role. With [wireDevices], every device whose `interface` bitmask
 * includes the role's [DeviceRole]; otherwise the rig profile's category selections.
 * [TrainRole.SCOPE] always reads the Scopes catalog ([wireScopes] or [scopes]).
 */
fun AppState.trainRolePool(role: TrainRole): List<String> {
    val live = wireDevices
    val deviceRoles = TRAIN_ROLE_DEVICE_ROLES[role]
    if (live != null && deviceRoles != null) {
        val names = live.filter { d -> d.roles.any { it in deviceRoles } }.map { it.name }
        return if (role == TrainRole.MOUNT || role == TrainRole.CAMERA) names else (listOf("None") + names).distinct()
    }
    return when (role) {
        TrainRole.MOUNT -> listOfNotNull(selectedDeviceNames["mount"])
        TrainRole.CAMERA -> listOfNotNull(selectedDeviceNames["cam"], selectedDeviceNames["guide"]).distinct()
        TrainRole.ROTATOR -> (listOf("None") + listOfNotNull(selectedDeviceNames["rotator"])).distinct()
        TrainRole.GUIDE_VIA -> (listOf("None") + listOfNotNull(selectedDeviceNames["mount"])).distinct()
        TrainRole.DUST_CAP -> listOf("None")
        TrainRole.ADAPTIVE_OPTICS -> listOf("None")
        TrainRole.SCOPE -> (wireScopes ?: scopes).map { it.name }.distinct()
        TrainRole.FILTER_WHEEL -> (listOf("None") + listOfNotNull(selectedDeviceNames["efw"])).distinct()
        TrainRole.FOCUSER -> (listOf("None") + listOfNotNull(selectedDeviceNames["focus"])).distinct()
        TrainRole.LIGHT_BOX -> listOf("None")
    }
}

/**
 * One device category a rig profile can assign, mirroring an INDI `DRIVER_INTERFACE` family.
 * [catalog] is the default device list; optional (non-[req]) categories start with "None".
 */
data class Device(
    val key: String,
    val name: String,
    val detail: String,
    val req: Boolean,
    val cfg: List<Pair<String, String>>,
    val catalog: List<String>,
)

val DEVICES = listOf(
    Device("mount", "None", "", true, emptyList(), emptyList()),
    Device("cam", "None", "", true, emptyList(), emptyList()),
    Device("guide", "None", "", false, emptyList(), emptyList()),
    Device("efw", "None", "", false, emptyList(), emptyList()),
    Device("focus", "None", "", false, emptyList(), emptyList()),
    Device("rotator", "None", "", false, emptyList(), emptyList()),
    Device("dome", "None", "", false, emptyList(), emptyList()),
    Device("weather", "None", "", false, emptyList(), emptyList()),
    Device("powerbox", "None", "", false, emptyList(), emptyList()),
)

/** [DeviceRole] bits each rig-setup category's device pool draws from. */
private val CATEGORY_DEVICE_ROLES: Map<String, Set<DeviceRole>> = mapOf(
    "mount" to setOf(DeviceRole.TELESCOPE),
    "cam" to setOf(DeviceRole.CCD),
    "guide" to setOf(DeviceRole.CCD, DeviceRole.GUIDER),
    "efw" to setOf(DeviceRole.FILTER),
    "focus" to setOf(DeviceRole.FOCUSER),
    "rotator" to setOf(DeviceRole.ROTATOR),
    "dome" to setOf(DeviceRole.DOME),
    "weather" to setOf(DeviceRole.WEATHER),
    "powerbox" to setOf(DeviceRole.AUX),
)

/**
 * Rig-setup category → `ProfileInfo.drivers` family key. `"Telescopes"`/`"CCDs"`/`"Focusers"`/
 * `"Filter Wheels"` are documented (docs/PROTOCOL.md); the rest follow INDI's driver families.
 * A wrong key lands the device under "Aux". Used by profile_add/update and [realDeviceOptions].
 *
 * `"guide"` maps to `"CCDs"` like `"cam"`: Ekos has no "Guiders" family, a guide camera is a
 * second CCD marked by the `guider` field ([WireProfile.guider]/[RigProfile.guider]). This map
 * only builds the pool; the selected guide camera must be read from `.guider`.
 */
val CATEGORY_TO_DRIVER_FAMILY = mapOf(
    "mount" to "Telescopes",
    "cam" to "CCDs",
    "guide" to "CCDs",
    "efw" to "Filter Wheels",
    "focus" to "Focusers",
    "rotator" to "Rotators",
    "dome" to "Domes",
    "weather" to "Weather",
    // INDI files power boxes (Pegasus UPB/PPB, Terrans, ...) under "Power" (/usr/share/indi);
    // "Aux" is not a valid family.
    "powerbox" to "Power",
)

/**
 * "Used before" device options for one rig-setup category in the profile wizard
 * ([DevicePickerBody]): connected devices ([wireDevices], only once Ekos runs) plus every driver
 * saved in any profile ([wireKnownDrivers], available before Ekos starts). Null when neither has
 * arrived; callers then fall back to [Device.catalog]. Full catalog: [installedDriverOptions].
 */
fun AppState.realDeviceOptions(key: String): List<String>? {
    val family = CATEGORY_TO_DRIVER_FAMILY[key]
    val connected = wireDevices?.let { live ->
        val roles = CATEGORY_DEVICE_ROLES[key] ?: return@let emptyList()
        live.filter { d -> d.roles.any { it in roles } }.map { it.name }
    }
    val everConfigured = family?.let { wireKnownDrivers?.get(it) }
    if (connected == null && everConfigured == null) return null
    val names = ((connected ?: emptyList()) + (everConfigured ?: emptyList())).distinct()
    val required = DEVICES.firstOrNull { it.key == key }?.req ?: false
    return if (required) names else (listOf("None") + names).distinct()
}

/**
 * Every installed INDI driver for one rig-setup role ([wireDriverCatalog], `get_drivers`), one per
 * label, sorted by manufacturer then label. Null until the catalog arrives (or KStars lacks the command).
 */
fun AppState.installedDriverOptions(key: String): List<com.nocturne.protocol.WireDriver>? {
    val catalog = wireDriverCatalog ?: return null
    val family = CATEGORY_TO_DRIVER_FAMILY[key] ?: return emptyList()
    return catalog.filter { it.family == family && it.label.isNotBlank() }
        .distinctBy { it.label }
        .sortedWith(compareBy({ it.manufacturer.ifBlank { "~" }.lowercase() }, { it.label.lowercase() }))
}

/**
 * Generic INDI property vector, mirroring `device_get`/`device_property_get` shapes
 * (Switch/Number/Text/Light) so the UI can render any device's controls.
 */
sealed class IndiProperty {
    abstract val name: String
    abstract val label: String
    abstract val group: String

    data class SwitchProp(
        override val name: String, override val label: String, override val group: String,
        val options: List<String>,
        val selected: Int,
        /**
         * INDI element name per option, for `device_property_set`; it can differ from the label.
         * Defaults to [options].
         */
        val elementNames: List<String> = options,
    ) : IndiProperty()

    data class NumberProp(
        override val name: String, override val label: String, override val group: String,
        val value: Double, val min: Double, val max: Double, val step: Double, val format: String = "%.1f",
        /** INDI element name within this vector; see [SwitchProp.elementNames]. */
        val elementName: String = name,
        /**
         * Vector-level IPState: 0 Idle, 1 Ok, 2 Busy, 3 Alert. `CCD_EXPOSURE` is Busy while an
         * exposure runs, which drives the "capturing" indicator; see [indiBusy].
         */
        val state: Int = 1,
    ) : IndiProperty()

    /**
     * Text vectors can have several elements (e.g. `FILTER_NAME`, one per filter slot).
     * `elements` is (INDI element name, value).
     */
    data class TextProp(
        override val name: String, override val label: String, override val group: String,
        val elements: List<Pair<String, String>>,
    ) : IndiProperty()

    /** Read-only — IPState per element: 0 Idle, 1 Ok, 2 Busy, 3 Alert. */
    data class LightProp(
        override val name: String, override val label: String, override val group: String,
        val elements: List<Pair<String, Int>>,
    ) : IndiProperty()
}

/** A NumberProp's value by property name, or null when the device hasn't reported it. */
fun AppState.indiNumber(deviceName: String, propName: String): Double? {
    val props = indiProps[deviceName] ?: emptyList()
    return (props.firstOrNull { it.name == propName } as? IndiProperty.NumberProp)?.value
}

/** A switch property's currently-on INDI element name, or null if the property isn't known. */
fun AppState.indiSwitchElement(deviceName: String, propName: String): String? {
    val prop = indiProps[deviceName]?.firstOrNull { it.name == propName } as? IndiProperty.SwitchProp ?: return null
    return prop.elementNames.getOrNull(prop.selected)
}

/** A switch property's element names (all choices), empty if unknown. */
fun AppState.indiSwitchElements(deviceName: String, propName: String): List<String> =
    (indiProps[deviceName]?.firstOrNull { it.name == propName } as? IndiProperty.SwitchProp)?.elementNames.orEmpty()

/** Whether a NumberProp is Busy (see [IndiProperty.NumberProp.state]). */
fun AppState.indiBusy(deviceName: String, propName: String): Boolean {
    val props = indiProps[deviceName] ?: emptyList()
    return (props.firstOrNull { it.name == propName } as? IndiProperty.NumberProp)?.state == 2
}

@Serializable
data class Block(
    val id: String,
    val filter: String,
    val exposureSec: Int,
    val subCount: Int,
    val doneCount: Int,
    val gain: Int,
    val offset: Int,
    val binning: Int,
    /**
     * Per-block dither frequency; `null` = off (`SequenceJob::SJ_DitherPerJobEnabled`). Ekos writes
     * off as `-1` in `<GuideDitherPerJob>` (`sequencejob.cpp:1070`, read back at 841-850), and `0`
     * is a valid frequency, so off must stay distinct from `0`. The `-1` encoding lives in [EsqWriter].
     */
    val ditherEvery: Int?,
)

/**
 * One queued target's capture plan, the counterpart of Ekos's `SchedulerJob`
 * (`scheduler_get_jobs`/`scheduler_add_jobs`): one target, one sequence of [blocks].
 * Not named `Job` to avoid clashing with `kotlinx.coroutines.Job`.
 */
@Serializable
data class SequenceJob(
    val id: String,
    val targetId: String,
    val blocks: List<Block> = emptyList(),
    val blockSeq: Int = 1,
    /**
     * True once "Push to Ekos" was confirmed by a `scheduler_get_jobs` round-trip. Says only that
     * Ekos lists the job; use [wireJobFor] for its state. The block editor is read-only while true
     * (the wire can't edit a queued job).
     *
     * Don't cache a wire index or running flag here: `WireSchedulerJob` has no id, only `name`,
     * and indices shift when the queue changes. Resolve by name via [wireJobFor] when needed.
     */
    val synced: Boolean = false,
    /**
     * Creation time, for [targetNameFor]'s date suffix. `0L` for jobs persisted before this field;
     * those keep their undated name so a synced job still matches what Ekos has.
     */
    val createdAtMs: Long = 0L,
)

/**
 * The name Ekos knows a job by, used to name-match [AppState.wireSchedulerJobs]:
 * `"<target>_<job id>_<yyyyMMdd_HHmmss>"`.
 *
 * - The job id keeps two jobs for the same target apart; Ekos's Scheduler has only `name`, no id.
 * - The date keeps names unique if the local id counter restarts (reinstall, cleared store), so
 *   Ekos's remembered job progress (`RememberJobProgress`) can't latch onto an unrelated job.
 * - Separator is `_`: Ekos's filename sanitizer (`ksutils.cpp:1924` `KSUtils::sanitize()`)
 *   doesn't strip `#`, and a `#` in the save path makes image writes fail.
 */
fun AppState.targetNameFor(job: SequenceJob): String {
    val target = findTarget(job.targetId)
    val base = target?.common ?: target?.id ?: job.targetId
    val dated = if (job.createdAtMs > 0L) "_${JOB_NAME_DATE_FORMATTER.format(Instant.ofEpochMilli(job.createdAtMs).atZone(ZoneId.systemDefault()))}" else ""
    return "${base}_${job.id}$dated"
}

private val JOB_NAME_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

/**
 * Filesystem-safe token: anything but `[A-Za-z0-9_-]` becomes `_`. Same rule as
 * [com.nocturne.data.FrameFileWriter]'s `sanitize`; used to turn [targetNameFor] into an `.esq` filename.
 */
fun sanitizeFileToken(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

/**
 * This job's counterpart on the wire, if any. Always null for an unsynced job: name-matching an
 * unpushed job can latch onto someone else's job. The single source of "is this job running".
 */
fun AppState.wireJobFor(job: SequenceJob): WireSchedulerJob? =
    if (!job.synced) null else wireSchedulerJobs.orEmpty().firstOrNull { it.name == targetNameFor(job) }

/**
 * Scheduler jobs no synced [SequenceJob] claims (added in KStars, or left over). Shown as-is;
 * nothing is cleared on connect.
 */
val AppState.unmanagedWireJobs: List<WireSchedulerJob> get() {
    val claimed = jobs.filter { it.synced }.map { targetNameFor(it) }.toSet()
    return wireSchedulerJobs.orEmpty().filterNot { it.name in claimed }
}

/**
 * The job the Session tab reflects: a synced job that is [SchedulerJobStatus.BUSY], else one in
 * [ACTIVE_SCHEDULER_STATES] (committed, even pre-slew), else the last active job, else the first.
 */
val AppState.contractJob: SequenceJob? get() =
    jobs.firstOrNull { wireJobFor(it)?.state == SchedulerJobStatus.BUSY }
        ?: jobs.firstOrNull { wireJobFor(it)?.state in ACTIVE_SCHEDULER_STATES }
        ?: jobs.firstOrNull { it.id == lastActiveJobId }
        ?: jobs.firstOrNull()

/** The job [endSession] stopped; the subject of the Summary sheet and its export. */
val AppState.endedJob: SequenceJob? get() = jobs.firstOrNull { it.id == lastEndedJobId }

/**
 * Looks up a target by id in the user catalogue, then the live search results, then the
 * snapshot taken when it was picked (so a framed target outlives the search it came from).
 */
fun AppState.findTarget(id: String): Target? =
    userTargets.firstOrNull { it.id == id }
        ?: wireSearchResults?.firstOrNull { it.id == id }
        ?: targetSnapshot?.takeIf { it.id == id }

/** "NGC 7000 — North America"; just the name for custom targets, which have no catalog id. */
val Target.displayName: String get() = if (custom) common else "$id — $common"

/**
 * The name to send to wire calls that resolve an object by name (`mount_goto_target`,
 * `astro_get_objects_riseset`, the Scheduler's `nameEdit`). KStars resolves designations
 * ("NGC 7000"), not nicknames ("North America"), so use `id`, except for custom targets whose
 * `id` is a synthetic `"custom_N"`; those use what the user typed.
 */
val Target.realLookupName: String get() = if (custom) common.ifBlank { id } else id.ifBlank { common }

/**
 * J2000 RA/Dec in decimal degrees. Uses [Target.ra0] (hours, hence `* 15.0`) and [Target.de0]
 * (degrees) when set, else parses [Target.coords] ("HHhMMmSSs ±DD°MM′SS″", the shape
 * `COORDS_REGEX` matches). Null when `coords` doesn't parse.
 */
val Target.raDecDegrees: Pair<Double, Double>?
    get() {
        if (ra0 != null && de0 != null) return (ra0 * 15.0) to de0
        val m = Regex("""(\d+)h(\d+)m(\d+)s\s+([+-])(\d+)°(\d+)′(\d+)″""").find(coords) ?: return null
        val (rh, rm, rs, sign, dd, dm, ds) = m.destructured
        val raDeg = (rh.toDouble() + rm.toDouble() / 60.0 + rs.toDouble() / 3600.0) * 15.0
        val decMag = dd.toDouble() + dm.toDouble() / 60.0 + ds.toDouble() / 3600.0
        return raDeg to (if (sign == "-") -decMag else decMag)
    }

/**
 * Whether a just-arrived capture frame belongs to a scheduler job (Plan/) or is a test capture
 * (Preview/): a `BUSY` job means a job capture. Can misfile around a job's start/stop, but frame
 * headers carry no target or filter, so there is no better signal.
 */
fun AppState.activeFrameSource(): FrameSource {
    val busyJob = wireSchedulerJobs?.firstOrNull { it.state == SchedulerJobStatus.BUSY } ?: return FrameSource.Test
    return FrameSource.Plan(
        target = busyJob.name,
        filter = wireCaptureSettings?.FilterPosCombo,
        temperatureC = indiNumber(primaryTrain.camera, "CCD_TEMPERATURE"),
        targetRA = busyJob.targetRA,
        targetDEC = busyJob.targetDEC,
    )
}

/** Median HFR across kept frames; null if no kept frame has an HFR yet. */
val AppState.medHfr: Double? get() {
    val sorted = frameRows.filter { it.keep }.mapNotNull { it.hfr }.sorted()
    return if (sorted.isEmpty()) null else sorted[sorted.size / 2]
}

/** First not-yet-complete block, or the last block if all are done. */
val SequenceJob.currentBlockIndex: Int? get() =
    if (blocks.isEmpty()) null else blocks.indexOfFirst { it.doneCount < it.subCount }.let { if (it == -1) blocks.lastIndex else it }

/**
 * Filter slot names (INDI `FILTER_NAME`) from the connected filter wheel; null otherwise, and
 * callers treat that as unknown. [indiProps] is keyed by device name, not the `"efw"` key.
 */
val AppState.realFilterNames: List<String>? get() {
    val filterWheel = wireDevices?.firstOrNull { DeviceRole.FILTER in it.roles } ?: return null
    val prop = indiProps[filterWheel.name]?.firstOrNull { it.name == "FILTER_NAME" } as? IndiProperty.TextProp
    return prop?.elements?.map { it.second }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
}
val BINNING_OPTIONS = listOf(1, 2, 3, 4)
/** `null` is "Off"; see [Block.ditherEvery] for why off is distinct from `0`. */
val DITHER_OPTIONS: List<Int?> = listOf(null, 1, 2, 3, 5)

/** "300 s × 40" — collapsed-card headline, derived so an edit is reflected immediately. */
val Block.spec: String get() = "$exposureSec s × $subCount"

val Block.pct: Float get() = if (subCount == 0) 0f else (doneCount.toFloat() / subCount).coerceIn(0f, 1f)

internal fun formatHm(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return "${h}h ${m.toString().padStart(2, '0')}m"
}

/** Integration time actually captured for this block: doneCount × exposureSec. */
val Block.doneSpec: String get() = formatHm(doneCount * exposureSec)

/** "12 done · 2h 20m left" / "queued · 2h 30m" — derived from exposure × remaining subs. */
val Block.meta: String get() {
    val remaining = formatHm((subCount - doneCount).coerceAtLeast(0) * exposureSec)
    return if (doneCount > 0) "$doneCount done · $remaining left" else "queued · $remaining"
}

/** Total subs done/planned across all blocks. */
val SequenceJob.totalDone: Int get() = blocks.sumOf { it.doneCount }
val SequenceJob.totalSubs: Int get() = blocks.sumOf { it.subCount }

/** "Ha 12/40 · OIII 0/30" — per-filter done/planned breakdown, in block order. */
val SequenceJob.filterBreakdown: String get() =
    blocks.joinToString(" · ") { "${it.filter} ${it.doneCount}/${it.subCount}" }

/** Total planned/captured integration time across all blocks — exposureSec × count, summed. */
val SequenceJob.totalPlannedSec: Int get() = blocks.sumOf { it.exposureSec * it.subCount }
val SequenceJob.totalDoneSec: Int get() = blocks.sumOf { it.exposureSec * it.doneCount }

private fun formatHM(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return "$h:${m.toString().padStart(2, '0')}"
}

/** "1:00" / "3:20" style (hours:minutes, unpadded hour), as NightArcCard shows. */
val SequenceJob.doneHM: String get() = formatHM(totalDoneSec)
val SequenceJob.plannedHM: String get() = formatHM(totalPlannedSec)

/**
 * Dusk/dawn as instants: [AppState.wireDusk]/[AppState.wireDawn] offsets applied to the site's
 * midnight in [AppState.wireSiteTz], not the phone's timezone (the almanac is for the Pi's site).
 * Null until all three have arrived.
 */
val AppState.realNightWindow: Pair<Instant, Instant>? get() {
    val dusk = wireDusk ?: return null
    val dawn = wireDawn ?: return null
    val zone = siteZoneOffset ?: return null
    val anchor = nearestMidnight(zone)
    return anchor.plusSeconds((dusk * 86400).roundToLong()) to anchor.plusSeconds((dawn * 86400).roundToLong())
}

/** [wireSiteTz] as a [ZoneOffset]; null until it has arrived. */
val AppState.siteZoneOffset: ZoneOffset? get() = wireSiteTz?.let { ZoneOffset.ofTotalSeconds((it * 3600).roundToInt()) }

/**
 * The site midnight nearest to now. Almanac and riseset offsets are relative to "tonight's"
 * midnight, which is the one just passed in the small hours but the upcoming one in the
 * afternoon; always using today's start would put an afternoon dusk a day early.
 */
private fun nearestMidnight(zone: ZoneOffset): Instant {
    val now = Instant.now()
    val nowDate = now.atZone(zone).toLocalDate()
    val todayStart = nowDate.atStartOfDay(zone).toInstant()
    val tomorrowStart = nowDate.plusDays(1).atStartOfDay(zone).toInstant()
    return if (abs(now.epochSecond - todayStart.epochSecond) <= abs(now.epochSecond - tomorrowStart.epochSecond)) todayStart else tomorrowStart
}

/**
 * The ±12h-around-midnight window an `astro_get_objects_riseset` altitude curve spans (see
 * [WireRiseset.altitudes]), unlike [realNightWindow]'s dusk-to-dawn. Null until [wireSiteTz] arrives.
 */
val AppState.realDayWindow: Pair<Instant, Instant>? get() {
    val zone = siteZoneOffset ?: return null
    val anchor = nearestMidnight(zone)
    return anchor.minusSeconds(43_200) to anchor.plusSeconds(43_200)
}

/**
 * Seconds tonight that [riseset]'s altitude curve (49 samples, 30 min apart, over [realDayWindow])
 * is above [thresholdDeg] (default matches the "Alt > 40°" chip) and inside [realNightWindow].
 * Interpolates linearly between samples, as [AltitudeChart] draws. Null until both windows are
 * known or with fewer than 2 samples.
 */
fun AppState.realUsableSeconds(riseset: WireRiseset, thresholdDeg: Double = 40.0): Int? {
    val dayStart = realDayWindow?.first ?: return null
    val night = realNightWindow ?: return null
    val alts = riseset.altitudes.takeIf { it.size >= 2 } ?: return null
    val stepSec = 1800L
    var usable = 0.0
    for (i in 0 until alts.size - 1) {
        val t0 = dayStart.epochSecond + i * stepSec
        val t1 = dayStart.epochSecond + (i + 1) * stepSec
        val lo = maxOf(t0, night.first.epochSecond)
        val hi = minOf(t1, night.second.epochSecond)
        if (lo >= hi) continue
        usable += aboveThresholdSeconds(t0, t1, alts[i], alts[i + 1], lo, hi, thresholdDeg)
    }
    return usable.roundToInt()
}

/**
 * Within `[lo, hi]` (a subset of `[t0, t1]`), the seconds where the line from `(t0, a0)` to
 * `(t1, a1)` is above `threshold`.
 */
private fun aboveThresholdSeconds(t0: Long, t1: Long, a0: Double, a1: Double, lo: Long, hi: Long, threshold: Double): Double {
    if (a0 == a1) return if (a0 > threshold) (hi - lo).toDouble() else 0.0
    val frac = (threshold - a0) / (a1 - a0)
    val tc = (t0 + frac * (t1 - t0)).coerceIn(t0.toDouble(), t1.toDouble())
    return if (a1 > a0) {
        (hi - maxOf(lo.toDouble(), tc)).coerceAtLeast(0.0)
    } else {
        (minOf(hi.toDouble(), tc) - lo).coerceAtLeast(0.0)
    }
}

/** Fraction of [realDayWindow] elapsed now; always in range, since now is within ±12h of the nearest midnight. */
val AppState.realDayFraction: Double? get() {
    val (start, end) = realDayWindow ?: return null
    val total = end.epochSecond - start.epochSecond
    if (total <= 0) return null
    return (Instant.now().epochSecond - start.epochSecond).toDouble() / total
}

private val SITE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

/** [instant] formatted "HH:mm" in the site's local time. */
fun AppState.formatSiteTime(instant: Instant): String {
    val tz = wireSiteTz ?: return "--:--"
    return instant.atZone(ZoneOffset.ofTotalSeconds((tz * 3600).roundToInt())).format(SITE_TIME_FORMATTER)
}

/**
 * Fraction of tonight's dusk-to-dawn span elapsed now. Null (not clamped) outside the span, so the
 * UI shows "not observing" instead of a misplaced dot.
 */
val AppState.realNowFraction: Double? get() {
    val (dusk, dawn) = realNightWindow ?: return null
    val now = Instant.now()
    if (now.isBefore(dusk) || now.isAfter(dawn)) return null
    val totalSec = dawn.epochSecond - dusk.epochSecond
    if (totalSec <= 0) return null
    return (now.epochSecond - dusk.epochSecond).toDouble() / totalSec
}

/**
 * "T-H:MM" countdown to the next dusk, shown when [realNowFraction] is null. A dusk already in the
 * past is pushed a day ahead, which is close enough without a second almanac fetch.
 */
val AppState.realCountdownToDusk: String? get() {
    val (dusk, _) = realNightWindow ?: return null
    val now = Instant.now()
    val nextDusk = if (!dusk.isBefore(now)) dusk else dusk.plusSeconds(86400)
    val totalSec = (nextDusk.epochSecond - now.epochSecond).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return "T-$h:${m.toString().padStart(2, '0')}"
}

internal fun AppState.mapJob(jobId: String, f: (SequenceJob) -> SequenceJob): AppState =
    copy(jobs = jobs.map { if (it.id == jobId) f(it) else it })

internal fun AppState.mapJobBlock(jobId: String, blockId: String, f: (Block) -> Block): AppState =
    mapJob(jobId) { job -> job.copy(blocks = job.blocks.map { if (it.id == blockId) f(it) else it }) }

/**
 * One `new_notification` push. [source]/[severity] are raw `KSNotification::EventSource`/
 * `EventType` ints (tables in [EkosEvent.NewNotification]); see [sourceLabel]/[severityLabel].
 */
data class Alert(
    val uuid: String,
    val message: String,
    val time: String,
    val source: Int,
    val severity: Int,
)

private val ALERT_SOURCE_LABELS = listOf("General", "INDI", "Capture", "Focus", "Align", "Mount", "Guide", "Observatory", "Scheduler")
private val ALERT_SEVERITY_LABELS = listOf("Debug", "Info", "Warn", "Alert")
val Alert.sourceLabel: String get() = ALERT_SOURCE_LABELS.getOrNull(source) ?: "Unknown"
val Alert.severityLabel: String get() = ALERT_SEVERITY_LABELS.getOrNull(severity) ?: "Unknown"

/** Cap on [AppState.wireNotifications]; enough for the Alerts sheet and the Summary event log. */
const val MAX_NOTIFICATIONS = 100

/** `targetDiff` Ekos sends when a solve had no target (`m_TargetDiffTotal`'s 1e6 initial value). */
const val ALIGN_TARGET_DIFF_UNSET = 1e6

/** Cap on [AppState.alignAccuracyHistory]. */
const val MAX_ACCURACY_HISTORY = 30

/** Cap on [AppState.guideSamples] — one per guide frame, so ~2.5 min at a 1 s exposure. */
const val MAX_GUIDE_SAMPLES = 150

/** Cap on [AppState.focusSamples] — a full autofocus run is typically 10–30 frames. */
const val MAX_FOCUS_SAMPLES = 120

/** One measured focus frame: absolute focuser position (-1 on a relative focuser) and its HFR. */
data class FocusSample(val pos: Int, val hfr: Double)

/** Combined RMS, same formula as Ekos's own label (`Guide::setAxisSigma`: hypot(ra, de)). */
val AppState.guideRmsTotal: Double?
    get() = if (guideRmsRA != null && guideRmsDE != null) kotlin.math.hypot(guideRmsRA, guideRmsDE) else null

/** One guide frame's correction (fork `guideStats`): error in arcsec, pulse in ms (signed). */
data class GuideSample(val ra: Double, val de: Double, val raPulse: Int, val dePulse: Int)

// ── Derived values ─────────────────────────────────────────────────────────



/** Minutes until the next scheduled autofocus, from [WireCaptureSettings.refocusEveryN] and the last run. */

/** Focuser temperature (`FOCUS_TEMPERATURE`), or null when the focuser doesn't report one. */
val AppState.eafTemp: Double? get() = indiNumber(primaryTrain.focuser, "FOCUS_TEMPERATURE")

/**
 * Focuser position for the Bench Focuser card: the focuser's `ABS_FOCUS_POSITION` when known,
 * else the local optimistic [focPos]. Always prefer the INDI value; [focPos] drifts with jogs.
 */
val AppState.benchFocPos: Int? get() =
    indiNumber(primaryTrain.focuser, "ABS_FOCUS_POSITION")?.roundToInt() ?: focPos

/**
 * Focuser end stops: `ABS_FOCUS_POSITION`'s `min`/`max`, as Ekos uses them (`focus.cpp`
 * `absMove[0].getMin()`/`getMax()`). Not [WireFocusSettings.focusMaxTravel], which caps one
 * autofocus search. Null until known (0..0 means not populated).
 *
 * [SessionController.focusStepIn]/`focusStepOut` clamp to this range on top of the server's own
 * end-stop check. (`focus_in`/`focus_out` move by `steps`; `steps <= 0` means Ekos's Relative
 * Step, `WireFocusSettings.focusTicks`.)
 */
val AppState.focRange: IntRange? get() {
    val props = indiProps[primaryTrain.focuser] ?: emptyList()
    val prop = props.firstOrNull { it.name == "ABS_FOCUS_POSITION" } as? IndiProperty.NumberProp ?: return null
    if (prop.min == 0.0 && prop.max == 0.0) return null
    return prop.min.roundToInt()..prop.max.roundToInt()
}

/**
 * Guide camera device name, from the train assigned to the Guide module
 * (`moduleTrainAssignments["guide"]`), not [secondaryTrain], which is just the second train.
 * Null until trains and assignments have arrived.
 */
val AppState.guideCameraDevice: String?
    get() = wireTrains?.firstOrNull { it.name == moduleTrainAssignments?.get("guide") }?.camera?.takeIf { it.isNotBlank() }

val AppState.alignCameraDevice: String?
    get() = wireTrains?.firstOrNull { it.name == moduleTrainAssignments?.get("align") }?.camera?.takeIf { it.isNotBlank() }

val AppState.focusCameraDevice: String?
    get() = wireTrains?.firstOrNull { it.name == moduleTrainAssignments?.get("focus") }?.camera?.takeIf { it.isNotBlank() }

/**
 * Ekos's "--" gain for [device]: Capture/Guide/Focus all set their gain spinbox range to
 * `min - step` and show that value as "--", meaning "send no gain, leave the driver's own"
 * (`camera_device.cpp`, `guide.cpp`, `focus.cpp` "Gain Check"). `*_get_all_settings` reports the
 * raw number (e.g. 99 for a ToupTek G3M178 whose gain min is 100, -10 for the CCD Simulator), so
 * any module gain at or below this is "--", not a real gain. Null until the camera's range is known.
 */
fun AppState.gainSpecialValue(device: String?): Double? =
    device?.let { wireGainRangeByDevice[it] }?.let { it.min - it.step }

/**
 * Exposure countdown/busy state for a Snap shot, read from the camera's INDI `CCD_EXPOSURE`
 * vector: `guide_capture` emits no `new_capture_state`, so this is the only signal that works for
 * both cameras. `remainingSec`/`busy` are null/false until the property arrives.
 */
data class CaptureProgress(val remainingSec: Double?, val totalSec: Double, val busy: Boolean)

fun AppState.captureProgress(cameraDevice: String?, totalSec: Double): CaptureProgress? {
    if (cameraDevice.isNullOrBlank()) return null
    return CaptureProgress(
        remainingSec = indiNumber(cameraDevice, "CCD_EXPOSURE"),
        totalSec = totalSec,
        busy = indiBusy(cameraDevice, "CCD_EXPOSURE"),
    )
}

/**
 * The mount's `TELESCOPE_SLEW_RATE` switch. Rate lists vary by driver (an LX200 OnStep has 10).
 * `mount_set_slew_rate`'s `rate` is an index into this vector's element order, so option `i`
 * maps directly. Null until the mount's `device_get` reply arrives.
 */
val AppState.realSlewRateProp: IndiProperty.SwitchProp?
    get() = (indiProps[primaryTrain.mount] ?: emptyList())
        .firstOrNull { it.name == "TELESCOPE_SLEW_RATE" } as? IndiProperty.SwitchProp

/**
 * Mount tracking from INDI `TELESCOPE_TRACK_STATE` (`TRACK_ON`/`TRACK_OFF`), else [mountTracking].
 * Display only; writes go through `mount_set_tracking`.
 */
val AppState.mountTrackingOn: Boolean? get() {
    val prop = (indiProps[primaryTrain.mount] ?: emptyList()).firstOrNull { it.name == "TELESCOPE_TRACK_STATE" } as? IndiProperty.SwitchProp
    return prop?.let { it.elementNames.getOrNull(it.selected) == "TRACK_ON" } ?: mountTracking
}

/**
 * Mount parked state from INDI `TELESCOPE_PARK` (`PARK`/`UNPARK`), else [mountParked].
 * Display only; writes go through `mount_park`/`mount_unpark`.
 */
val AppState.mountParkedReal: Boolean get() {
    val prop = (indiProps[primaryTrain.mount] ?: emptyList()).firstOrNull { it.name == "TELESCOPE_PARK" } as? IndiProperty.SwitchProp
    return prop?.let { it.elementNames.getOrNull(it.selected) == "PARK" } ?: mountParked
}

/**
 * [wireMountPierSide] named. `1` = West is from one observation on the rig, `0` = East by
 * elimination; not verified across positions. Other values show the raw ordinal.
 */
val AppState.mountPierSideLabel: String? get() = when (wireMountPierSide) {
    1 -> "West"
    0 -> "East"
    null -> null
    else -> "raw $wireMountPierSide"
}

/**
 * Pixel scale (arcsec/px) for the Framing card: `206.265 × pixel_µm / focal_mm`. Null until the
 * primary scope's focal length and the primary camera's [CcdInfo] are known.
 */
val AppState.framingPixelScaleArcsecPerPx: Double? get() {
    val focalMm = findScope(primaryTrain.scope)?.focalMm?.takeIf { it > 0 } ?: return null
    val ccd = wireCcdInfoByDevice[primaryTrain.camera] ?: return null
    return 206.265 * ccd.pixelUm / focalMm
}

/** Field of view (width, height) in degrees, from [framingPixelScaleArcsecPerPx] and the sensor size. */
val AppState.framingFovDeg: Pair<Double, Double>? get() {
    val scale = framingPixelScaleArcsecPerPx ?: return null
    val ccd = wireCcdInfoByDevice[primaryTrain.camera] ?: return null
    return (scale * ccd.maxX / 3600.0) to (scale * ccd.maxY / 3600.0)
}

val AppState.paTotal: Double? get() = if (paAlt != null && paAz != null) hypot(paAlt, paAz) else null
val AppState.rejectCount: Int get() = frameRows.count { !it.keep }
val AppState.keepCount: Int get() = frameRows.count { it.keep }
val AppState.ready: Boolean get() = ekosRunning && isOn("mount") && isOn("cam")
/**
 * Whether the rig has a connected device for a setup role: Ekos running and, among the devices
 * Ekos reports, the one on the primary train (else any with the role's INDI interface) connected.
 */
fun AppState.isOn(key: String): Boolean {
    if (!ekosRunning) return false
    val live = wireDevices ?: return false
    val trainDevice = when (key) {
        "mount" -> primaryTrain.mount
        "cam" -> primaryTrain.camera
        "focus" -> primaryTrain.focuser
        "efw" -> primaryTrain.filterWheel
        "rotator" -> primaryTrain.rotator
        else -> null
    }?.takeIf { it != "None" }
    if (trainDevice != null) return live.any { it.name == trainDevice && it.connected }
    val roles = CATEGORY_DEVICE_ROLES[key] ?: return false
    return live.any { d -> d.connected && d.roles.any { it in roles } }
}

/** Whether a device is picked for the profile being built, regardless of Ekos running. */
fun AppState.isSelected(key: String): Boolean = key !in devOff
val AppState.missing: String get() {    val parts = buildList {
        if (!isOn("mount")) add("mount")
        if (!isOn("cam")) add("camera")
    }
    return parts.joinToString(" + ")
}

