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

    /**
     * `new_mount_state` push. Real pushes come in (at least) two disjoint shapes, confirmed live:
     * the documented `{status, target, slewRate, pierSide}` one (fires on an actual mount status
     * transition — slew start/complete, park/unpark, tracking change) and an undocumented,
     * far more frequent (~1/sec) coordinate-telemetry one (`{at, az, de, de0, ha, ra, ra0}`,
     * ignored here — out of scope for what Session tab needs). All 4 fields need defaults for the
     * same reason as [NewPolarState]: without them, every coordinate-shaped push (the common case)
     * fails to decode (missing required fields) and silently degrades to [Raw], meaning
     * `wireMountPierSide` would in practice almost never actually update on a real rig.
     */
    @Serializable
    data class NewMountState(
        val status: String? = null,
        val target: String? = null,
        val slewRate: Int? = null,
        val pierSide: Int? = null,
    ) : EkosEvent

    @Serializable
    data class NewFocusState(val status: String) : EkosEvent

    @Serializable
    data class NewGuideState(val status: String) : EkosEvent

    /**
     * `new_align_state` push — **a 4th occurrence of this repo's own standing decode lesson**
     * (see [NewMountState]/[NewPolarState]/[NewManualRotatorStatus]'s own docs). Confirmed against
     * the real fork source (`message.cpp:942-969`): `Message::setAlignStatus` (`{"status"}`) and
     * `Message::setAlignSolution` (`{"solution"}`) are **two entirely independent senders sharing
     * this one event name** — never combined. The old model here required `status`, so every real
     * `{"solution"}`-only push (fires on **every successful solve**, unconditionally — not gated
     * on `rotator_control` the way [com.nocturne.session.AppState.wireRotatorCurrentPA] is) failed
     * to decode and silently degraded to [Raw]. The real solved PA/RA/Dec/pointing-error data was
     * being discarded outright, not merely unused.
     */
    @Serializable
    data class NewAlignState(
        val status: String? = null,
        val solution: WireAlignSolution? = null,
    ) : EkosEvent

    /**
     * `align_manual_rotator_status` push (M5, docs/STATUS.md) — **server push only**, no request
     * handler (`Message::sendManualRotatorStatus`, message.cpp:2731): `{currentPA, targetPA,
     * threshold}`, all 3 always sent together per the one real call site. Still defaulted/merged
     * non-null rather than required, matching this repo's standing defensive-decode norm for any
     * new event type (the same required-field mistake has silently degraded pushes to [Raw] 3
     * times already — see [NewMountState]/[NewPolarState]'s own docs) even though no partial
     * shape has actually been observed live yet for this one.
     */
    @Serializable
    data class NewManualRotatorStatus(
        val currentPA: Double? = null,
        val targetPA: Double? = null,
        val threshold: Double? = null,
    ) : EkosEvent

    /**
     * `new_polar_state` push. Real pushes arrive as independent **partial** shapes, confirmed
     * against the 5 real sender functions in `message.cpp` (`Message::setPAHStage`/
     * `setPAHMessage`/`setPolarResults`/`setUpdatedErrors`/`setPAHEnabled`) — `{"stage"}` alone,
     * `{"message"}` alone, `{"vector": {...}}` alone, `{"updatedError","updatedAZError",
     * "updatedALTError"}` alone (top-level, NOT nested under `vector`), `{"enabled"}` alone,
     * never combined. Every field needs a default for that reason — without one, every partial
     * real payload fails to decode (missing required field) and silently degrades to [Raw].
     * [vector]'s own fields (`setPolarResults`, `message.cpp:1222-1243`) describe the real
     * correction-vector line Ekos itself overlays on the align image: `center_x`/`center_y` are
     * the vector's midpoint in the *native* align-frame's own pixel space (matches
     * [MediaHeader.resolution], not the on-screen scaled size), `mag`/`pa` its length/angle
     * (`QLineF::length()`/`QLineF::angle()` — Qt's own rotation convention here isn't confirmed
     * against a live solve, so no directional arrow is drawn from this yet, only the numeric
     * error fields — see [com.nocturne.session.AppState] doc).
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
     * `new_scheduler_state` push — same "independent partial shapes" pattern as [NewPolarState]:
     * confirmed live (2026-08-23) it fires with **either** `{"status": Int}` (the real
     * `Ekos::SchedulerState` enum — `0`=IDLE, `1`=STARTUP, `2`=RUNNING, `3`=PAUSED, `4`=SHUTDOWN,
     * `5`=ABORTED, `6`=LOADING, confirmed against the real KStars source
     * `kstars/ekos/ekos.h`, not guessed) **or** `{"log": String}` (the Scheduler's own running
     * log history, re-pushed on every new log line, unrelated to status and not modeled here —
     * only [status] is read). Fires on ANY real transition, not just ones this app's own
     * `scheduler_start_job` toggle caused — confirmed live it also fires from the real Ekos
     * Scheduler evaluating on its own with an empty queue. This is what [EkosRemoteController]'s
     * `schedulerRunning` was missing before this — an in-memory optimistic bool with no real
     * feedback loop, confirmed live to go stale and send `scheduler_start_job` in the *wrong*
     * direction (re-starting an already-stopped real Scheduler) once reality diverged from it.
     */
    @Serializable
    data class NewSchedulerState(val status: Int? = null, val log: String? = null) : EkosEvent

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

    /**
     * `astro_get_almanac` reply — "computed for local midnight today at the configured geo
     * location" per the reference. Curated to the 2 fields Session tab's real night-arc needs
     * (dawn/dusk), not the full Sun/Moon field set. Confirmed live: **not** clock times —
     * fraction-of-day *offsets from local midnight*, signed (e.g. `dusk: -0.0896` = 2h9m
     * *before* tonight's midnight; `dawn: 0.1938` = 4h39m *after* it), unlike `SunRise`/`SunSet`
     * (unsigned fraction-of-day-since-previous-midnight, not modeled here — not needed). Real key
     * names are capitalized (`"Dusk"`/`"Dawn"`), hence the `@SerialName`s.
     */
    @Serializable
    data class AstroAlmanac(
        @SerialName("Dusk") val dusk: Double = 0.0,
        @SerialName("Dawn") val dawn: Double = 0.0,
        /** Real Moon illuminated fraction, `[0.0, 1.0]` (`KSAlmanac::getMoonIllum()`, confirmed
         * against source — same reply this app already fetches for Dusk/Dawn, this field was just
         * never decoded before; `MoonPhase` (`[0, 180]` degrees, the Sun-Moon elongation angle —
         * *not* a full 0-360 waxing/waning-distinguishing angle) exists in the same reply too but
         * isn't modeled here, since illuminated fraction alone is the unambiguous, directly
         * displayable real number — user's own "lunar phase" ask. */
        @SerialName("MoonIllum") val moonIllum: Double? = null,
    ) : EkosEvent

    /**
     * `astro_get_location` reply — curated to [tz] alone (real signed hour offset from UTC,
     * confirmed live e.g. `-7` for PDT), the one field [AstroAlmanac]'s offsets need to resolve
     * into an absolute real-world instant. `latitude`/`longitude`/`elevation`/`name`/`tz0` exist
     * on the real reply too but aren't needed for this.
     */
    @Serializable
    data class AstroLocation(val tz: Double = 0.0) : EkosEvent

    // ── M3: scheduler ────────────────────────────────────────────────────

    /** `scheduler_get_jobs` reply. */
    @Serializable
    data class SchedulerJobs(val jobs: List<WireSchedulerJob>) : EkosEvent

    /**
     * `scheduler_save_sequence_file` reply. [path] is the input `path` **resolved** to an
     * absolute filesystem path server-side (home-relative input, confirmed live: sending
     * `"foo.esq"` echoes back `"/home/<user>/foo.esq"`) — this differs from `sequenceEdit`'s own
     * resolution in `scheduler_set_all_settings`, where a bare relative filename produces a
     * broken unresolved `file:foo.esq` URI, not a home-relative one (confirmed live the hard
     * way). [EkosRemoteController.toggleJobRun] must wait for this reply and reuse [path]
     * verbatim as `sequenceEdit`, never guess a home directory client-side.
     */
    @Serializable
    data class SchedulerSaveSequenceFile(val result: Boolean = false, val path: String = "") : EkosEvent

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

    /**
     * `option_get` reply (M4.5 half B, docs/STATUS.md) — bare array, one entry per requested
     * option, in request order (`message.cpp:1456-1470`). Same reply `type` as the request
     * (`OPTION_GET`, not a distinct push name) — matches this repo's own established
     * request/reply-reuse-the-same-type-string norm (see this file's own top doc).
     */
    @Serializable
    data class OptionValues(val options: List<WireOption>) : EkosEvent

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
     * [WireFocusSettings] models 6 curated (`absTicksSpin` for the Focuser-position seed, plus
     * exposure/gain/filter/backlash/algorithm for the Focus settings sheet, M3.3 phase 6) — see
     * that class's own doc. `ignoreUnknownKeys` drops the other 78.
     */
    @Serializable
    data class FocusSettings(val settings: WireFocusSettings) : EkosEvent

    /**
     * `scheduler_get_all_settings` reply — real Ekos's Scheduler reports ~70 fields (same
     * reflection pattern as Mount/Camera/Align/Guide/Focus above); [WireSchedulerSettings] models
     * the ~30 curated as the Scheduler's own *global policy* — Startup condition, Constraints
     * (+ per-job step toggles, same tab in real Ekos), Completion condition, Observatory
     * startup/shutdown procedure, Aborted-job handling — matching real Ekos's own Scheduler tab
     * layout (docs M2026-08). Explicitly excluded: per-job/job-creation-form fields already sent
     * elsewhere by [EkosRemoteController] (`nameEdit`/`sequenceEdit`/`raBox`/`decBox`/
     * `opticalTrainCombo`/`startupTimeConditionR` — bug #19's `toggleJobRun` chain), Weather-tied
     * fields (`kcfg_SchedulerWeather*`, `kcfg_SchedulerSafetyMonitorConnectionString` — same scope
     * as the deferred Sky & Site weather pass), deeper Options-page align-recovery/greedy-
     * scheduling toggles (`kcfg_AlignCheck*`, `kcfg_*ResetMountModel*`,
     * `kcfg_RealignAfterCalibrationFailure`, `kcfg_GreedyScheduling`),
     * and `executionSequenceLimit`/`schedulerProfileCombo`/`epochCB`/`positionAngleSpin`/`groupEdit`/
     * `fitsEdit`/`leadFollowerSelectionCB` (job-form or unclear-purpose, not scheduler policy).
     * `ignoreUnknownKeys` drops all of the above. **`kcfg_RememberJobProgress` was un-excluded
     * 2026-08-23** — see [WireSchedulerSettings.kcfg_RememberJobProgress]'s own doc for why.
     * **`kcfg_ForceAlignmentBeforeJob` was un-excluded 2026-08-23 too** — see that field's own doc.
     */
    @Serializable
    data class SchedulerSettings(val settings: WireSchedulerSettings) : EkosEvent

    /**
     * `new_camera_state` push (`Message::sendTemperature`, `message.cpp:438-449`) — real per-device
     * temperature telemetry. **Not** `new_temperature`: that command name is declared in
     * `commands.h` but never actually sent anywhere in `message.cpp` — confirmed dead, same class
     * as the already-known `mount_clear`/`device_restart`/`device_blob_get` dead entries (README §8).
     */
    @Serializable
    data class NewCameraState(val name: String, val temperature: Double) : EkosEvent

    /**
     * `new_notification` push (M4.5 half A, docs/STATUS.md) — **server push only**, no request
     * handler (`Message::sendEvent`, `message.cpp:2721-2735`). Confirmed against the real fork
     * source this is a genuinely comprehensive stream: `KSNotification::event()`
     * (`ksnotification.cpp:98-113`) is the single generic entry point essentially every notable
     * real event anywhere in KStars already goes through (mount faults, capture/focus/guide/align
     * failures, scheduler transitions, INDI server messages...) — routed via
     * `Manager::announceEvent` → this push, gated only by the real `Options::ekosRemoteNotifications()`
     * (kcfg default `true`). `source`/`severity` are the raw real `KSNotification::EventSource`/
     * `EventType` enum ints (`ksnotification.h`): source `0`=General `1`=INDI `2`=Capture
     * `3`=Focus `4`=Align `5`=Mount `6`=Guide `7`=Observatory `8`=Scheduler; severity `0`=Debug
     * `1`=Info `2`=Warn `3`=Alert. All 4 fields still defaulted per this repo's standing decode
     * norm even though the real sender constructs one literal `QJsonObject` with all 4 together
     * (no independently-partial shape confirmed for this one specifically).
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
 * `new_polar_state`'s `vector` field (`Message::setPolarResults`, `message.cpp:1222-1243`) — the
 * real correction-vector line Ekos overlays on its own align image. All fields nullable/defaulted
 * since [EkosEvent.NewPolarState] itself models 5 independently-partial real payload shapes and
 * this one is never combined with the others.
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
 * `new_align_state`'s `solution` field (M5, docs/STATUS.md — `Message::setAlignSolution`,
 * `align_solver.cpp:875-891`) — the real result of every successful solve, unconditional (unlike
 * [com.nocturne.session.AppState.wireRotatorCurrentPA], which only arrives when `rotator_control`
 * is on). [pa] is the real solved position angle — the correct primary source for the Framing
 * card's/Controls tab's "current camera angle" FOV box, confirmed against
 * [EkosEvent.NewAlignState]'s own doc. [targetDiff] is the real total pointing error (arcsec) the
 * solve's own goto/sync accuracy is judged against (`alignAccuracyThreshold`). All fields
 * nullable/defaulted per this repo's standing decode norm, even though the real sender constructs
 * every field together in one `QJsonObject` literal (no independently-partial shape confirmed for
 * this one specifically) — cheap insurance, matches every sibling model.
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
 * Real states meaning "Ekos has committed to this job" — evaluating it, scheduled for a future
 * window, or actually running it. Moved here (was a private file-scope val in
 * `EkosRemoteController.kt`) once `AppState.contractJob` needed it too, not just the controller's
 * own reconcile logic. See `EkosRemoteController`'s `reconcileSyncedJobStatus`/`contractJob`'s doc
 * for the live-confirmed history of why `EVALUATION`/`SCHEDULED` count as "active" alongside `BUSY`.
 */
