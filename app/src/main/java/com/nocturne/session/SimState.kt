package com.nocturne.session

import com.nocturne.protocol.DeviceRole
import com.nocturne.protocol.WireAlignSettings
import com.nocturne.protocol.WireCaptureSettings
import com.nocturne.protocol.WireFocusSettings
import com.nocturne.protocol.WireGuideSettings
import com.nocturne.protocol.WireMountSettings
import com.nocturne.protocol.WireSchedulerJob
import com.nocturne.protocol.WireTrain
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** Which detail sheet is open. */
enum class SheetType {
    GUIDE, FOCUS, ALERTS, PREFS, SETUP, PA, DEVICE, SUMMARY, AUTOFOCUS_RULES,
    OPTICAL_TRAIN, SCOPES, MODULE_ASSIGNMENTS, MAINTENANCE, MOUNT_SETTINGS, CAMERA_SETTINGS, ALIGN_SETTINGS,
    GUIDE_SETTINGS,
}

/**
 * Progress of a [SessionController.rebootRig] request against the rig's
 * companion reboot daemon (separate HTTP+token channel — the EkosRemote wire
 * itself has no OS-level reboot command, see `pi-tools/reboot-daemon/`).
 */
enum class RigRebootState { IDLE, SENDING, SENT, FAILED }

/** Which meridian-flip action is awaiting confirmation. */
enum class FlipConfirm { DEFER, NOW }

/** Every mutable field the simulator drives, mirroring the prototype's state. */
data class SimState(
    val t: Int = 0,
    val sheet: SheetType? = null,
    /** Session tab's sub preview expanded to a full-screen overlay. */
    val subPreviewExpanded: Boolean = false,
    /** Frames tab: id of the frame expanded to a full-screen overlay, if any. */
    val expandedFrameId: String? = null,
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
    val lastActiveJobId: String? = DEFAULT_JOBS.firstOrNull { it.running }?.id,
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
    val cut: Set<String> = setOf("017", "023"),
    val devOff: Set<String> = setOf("rotator", "dome"),
    /** Which catalog entry is assigned per device category — key -> chosen name from that [Device.catalog]. */
    val selectedDeviceNames: Map<String, String> = DEVICES.associate { it.key to it.name },
    val primaryTrain: TrainAssignment = TrainAssignment(),
    val secondaryTrain: TrainAssignment = TrainAssignment(
        camera = "ASI174MM mini", rotator = "None", scope = "OAG",
        filterWheel = "None", focuser = "None",
    ),
    /**
     * Sequence-wide autofocus trigger rule. Real Ekos enforces this once per
     * running queue (`enforceRefocusEveryN`/`refocusEveryN`, `maxFocusTemperatureDelta`
     * via `capture_get_all_settings`) — there's no per-job override on the wire,
     * so this is deliberately one setting, not per-block.
     */
    val afRefocusMin: Int = 45,
    val afTempDeltaC: Double = 1.0,
    val afOnFilterChange: Boolean = true,
    /** Focus sheet: snapshot from the last "Run autofocus now" tap. */
    val focusLastBestPos: Int = 18422,
    val focusLastHfr: Double = 2.27,
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
    /** True under [EkosRemoteController]; false under [SimulatedController] — gates [MAINTENANCE] sheet's rig-reboot UI, which is meaningless without a real Pi. */
    val isRealRig: Boolean = false,
    /** Companion reboot daemon's port on the rig's Pi — separate from the EkosRemote wire port (see `pi-tools/reboot-daemon/`). */
    val rigRebootPort: Int = 9001,
    /** Whether a reboot token has been configured — the token itself never enters [SimState] (kept only inside the controller), so nothing display-worthy leaks it into logs/recompositions. */
    val rigRebootTokenSet: Boolean = false,
    /** True once a token is configured under a real rig — [MaintenanceSheet] shows the reboot button only then. */
    val rigRebootAvailable: Boolean = false,
    val rigRebootState: RigRebootState = RigRebootState.IDLE,
    val rigRebootError: String? = null,
)

