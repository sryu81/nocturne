package com.nocturne.session

import com.nocturne.data.FrameEntity
import com.nocturne.data.FrameSource
import com.nocturne.protocol.ACTIVE_SCHEDULER_STATES
import com.nocturne.protocol.DeviceRole
import com.nocturne.protocol.WireAlignSettings
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
 * Progress of a [SessionController.rebootRig] request against the rig's
 * companion reboot daemon (separate HTTP+token channel — the EkosRemote wire
 * itself has no OS-level reboot command, see `pi-tools/reboot-daemon/`).
 */
enum class RigRebootState { IDLE, SENDING, SENT, FAILED }

/** Which meridian-flip action is awaiting confirmation. */
enum class FlipConfirm { DEFER, NOW }

/** Frames tab's top-level category picker (M4.5 Part C) — see [AppState.frameCategory]. */
enum class FrameCategory { PREVIEW, PLAN }

/** Every mutable field the simulator drives, mirroring the prototype's state. */
data class AppState(
    val t: Int = 0,
    val sheet: SheetType? = null,
    /** Session tab's sub preview expanded to a full-screen overlay. */
    val subPreviewExpanded: Boolean = false,
    /** Frames tab: id of the frame expanded to a full-screen overlay, if any. */
    val expandedFrameId: String? = null,
    /**
     * Frames tab navigation (M4.5 Part C) — pure local UI nav, no wire effect, same shape as
     * [openBlockId]/[activeJobId]. Null [frameCategory] = top-level Preview/Plan picker;
     * [frameTarget] only meaningful once [frameCategory] is [FrameCategory.PLAN] — null there
     * means "show the target list," non-null means "show that target's own frame grid."
     */
    val frameCategory: FrameCategory? = null,
    val frameTarget: String? = null,
    /** Cumulative seconds the meridian flip countdown has been pushed back via DEFER. */
    val flipDeferSec: Int = 0,
    /** Flip/defer action awaiting user confirmation on the Session tab. */
    val pendingFlipConfirm: FlipConfirm? = null,
    val jobs: List<SequenceJob> = DEFAULT_JOBS,
    /** Which job's block editor is drilled into on the Sequence tab; null = job-list view. */
    val activeJobId: String? = DEFAULT_JOBS.firstOrNull()?.id,
    /** Monotonic job id counter — survives removals, unlike jobs.size + 1. */
    val jobSeq: Int = DEFAULT_JOBS.size + 1,
    /**
     * Last job that was actually started (running set true) — the Session
     * tab's fallback when nothing is currently running, so pausing the job
     * you're looking at doesn't jump you to some other job by list position.
     * Only [contractJob] should read this; everything else should keep using
     * `jobs`.
     */
    val lastActiveJobId: String? = null,
    /** Which block card is expanded — global scalar is fine, only one job is ever drilled into at a time. */
    val openBlockId: String? = DEFAULT_BLOCKS.getOrNull(1)?.id,
    /** The job [endSession] stopped, pending a Back-to-session/Next-job/Finish choice on the Summary sheet. */
    val lastEndedJobId: String? = null,
    val mountParked: Boolean = false,
    /** Fixture-only tracking on/off — real rig reads [mountTrackingOn] instead (real INDI `TELESCOPE_TRACK_STATE`). */
    val mountTracking: Boolean = true,
    /**
     * Client-side optimistic "is a start/stop routine running" flag for real Autofocus/Guide/
     * Polar-Align control — flipped on tap, same shape as [mountTracking]/[mountParked]. Doesn't
     * attempt to string-match the real wire's undocumented status/stage values (see
     * `EkosRemoteController.startAutofocus`'s doc) — the raw wire text is shown alongside these,
     * never derived from them. Known limitation, accepted: none of these three auto-clear when a
     * real routine finishes on its own; user taps Stop manually even after real completion.
     */
    val focusRunning: Boolean = false,
    val guiding: Boolean = false,
    val polarRunning: Boolean = false,
    val deviceKey: String = "mount",
    /** Sheet to return to when the device sheet closes, e.g. SETUP when opened from the rig wizard's device list. */
    val deviceOrigin: SheetType? = null,
    val focPos: Int = 18422,
    val coolTarget: Double = -10.0,
    val coolNow: Double = 12.4,
    val snappedMain: Boolean = false,
    val snappedGuide: Boolean = false,
    val rate: Int = 2,
    val slewDir: String? = null,
    val paStep: Int = 0,
    val paAlt: Double = 4.2,
    val paAz: Double = -1.8,
    val paRate: Int = 1,
    val query: String = "",
    val chips: List<Int> = listOf(0),
    val targetId: String = "NGC 7000",
    /**
     * The user catalogue — exactly one, always exists, name is the only thing
     * about the catalogue itself the user can change (no add/remove catalogues,
     * no second one). Targets within it are freely add/edit/remove-able.
     */
    val userCatalogName: String = "My targets",
    val userTargets: List<Target> = emptyList(),
    /** Monotonic id counter for custom targets — survives removals. */
    val userTargetSeq: Int = 1,
    val editingUserTargetId: String? = null,
    val addingUserTarget: Boolean = false,
    /**
     * The Scopes catalog (M3.1) — real Ekos manages telescopes/lenses in
     * their own dialog (`get_scopes`/`scope_add`, `EkosRemote-Command-
     * Reference.md` §4), entirely separate from both the rig Profile and
     * the Optical Train's per-slot role pickers; a train's Scope role just
     * *references* one of these by name, same as every other role. Lives on
     * the Gear tab next to Rig profile — configured once, prior to and
     * independent of which train slot uses it.
     */
    val scopes: List<ScopeDef> = DEFAULT_SCOPES,
    /** Monotonic id counter for user-added scopes — survives removals, same pattern as [userTargetSeq]. */
    val scopeSeq: Int = DEFAULT_SCOPES.size + 1,
    val editingScopeId: String? = null,
    val addingScope: Boolean = false,
    val profileName: String = "Field · 550 mm",
    val ekosRunning: Boolean = true,
    val activeProfile: String? = "Field · 550 mm",
    val profiles: List<RigProfile> = DEFAULT_PROFILES,
    val selectedProfile: String? = "Field · 550 mm",
    val setupEditingName: String? = null,
    val rotatorAngle: Double = 118.4,
    val domeOpen: Boolean = true,
    /** User edits only — keyed by catalog device name; unedited defaults come from [DRIVER_INDI_PROPS]. */
    val indiProps: Map<String, List<IndiProperty>> = emptyMap(),
    val prefs: Map<String, Boolean> = mapOf(
        "guide" to true,
        "cloud" to true,
        "disconnect" to true,
        "flip" to true,
        "frameCut" to false,
        "seqEnd" to true,
    ),
    /**
     * Real persisted capture frames (M4.3), newest-first — Room-backed via [FrameRepository],
     * fed by [com.nocturne.session.EkosRemoteController]'s own `frameRepo.observeAll()` collector.
     * Replaces the old fixture `cut: Set<String>`/`Frame`/`FRAME_IDS`/`FRAME_HFRS` entirely — each
     * row's own [FrameEntity.keep] *is* the real keep/cut state now, not a separate local set.
     */
    val frameRows: List<FrameEntity> = emptyList(),
    val devOff: Set<String> = setOf("rotator", "dome"),
    /** Which catalog entry is assigned per device category — key -> chosen name from that [Device.catalog]. */
    val selectedDeviceNames: Map<String, String> = DEVICES.associate { it.key to it.name },
    val primaryTrain: TrainAssignment = TrainAssignment(),
    val secondaryTrain: TrainAssignment = TrainAssignment(
        camera = "ASI174MM mini", rotator = "None", scope = "OAG",
        filterWheel = "None", focuser = "None",
    ),
    /** Focus sheet: real bookkeeping from the last "Run autofocus now" tap — feeds [focusNextAfMin]/TEMP Δ. */
    val focusLastAfAt: Int = 0,
    val focusTempAtLastAf: Double = -0.6,
    val quietHoursEnabled: Boolean = true,
    /** Bench sheet: live mount pointing, driven by the D-pad in [SimulatedController.tick]. */
    val mountAlt: Double = 49.2,
    val mountAz: Double = 71.6,
    /** Cleared whenever the mount slews — a solve is only valid until the mount moves again. */
    val mountSolved: Boolean = false,
    /**
     * Wire-mirror fields (M2): raw `new_*_state` payload fields from a real
     * EkosRemote connection, populated only by [EkosRemoteController] and
     * never read or written by [SimulatedController]. Additive and separate
     * from the simulator's own fields above rather than reusing them —
     * e.g. `new_mount_state` has no alt/az, so [mountAlt]/[mountAz] stay at
     * their simulator defaults on a real connection rather than being
     * fabricated. Null until the corresponding push has arrived at least once.
     */
    val wireCaptureStatus: String? = null,
    val wireMountStatus: String? = null,
    val wireMountTarget: String? = null,
    val wireMountSlewRate: Int? = null,
    val wireMountPierSide: Int? = null,
    val wireFocusStatus: String? = null,
    val wireGuideStatus: String? = null,
    val wireAlignStatus: String? = null,
    val wirePolarStage: String? = null,
    /** `new_polar_state`'s `enabled`/`message` fields — decoded since [EkosEvent.NewPolarState]
     * models them, but unused until Polar Alignment gets real wiring; kept alongside
     * [wirePolarStage] rather than discarded. */
    val wirePolarEnabled: Boolean? = null,
    val wirePolarMessage: String? = null,
    /**
     * `new_polar_state`'s real correction-vector/error fields (M4.4). [wirePolarVector]'s own
     * `pa`/`mag` aren't drawn as a directional arrow yet — Qt's `QLineF::angle()` rotation
     * convention isn't confirmed against a live solve (see [WirePolarVector]'s own doc) — but the
     * numeric az/alt/total error fields are real and shown as text in [PaRealSheet].
     * [wirePolarUpdatedError]/Az/Alt come from a *separate* real push (`setUpdatedErrors`, no
     * `vector` wrapper) that fires after a correction slew, not alongside [wirePolarVector].
     */
    val wirePolarVector: WirePolarVector? = null,
    val wirePolarUpdatedError: Double? = null,
    val wirePolarUpdatedAzError: Double? = null,
    val wirePolarUpdatedAltError: Double? = null,
    /**
     * `align_manual_rotator_status` push (M5, docs/STATUS.md) — Ekos's own current-vs-target
     * camera-position-angle readback, used both for a by-hand manual rotator turn (no real
     * rotator device) and to drive a real one via [rotatorAutoControl]. Only ever populates once
     * [rotatorAutoControl] is on *and* a solve (`align_solve`) has run — confirmed against the
     * real fork source (`align_goto.cpp`'s `checkIfRotationRequired()`), not driven by anything
     * else. Null until then; Ekos doesn't push this proactively.
     */
    val wireRotatorCurrentPA: Double? = null,
    val wireRotatorTargetPA: Double? = null,
    val wireRotatorThreshold: Double? = null,
    /** Local optimistic mirror of `align_set_astrometry_settings`'s `rotator_control` bool — the
     * **master gate** for the whole rotator feature (see [wireRotatorCurrentPA]'s own doc),
     * required on regardless of whether the primary train has a real rotator device
     * ([TrainAssignment.rotator] != "None") or not; which server-side branch runs (real auto-drive
     * vs. no-hardware manual-diff readback) depends on that, but this switch itself doesn't. */
    val rotatorAutoControl: Boolean = false,
    /**
     * `get_devices` translated to app-friendly shape — null until the first
     * push arrives (still showing the fixture [DEVICES] catalog), populated
     * only by [EkosRemoteController]. [SimulatedController] never touches
     * this, same discipline as every other wire-mirror field above.
     */
    val wireDevices: List<LiveDevice>? = null,
    /**
     * Real cameras' `CCD_INFO` vector (sensor resolution + pixel size), keyed by device name —
     * needed for Plan tab's Framing card to compute a real pixel-scale/FOV instead of the
     * literal placeholder it shipped with. Parsed specially (not via the generic single-value
     * [indiProps] map) because `CCD_INFO` has multiple number elements
     * (`CCD_MAX_X`/`CCD_MAX_Y`/`CCD_PIXEL_SIZE`) and the generic decode
     * (`WireProperty.Number.toIndiProperty`) only keeps the first one — a real gap in that
     * generic path for any multi-element vector, out of scope to fix generally here. Empty
     * until each connected camera's own `device_get` reply arrives.
     */
    val wireCcdInfoByDevice: Map<String, CcdInfo> = emptyMap(),
    /** `train_get_all` translated (M3) — read by `OpticalTrainCard` when present instead of [trainRolePool]. */
    val wireTrains: List<WireTrain>? = null,
    /**
     * Which real train (by name) each Ekos module currently uses (M3) — the
     * real `ProfileSettings` mechanism (`train_get_profiles`/`train_set`)
     * that actually connects an active Profile to its Optical Trains, kept
     * per-active-profile server-side. Keys are the module strings `train_set`
     * itself accepts: `"capture"`, `"focus"`, `"mount"`, `"guide"`, `"align"`,
     * `"darklibrary"`. Null until the first `train_get_profiles` reply —
     * [SimulatedController] never sets this, no fixture default either,
     * since there's no fixture equivalent of "which module uses which train."
     */
    val moduleTrainAssignments: Map<String, String>? = null,
    /**
     * Every driver label ever saved across any profile on this Pi, unioned
     * from every `get_profiles` reply's `drivers` maps and keyed by the same
     * family strings as [CATEGORY_TO_DRIVER_FAMILY] (M3) — see
     * [realDeviceOptions]. Null until the first `get_profiles` reply.
     */
    val wireKnownDrivers: Map<String, List<String>>? = null,
    /**
     * `get_scopes` translated (M3.1) — real Ekos's Scopes catalog is a
     * separate dialog from Optical Trains entirely (`get_scopes`/`scope_add`/
     * `scope_update`/`scope_delete`, message.cpp:204/1469/1474/1479), not
     * bundled into either the Profile editor or the Optical Train dialog.
     * Read by [ScopesCard]/[trainRolePool] instead of [scopes] when present.
     * Null until the first `get_scopes` reply (it's sent in the same
     * pre-online-ok burst as `get_profiles`/`train_get_all`).
     */
    val wireScopes: List<ScopeDef>? = null,
    /**
     * Name of a `profile_delete` that came back refused (M3) — real Ekos
     * silently refuses to delete `"Simulators"` or the active profile, with
     * no error sent; [EkosRemoteController] detects this by diffing the next
     * `get_profiles` push against the pre-delete list, and surfaces it here
     * rather than leaving the UI to silently do nothing.
     */
    val profileDeleteRefused: String? = null,
    /**
     * `astro_search_objects` → `astro_get_objects_info`/`astro_get_objects_riseset`
     * pipeline result (M3) — read by `PlanScreen`'s `matches` instead of
     * filtering the fixture [TARGETS] list when non-null. Null = no search
     * run yet (or [SimulatedController], which never touches this).
     */
    val wireSearchResults: List<Target>? = null,
    /** `scheduler_get_jobs` translated (M3) — cross-referenced for progress; see [SequenceJob.synced]. */
    val wireSchedulerJobs: List<WireSchedulerJob>? = null,
    /**
     * Whether the real Scheduler is currently toggled on — promoted out of what used to be a
     * private `EkosRemoteController` var so the UI can render a real Start/Stop-Scheduler
     * control. Written only from the real `new_scheduler_state` push, same "derived from real
     * events only, never optimistic" shape [ekosRunning] already uses — a prior fix this session
     * had to remove an optimistic flip here for causing a live "tapped stop, it started again"
     * bug, so this field must never be set from a local tap, only from a real event.
     */
    val schedulerRunning: Boolean = false,
    /**
     * Target name of a "Push to Ekos" that came back refused (M3.4) — real Ekos refuses a
     * duplicate job name outright (`"A job with name '...' already exists"`, confirmed live);
     * [EkosRemoteController.pushJob] checks [wireSchedulerJobs] before ever sending the add and
     * surfaces the refusal here rather than racing a server-side one, same shape as
     * [profileDeleteRefused].
     */
    val jobPushRefused: String? = null,
    /**
     * Target name of a "Remove from Ekos" refused *before it was even sent* (M3.4) — real Ekos
     * refuses `scheduler_remove_jobs` while the *Scheduler itself* is running
     * ([schedulerRunning]), confirmed live: `"Cannot delete currently running job"`.
     *
     * **Not** gated on the job's own real `state` — confirmed live this was the wrong signal: a
     * freshly-pushed job can read `SCHEDULED` from Ekos's own one-shot evaluation on add even
     * while the Scheduler itself stays `IDLE` (never toggled on), and removal is perfectly safe
     * in that case. [schedulerRunning] is the actual gating condition, independent of any one
     * job's own state.
     *
     * User feedback (2026-08-23): a blanket "are you sure?" confirm on every remove tap was the
     * wrong UX — [EkosRemoteController.removeJob] checks this first instead and only ever asks
     * the user to look here if it's actually going to be refused; otherwise the remove is sent
     * immediately, no dialog. Same shape as [jobPushRefused].
     */
    val jobRemoveRefused: String? = null,
    /**
     * Most recent real `/media/ekos` binary frame per device (M4.1), keyed by
     * [com.nocturne.protocol.MediaFrameType] — no history buffer here, this is just "what's on
     * screen right now"; [FramesScreen]'s own grid (M4.3) is backed by Room instead, not this.
     * Null until the first frame of that type actually arrives; [EkosRemoteController] routes
     * each inbound [com.nocturne.protocol.MediaFrame] into the matching field by its header's
     * real `uuid` tag ([com.nocturne.protocol.frameType]).
     */
    val latestCaptureFrame: com.nocturne.protocol.MediaFrame? = null,
    val latestAlignFrame: com.nocturne.protocol.MediaFrame? = null,
    val latestFocusFrame: com.nocturne.protocol.MediaFrame? = null,
    val latestGuideFrame: com.nocturne.protocol.MediaFrame? = null,
    /**
     * `mount_get_all_settings` translated (M3.3, curated subset — see docs/M3.3-plan.md).
     * Null until the first reply (sent when [MOUNT_SETTINGS] sheet opens); also gates that
     * sheet's real-vs-simulator content, same as [wireTrains]/[wireScopes].
     */
    val wireMountSettings: WireMountSettings? = null,
    /**
     * `capture_get_all_settings` translated (M3.3, curated subset — see docs/M3.3-plan.md).
     * Null until the first reply (sent eagerly on connect, same as [wireMountSettings]); also
     * gates [CAMERA_SETTINGS] sheet's real-vs-simulator content.
     */
    val wireCaptureSettings: WireCaptureSettings? = null,
    /**
     * `align_get_all_settings` translated (M3.3 phase 3, curated subset — see
     * docs/M3.3-plan.md). Null until the first reply (sent eagerly on connect, same as
     * [wireMountSettings]/[wireCaptureSettings]); also gates [ALIGN_SETTINGS] sheet's
     * real-vs-simulator content.
     */
    val wireAlignSettings: WireAlignSettings? = null,
    /**
     * `guide_get_all_settings` translated (partial — see [WireGuideSettings]'s own doc). Null
     * until the first reply (sent eagerly on connect, same as the other module settings above);
     * gates Bench "Snap guide"'s exposure/gain/binning controls (real-rig only, no fixture
     * equivalent, same as the others).
     */
    val wireGuideSettings: WireGuideSettings? = null,
    /**
     * `focus_get_all_settings` translated (partial — see [WireFocusSettings]'s own doc). Null
     * until the first reply (sent eagerly on connect, same as the other module settings above).
     * Not read directly by the UI — its `absTicksSpin` seeds [focPos] once, on arrival (see
     * [EkosRemoteController.applyEvent]'s `FocusSettings` arm); kept here mainly so
     * [benchFocPos] can tell "already seeded" apart from "still on the raw-INDI fallback".
     */
    val wireFocusSettings: WireFocusSettings? = null,
    /**
     * `scheduler_get_all_settings` translated (curated subset — see [WireSchedulerSettings]'s own
     * doc). Null until the first reply (sent eagerly on connect, same as the other module
     * settings above); gates [SCHEDULER_SETTINGS] sheet's real-vs-simulator content, no fixture
     * equivalent (same as [wireMountSettings] etc).
     */
    val wireSchedulerSettings: WireSchedulerSettings? = null,
    /**
     * `astro_get_almanac`'s `Dusk`/`Dawn` (M2026-08 Session tab real night-arc) — signed
     * fraction-of-day offsets from local midnight, see [EkosEvent.AstroAlmanac]'s own doc for the
     * exact sign convention. Null until the first reply (sent eagerly on connect, same as the
     * module settings above); real-rig only, no fixture equivalent.
     */
    val wireDusk: Double? = null,
    val wireDawn: Double? = null,
    /** `astro_get_location`'s `tz` — real signed UTC hour offset, needed to resolve [wireDusk]/[wireDawn] into an absolute instant. Null until the first reply. */
    val wireSiteTz: Double? = null,
    /**
     * Real `astro_get_objects_riseset` for the Plan tab's *currently framed* target specifically —
     * independent of [wireSearchResults]' own riseset-driven fields (`Target.max`/`peak`), which
     * only ever cover live search results, never the fixture `TARGETS` catalog or custom targets.
     * Populated on demand (see [EkosRemoteController.ensureTargetRiseset]) whenever the framed
     * target changes; check `.name` matches the framed target before trusting it — real-rig only.
     */
    val wireTargetRiseset: WireRiseset? = null,
    /**
     * Real astronomy reference-image cutout (M5, docs/STATUS.md) for the Plan tab's Framing card
     * — a real DSS sky survey image centered on the framed target's own RA/Dec, fetched from CDS
     * Strasbourg's `hips2fits` service (`transport/ReferenceImageClient.kt`) — **the first direct
     * internet call this app makes that isn't to the Pi itself**, worth remembering next time the
     * network/trust-model doc gets revisited. User's explicit call: use this over the Plan tab's
     * own just-captured live camera frame (which used to sit behind the FOV box there) — framing
     * is about the sky the target actually sits in, not whatever this session's own camera has
     * captured so far. [referenceImageForTargetId] guards against a stale image surviving a target
     * switch before the new fetch lands; null jpeg (fetch failed/offline/no internet) falls back to
     * an honest placeholder, not a silently wrong image.
     */
    val referenceImageJpeg: ByteArray? = null,
    val referenceImageForTargetId: String? = null,
    /** Companion reboot daemon's port on the rig's Pi — separate from the EkosRemote wire port (see `pi-tools/reboot-daemon/`). */
    val rigRebootPort: Int = 9001,
    /** Whether a reboot token has been configured — the token itself never enters [AppState] (kept only inside the controller), so nothing display-worthy leaks it into logs/recompositions. */
    val rigRebootTokenSet: Boolean = false,
    /** True once a token is configured under a real rig — [MaintenanceSheet] shows the reboot button only then. */
    val rigRebootAvailable: Boolean = false,
    val rigRebootState: RigRebootState = RigRebootState.IDLE,
    val rigRebootError: String? = null,
)