val ACTIVE_SCHEDULER_STATES = setOf(SchedulerJobStatus.EVALUATION, SchedulerJobStatus.SCHEDULED, SchedulerJobStatus.BUSY)

/**
 * Raw `state` int → short UI label. Same "named only where confirmed, honest fallback otherwise"
 * shape as `AppState.mountPierSideLabel` — every value here is directly enumerated in
 * [SchedulerJobStatus] against the real `schedulertypes.h` source, so `"raw $state"` should never
 * actually be reachable, but stays as a non-inventing fallback rather than an unchecked `!!`/crash
 * if a future Ekos version ever adds a 9th value. `BUSY` deliberately reuses "Imaging" — the exact
 * word `NocturneApp.kt`'s header already uses for the same real condition.
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
 * `option_get`'s per-entry reply shape (M4.5 half B, docs/STATUS.md — `message.cpp:1464-1467`):
 * `{"name": string, "value": <any QVariant>}`. [value] stays a raw [JsonElement] since real
 * `Options::self()` properties span every Qt type (bool/int/double/string/...) — callers decode
 * whichever shape they actually requested (this app only ever requests real `Bool`-typed kcfg
 * entries so far, see [EkosRemoteController.ensurePrefsLoaded]).
 */
@Serializable
data class WireOption(val name: String, val value: JsonElement)

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
 * Curated subset (~30 of ~70 real fields) of real Ekos's Scheduler-wide *policy* settings — see
 * [EkosEvent.SchedulerSettings]'s doc for exactly what's excluded and why. Field names match the
 * wire verbatim (`Scheduler::getAllSettings`'s Qt widget object names), confirmed live against
 * the real rig (real values seen 2026-08-22: `asapConditionR true`, `schedulerTwilight true`,
 * `schedulerAltitude true` / `schedulerAltitudeValue -15`, `kcfg_DawnOffset 0` /
 * `kcfg_DuskOffset 0`, `schedulerPostStartupScript
 * "/usr/share/kstars/taskqueue/collections/observatory_startup.json"`,
 * `schedulerPreShutdownScript ".../observatory_shutdown.json"` — a real, already-active
 * observatory automation this app had never shown or let the user manage).
 *
 * **`schedulerTwilight true` here is the real root config behind the twilight-shutdown incident**
 * (see project notes) — this app had zero visibility into it before now; this sheet is the first
 * place it's ever surfaced client-side.
 *
 * Startup condition + timing:
 * - [asapConditionR] / [startupTimeConditionR] — mutually exclusive radio pair; real Ekos
 *   reflects each radio button as its own bool (confirmed live: exactly one is `true` at a time),
 *   **not** the single-int shape `EkosRemoteController.toggleJobRun`'s existing
 *   `put("startupTimeConditionR", 0)` send assumes for a brand-new job (that pre-existing,
 *   already-shipped, already-live-verified send is left as-is — Qt evidently coerces the int 0
 *   into this bool field on write without erroring; not touched here, out of scope for this pass).
 * - [startupTimeEdit] — ISO datetime string, only meaningful when [startupTimeConditionR] is set.
 * - [kcfg_LeadTime]/[kcfg_PreDawnTime] — minutes; how early to prep before a job's start time, and
 *   how close to dawn a job is allowed to still be running.
 *
 * Constraints (+ real Ekos's own per-job step toggles, same tab in the real Scheduler UI):
 * - [schedulerAltitude]/[schedulerAltitudeValue], [schedulerMoonSeparation]/[schedulerMoonSeparationValue],
 *   [schedulerMoonAltitude]/[schedulerMoonAltitudeMaxValue], [schedulerTwilight], [schedulerHorizon].
 * - [kcfg_DawnOffset]/[kcfg_DuskOffset] — signed hours padding on top of the real astronomical
 *   dawn/dusk instant (same source `AppState.realNightWindow` reads) before twilight constraints
 *   engage.
 * - [schedulerTrackStep]/[schedulerFocusStep]/[schedulerAlignStep]/[schedulerGuideStep] — per-job
 *   step defaults (Track/Focus/Align/Guide), same Constraints tab in real Ekos.
 *
 * Completion condition (mutually exclusive group, same shape as Startup condition above):
 * [schedulerCompleteSequences], [schedulerRepeatSequences]/[schedulerRepeatSequencesLimit],
 * [schedulerRepeatEverything], [schedulerUntilTerminated], [schedulerUntil]/[schedulerUntilValue].
 *
 * Observatory startup/shutdown procedure:
 * [schedulerStartupEnabled]/[schedulerPreStartupScript]/[schedulerPostStartupScript],
 * [schedulerShutdownEnabled]/[schedulerPreShutdownScript]/[schedulerPostShutdownScript],
 * [kcfg_PreemptiveShutdown]/[kcfg_PreemptiveShutdownTime], [kcfg_StopEkosAfterShutdown],
 * [kcfg_ShutdownScriptTerminatesINDI].
 *
 * Aborted-job handling: [errorHandlingDontRestartButton]/[errorHandlingRestartImmediatelyButton]/
 * [errorHandlingRestartQueueButton] (mutually exclusive), [errorHandlingRescheduleErrorsCB],
 * [errorHandlingStrategyDelay] (minutes).
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
     * `Options::forceAlignmentBeforeJob()` (real default `false`, `kstars.kcfg:3365-3368`) —
     * "force alignment before starting or restarting each job." **Confirmed against source
     * (`schedulerprocess.cpp:306,364,417`): this is still gated by `SchedulerJob::USE_ALIGN`**
     * (`getStepPipeline() & USE_ALIGN && forceAlignmentBeforeJob()`) — it does NOT override a job
     * whose Align step is genuinely off; it only adds *extra* align-before-(re)start moments for
     * a job that already has Align enabled. Not the fix for "I unchecked Align but it keeps
     * aligning" (that's almost certainly the *other* real mechanic: [schedulerAlignStep] here is
     * only the **default for a newly-created job** — editing it after a job already exists on the
     * real Scheduler doesn't retroactively change that job's own already-baked-in step pipeline).
     * Surfaced anyway since it's a real, previously-invisible-to-this-app toggle in its own right.
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
     * Added 2026-08-23, previously on the exclusion list above — real root cause behind this
     * app's own "Ekos auto-resumes a stale queued job on its own restart" investigation (see
     * `docs/app-side-feature-backlog.md`/project notes): confirmed `true` on this rig via a
     * one-off wire probe well before this field had any UI at all. Surfaced now for real
     * visibility given how central it turned out to be to that whole incident.
     */
    val kcfg_RememberJobProgress: Boolean = true,
)