/** One `get_devices` entry, decoded to the roles its `interface` bitmask ORs together. */
data class LiveDevice(val name: String, val connected: Boolean, val roles: Set<DeviceRole>)

/** A real camera's `CCD_INFO` vector — see [SimState.wireCcdInfoByDevice]. */
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
     * [SimState.wireSearchResults] entries (from `astro_get_objects_info`),
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
 * Scopes catalog ([ScopeDef]/[SimState.scopes], M3.1), referenced by a
 * train's [TrainAssignment.scope] — a real Profile carries no optics of its
 * own, that's the Scopes catalog + Optical Train's job.
 */
data class RigProfile(
    val name: String,
    val deviceKeys: List<String>,
    /**
     * Real `get_profiles`' `drivers` map (`{"<DeviceFamily>": [labels...]}`,
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

val SimState.activeRigProfile: RigProfile? get() = profiles.firstOrNull { it.name == activeProfile }

/** Looks up a [ScopeDef] by name (a [TrainAssignment.scope]/[TrainAssignment] reference) — real or fixture catalog, whichever is current. */
fun SimState.findScope(name: String): ScopeDef? = (wireScopes ?: scopes).firstOrNull { it.name == name }

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
 * [SimState.wireScopes] from the wire); locally it's `"scope_<seq>"`.
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

fun SimState.train(slot: TrainSlot): TrainAssignment = when (slot) {
    TrainSlot.PRIMARY -> primaryTrain
    TrainSlot.SECONDARY -> secondaryTrain
}