/** One `get_devices` entry, decoded to the roles its `interface` bitmask ORs together. */
data class LiveDevice(val name: String, val connected: Boolean, val roles: Set<DeviceRole>)

/** A real camera's `CCD_INFO` vector — see [AppState.wireCcdInfoByDevice]. */
data class CcdInfo(val maxX: Int, val maxY: Int, val pixelUm: Double)

// ── Catalog data (prototype script constants) ──────────────────────────────

val PA_SECS = listOf(1, 2, 5, 10)
val PLAN_CHIPS = listOf("Up tonight", "Alt > 40°", "Narrowband", "Fits FOV")

/**
 * [size]/[band]/[max]/[peak]/[usable]/[fov] are precomputed fixture display
 * values for the well-known catalog below — there's no real astro engine in
 * M1 (that's `astro_get_object_info` etc., M2/M3). They're nullable because
 * user-catalogue [custom] targets only ever get [id]/[common]/[coords] —
 * there's nothing to compute those fields from yet.
 */
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
     * Resolved J2000 coords/magnitude (M3) — populated only for
     * [AppState.wireSearchResults] entries (from `astro_get_objects_info`),
     * carried through to [addToSequence]/`SequenceJob.targetRA/DEC`. Null for
     * every fixture/user-catalogue target, which never resolves against the
     * real astro engine.
     */
    val ra0: Double? = null,
    val de0: Double? = null,
    val magnitude: Double? = null,
)