/**
 * Curated subset (13 of 59 real fields, docs/M3.3-plan.md) of real Ekos's Camera module
 * settings — save path (+ its separate placeholder-format template, added 2026-08-23),
 * guide-deviation abort guard, start-of-job guide-drift guard, per-job
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
 * preview's), HFR-deviation/meridian-flip refocus triggers and calibration/script fields
 * (deeper, deferred). **`enforceRefocusEveryN`/`refocusEveryN`/`enforceAutofocusOnTemperature`/
 * `maxFocusTemperatureDelta` were added 2026-08-23** — see their own doc below; no longer
 * "one-time setup, not per-session" once the app actually needed real autofocus-trigger
 * settings.
 */
@Serializable
data class WireCaptureSettings(
    val fileDirectoryT: String = "",
    /**
     * The real save-path *template* applied on top of [fileDirectoryT] — confirmed against
     * source (`camera_config.cpp:1076`: `m_format = fileDirectoryT->text() +
     * placeholderFormatT->text() + ...`) that these are two genuinely separate real fields
     * concatenated, not one. Real default something like `/%t/%T/%F/%t_%T_%F_...`
     * (`Options::placeholderFormat()`/`PlaceholderPath::defaultFormat()`) — `%t`=target name,
     * `%T`=frame type (Light/Dark/Flat/...), `%F`=filter. Added 2026-08-23 — previously this app
     * only exposed [fileDirectoryT], leaving the actual on-disk subfolder structure real
     * captures land in completely invisible/uneditable.
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
    val cameraTemperatureN: Double = -10.0,
    /**
     * Real "Refocus every:" trigger (2026-08-23) — confirmed against real KStars source
     * (`limits.ui`: `enforceRefocusEveryN` checkbox + `refocusEveryN` spinbox,
     * `sequencequeue.cpp:245`: `Options::enforceRefocusEveryN()`). Previously excluded from
     * this curated subset as "one-time setup, not per-session" — revisited once the app's own
     * `Block`-level "force AF at block start" idea turned out to have no real backing at all;
     * this pair is the genuine, real, always-global equivalent (`capture_set_all_settings`, no
     * job index — confirmed `message.cpp:542,546`). Merged into Camera settings rather than a
     * separate sheet since it's the exact same wire command already used there.
     */
    val enforceRefocusEveryN: Boolean = true,
    val refocusEveryN: Int = 60,
    /** Real "Refocus if ΔT° >:" trigger — same real source/command as [enforceRefocusEveryN] above. */
    val enforceAutofocusOnTemperature: Boolean = false,
    val maxFocusTemperatureDelta: Double = 1.0,
    /**
     * The Capture tab's own current filter selection (real `camera.ui`'s `FilterPosCombo`
     * `QComboBox`, confirmed live 2026-08-23 — real reply carried `"FilterPosCombo":"L"` for
     * this rig) — what a bare `capture_preview`/Bench "Snap main" actually shoots with, same
     * "whatever the Capture module's own currently-loaded values are" shape as
     * [captureExposureN]/[captureGainN]/[captureBinHN]/[captureBinVN] above. Kept as the exact
     * wire key verbatim (capital-leading, unlike every other field here) rather than renamed to
     * a lowerCamelCase Kotlin-style name — same precedent as the `kcfg_`-prefixed Scheduler
     * fields keeping their own unusual real casing, so the property name stays a direct,
     * grep-able match to the wire.
     */
    val FilterPosCombo: String = "",
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
 * `AppState.focPos` from this value the moment it arrives (see
 * `EkosRemoteController.applyEvent`'s `FocusSettings` arm) rather than switching the display
 * over to this field permanently — `focPos` already live-tracks jogs via local optimistic
 * increment (`jogFocus`), so seeding it once here gets both properties right: matches Ekos's own
 * number at connect, keeps live-updating afterward exactly like it already did.
 *
 * Extended for M3.3 phase 6 (Focus settings sheet) with exposure/gain/filter/backlash/algorithm
 * — field names match the wire verbatim, confirmed live against the real rig (real values seen:
 * `focusExposure 2`, `focusGain 99`, `focusFilter "L"`, `focusBacklash 0`,
 * `focusAlgorithm "Linear 1 Pass"`). Unlike Guide's field-name history, this list carries no
 * risk: `EkosRemote-Command-Reference.md` flags Focus's field list "Live-captured" (the same
 * trusted bucket as Capture/Align, not Guide's "not live-verified" flag), and all 5 names
 * appeared verbatim in that list before this probe — this confirms types only, not names.
 * **`focusAlgorithm` is a string** (an algorithm-name combo selection like `"Linear 1 Pass"`),
 * not an enum index — same shape as `alignBinning`/`guideBinning`, don't assume otherwise.
 */
@Serializable
data class WireFocusSettings(
    val absTicksSpin: Int = 18422,
    val focusExposure: Double = 2.0,
    val focusGain: Double = 99.0,
    val focusFilter: String = "L",
    val focusBacklash: Int = 0,
    val focusAlgorithm: String = "Linear 1 Pass",
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
    /**
     * Real `QCheckBox` (`align.ui:739`) — confirmed against `align_devices.cpp:583/590`
     * (`Align::checkFilter`): when checked, `alignFilter`'s combo is **disabled** and forced to
     * whatever the filter wheel is actually on (`m_FilterManager->getFilterPosition()`) — a solve
     * never changes the filter. When unchecked, `alignFilter` is a fixed user choice that a solve
     * *does* force-switch to every time, even mid-sequence. Was missing from this model entirely
     * — Nocturne's own Filter chip always behaved like the unchecked/fixed case, silently forcing
     * a filter switch on every solve regardless of what was actually loaded.
     */
    val alignUseCurrentFilter: Boolean = false,
    /**
     * The Align module's solver-action radio group — what real Ekos does automatically after a
     * successful `align_solve`, confirmed live: `nothingR` (report only, the real default),
     * `slewR` (re-slew onto the solved position for real — this is what a real "goto & center"
     * needs, no client-side offset math required), `syncR` (sync the mount's internal model
     * instead of moving it). Mutually exclusive on the real side; curated in here (rather than a
     * one-off raw read) so [EkosRemoteController.gotoAndCenter] can save the user's normal
     * setting and restore it afterward instead of silently leaving `slewR` on.
     */
    val nothingR: Boolean = true,
    val slewR: Boolean = false,
    val syncR: Boolean = false,
    /**
     * M5 (docs/STATUS.md) — real `kcfg_AstrometryRotatorThreshold`, arcminutes (confirmed against
     * `kstars.kcfg`: `<default>30</default>`, "Threshold between measured and FITS position
     * angles in arcminutes to consider the load and slew operation successful"). Unlike
     * `rotator_control` (a `QGroupBox` in `opsalign.ui`, not covered by the reflection this
     * command's GET/SET share), this one's a normal `QDoubleSpinBox` — same reflection path as
     * every other field here, real read *and* write, no special-cased command needed. Distinct
     * unit from [com.nocturne.session.AppState.wireRotatorThreshold] (that one's pushed already
     * converted to **degrees** — confirmed `align_goto.cpp`: `Options::astrometryRotatorThreshold()
     * / 60.0` — for direct diff-vs-currentPA/targetPA comparison; this field stays raw arcminutes,
     * matching the settings-sheet control the user edits it through).
     */
    val kcfg_AstrometryRotatorThreshold: Double = 30.0,
)