fun SimState.withTrain(slot: TrainSlot, train: TrainAssignment): SimState = when (slot) {
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
fun SimState.trainRolePool(role: TrainRole): List<String> {
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
fun SimState.realDeviceOptions(key: String): List<String>? {
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
    ) : IndiProperty()

    data class TextProp(
        override val name: String, override val label: String, override val group: String,
        val value: String,
        /** Real INDI element name within this vector (M3) — see [SwitchProp.elementNames]. */
        val elementName: String = name,
    ) : IndiProperty()

    /** Read-only — IPState per element: 0 Idle, 1 Ok, 2 Busy, 3 Alert. */
    data class LightProp(
        override val name: String, override val label: String, override val group: String,
        val elements: List<Pair<String, Int>>,
    ) : IndiProperty()
}

/** Looks up a NumberProp's live value by property name — user's own edit first, else the driver fixture default. */
fun SimState.indiNumber(deviceName: String, propName: String): Double? {
    val props = indiProps[deviceName] ?: DRIVER_INDI_PROPS[deviceName] ?: emptyList()
    return (props.firstOrNull { it.name == propName } as? IndiProperty.NumberProp)?.value
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

/** Frames grid — 12 sub previews with per-sub HFR. */
data class Frame(
    val id: String,
    val hfr: Double,
    val cut: Boolean,
)

val FRAME_IDS = listOf("011", "012", "013", "014", "015", "016", "017", "018", "019", "020", "021", "022")
val FRAME_HFRS = listOf(2.28, 2.31, 2.30, 2.35, 2.41, 2.33, 2.94, 2.44, 2.38, 2.36, 2.29, 2.32)

data class Block(
    val id: String,
    val filter: String,
    val exposureSec: Int,
    val subCount: Int,
    val doneCount: Int,
    val gain: Int,
    val offset: Int,
    val binning: Int,
    val ditherEvery: Int,
    /**
     * App-side override, not an Ekos setting: force one `focus_start` right as
     * this block begins, layered on top of (not replacing) the global
     * autofocus rule. Wiring the actual trigger needs real capture-state
     * pushes to detect "this block just started" — M2/M3; this flag is a
     * no-op stub under [SimulatedController].
     */
    val forceAfOnStart: Boolean = false,
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
data class SequenceJob(
    val id: String,
    val targetId: String,
    val blocks: List<Block> = DEFAULT_BLOCKS,
    val blockSeq: Int = DEFAULT_BLOCKS.size + 1,
    val running: Boolean = false,
    /**
     * True once this job has been pushed to the real Scheduler (M3) —
     * `scheduler_add_jobs` was sent and confirmed via a `scheduler_get_jobs`
     * round-trip. The block editor goes read-only while true (matches real
     * Ekos — there's no live-edit-a-running-job wire primitive). Stays
     * `false` forever under [SimulatedController].
     */
    val synced: Boolean = false,
    /** This job's index in the last `scheduler_get_jobs` reply — needed for `scheduler_remove_jobs {index}`. */
    val wireIndex: Int? = null,
)

val DEFAULT_JOBS = listOf(
    SequenceJob(id = "j1", targetId = "NGC 7000", blocks = DEFAULT_BLOCKS, blockSeq = DEFAULT_BLOCKS.size + 1, running = true),
)

/**
 * Which job the Session tab reflects: the running one, or the first queued
 * job if none is running, or null if the queue is empty. Session tab's own
 * telemetry (night arc, HFR/RMS/SNR) stays the decoupled simulator fixture
 * it always was — only the header (target name, block progress) is wired to
 * this job.
 */
val SimState.contractJob: SequenceJob? get() =
    jobs.firstOrNull { it.running } ?: jobs.firstOrNull { it.id == lastActiveJobId } ?: jobs.firstOrNull()

/** The job [endSession] stopped — what the Summary sheet and its export report are about. */
val SimState.endedJob: SequenceJob? get() = jobs.firstOrNull { it.id == lastEndedJobId }

/** Looks up a target by id across both catalogs, then the live search results if any (M3). */
fun SimState.findTarget(id: String): Target? =
    TARGETS.firstOrNull { it.id == id } ?: userTargets.firstOrNull { it.id == id }
        ?: wireSearchResults?.firstOrNull { it.id == id }

/** "NGC 7000 — North America" for the well-known catalog; just the name for custom targets (no catalog id to show). */
val Target.displayName: String get() = if (custom) common else "$id — $common"

/** Median of the frame HFR list — real per-frame data, not fixture. */
val SimState.medHfr: Double get() {
    val sorted = frames.map { it.hfr }.sorted()
    return if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
}

/** First not-yet-complete block, or the last block if all are done. */
val SequenceJob.currentBlockIndex: Int? get() =
    if (blocks.isEmpty()) null else blocks.indexOfFirst { it.doneCount < it.subCount }.let { if (it == -1) blocks.lastIndex else it }

val FILTER_CYCLE = listOf("Ha", "OIII", "SII", "L", "R", "G", "B")
val BINNING_OPTIONS = listOf(1, 2, 3, 4)
val DITHER_OPTIONS = listOf(1, 2, 3, 5)

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

internal fun SimState.mapJob(jobId: String, f: (SequenceJob) -> SequenceJob): SimState =
    copy(jobs = jobs.map { if (it.id == jobId) f(it) else it })

internal fun SimState.mapJobBlock(jobId: String, blockId: String, f: (Block) -> Block): SimState =
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

val SimState.elapsed: Int get() = (128 + t) % 300
val SimState.expRemain: String get() = hhmm(300 - elapsed)
val SimState.flipIn: String get() {
    val total = (2530 + flipDeferSec - t).coerceAtLeast(0)
    val m = total / 60
    val r = total % 60
    return "T−$m:${r.toString().padStart(2, '0')}"
}
val SimState.rms: Double get() = 0.48 + sin(t / 7.0) * 0.04
val SimState.guideStarSnr: Double get() = 38.0 + sin(t / 11.0) * 4.0
val SimState.fNow: Double get() = 0.485 + t / 9000.0

/** Minutes until the next scheduled autofocus — real countdown off [afRefocusMin] and the last run's timestamp. */
val SimState.focusNextAfMin: Int get() = (afRefocusMin - (t - focusLastAfAt) / 60).coerceAtLeast(0)

/** Live EAF focuser temperature — reads the user's own edits first, falls back to the driver fixture default. */
val SimState.eafTemp: Double get() = indiNumber("EAF", "FOCUS_TEMPERATURE") ?: -0.6

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
val SimState.benchFocPos: Int get() =
    if (wireFocusSettings != null) focPos else (indiNumber(primaryTrain.focuser, "ABS_FOCUS_POSITION")?.roundToInt() ?: focPos)

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
val SimState.realSlewRateProp: IndiProperty.SwitchProp?
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
val SimState.mountTrackingOn: Boolean get() {
    val prop = (indiProps[primaryTrain.mount] ?: emptyList()).firstOrNull { it.name == "TELESCOPE_TRACK_STATE" } as? IndiProperty.SwitchProp
    return prop?.let { it.elementNames.getOrNull(it.selected) == "TRACK_ON" } ?: mountTracking
}

/**
 * Real mount's actual parked state, read from its own `TELESCOPE_PARK` INDI switch (standard
 * INDI telescope interface property, confirmed live: elements `PARK`/`UNPARK`) — falls back to
 * the fixture-only [mountParked] field. Same read-display/write-via-Ekos-command split as
 * [mountTrackingOn]: writes go through `mount_park`/`mount_unpark`, not this property directly.
 */
val SimState.mountParkedReal: Boolean get() {
    val prop = (indiProps[primaryTrain.mount] ?: emptyList()).firstOrNull { it.name == "TELESCOPE_PARK" } as? IndiProperty.SwitchProp
    return prop?.let { it.elementNames.getOrNull(it.selected) == "PARK" } ?: mountParked
}

/**
 * Real pixel scale (arcsec/pixel) for Plan tab's Framing card — standard formula
 * `206.265 × pixel_size_µm / focal_length_mm`. Null (falls back to the card's own placeholder)
 * until both the primary scope's focal length and the primary camera's [CcdInfo] are known —
 * on the simulator that's always, since [wireCcdInfoByDevice] is only ever populated by
 * [EkosRemoteController].
 */
val SimState.framingPixelScaleArcsecPerPx: Double? get() {
    val focalMm = findScope(primaryTrain.scope)?.focalMm?.takeIf { it > 0 } ?: return null
    val ccd = wireCcdInfoByDevice[primaryTrain.camera] ?: return null
    return 206.265 * ccd.pixelUm / focalMm
}

/** Real field of view (width, height) in degrees, derived from [framingPixelScaleArcsecPerPx] and the primary camera's sensor resolution. */
val SimState.framingFovDeg: Pair<Double, Double>? get() {
    val scale = framingPixelScaleArcsecPerPx ?: return null
    val ccd = wireCcdInfoByDevice[primaryTrain.camera] ?: return null
    return (scale * ccd.maxX / 3600.0) to (scale * ccd.maxY / 3600.0)
}

val SimState.paTotal: Double get() = hypot(paAlt, paAz)
val SimState.coolAtSetPoint: Boolean get() = abs(coolNow - coolTarget) < 0.2
val SimState.coolBarPct: Int get() {
    val raw = ((12.4 - coolNow) / (12.4 - coolTarget) * 100).roundToInt()
    return raw.coerceIn(2, 100)
}
val SimState.coolPowerPct: Int get() = min(99, (abs(coolNow - 12.4) * 3 + 8).roundToInt())
val SimState.frames: List<Frame> get() = FRAME_IDS.mapIndexed { i, id ->
    Frame(id, FRAME_HFRS[i], cut.contains(id))
}
val SimState.rejectCount: Int get() = frames.count { it.cut }
val SimState.keepCount: Int get() = frames.size - rejectCount
val SimState.ready: Boolean get() = ekosRunning && isOn("mount") && isOn("cam")
fun SimState.isOn(key: String): Boolean = ekosRunning && key !in devOff

/** Whether a device is picked for the profile being built — independent of Ekos actually running. */
fun SimState.isSelected(key: String): Boolean = key !in devOff
val SimState.missing: String get() {    val parts = buildList {
        if (!isOn("mount")) add("mount")
        if (!isOn("cam")) add("camera")
    }
    return parts.joinToString(" + ")
}

/** "45 min · 1.0 °C · filter change" — same rule shown on every block, since it's global. */
val SimState.autofocusRuleText: String get() {
    val parts = buildList {
        add("$afRefocusMin min")
        add("${"%.1f".format(afTempDeltaC)} °C")
        if (afOnFilterChange) add("filter change")
    }
    return parts.joinToString(" · ")
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