/** Well-known catalog — read-only, bundled with the app. Kept at 10 fixture entries for M1. */
val TARGETS = listOf(
    Target("NGC 7000", "North America", "20h59m17s +44°31′44″", "120′×100′", "Ha", 78, "01:12", "4h 22m", 1),
    Target("IC 1396", "Elephant Trunk", "21h39m06s +57°30′00″", "170′×140′", "Ha", 81, "01:44", "4h 40m", 0),
    Target("NGC 6888", "Crescent", "20h12m07s +38°21′18″", "18′×12′", "Ha OIII", 72, "00:18", "3h 50m", 1),
    Target("IC 1805", "Heart", "02h32m42s +61°27′00″", "150′×150′", "SHO", 84, "04:02", "2h 10m", 0),
    Target("M 31", "Andromeda", "00h42m44s +41°16′09″", "190′×60′", "LRGB", 76, "02:48", "3h 30m", 0),
    Target("M 27", "Dumbbell", "19h59m36s +22°43′16″", "8′×6′", "LRGB Ha", 58, "23:40", "2h 55m", 1),
    Target("NGC 7380", "Wizard", "22h47m00s +58°06′00″", "25′×25′", "SHO", 82, "02:26", "4h 05m", 1),
    Target("IC 5070", "Pelican", "20h50m48s +44°21′00″", "60′×50′", "Ha", 78, "01:04", "4h 18m", 1),
    Target("NGC 281", "Pacman", "00h52m59s +56°37′19″", "35′×30′", "SHO", 83, "03:20", "3h 05m", 1),
    Target("M 45", "Pleiades", "03h47m24s +24°07′00″", "110′×110′", "LRGB", 61, "04:10", "1h 20m", 0),
)

data class PrefDef(val key: String, val label: String, val desc: String)

val PREF_DEFS = listOf(
    PrefDef("guide", "Guiding degraded", "RMS > 1.0″ for 3 subs"),
    PrefDef("cloud", "Cloud or unsafe weather", "cloud > 30% · wind > 35 km/h · rain"),
    PrefDef("disconnect", "Device disconnect", "any linked device drops"),
    PrefDef("flip", "Meridian flip", "10 min before, and on completion"),
    PrefDef("frameCut", "Frame rejected", "HFR or star count outside limits"),
    PrefDef("seqEnd", "Sequence finished", "or paused for any reason"),
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
 * A saved equipment profile (`get_profiles`/`profile_add` per the wire
 * protocol) — what a real Ekos Profile actually is: name + driver selection
 * + connection mode. Optics (focal length/aperture) live entirely in the
 * Scopes catalog ([ScopeDef]/[AppState.scopes], M3.1), referenced by a
 * train's [TrainAssignment.scope] — a real Profile carries no optics of its
 * own, that's the Scopes catalog + Optical Train's job.
 */
data class RigProfile(
    val name: String,
    val deviceKeys: List<String>,
    /**
     * Real `get_profiles`'s `drivers` map (device-family name to that family's driver-label list,
     * M3) — [deviceKeys] alone (a flattened label list) can't drive
     * [editProfile]'s per-category picker, since it's lost which family
     * each label came from. Empty for every [SimulatedController] fixture
     * profile — [deviceKeys] there is category *keys*, not driver labels,
     * a different thing entirely (see the doc above `DEFAULT_PROFILES`).
     */
    val drivers: Map<String, List<String>> = emptyMap(),
    /** Which entry in `drivers["CCDs"]` (if any) is the guide camera — see [WireProfile.guider]. */
    val guider: String = "",
)

val DEFAULT_PROFILES = listOf(
    RigProfile("Field · 550 mm", listOf("mount", "cam", "efw", "guide", "focus", "rotator", "weather")),
    RigProfile("Wide field · 250 mm", listOf("mount", "cam", "efw", "focus", "weather")),
    RigProfile("RC8 imaging · 1000 mm", listOf("mount", "cam", "efw", "guide", "focus", "rotator", "weather")),
    RigProfile("Bench test · 550 mm", listOf("mount", "cam")),
)

val AppState.activeRigProfile: RigProfile? get() = profiles.firstOrNull { it.name == activeProfile }

/** Looks up a [ScopeDef] by name (a [TrainAssignment.scope]/[TrainAssignment] reference) — real or fixture catalog, whichever is current. */
fun AppState.findScope(name: String): ScopeDef? = (wireScopes ?: scopes).firstOrNull { it.name == name }

/** "1160 mm · f/8.9" — formats the F-ratio from a [ScopeDef]'s focal length + aperture. */
fun fRatio(focalMm: Int, apertureMm: Int): String =
    if (apertureMm <= 0) "—" else "f/${"%.1f".format(focalMm.toDouble() / apertureMm)}"

/**
 * One entry in the Scopes catalog — real Ekos's `OAL::Scope::toJson()`
 * (`get_scopes`/`scope_add`/`scope_update`/`scope_delete`,
 * `EkosRemote-Command-Reference.md` §4): `{id, model, vendor, type, name,
 * focal_length, aperture}`. Entirely separate from both the rig Profile and
 * the Optical Train dialog in real Ekos — a train's Scope role just
 * references one of these by [name], same as every other role's device pick.
 * `id` is server-assigned once real ([EkosRemoteController] populates
 * [AppState.wireScopes] from the wire); locally it's `"scope_<seq>"`.
 */
data class ScopeDef(
    val id: String,
    val name: String,
    val vendor: String = "",
    val type: String = "",
    val focalMm: Int,
    val apertureMm: Int,
)

val DEFAULT_SCOPES = listOf(
    ScopeDef(id = "scope_1", name = "Field APO", vendor = "", type = "Refractor", focalMm = 550, apertureMm = 130),
    ScopeDef(id = "scope_2", name = "OAG", vendor = "", type = "Guide scope", focalMm = 240, apertureMm = 50),
)

/** Which Optical Train slot — Ekos only ever has these two roles. */
enum class TrainSlot { PRIMARY, SECONDARY }

/** One assignable role within a train — everything but [TrainAssignment.reducer]. */
enum class TrainRole { MOUNT, CAMERA, ROTATOR, GUIDE_VIA, DUST_CAP, SCOPE, FILTER_WHEEL, FOCUSER, LIGHT_BOX, ADAPTIVE_OPTICS }

/**
 * One Optical Train's device-role assignments — mirrors `train_get_all`'s
 * `mount`/`camera`/`guider`/`focuser`/`filterwheel`/`rotator`/`reducer`/
 * `dustcap`/`lightbox`/`scope` fields (message.cpp:236, `OpticalTrainManager::getOpticalTrains()`).
 * Each field's picker pool is whatever's currently selected in the rig
 * profile's device/scope categories — Dust cap and Light box have no backing
 * category yet, so they stay "None" (matches the real dialog's own idle state
 * for optional roles with nothing connected).
 */
data class TrainAssignment(
    val mount: String = "EQ6-R Pro",
    val camera: String = "ASI2600MM Pro",
    val rotator: String = "None",
    val guideVia: String = "EQ6-R Pro",
    val dustCap: String = "None",
    val scope: String = "Field APO",
    val filterWheel: String = "EFW 7×36 mm",
    val focuser: String = "EAF",
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

/** Which [DeviceRole] bit(s) a train role's real-device pool should draw from — [SCOPE] has none, it's the Scopes catalog, not a device. */
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
 * Picker pool for one train role. Real connection ([wireDevices] non-null):
 * every connected device whose `interface` bitmask ORs in this role's
 * [DeviceRole] — [TrainRole.SCOPE] has no device backing (it's the Scopes
 * catalog, `get_scopes`/`scope_add`, not an INDI device) so it always falls
 * through to the `when` block below regardless of connection mode, where it
 * reads [wireScopes] (real) or [scopes] (fixture) by name.
 * [SimulatedController] pool (fixture): sourced from the rig profile's
 * device category selections, per the design brief ("the dropdown list is
 * from the devices in the rig profile"). Dust cap/Light box/AO have no
 * backing category there, so they're always just "None".
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
 * One device role/category a rig profile can assign — mirrors an INDI
 * `DRIVER_INTERFACE` family (`get_devices`'s `interface` bitmask decides which
 * role a connected device fills). [catalog] simulates the list of devices
 * `get_devices` would return for that family; first entry ("None") only for
 * optional (non-[req]) roles, since a rig doesn't need a dome or rotator.
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
    Device("mount", "EQ6-R Pro", "tracking · sidereal · W of pier", true, listOf(
        "Driver" to "EQMOD ASCOM",
        "Port" to "USB · COM4 · 115 200",
        "Site" to "52.37 N · 4.89 E · 12 m",
        "Pier side" to "West · flip at +5 min",
    ), catalog = listOf("EQ6-R Pro", "LX200 OnStep", "iOptron CEM70")),
    Device("cam", "ASI2600MM Pro", "−10.0 °C · cooler 68% · 42 subs", true, listOf(
        "Driver" to "ZWO ASCOM · native",
        "Set point" to "−10.0 °C · cooler 68%",
        "Readout" to "Mode 0 · gain 100 · off 50",
        "Pixel scale" to "3.76 µm · 1.24 ″/px @ 550 mm",
    ), catalog = listOf("ASI2600MM Pro", "ToupTek ATR2600M", "QHY268M")),
    Device("guide", "ASI174MM mini", "2 s · 38 SNR · 0.48″", false, listOf(
        "Driver" to "PHD2 · localhost:4400",
        "Scope" to "OAG · 550 mm · 4.86 ″/px",
        "Calibration" to "valid · 22:04",
    ), catalog = listOf("None", "ASI174MM mini", "ASI120MM mini")),
    Device("efw", "EFW 7×36 mm", "pos 2 · Ha 3 nm", false, listOf(
        "Driver" to "ZWO EFW",
        "Slots" to "L · R · G · B · Ha · OIII · SII",
        "Offsets" to "per-filter focus offsets on",
    ), catalog = listOf("None", "EFW 7×36 mm", "ZWO EFW mini")),
    Device("focus", "EAF", "18 422 · −0.6 °C drift", false, listOf(
        "Driver" to "ZWO EAF",
        "Range" to "0 → 62 000 · backlash 90",
        "Temp comp" to "−12 steps / °C",
    ), catalog = listOf("None", "EAF", "Pegasus FocusCube 3")),
    Device("rotator", "Optec Pyxis", "118.4° · sky PA locked", false, listOf(
        "Driver" to "Optec Pyxis",
        "Sky PA" to "locked to 118.4°",
        "Reverse" to "off",
    ), catalog = listOf("None", "Optec Pyxis")),
    Device("dome", "None", "not assigned", false, listOf(
        "Driver" to "—",
    ), catalog = listOf("None", "MaxDome II", "NexDome")),
    Device("weather", "AAG CloudWatcher NG", "safe · cloud 4% · wind 6 km/h", false, listOf(
        "Driver" to "AAG CloudWatcher NG",
        "Unsafe when" to "cloud > 30% · wind > 35 km/h · rain",
        "On unsafe" to "park + close roof",
    ), catalog = listOf("None", "AAG CloudWatcher NG", "Weather Watcher")),
    Device("powerbox", "Pegasus Ultimate Powerbox v2", "12.1 V · 3.42 A", false, listOf(
        "Driver" to "Pegasus Ultimate Powerbox v2",
    ), catalog = listOf("None", "Pegasus Ultimate Powerbox v2", "Pegasus Pocket Powerbox")),
)

/** Which [DeviceRole] bit(s) a rig-setup category's real-device pool should draw from. */
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
 * `ProfileInfo.drivers` family keys (`EkosRemote-Command-Reference.md` §3
 * confirms `"Telescopes"`/`"CCDs"`/`"Focusers"`/`"Filter Wheels"` only — the
 * rest are this app's best-effort guess at the remaining `INDI::DriverInterface`
 * family names Ekos's own driver-selection combo uses, unverified against
 * source. `finishSetup`'s wire payload is fire-and-refresh either way
 * (auto-replies with a fresh `get_profiles`), so a wrong family key here
 * just means that device lands under "Aux" until corrected in real Ekos.
 * Shared by [EkosRemoteController]'s profile_add/update translation and by
 * [realDeviceOptions] below.
 *
 * `"guide"` deliberately maps to the *same* family as `"cam"` — confirmed
 * live, real Ekos has no separate "Guiders" family; a guide camera is just
 * a second `"CCDs"` entry, disambiguated only by the legacy `guider` field
 * ([WireProfile.guider]/[RigProfile.guider]), not by `drivers` map structure
 * at all. Only used for building the *pool* here — resolving which value is
 * actually *selected* for `"guide"` must read `.guider` directly, never this
 * family lookup (which would just return the main camera).
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
)

/**
 * Real-device options for one rig-setup category (M3) — [DevicePickerBody]
 * (the New/Edit rig profile wizard) reads this instead of [Device.catalog]'s
 * fixed fixture list whenever connected, same discipline as the Gear tab's
 * device list/Optical Train already following [wireDevices] over fixtures.
 *
 * There's no wire command to list installed-but-not-running drivers (only
 * `get_devices` — currently connected — and each profile's own saved
 * selections) — a real Profile Editor sources that from a local drivers.xml
 * this protocol never exposes. So the pool here is the union of two real
 * (not invented) sources: whatever's *currently connected* ([wireDevices],
 * empty/absent until Ekos is actually running) and every driver label
 * *ever saved* across any profile on this Pi ([wireKnownDrivers], available
 * as soon as `get_profiles` replies — even before Ekos starts, which is
 * exactly the case a rig-setup wizard needs). Null only when neither source
 * has arrived yet at all, i.e. not connected — falls back to the fixture
 * catalog then.
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
 * Generic INDI property vector — mirrors the wire protocol's `device_get`/
 * `device_property_get` shapes (Switch/Number/Text/Light) so the UI can render
 * any device's controls the same way, without per-device-specific screens.
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
         * Real element name per option (M3) — defaults to [options] so all 19
         * [DRIVER_INDI_PROPS] fixture entries keep compiling/rendering
         * unchanged. A real device's `device_property_set` needs the actual
         * INDI element name (`elementNames[index]`), which isn't always the
         * same string as the display label.
         */
        val elementNames: List<String> = options,
    ) : IndiProperty()

    data class NumberProp(
        override val name: String, override val label: String, override val group: String,
        val value: Double, val min: Double, val max: Double, val step: Double, val format: String = "%.1f",
        /** Real INDI element name within this vector (M3) — see [SwitchProp.elementNames]. */
        val elementName: String = name,
        /**
         * Real vector-level IPState (M3.4) — 0 Idle, 1 Ok, 2 Busy, 3 Alert. Defaults to 1 (Ok) so
         * every pre-existing [DRIVER_INDI_PROPS] fixture entry keeps compiling/rendering unchanged.
         * `WireProperty.Number.state` already decodes this; only the generic-property conversion
         * had been dropping it. Added specifically so a real `CCD_EXPOSURE` vector's own Busy state
         * (confirmed live: 2 while an exposure is running, 1 once idle/complete) can drive a real
         * "capturing" indicator — see [indiBusy].
         */
        val state: Int = 1,
    ) : IndiProperty()

    /**
     * Multi-element (2026-08-23, real gap found live): used to hold a single `value`/
     * `elementName` — real INDI text vectors can carry several elements at once (confirmed
     * live: `FILTER_NAME`, one element per filter-wheel slot), and the old shape silently kept
     * only the first, with no way to even represent the rest. `elements` is (real INDI element
     * name, current value), same `Pair`-list shape [LightProp.elements] already uses for the
     * same reason.
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

/** Looks up a NumberProp's live value by property name — user's own edit first, else the driver fixture default. */
fun AppState.indiNumber(deviceName: String, propName: String): Double? {
    val props = indiProps[deviceName] ?: DRIVER_INDI_PROPS[deviceName] ?: emptyList()
    return (props.firstOrNull { it.name == propName } as? IndiProperty.NumberProp)?.value
}

/** Looks up a NumberProp's own vector-level IPState (see [IndiProperty.NumberProp.state]) by property name. */
fun AppState.indiBusy(deviceName: String, propName: String): Boolean {
    val props = indiProps[deviceName] ?: DRIVER_INDI_PROPS[deviceName] ?: emptyList()
    return (props.firstOrNull { it.name == propName } as? IndiProperty.NumberProp)?.state == 2
}

/**
 * Real per-driver property layouts — keyed by catalog device name, not by
 * role. Sourced from the actual driver code in `~/cc/repo/indi` and
 * `~/cc/repo/indi-3rdparty` (property names/groups/ranges verified against
 * source, not invented). Trimmed to the "Main Control"-tier controls a mobile
 * panel would expose — diagnostics/firmware/alignment tabs are skipped, same
 * as the old fake per-role set did implicitly.
 *
 * Values queried live from the SDK on a real connection (gain ranges, step
 * counts, etc.) are given plausible fixed defaults here — the shape (which
 * properties exist, their groups) is what's verified, not every live value.
 */
val DRIVER_INDI_PROPS: Map<String, List<IndiProperty>> = mapOf(
    // Mounts — INDI::Telescope base (EQUATORIAL_EOD_COORD/ON_COORD_SET/etc.) omitted;
    // Nocturne's Session tab already covers RA/DEC/slew/park at a higher level.
    "EQ6-R Pro" to listOf(
        IndiProperty.SwitchProp("TELESCOPE_SLEW_RATE", "Slew Rate", "Motion Control", listOf("1x", "32x", "128x", "800x"), 2),
        IndiProperty.SwitchProp("TELESCOPE_TRACK_MODE", "Track Mode", "Main Control", listOf("Sidereal", "Solar", "Lunar", "Custom"), 0),
        IndiProperty.NumberProp("GUIDE_RATE_WE", "Guide rate WE", "Motion Control", 0.5, 0.0, 1.0, 0.1),
        IndiProperty.NumberProp("GUIDE_RATE_NS", "Guide rate NS", "Motion Control", 0.5, 0.0, 1.0, 0.1),
        IndiProperty.SwitchProp("REVERSEDEC", "Reverse DEC", "Main Control", listOf("Enable", "Disable"), 1),
    ),
    "LX200 OnStep" to listOf(
        IndiProperty.SwitchProp("TELESCOPE_SLEW_RATE", "Slew Rate", "Motion Control", listOf("0.25x", "1x", "8x", "Max"), 2),
        IndiProperty.SwitchProp("TELESCOPE_TRACK_MODE", "Track Mode", "Main Control", listOf("Sidereal", "Solar", "Lunar", "Custom"), 0),
        IndiProperty.SwitchProp("COMPENSATION", "Compensation Tracking", "Motion Control", listOf("Full Compensation", "Refraction", "Off"), 2),
        IndiProperty.SwitchProp("AUTOFLIP", "Meridian Auto Flip", "Motion Control", listOf("Off", "On"), 1),
        IndiProperty.NumberProp("GUIDE_RATE_WE", "Guide rate WE", "Motion Control", 0.5, 0.0, 1.0, 0.25),
        IndiProperty.NumberProp("GUIDE_RATE_NS", "Guide rate NS", "Motion Control", 0.5, 0.0, 1.0, 0.25),
    ),
    "iOptron CEM70" to listOf(
        IndiProperty.SwitchProp("TELESCOPE_SLEW_RATE", "Slew Rate", "Motion Control", listOf("1x", "64x", "256x", "MAX"), 1),
        IndiProperty.SwitchProp("TELESCOPE_TRACK_MODE", "Track Mode", "Main Control", listOf("Sidereal", "Lunar", "Solar"), 0),
        IndiProperty.SwitchProp("MERIDIAN_ACTION", "Meridian Action", "Meridian Behavior", listOf("Stop", "Flip"), 1),
        IndiProperty.NumberProp("MERIDIAN_LIMIT", "Meridian limit", "Meridian Behavior", 0.0, 0.0, 10.0, 1.0, "%.0f°"),
        IndiProperty.NumberProp("RA_GUIDE_RATE", "RA guide rate", "Motion Control", 0.5, 0.01, 0.9, 0.1),
        IndiProperty.NumberProp("DE_GUIDE_RATE", "DE guide rate", "Motion Control", 0.5, 0.1, 0.99, 0.1),
    ),

    // Cameras — ZWO ASI (same driver for all three), ToupTek, QHY.
    "ASI2600MM Pro" to listOf(
        IndiProperty.SwitchProp("CCD_COOLER", "Cooler", "Main Control", listOf("On", "Off"), 0),
        IndiProperty.NumberProp("CCD_TEMPERATURE", "CCD temperature", "Main Control", -10.0, -50.0, 50.0, 1.0, "%.1f °C"),
        IndiProperty.NumberProp("Gain", "Gain", "Controls", 100.0, 0.0, 570.0, 1.0, "%.0f"),
        IndiProperty.NumberProp("Offset", "Offset", "Controls", 50.0, 0.0, 400.0, 1.0, "%.0f"),
    ),
    "ASI174MM mini" to listOf(
        IndiProperty.NumberProp("Gain", "Gain", "Controls", 200.0, 0.0, 570.0, 1.0, "%.0f"),
        IndiProperty.NumberProp("Offset", "Offset", "Controls", 20.0, 0.0, 400.0, 1.0, "%.0f"),
        IndiProperty.SwitchProp("FLIP", "Flip", "Main Control", listOf("Horizontal", "Vertical"), -1),
    ),
    "ASI120MM mini" to listOf(
        IndiProperty.NumberProp("Gain", "Gain", "Controls", 200.0, 0.0, 600.0, 1.0, "%.0f"),
        IndiProperty.NumberProp("Offset", "Offset", "Controls", 20.0, 0.0, 400.0, 1.0, "%.0f"),
    ),
    "ToupTek ATR2600M" to listOf(
        IndiProperty.SwitchProp("CCD_COOLER", "Cooler", "Main Control", listOf("On", "Off"), 0),
        IndiProperty.NumberProp("CCD_TEMPERATURE", "CCD temperature", "Main Control", -10.0, -40.0, 20.0, 1.0, "%.1f °C"),
        IndiProperty.NumberProp("Gain", "Gain", "Control", 100.0, 0.0, 1000.0, 1.0, "%.0f"),
        IndiProperty.NumberProp("Contrast", "Contrast", "Control", 0.0, -255.0, 255.0, 1.0, "%.0f"),
        IndiProperty.NumberProp("Gamma", "Gamma", "Control", 100.0, 20.0, 180.0, 1.0, "%.0f"),
    ),
    "QHY268M" to listOf(
        IndiProperty.SwitchProp("CCD_COOLER", "Cooler", "Main Control", listOf("On", "Off"), 0),
        IndiProperty.NumberProp("CCD_COOLER_POWER", "Cooling power", "Main Control", 68.0, 0.0, 100.0, 5.0, "%.0f %"),
        IndiProperty.NumberProp("CCD_TEMPERATURE", "CCD temperature", "Main Control", -10.0, -40.0, 20.0, 1.0, "%.1f °C"),
        IndiProperty.NumberProp("GAIN", "Gain", "Main Control", 30.0, 0.0, 100.0, 1.0, "%.0f"),
        IndiProperty.NumberProp("OFFSET", "Offset", "Main Control", 20.0, 0.0, 255.0, 1.0, "%.0f"),
    ),

    // Filter wheel — ZWO EFW driver (asi_wheel.cpp).
    "EFW 7×36 mm" to listOf(
        IndiProperty.NumberProp("FILTER_SLOT_VALUE", "Filter", "Filter Wheel", 2.0, 1.0, 7.0, 1.0, "%.0f"),
        IndiProperty.SwitchProp("FILTER_UNIDIRECTIONAL_MOTION", "Uni Direction", "Main Control", listOf("Enable", "Disable"), 1),
    ),
    "ZWO EFW mini" to listOf(
        IndiProperty.NumberProp("FILTER_SLOT_VALUE", "Filter", "Filter Wheel", 1.0, 1.0, 5.0, 1.0, "%.0f"),
        IndiProperty.SwitchProp("FILTER_UNIDIRECTIONAL_MOTION", "Uni Direction", "Main Control", listOf("Enable", "Disable"), 1),
    ),

    // Focusers — ZWO EAF (asi_focuser.cpp), Pegasus FocusCube3.
    "EAF" to listOf(
        IndiProperty.SwitchProp("FOCUS_MOTION", "Direction", "Main Control", listOf("Focus In", "Focus Out"), 0),
        IndiProperty.NumberProp("FOCUS_ABSOLUTE_POSITION", "Absolute position", "Main Control", 18422.0, 0.0, 100000.0, 1000.0, "%.0f"),
        IndiProperty.SwitchProp("FOCUS_REVERSE_MOTION", "Reverse Motion", "Main Control", listOf("Enabled", "Disabled"), 1),
        IndiProperty.SwitchProp("FOCUS_BACKLASH_TOGGLE", "Backlash", "Main Control", listOf("Enabled", "Disabled"), 1),
        IndiProperty.NumberProp("FOCUS_TEMPERATURE", "Temperature", "Main Control", -0.6, -50.0, 70.0, 0.1, "%.1f °C"),
    ),
    "Pegasus FocusCube 3" to listOf(
        IndiProperty.SwitchProp("FOCUS_MOTION", "Direction", "Main Control", listOf("Focus In", "Focus Out"), 0),
        IndiProperty.NumberProp("FOCUS_ABSOLUTE_POSITION", "Absolute position", "Main Control", 18422.0, 0.0, 1317500.0, 1000.0, "%.0f"),
        IndiProperty.NumberProp("TEMP", "Temperature", "Settings", -0.6, -40.0, 40.0, 0.1, "%.1f °C"),
        IndiProperty.NumberProp("MaxSpeed", "Max speed", "Settings", 400.0, 100.0, 1000.0, 100.0, "%.0f"),
    ),

    // Rotator — Optec Pyxis (core INDI, drivers/rotator/pyxis.cpp).
    "Optec Pyxis" to listOf(
        IndiProperty.NumberProp("ANGLE", "Angle", "Main Control", 118.4, 0.0, 360.0, 10.0, "%.2f°"),
        IndiProperty.SwitchProp("ROTATOR_REVERSE", "Reverse", "Main Control", listOf("Enabled", "Disabled"), 1),
        IndiProperty.NumberProp("ROTATION_RATE", "Rotation rate", "Settings", 8.0, 0.0, 99.0, 10.0, "%.0f"),
    ),

    // Domes — NexDome (indi-3rdparty, verified in detail); MaxDome II only
    // verified to exist (not researched in detail) — uses the shared
    // INDI::Dome base properties every dome driver inherits.
    "NexDome" to listOf(
        IndiProperty.SwitchProp("DOME_SHUTTER", "Shutter", "Main Control", listOf("Open", "Close"), 1),
        IndiProperty.SwitchProp("DOME_MOTION", "Motion", "Main Control", listOf("Dome CW", "Dome CCW"), -1),
        IndiProperty.NumberProp("DOME_ABSOLUTE_POSITION", "Absolute position", "Main Control", 0.0, 0.0, 360.0, 1.0, "%.0f°"),
        IndiProperty.SwitchProp("DOME_PARK", "Parking", "Main Control", listOf("Park", "UnPark"), 1),
        IndiProperty.SwitchProp("DOME_AUTOSYNC", "Slaving", "Dome Slaving", listOf("Enable", "Disable"), 1),
    ),
    "MaxDome II" to listOf(
        IndiProperty.SwitchProp("DOME_SHUTTER", "Shutter", "Main Control", listOf("Open", "Close"), 1),
        IndiProperty.SwitchProp("DOME_MOTION", "Motion", "Main Control", listOf("Dome CW", "Dome CCW"), -1),
        IndiProperty.NumberProp("DOME_ABSOLUTE_POSITION", "Absolute position", "Main Control", 0.0, 0.0, 360.0, 1.0, "%.0f°"),
        IndiProperty.SwitchProp("DOME_PARK", "Parking", "Main Control", listOf("Park", "UnPark"), 1),
    ),

    // Weather — AAG CloudWatcher NG (indi-3rdparty, real driver); Weather
    // Watcher (core INDI, generic file/URL-poll driver).
    "AAG CloudWatcher NG" to listOf(
        IndiProperty.LightProp("WEATHER_STATUS", "Status", "Main Control", listOf("Wind" to 1, "Rain" to 1, "Cloud" to 1, "Humidity" to 1)),
        IndiProperty.NumberProp("WEATHER_WIND_SPEED", "Wind speed", "Parameters", 6.0, 0.0, 30.0, 1.0, "%.1f km/h"),
        IndiProperty.NumberProp("WEATHER_CLOUD", "Cloud (sky-temp diff)", "Parameters", -20.0, -40.0, 60.0, 1.0, "%.1f °C"),
        IndiProperty.NumberProp("WEATHER_HUMIDITY", "Humidity", "Parameters", 42.0, 0.0, 100.0, 1.0, "%.0f %"),
        IndiProperty.NumberProp("WEATHER_RAIN", "Rain (cycles)", "Parameters", 8500.0, 2000.0, 10000.0, 100.0, "%.0f"),
    ),
    "Weather Watcher" to listOf(
        IndiProperty.LightProp("WEATHER_STATUS", "Status", "Main Control", listOf("Rain" to 1, "Wind" to 1, "Clouds" to 1)),
        IndiProperty.NumberProp("WEATHER_TEMPERATURE", "Temperature", "Parameters", 12.0, -10.0, 30.0, 1.0, "%.1f °C"),
        IndiProperty.NumberProp("WEATHER_WIND_SPEED", "Wind speed", "Parameters", 6.0, 0.0, 20.0, 1.0, "%.1f km/h"),
        IndiProperty.NumberProp("WEATHER_HUMIDITY", "Humidity", "Parameters", 42.0, 0.0, 100.0, 1.0, "%.0f %"),
    ),

    // Powerbox — Pegasus UPB v2 / PPBA (migrated to core indi/drivers/power/).
    "Pegasus Ultimate Powerbox v2" to listOf(
        IndiProperty.SwitchProp("POWER_CHANNEL_1", "Power ch. 1", "Power", listOf("On", "Off"), 0),
        IndiProperty.SwitchProp("POWER_CHANNEL_2", "Power ch. 2", "Power", listOf("On", "Off"), 0),
        IndiProperty.NumberProp("DEW_A", "Dew A duty cycle", "Dew", 62.0, 0.0, 100.0, 10.0, "%.0f %"),
        IndiProperty.NumberProp("DEW_B", "Dew B duty cycle", "Dew", 40.0, 0.0, 100.0, 10.0, "%.0f %"),
        IndiProperty.SwitchProp("AUTO_DEW_CONTROL", "Auto dew", "Dew", listOf("Enable", "Disable"), 0),
        IndiProperty.NumberProp("SENSOR_VOLTAGE", "Voltage", "Main Control", 12.1, 0.0, 15.0, 0.1, "%.1f V"),
        IndiProperty.NumberProp("SENSOR_CURRENT", "Current", "Main Control", 3.42, 0.0, 20.0, 0.1, "%.2f A"),
    ),
    "Pegasus Pocket Powerbox" to listOf(
        IndiProperty.SwitchProp("POWER_CHANNELS", "Quad output", "Power", listOf("On", "Off"), 0),
        IndiProperty.SwitchProp("ADJOUT_VOLTAGE", "Adjustable output", "Power", listOf("Off", "3V", "5V", "8V", "9V", "12V"), 0),
        IndiProperty.NumberProp("DEW_A", "Dew A duty cycle", "Dew", 50.0, 0.0, 100.0, 10.0, "%.0f %"),
        IndiProperty.NumberProp("DEW_B", "Dew B duty cycle", "Dew", 30.0, 0.0, 100.0, 10.0, "%.0f %"),
    ),
)

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
     * `null` means dithering is off for this block — a genuine, real per-block Ekos concept
     * (`SequenceJob::SJ_DitherPerJobEnabled`, confirmed against real KStars source,
     * `sequencejob.cpp`), unlike autofocus rules which are purely global. Real Ekos itself
     * encodes "off" as `-1` in the written `<GuideDitherPerJob>` element (`sequencejob.cpp:1070`:
     * `ditherPerJobEnabled ? ditherPerJobFrequency : -1`) and reads it back the same way
     * (`value >= 0` → enabled at that frequency, `value < 0` → disabled;
     * `sequencejob.cpp:841-850`) — note `0` itself is a *valid* real frequency ("dither every
     * frame"), not a stand-in for off, so `null`/`-1` had to be a distinct value from `0`. This
     * wire-specific `-1` encoding stays confined to [EsqWriter] — the app's own model uses a
     * real `null`, not a magic number, for "off".
     */
    val ditherEvery: Int?,
)

val DEFAULT_BLOCKS = listOf(
    Block("b1", "Ha", 300, 40, 12, 100, 50, 1, 2),
    Block("b2", "OIII", 300, 30, 0, 100, 50, 1, 2),
    Block("b3", "SII", 300, 20, 0, 100, 50, 1, 2),
)

/**
 * One queued target's capture plan — Nocturne's counterpart to Ekos's
 * `SchedulerJob` (`scheduler_get_jobs`/`scheduler_add_jobs`): one target, one
 * sequence (`blocks`), queued alongside other jobs rather than Capture's flat
 * single-sequence model. Named `SequenceJob`, not `Job`, to avoid colliding
 * with `kotlinx.coroutines.Job` already used in [SimulatedController].
 */
@Serializable
data class SequenceJob(
    val id: String,
    val targetId: String,
    val blocks: List<Block> = DEFAULT_BLOCKS,
    val blockSeq: Int = DEFAULT_BLOCKS.size + 1,
    /**
     * True once this job has been pushed to the real Scheduler ("Push to Ekos", M3.4) —
     * `scheduler_add_jobs` was sent and confirmed via a `scheduler_get_jobs` round-trip. Means
     * only "Ekos currently lists a job with this target's name" — nothing about whether it's
     * actually running; use [wireJobFor] for that. The block editor goes read-only while true
     * (matches real Ekos — there's no live-edit-a-running-job wire primitive).
     *
     * `running: Boolean` and `wireIndex: Int?` used to live here too (M3/M3.4) — dropped
     * 2026-08-23 once the push/start/stop split landed: `WireSchedulerJob` has no server-assigned
     * id, only `name`, so a cached index went stale the instant anything else on the real queue
     * was added/removed (confirmed live — this exact staleness is what let a duplicate-name push
     * silently latch onto a different job's slot). Both are now resolved fresh, by name, against
     * [AppState.wireSchedulerJobs] at the moment a command needs them, via [wireJobFor].
     */
    val synced: Boolean = false,
    /**
     * Real wall-clock time this job was created (2026-08-25, user's own call) — feeds
     * [targetNameFor]'s date/time suffix so pushing the same target months apart never produces
     * the exact same real Scheduler job name/`.esq` filename twice. `0L` for any job persisted
     * before this field existed (deserializes to the default, per kotlinx.serialization) —
     * [targetNameFor] deliberately falls back to the old undated name in that case rather than
     * collide every pre-existing job on `0`, since a currently-`synced` old-format job must keep
     * matching the exact name Ekos already has for it (see [wireJobFor]) or the app silently loses
     * track of it.
     */
    val createdAtMs: Long = 0L,
)

val DEFAULT_JOBS = listOf(
    SequenceJob(id = "j1", targetId = "NGC 7000", blocks = DEFAULT_BLOCKS, blockSeq = DEFAULT_BLOCKS.size + 1),
)

/**
 * The name Ekos would know a job by — used to name-match [AppState.wireSchedulerJobs]. Always
 * suffixed with this job's own local id (`"NGC 7000_j2"`), not just the target's own display
 * name — real Ekos's Scheduler has no separate job-id field, only `name`, so two local jobs for
 * the *same target* (different filters/exposures — a real user need: "different session
 * profile") would otherwise both resolve to the identical wire name and collide, either racing
 * this app's own duplicate-name refusal or (worse) real Ekos's own. The suffix is stable for a
 * given job's whole lifetime (the id never changes), so a job's wire identity never shifts out
 * from under a live sync just because a sibling job for the same target was added or removed.
 *
 * **Real bug, found live 2026-08-23**: this used a literal `#` separator (`"$base #${job.id}"`)
 * until now — confirmed against source (`ksutils.cpp:1924` `KSUtils::sanitize()`) that real
 * Ekos's own filename sanitizer does NOT strip `#` (only whitespace/`/`/`(`/`)`/`:`/`*`/`+`/`~`/
 * `"`), so every job this app ever pushed had a `#` baked into its real save-path target name —
 * real symptom: "image write failed" once a job with a `#`-suffixed name actually reached
 * capture. Switched to `_`, which Ekos's own sanitizer already treats as the safe replacement
 * character for everything else it strips.
 *
 * **Date/time suffix added 2026-08-25 (user's own call)**: `job.id` (`"j1"`, `"j2"`, ...) is a
 * local, in-app monotonic counter — it only ever *increases* for the app's own persisted
 * lifetime, but that lifetime isn't forever (a reinstall, or the sequence-jobs DataStore being
 * cleared, restarts it from `"j1"` again). Pushing the same target as job "j1" months apart would
 * otherwise produce the exact same real Scheduler job name and `.esq` filename twice — the user's
 * own concrete worry: real Ekos could plausibly treat the second, unrelated push as a continuation
 * of the first (whatever it keys its own "remember job progress"/completion state on,
 * `kcfg_RememberJobProgress`, confirmed `true` on this rig — not confirmed *what* key it uses, so
 * removing the collision outright rather than reasoning about that mechanism). [SequenceJob.createdAtMs]
 * makes every future job's name unique regardless of *why* `job.id` collided. Falls back to the
 * pre-2026-08-25 undated name when `createdAtMs` is `0` (a job persisted before this field
 * existed) — required so an already-`synced` old-format job keeps matching the exact name Ekos
 * already has for it; see [SequenceJob.createdAtMs]'s own doc.
 */
fun AppState.targetNameFor(job: SequenceJob): String {
    val target = findTarget(job.targetId)
    val base = target?.common ?: target?.id ?: job.targetId
    val dated = if (job.createdAtMs > 0L) "_${JOB_NAME_DATE_FORMATTER.format(Instant.ofEpochMilli(job.createdAtMs).atZone(ZoneId.systemDefault()))}" else ""
    return "${base}_${job.id}$dated"
}

private val JOB_NAME_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

/**
 * Filesystem-safe token from a real name — spaces/punctuation a filesystem path shouldn't carry
 * replaced with `_`. Same convention as [com.nocturne.data.FrameFileWriter]'s own private
 * `sanitize`, shared here since [pushRealJob]-style callers need an identical transform on
 * [targetNameFor]'s output before it can be used as an actual `.esq` filename.
 */
fun sanitizeFileToken(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

/**
 * This job's real counterpart on the wire, if any — `null` for an unsynced job by construction
 * (never name-match a job that hasn't been confirmed pushed; this is exactly where the
 * duplicate-name bug bit before this field was added). This is the one place "is this job really
 * running" gets answered from now on — [SequenceJob] itself carries no local running flag.
 */
fun AppState.wireJobFor(job: SequenceJob): WireSchedulerJob? =
    if (!job.synced) null else wireSchedulerJobs.orEmpty().firstOrNull { it.name == targetNameFor(job) }

/**
 * Real jobs Ekos's Scheduler holds that no local, synced [SequenceJob] claims — added directly in
 * KStars, or left over from before this app ever touched them. The app never hides these: it
 * reads Ekos's real state and shows it as-is, it doesn't force/clear anything on connect.
 */
val AppState.unmanagedWireJobs: List<WireSchedulerJob> get() {
    val claimed = jobs.filter { it.synced }.map { targetNameFor(it) }.toSet()
    return wireSchedulerJobs.orEmpty().filterNot { it.name in claimed }
}

/**
 * Which job the Session tab reflects: prefers real truth over local guesswork — a synced job
 * actually [SchedulerJobStatus.BUSY] right now, else one in [ACTIVE_SCHEDULER_STATES] (Ekos has
 * committed to it, even pre-slew), else whichever job was last active locally, else the first
 * queued job, else null. Session tab's own telemetry (night arc, HFR/RMS/SNR) stays the decoupled
 * simulator fixture it always was — only the header (target name, block progress) is wired to
 * this job.
 */
val AppState.contractJob: SequenceJob? get() =
    jobs.firstOrNull { wireJobFor(it)?.state == SchedulerJobStatus.BUSY }
        ?: jobs.firstOrNull { wireJobFor(it)?.state in ACTIVE_SCHEDULER_STATES }
        ?: jobs.firstOrNull { it.id == lastActiveJobId }
        ?: jobs.firstOrNull()

/** The job [endSession] stopped — what the Summary sheet and its export report are about. */
val AppState.endedJob: SequenceJob? get() = jobs.firstOrNull { it.id == lastEndedJobId }

/** Looks up a target by id across both catalogs, then the live search results if any (M3). */
fun AppState.findTarget(id: String): Target? =
    TARGETS.firstOrNull { it.id == id } ?: userTargets.firstOrNull { it.id == id }
        ?: wireSearchResults?.firstOrNull { it.id == id }

/** "NGC 7000 — North America" for the well-known catalog; just the name for custom targets (no catalog id to show). */
val Target.displayName: String get() = if (custom) common else "$id — $common"

/**
 * The name to send to any real wire call that resolves an object by name (`mount_goto_target`,
 * `astro_get_objects_riseset`, the Scheduler's `nameEdit`, etc.) — **not** [displayName] or
 * [common] unconditionally. For the fixture `TARGETS` catalog, `id` is the real resolvable
 * designation ("NGC 7000") while `common` is just the human nickname ("North America"), which
 * real KStars won't resolve as an object name at all — confirmed live (a real
 * `astro_get_objects_riseset { "names": ["North America"] }` call was the original bug here).
 * For a live search result, `id`/`common` are already identical (both the resolved catalog name),
 * so this is a no-op there. Only for a user's custom target — where `id` is a synthetic
 * `"custom_N"` string, not resolvable at all — is `common` (whatever they actually typed) the
 * better (if still not guaranteed to resolve) choice.
 */
val Target.realLookupName: String get() = if (custom) common.ifBlank { id } else id.ifBlank { common }

/**
 * Real J2000 RA/Dec as decimal degrees (M5, docs/STATUS.md — reference-image fetch). Prefers
 * [Target.ra0]/[Target.de0] when set (real, only populated for live search results — `ra0` is in
 * **hours**, confirmed against [formatRaHours]'s own param name, so `* 15.0` converts to degrees;
 * `de0` is already degrees). Falls back to parsing [Target.coords]'s own "HHhMMmSSs ±DD°MM′SS″"
 * string (every target has one, fixture/custom included) with the same shape
 * [EkosRemoteController]'s `COORDS_REGEX` matches for the scheduler wire — just computed into
 * actual degrees here instead of re-formatted sexagesimal strings. Null only for a free-text
 * custom target whose `coords` doesn't match this shape at all.
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
 * Real "is a scheduler job actually imaging right now" check (M4.5) — decides Preview/ vs Plan/
 * on disk for a just-arrived capture frame. A real `BUSY` job means a real target+filter
 * capture; no `BUSY` job means a test/bench capture. Fragile only around the exact moment a job
 * starts/stops — no better wire signal exists (frame headers carry no target/filter at all,
 * confirmed against source) — see docs/M4.5-plan.md.
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

/** Median HFR across real *kept* frames only (M4.3, Room-backed) — null if no kept frame has an HFR yet. */
val AppState.medHfr: Double? get() {
    val sorted = frameRows.filter { it.keep }.mapNotNull { it.hfr }.sorted()
    return if (sorted.isEmpty()) null else sorted[sorted.size / 2]
}

/** First not-yet-complete block, or the last block if all are done. */
val SequenceJob.currentBlockIndex: Int? get() =
    if (blocks.isEmpty()) null else blocks.indexOfFirst { it.doneCount < it.subCount }.let { if (it == -1) blocks.lastIndex else it }

val FILTER_CYCLE = listOf("Ha", "OIII", "SII", "L", "R", "G", "B")

/**
 * Real filter-wheel slot names (INDI's `FILTER_NAME`, one element per slot), if the real filter
 * wheel is connected and has reported them (2026-08-23, wired once `FILTER_NAME` itself became
 * editable) — null otherwise, letting every caller fall back to the fixture [FILTER_CYCLE].
 * Looked up by the filter wheel's own real device *name*, not the `"efw"` role key —
 * `indiProps` is keyed by whatever name the driver reports (see `IndiPropertyPanel`'s call
 * sites), found here via the same [DeviceRole]-filtered [wireDevices] lookup
 * `realDeviceOptions` already uses for the same role.
 */
val AppState.realFilterNames: List<String>? get() {
    val filterWheel = wireDevices?.firstOrNull { DeviceRole.FILTER in it.roles } ?: return null
    val prop = indiProps[filterWheel.name]?.firstOrNull { it.name == "FILTER_NAME" } as? IndiProperty.TextProp
    return prop?.elements?.map { it.second }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
}
val BINNING_OPTIONS = listOf(1, 2, 3, 4)
/** `null` ("Off") leads the list — see [Block.ditherEvery]'s own doc for why real Ekos needs a distinct off-value from `0`. */
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

/** Total subs done/planned across all blocks — real once [SequenceJob.synced], fixture math otherwise. */
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

/** "1:00" / "3:20" style (hours:minutes, unpadded hour) — matches NightArcCard's existing literal shape. */
val SequenceJob.doneHM: String get() = formatHM(totalDoneSec)
val SequenceJob.plannedHM: String get() = formatHM(totalPlannedSec)

/**
 * Real dusk/dawn as absolute instants, resolved from [AppState.wireDusk]/[AppState.wireDawn]'s
 * signed day-fraction-from-midnight offsets against the *site's* own local "today" (per
 * [AppState.wireSiteTz]) — deliberately not the Android device's own timezone, since the almanac
 * itself is anchored to the Pi's configured site, which could differ from wherever the phone
 * happens to be. Null until all three wire fields have arrived — [SessionScreen.kt]'s
 * `NightArcCard` keeps its hardcoded "21:48 → 04:12" until then.
 */
val AppState.realNightWindow: Pair<Instant, Instant>? get() {
    val dusk = wireDusk ?: return null
    val dawn = wireDawn ?: return null
    val zone = siteZoneOffset ?: return null
    val anchor = nearestMidnight(zone)
    return anchor.plusSeconds((dusk * 86400).roundToLong()) to anchor.plusSeconds((dawn * 86400).roundToLong())
}

/** [wireSiteTz] as a [ZoneOffset] — null until it's arrived (real-rig only). */
val AppState.siteZoneOffset: ZoneOffset? get() = wireSiteTz?.let { ZoneOffset.ofTotalSeconds((it * 3600).roundToInt()) }

/**
 * "Local midnight today" is ambiguous by clock time alone — offsets like [wireDusk]/[wireDawn], or
 * the ±12h span a real `astro_get_objects_riseset` altitude curve is centered on, are small deltas
 * from *some* midnight, but which one (the one just passed, in the small hours, or the upcoming
 * one, in the evening/daytime) depends on where "now" actually sits. Anchoring on whichever
 * midnight is chronologically nearest to "now" resolves both cases correctly without guessing
 * AM/PM — confirmed live: anchoring on today's midnight-*start* unconditionally (the first cut of
 * [realNightWindow]) put a 2pm-computed dusk a full day early.
 */
private fun nearestMidnight(zone: ZoneOffset): Instant {
    val now = Instant.now()
    val nowDate = now.atZone(zone).toLocalDate()
    val todayStart = nowDate.atStartOfDay(zone).toInstant()
    val tomorrowStart = nowDate.plusDays(1).atStartOfDay(zone).toInstant()
    return if (abs(now.epochSecond - todayStart.epochSecond) <= abs(now.epochSecond - tomorrowStart.epochSecond)) todayStart else tomorrowStart
}

/**
 * The real ±12h-around-local-midnight window a `astro_get_objects_riseset` altitude curve spans
 * (see [WireRiseset.altitudes]'s own doc) — a full day cycle centered on midnight, distinct from
 * [realNightWindow]'s dusk-to-dawn observing window. Null until [wireSiteTz] has arrived.
 */
val AppState.realDayWindow: Pair<Instant, Instant>? get() {
    val zone = siteZoneOffset ?: return null
    val anchor = nearestMidnight(zone)
    return anchor.minusSeconds(43_200) to anchor.plusSeconds(43_200)
}

/**
 * Real "usable tonight" duration for [riseset] — replaces the fixture `Target.usable` string
 * (M1, same fixed value every night regardless of date/season/real sky) once real riseset data
 * for this exact target has arrived. Total time [riseset]'s real altitude curve (see
 * [WireRiseset.altitudes]'s own doc — 49 points, every 30 min, spanning [realDayWindow]) is both
 * above [thresholdDeg] — 40° by default, matching this app's own existing "Alt > 40°" Plan-tab
 * filter chip, no other "usable" altitude convention exists anywhere else in this codebase to
 * borrow instead — *and* within [realNightWindow]'s real dusk-to-dawn dark window. Linear
 * interpolation between the 30-min samples for the threshold-crossing instant, same "the curve is
 * linear between samples" assumption [AltitudeChart] itself already renders under. Null until
 * both [realDayWindow] (site tz) and [realNightWindow] (dusk/dawn) have arrived, or if [riseset]
 * has fewer than 2 samples.
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
 * Within absolute time range `[lo, hi]` (a subset of `[t0, t1]`), the seconds where a straight
 * line from `(t0, a0)` to `(t1, a1)` is above `threshold`. Shared math behind [realUsableSeconds].
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

/**
 * Real peak altitude + its instant, restricted to tonight's real dusk-to-dawn window — as opposed
 * to `riseset.altitudes.maxOrNull()` (the target's absolute *daily* peak, which can fall during
 * broad daylight for a target whose transit doesn't land at night). User-found confusion
 * (2026-08-22): pairing that daytime peak's "max 86° @ 15:39" with an honestly-computed
 * "0h 00m usable" (see [realUsableSeconds] — that target genuinely never climbs back above 40°
 * before dawn) read as contradictory, even though both numbers were individually correct —
 * confirmed live against raw wire data down to the minute. This is the fix: a "max"/"peak" that's
 * restricted to the same real dark window [realUsableSeconds] already is, so the two numbers on
 * the card can never again look like they disagree. Sample-resolution only (30 min, no
 * interpolation) — same simplicity level the original whole-day max/peak already had (a bare
 * `maxOrNull()`/`riseset.transit`, no interpolation there either). Null if no real night window is
 * known, or [riseset] has no altitude samples.
 */
fun AppState.realNightMaxAltitude(riseset: WireRiseset): Pair<Double, Instant>? {
    val dayStart = realDayWindow?.first ?: return null
    val night = realNightWindow ?: return null
    if (riseset.altitudes.isEmpty()) return null
    val stepSec = 1800L
    return riseset.altitudes.withIndex()
        .map { (i, alt) -> alt to dayStart.plusSeconds(i * stepSec) }
        .filter { (_, t) -> !t.isBefore(night.first) && !t.isAfter(night.second) }
        .maxByOrNull { (alt, _) -> alt }
        ?.let { (alt, t) -> alt to t }
}

/** Real fraction of [realDayWindow] elapsed right now — always defined once the window is (by construction, "now" sits within ±12h of its own nearest midnight). */
val AppState.realDayFraction: Double? get() {
    val (start, end) = realDayWindow ?: return null
    val total = end.epochSecond - start.epochSecond
    if (total <= 0) return null
    return (Instant.now().epochSecond - start.epochSecond).toDouble() / total
}

private val SITE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

/** [instant] formatted "HH:mm" in the site's own local time — real replacement for the "21:48"/"04:12" literals. */
fun AppState.formatSiteTime(instant: Instant): String {
    val tz = wireSiteTz ?: return "--:--"
    return instant.atZone(ZoneOffset.ofTotalSeconds((tz * 3600).roundToInt())).format(SITE_TIME_FORMATTER)
}

/**
 * Real fraction of tonight's dusk-to-dawn span elapsed right now — null (not clamped) whenever
 * "now" falls outside that span (daytime, or between a stale dawn and the next dusk), so the UI
 * can show an honest "not observing" state instead of plotting a wrong dot position.
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
 * "T-H:MM" real countdown to the *next* dusk — shown when [realNowFraction] is null because
 * we're outside tonight's dark window (daytime, or already past dawn). Dusk recurs roughly every
 * 24h; adding a day to [realNightWindow]'s own dusk instant whenever it's already in the past
 * (the "already past dawn, waiting for tonight" case) lands on the correct next occurrence
 * without needing a second almanac fetch — confirmed sound because the dawn-to-next-dusk gap is
 * always well under 24h.
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

data class Alert(
    val text: String,
    val time: String,
    val warn: Boolean,
    val cut: Boolean,
    val cloud: Boolean,
    val ok: Boolean,
    val iconKind: AlertIcon,
)

enum class AlertIcon { FLIP, SCISSORS, CLOUD, CHECKS }

val ALERTS = listOf(
    Alert("Meridian flip in 42 min — mount will pause, re-guide, resume", "02:18", warn = true, cut = false, cloud = false, ok = false, AlertIcon.FLIP),
    Alert("Sub 017 cut — HFR 2.94 above 2.80 threshold", "01:06", warn = false, cut = true, cloud = false, ok = false, AlertIcon.SCISSORS),
    Alert("Cloud passed, guiding recovered after 14 min", "01:18", warn = false, cut = false, cloud = true, ok = false, AlertIcon.CLOUD),
    Alert("Autofocus complete — 18 422, HFR 2.27", "00:41", warn = false, cut = false, cloud = false, ok = true, AlertIcon.CHECKS),
)

// ── Derived values (mirror the prototype renderVals math) ─────────────────

private fun hhmm(s: Int): String {
    val m = floor(s / 60.0).toInt()
    val r = s % 60
    return "$m:${r.toString().padStart(2, '0')}"
}

val AppState.elapsed: Int get() = (128 + t) % 300
val AppState.expRemain: String get() = hhmm(300 - elapsed)
val AppState.fNow: Double get() = 0.485 + t / 9000.0

/** Minutes until the next scheduled autofocus — real countdown off the real [WireCaptureSettings.refocusEveryN] (2026-08-23) and the last run's timestamp. */
val AppState.focusNextAfMin: Int get() = ((wireCaptureSettings?.refocusEveryN ?: 45) - (t - focusLastAfAt) / 60).coerceAtLeast(0)

/** Live EAF focuser temperature — reads the user's own edits first, falls back to the driver fixture default. */
val AppState.eafTemp: Double get() = indiNumber("EAF", "FOCUS_TEMPERATURE") ?: -0.6

/**
 * Real position for Bench check's Focuser card (M3.2) — [focPos] itself stays a plain fixture
 * field (`jogFocus`'s local optimistic update needs it mutable), but on a real rig it was never
 * reconciled against the focuser's actual `ABS_FOCUS_POSITION` INDI property, which is already
 * fetched/subscribed like any other connected device's properties. `jogFocus`'s real override
 * does send real `focus_in`/`focus_out` — only the number shown ever went stale, silently
 * drifting from truth with every jog since the local math started from the fixture default
 * (confirmed live: real rig showed 29445 on the device sheet while Bench stayed stuck at
 * 18422, the exact `focPos` default). `primaryTrain.focuser` is the real focuser's own device
 * name once `wireTrains` is populated (see `WireTrain.toTrainAssignment`) — falls back to
 * [focPos] whenever that lookup misses (simulator, or before the real value has arrived).
 */
val AppState.benchFocPos: Int get() =
    if (wireFocusSettings != null) focPos else (indiNumber(primaryTrain.focuser, "ABS_FOCUS_POSITION")?.roundToInt() ?: focPos)

/**
 * Real guide camera's own device name — whichever train is *actually* assigned to the Guide
 * module (`moduleTrainAssignments["guide"]`, the same resolution `opticalTrainCombo` uses for
 * real scheduler jobs, bug #19), not [secondaryTrain] (a plain index-1 pick into [wireTrains],
 * never reconciled against which train the Guide module itself is really using). Null under the
 * simulator or before trains + assignments have both arrived.
 */
val AppState.guideCameraDevice: String?
    get() = wireTrains?.firstOrNull { it.name == moduleTrainAssignments?.get("guide") }?.camera?.takeIf { it.isNotBlank() }

/**
 * Real live exposure countdown/busy state for a Snap preview shot (M3.4 — Controls tab's Primary
 * Camera/Guide "Snap" panels had no Stop button and no status/elapsed indication at all). Read
 * straight off the camera's own generic INDI `CCD_EXPOSURE` vector rather than the `new_capture_state`
 * push: confirmed live that `guide_capture` never emits `new_capture_state` at all (only
 * `capture_preview` does, and only a `status`-only subset of shapes at that) — `CCD_EXPOSURE`'s own
 * value/Busy-state is the one progress signal that's actually real and uniform across both cameras.
 * `remainingSec`/`busy` are null/false whenever the property hasn't arrived yet (simulator, or before
 * the camera's own `device_get` reply lands).
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
 * Real mount's actual slew-rate options straight from its own `TELESCOPE_SLEW_RATE` INDI
 * switch vector — already flowing into [indiProps] via the generic device-property subscribe
 * (switch vectors decode every element, unlike the multi-element gap noted on
 * [wireCcdInfoByDevice]). Real driver rate lists vary in both count and labels — confirmed live,
 * an LX200 OnStep reports 10 (`0.25x`..`Max`), not the Bench check UI's fixed 5-option `RATES`
 * fixture list (`"0.5×"/"1×"/"8×"/"64×"/"max"`), which never matched any real driver's actual
 * rates. `mount_set_slew_rate`'s `rate` field is documented as a plain ordinal "index into
 * driver's slew-rate list" — this switch vector's own element order *is* that list, so the
 * index Bench check sends for option `i` here needs no translation. Null in the simulator or
 * before the mount's own `device_get` reply has arrived.
 */
val AppState.realSlewRateProp: IndiProperty.SwitchProp?
    get() = (indiProps[primaryTrain.mount] ?: emptyList())
        .firstOrNull { it.name == "TELESCOPE_SLEW_RATE" } as? IndiProperty.SwitchProp

/**
 * Real mount's actual tracking on/off, read from its own `TELESCOPE_TRACK_STATE` INDI switch
 * (standard INDI telescope interface property, confirmed live: elements `TRACK_ON`/`TRACK_OFF`)
 * — falls back to the fixture-only [mountTracking] field for the simulator or before the
 * property has arrived. Used by [MountControlCard]'s Tracking on/off button, which sends the
 * real `mount_set_tracking` Ekos-level command rather than writing this INDI property directly
 * (this is read-only display; the write goes through the more universal Ekos command).
 */
val AppState.mountTrackingOn: Boolean get() {
    val prop = (indiProps[primaryTrain.mount] ?: emptyList()).firstOrNull { it.name == "TELESCOPE_TRACK_STATE" } as? IndiProperty.SwitchProp
    return prop?.let { it.elementNames.getOrNull(it.selected) == "TRACK_ON" } ?: mountTracking
}

/**
 * Real mount's actual parked state, read from its own `TELESCOPE_PARK` INDI switch (standard
 * INDI telescope interface property, confirmed live: elements `PARK`/`UNPARK`) — falls back to
 * the fixture-only [mountParked] field. Same read-display/write-via-Ekos-command split as
 * [mountTrackingOn]: writes go through `mount_park`/`mount_unpark`, not this property directly.
 */
val AppState.mountParkedReal: Boolean get() {
    val prop = (indiProps[primaryTrain.mount] ?: emptyList()).firstOrNull { it.name == "TELESCOPE_PARK" } as? IndiProperty.SwitchProp
    return prop?.let { it.elementNames.getOrNull(it.selected) == "PARK" } ?: mountParked
}

/**
 * [wireMountPierSide]'s raw ordinal named, per a single live-confirmed data point (2026-08-09):
 * the real rig reported `pierSide = 1` while the user visually confirmed the tube was physically
 * on the mount's West side — so `1 -> "West"`, `0 -> "East"` by elimination. Not exhaustively
 * verified across every sky position; falls back to the raw ordinal (no invented label) for any
 * other value so a wrong guess never gets shown as if confirmed.
 */
val AppState.mountPierSideLabel: String? get() = when (wireMountPierSide) {
    1 -> "West"
    0 -> "East"
    null -> null
    else -> "raw $wireMountPierSide"
}

/**
 * Real pixel scale (arcsec/pixel) for Plan tab's Framing card — standard formula
 * `206.265 × pixel_size_µm / focal_length_mm`. Null (falls back to the card's own placeholder)
 * until both the primary scope's focal length and the primary camera's [CcdInfo] are known —
 * on the simulator that's always, since [wireCcdInfoByDevice] is only ever populated by
 * [EkosRemoteController].
 */
val AppState.framingPixelScaleArcsecPerPx: Double? get() {
    val focalMm = findScope(primaryTrain.scope)?.focalMm?.takeIf { it > 0 } ?: return null
    val ccd = wireCcdInfoByDevice[primaryTrain.camera] ?: return null
    return 206.265 * ccd.pixelUm / focalMm
}

/** Real field of view (width, height) in degrees, derived from [framingPixelScaleArcsecPerPx] and the primary camera's sensor resolution. */
val AppState.framingFovDeg: Pair<Double, Double>? get() {
    val scale = framingPixelScaleArcsecPerPx ?: return null
    val ccd = wireCcdInfoByDevice[primaryTrain.camera] ?: return null
    return (scale * ccd.maxX / 3600.0) to (scale * ccd.maxY / 3600.0)
}

val AppState.paTotal: Double get() = hypot(paAlt, paAz)
val AppState.coolAtSetPoint: Boolean get() = abs(coolNow - coolTarget) < 0.2
val AppState.coolBarPct: Int get() {
    val raw = ((12.4 - coolNow) / (12.4 - coolTarget) * 100).roundToInt()
    return raw.coerceIn(2, 100)
}
val AppState.coolPowerPct: Int get() = min(99, (abs(coolNow - 12.4) * 3 + 8).roundToInt())
// Real once Room has real rows (M4.3) — was fixture-derived (`frames`/`FRAME_IDS`/`FRAME_HFRS`,
// all deleted) before. SessionReport's own "kept/cut" export line still says M4-gap elsewhere
// pending the rest of M4.6; these two counts themselves are real now.
val AppState.rejectCount: Int get() = frameRows.count { !it.keep }
val AppState.keepCount: Int get() = frameRows.count { it.keep }
val AppState.ready: Boolean get() = ekosRunning && isOn("mount") && isOn("cam")
fun AppState.isOn(key: String): Boolean = ekosRunning && key !in devOff

/** Whether a device is picked for the profile being built — independent of Ekos actually running. */
fun AppState.isSelected(key: String): Boolean = key !in devOff
val AppState.missing: String get() {    val parts = buildList {
        if (!isOn("mount")) add("mount")
        if (!isOn("cam")) add("camera")
    }
    return parts.joinToString(" + ")
}

/** The prototype's trace generator: x = i + t*0.6 + seed, summed sines. */
fun wiggle(t: Int, seed: Int, n: Int, amp: Double): List<Double> =
    (0 until n).map { i ->
        val x = i + t * 0.6 + seed
        (sin(x * 0.7) + sin(x * 0.31) * 0.7 + sin(x * 1.9) * 0.35) / 2.05 * amp
    }

/** Ease a polar-align axis toward zero, exactly like the prototype. */
fun ease(v: Double): Double =
    if (abs(v) < 0.15) 0.0 else (v - sign(v) * 0.22)
