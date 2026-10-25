package com.nocturne.session

import com.nocturne.data.ConnectionRepository
import com.nocturne.data.FrameRepository
import com.nocturne.data.SequenceRepository
import com.nocturne.data.TargetRepository
import com.nocturne.data.UserCatalogSnapshot
import com.nocturne.data.SequenceSnapshot
import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.MediaFrameType
import com.nocturne.protocol.frameType
import com.nocturne.protocol.WireAstroObject
import com.nocturne.protocol.WireDevice
import com.nocturne.protocol.WireProfile
import com.nocturne.protocol.WireProperty
import com.nocturne.protocol.WireRiseset
import com.nocturne.protocol.WireScope
import com.nocturne.protocol.WireTrain
import com.nocturne.protocol.MODULE_KEY_BY_TRAIN_SETTING
import com.nocturne.protocol.SchedulerJobStatus
import com.nocturne.protocol.WireSchedulerJob
import com.nocturne.protocol.bitmaskToRoles
import com.nocturne.transport.EkosRemoteClient
import com.nocturne.transport.ReferenceImageClient
import com.nocturne.hips.SkyAtlas
import com.nocturne.transport.RigRebootClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/**
 * Session driver for an EkosRemote connection. Telemetry arrives as [EkosEvent] pushes on
 * [client] and is applied to the wire-mirror fields (`wireCaptureStatus`, `wireDevices`, ...).
 * Methods that send a wire command also update local state optimistically so the UI moves
 * immediately; the later push or `get_*` reply reconciles it. Local-UI methods inherited from
 * [AbstractLocalSessionController] stay local-only.
 */
class EkosRemoteController(
    private val client: EkosRemoteClient,
    private val scope: CoroutineScope,
    /** Persists the reboot daemon's port (see [setRigRebootPort]/[rebootRig]). Null in tests. */
    private val connectionRepo: ConnectionRepository? = null,
    /** Persists the app's local job queue — see [SequenceSnapshot] for why the app's list, not
     *  Ekos's Scheduler, is the source of truth. Null in tests. */
    private val sequenceRepo: SequenceRepository? = null,
    /** Persists capture frames for the Frames grid (see [FrameEntity]). Null in tests. */
    private val frameRepo: FrameRepository? = null,
    /** Persists the Plan tab's user catalogue ("My targets"). Null = not persisted. */
    private val targetRepo: TargetRepository? = null,
    /** Offline sky atlas for framing, copied from the rig. Optional: null = online cutouts only. */
    private val skyAtlas: SkyAtlas? = null,
    /** Asked before optional internet use (online framing cutout) — false on the rig's own,
     *  possibly SIM-metered network; see [com.nocturne.transport.RigNetwork]. */
    private val allowOptionalInternet: () -> Boolean = { true },
) : AbstractLocalSessionController() {

    /** Name a `profile_delete` was just sent for — checked against the next [EkosEvent.Profiles]. */
    private var pendingProfileDelete: String? = null

    /** Plain internet fetch for reference images; independent of the wire protocol. */
    private val referenceImageClient = ReferenceImageClient()
    private var skyAtlasCopyJob: Job? = null

    // Plan tab search. astro_get_objects_info and riseset replies can arrive in either order, so
    // the latest of each is kept and merged by buildSearchResults(). astro_search_objects has no
    // name filter (only type/alt/mag/FOV), so its flat name list (lastSearchNames) is
    // substring-filtered locally against AppState.query, and re-filtered on each keystroke.
    private var lastAstroObjects: List<WireAstroObject> = emptyList()
    private var lastRiseset: List<WireRiseset> = emptyList()
    private var lastSearchNames: List<String>? = null
    // No-type name search: every name KStars knows (astro_get_names), fetched once per
    // connection on first need and filtered locally like lastSearchNames.
    private var allNames: List<String>? = null
    private var allNamesRequested = false
    // Typed-name lookup (astro_get_object_info): finds names the type search misses (other type,
    // below the altitude cutoff, no catalogued magnitude). One request in flight at a time;
    // replies carry the resolved name, not the typed one, so a query typed mid-flight is queued
    // and the in-flight reply discarded. lastLookupObject is the current query's match, if any.
    private var lastLookupObject: WireAstroObject? = null
    private var lookupInFlight = false
    private var queuedLookupQuery: String? = null

    // Devices whose full property set has been requested (DEVICE_GET + full subscribe). The
    // DEVICE_GET reply itself carries CONNECTION=connected, so without this set every reply
    // would trigger another DEVICE_GET indefinitely.
    private val fullyFetchedDevices = mutableSetOf<String>()

    // Last time (ms) we pushed Preferred Pier Side = East per device — see enforcePreferredPierSide.
    private val pierSideFixSentAt = mutableMapOf<String, Long>()

    /** Name a standalone [ensureTargetRiseset] fetch is in flight for — see that function's doc. */
    private var pendingTargetRisesetName: String? = null

    /** Raw `train_get_profiles` reply (ordinal → train ID) — see [resolveModuleTrainAssignments]. */
    private var lastTrainProfileAssignments: Map<String, Int>? = null

    /**
     * Module → train-name for an optimistic [setModuleTrain] pick not yet confirmed by the
     * server's own snapshot — see [resolveModuleTrainAssignments] for why this exists.
     */
    private val pendingModuleTrainAssignments = mutableMapOf<String, String>()

    /**
     * Name a `scheduler_add_jobs` was just sent for, matched against the next
     * `scheduler_get_jobs` reply's `name` (the `nameEdit` sent) to capture the job's
     * `wireIndex` and mark it synced. `scheduler_add_jobs` has no reply of its own.
     */
    private var pendingSyncJobId: String? = null
    private var pendingSyncName: String? = null

    /**
     * Name a `scheduler_start_job` should follow once the pending sync lands. Consumed in
     * [sendFollowUpCommands] separately from [pendingSyncName], which [applySchedulerJobs] clears
     * first. [alsoStart] is true only for [pushRealJob] calls from [resumeSession]/[startNextJob],
     * and the toggle is sent only if `!schedulerRunning`: the start action is a toggle, so sending
     * it while running would stop an in-progress job. A plain [pushJob] never starts anything.
     */
    private data class PendingSchedulerStart(val name: String, val alsoStart: Boolean)
    private var pendingSchedulerStart: PendingSchedulerStart? = null

    /**
     * State for the rest of [pushRealJob]'s save→settings→add→get→(start) chain, held until the
     * save reply lands: `sequenceEdit` needs the server-resolved absolute path from that reply
     * (see [EkosEvent.SchedulerSaveSequenceFile]).
     */
    private data class PendingJobStart(val jobId: String, val targetName: String, val raDec: Pair<String, String>?, val trainName: String?, val alsoStart: Boolean = false)
    private var pendingJobStart: PendingJobStart? = null

    /** In-memory mirror of the reboot daemon's port, for [rebootRig]/[syncTimeToRig]. */
    private var rigRebootPort: Int = 9001

    /** Last-applied time per (device, property) for [shouldThrottleNumberPush]. */
    private val lastAppliedNumberPushMs = mutableMapOf<Pair<String, String>, Long>()

    /**
     * `CCD_EXPOSURE` pushes several times a second during an exposure, and each push recomposes
     * every screen reading the shared [AppState] (including expensive cards like
     * `MountControlCard`), enough to drop frames and risk ANR. Throttles only Busy→Busy number
     * updates to one per 400 ms; state transitions, first pushes and non-Busy updates always
     * apply immediately.
     */
    private fun shouldThrottleNumberPush(device: String, property: WireProperty, current: AppState): Boolean {
        if (property !is WireProperty.Number) return false
        val wasBusy = (current.indiProps[device]?.firstOrNull { it.name == property.name } as? IndiProperty.NumberProp)?.state == 2
        if (!(wasBusy && property.state == 2)) return false
        val key = device to property.name
        val now = System.currentTimeMillis()
        val last = lastAppliedNumberPushMs[key]
        if (last != null && now - last < 400) return true
        lastAppliedNumberPushMs[key] = now
        return false
    }

    init {
        if (skyAtlas != null) scope.launch { rescanSkyAtlas() }
        // AppState defaults jobs to DEFAULT_JOBS (one pre-marked running). Start empty until
        // sequenceRepo restores the saved queue below or the user adds one.
        _state.update {
            it.copy(
                jobs = emptyList(), activeJobId = null, jobSeq = 1, lastActiveJobId = null, openBlockId = null,
            )
        }
        scope.launch {
            client.events.collect { event ->
                _state.update { s -> applyEvent(s, event) }
                sendFollowUpCommands(event)
            }
        }
        // `/media/ekos` binary frames, routed by header uuid to the matching AppState field
        // (latest frame per type only). Capture frames also persist to Room via frameRepo, which
        // backs the Frames grid history.
        scope.launch {
            client.mediaFrames.collect { frame ->
                if (frame.header.frameType == MediaFrameType.CAPTURE) {
                    frameRepo?.let { repo ->
                        val source = _state.value.activeFrameSource()
                        scope.launch { repo.insert(frame, System.currentTimeMillis(), source) }
                    }
                }
                _state.update { s ->
                    when (frame.header.frameType) {
                        MediaFrameType.CAPTURE -> s.copy(latestCaptureFrame = frame)
                        MediaFrameType.ALIGN -> s.copy(latestAlignFrame = frame)
                        MediaFrameType.FOCUS -> s.copy(latestFocusFrame = frame)
                        MediaFrameType.GUIDE -> s.copy(latestGuideFrame = frame)
                        // Live-video (minimal header, no uuid) and dark/hips lookups aren't
                        // rendered anywhere yet — dropped rather than guessed into a slot.
                        MediaFrameType.LIVE_VIDEO, MediaFrameType.DARK, MediaFrameType.OTHER -> s
                    }
                }
            }
        }
        // Room is the source of truth for the Frames grid; mirror it into AppState so the UI
        // never reads the database directly.
        frameRepo?.let { repo ->
            scope.launch {
                repo.observeAll().collect { rows -> _state.update { it.copy(frameRows = rows) } }
            }
        }
        client.connect()

        connectionRepo?.let { repo ->
            scope.launch {
                val saved = repo.current()
                rigRebootPort = saved.rebootPort
                _state.update { it.copy(rigRebootPort = saved.rebootPort) }
            }
        }

        sequenceRepo?.let { repo ->
            // Restore the last queue. Purely local: nothing is synced to Ekos's Scheduler on
            // connect. A single suspend read, effectively immediate.
            scope.launch {
                val saved = repo.current()
                if (saved.jobs.isNotEmpty() || saved.lastActiveJobId != null) {
                    _state.update {
                        it.copy(jobs = saved.jobs, jobSeq = saved.jobSeq, lastActiveJobId = saved.lastActiveJobId)
                    }
                }
            }
            // Persist every later change from any source, in one place rather than in each mutator.
            scope.launch {
                _state.map { SequenceSnapshot(it.jobs, it.jobSeq, it.lastActiveJobId) }
                    .distinctUntilChanged()
                    .collect { repo.save(it) }
            }
        }

        targetRepo?.let { repo ->
            // Same load-then-persist-every-change shape as sequenceRepo above. The save
            // collector only starts after the load lands, so the default empty catalogue can
            // never be written over a real saved one in the gap between the two.
            scope.launch {
                val saved = repo.current()
                _state.update {
                    it.copy(userCatalogName = saved.name, userTargets = saved.targets, userTargetSeq = saved.targetSeq)
                }
                _state.map { UserCatalogSnapshot(it.userCatalogName, it.userTargets, it.userTargetSeq) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { repo.save(it) }
            }
        }
    }

    private fun applyEvent(s: AppState, event: EkosEvent): AppState = when (event) {
        // `online` also drives AppState.ekosRunning (Start/Stop Ekos, ReadyBanner). AppState
        // defaults it to true, which is wrong while the socket is open but Ekos isn't started.
        // activeProfile mirrors get_profiles' selection only while online.
        is EkosEvent.NewConnectionState -> s.copy(
            ekosRunning = event.online,
            activeProfile = if (event.online) s.selectedProfile else null,
        )
        is EkosEvent.NewCaptureState -> s.copy(wireCaptureStatus = event.status)
        // Merge non-null fields: the frequent coordinate-shaped push carries none of these and
        // must not clear a known status/pierSide.
        is EkosEvent.NewMountState -> s.copy(
            wireMountStatus = event.status ?: s.wireMountStatus,
            wireMountTarget = event.target ?: s.wireMountTarget,
            wireMountSlewRate = event.slewRate ?: s.wireMountSlewRate,
            wireMountPierSide = event.pierSide ?: s.wireMountPierSide,
        )
        is EkosEvent.NewFocusState -> {
            // Wire status wins over the optimistic Start/Stop flag (scheduler/desktop-started
            // runs, runs that finish or fail on their own) — same as NewGuideState below.
            val running = event.status?.let { it !in FOCUS_IDLE_STATES } ?: s.focusRunning
            // A new autofocus run (idle → busy) starts a fresh V-curve, like Ekos's own plot.
            // Not keyed off `focusinitHFRPlot`: Ekos re-sends that mid-run whenever it redraws.
            val newRun = event.status != null && running &&
                (s.wireFocusStatus == null || s.wireFocusStatus in FOCUS_IDLE_STATES)
            val base = if (newRun) s.copy(focusSamples = emptyList(), focusCurveTitle = null) else s
            // HFR <= 0 = no star detected on that frame — nothing to plot.
            val sample = if (event.hfr != null && event.hfr > 0 && event.pos != null) FocusSample(event.pos, event.hfr) else null
            base.copy(
                wireFocusStatus = event.status ?: s.wireFocusStatus,
                focusRunning = running,
                focusSamples = sample?.let { (base.focusSamples + it).takeLast(MAX_FOCUS_SAMPLES) } ?: base.focusSamples,
                focusCurveTitle = event.title?.takeIf { it.isNotBlank() } ?: base.focusCurveTitle,
            )
        }
        is EkosEvent.NewGuideState -> {
            // Wire status wins over the optimistic Start/Stop flag — catches guiding started by
            // the scheduler or desktop, and lost-star/calibration-failure aborts.
            val guiding = event.status?.let { it !in GUIDE_IDLE_STATES } ?: s.guiding
            // A new run (wire status idle → busy) starts a fresh trace, like Ekos's own graph.
            val newRun = event.status != null && guiding &&
                (s.wireGuideStatus == null || s.wireGuideStatus in GUIDE_IDLE_STATES)
            val base = if (newRun) s.copy(guideSamples = emptyList(), guideRmsRA = null, guideRmsDE = null) else s
            val sample = if (event.raError != null && event.decError != null) {
                GuideSample(event.raError, event.decError, event.raPulse ?: 0, event.decPulse ?: 0)
            } else null
            base.copy(
                wireGuideStatus = event.status ?: s.wireGuideStatus,
                guiding = guiding,
                guideSamples = sample?.let { (base.guideSamples + it).takeLast(MAX_GUIDE_SAMPLES) } ?: base.guideSamples,
                guideRmsRA = event.rmsRA ?: base.guideRmsRA,
                guideRmsDE = event.rmsDE ?: base.guideRmsDE,
                guideSnr = if (sample != null) event.snr?.takeIf { it > 0 } else s.guideSnr,
                guideStarCount = if (sample != null) event.numStars?.takeIf { it > 0 } else s.guideStarCount,
            )
        }
        // Merge non-null fields: status and solution come from independent senders sharing
        // one push name.
        is EkosEvent.NewAlignState -> s.copy(
            wireAlignStatus = event.status ?: s.wireAlignStatus,
            wireAlignSolution = event.solution ?: s.wireAlignSolution,
            // Per-solve pointing error (targetDiff, arcsec), bounded. Ekos leaves it at its 1e6
            // initial value when the solve had no target to compare against (plain "solve here").
            alignAccuracyHistory = event.solution?.targetDiff?.takeIf { it < ALIGN_TARGET_DIFF_UNSET }?.let {
                (s.alignAccuracyHistory + it).takeLast(MAX_ACCURACY_HISTORY)
            } ?: s.alignAccuracyHistory,
        )
        // Merge non-null fields, as for the other partial-shape pushes.
        is EkosEvent.NewManualRotatorStatus -> s.copy(
            wireRotatorCurrentPA = event.currentPA ?: s.wireRotatorCurrentPA,
            wireRotatorTargetPA = event.targetPA ?: s.wireRotatorTargetPA,
            wireRotatorThreshold = event.threshold ?: s.wireRotatorThreshold,
        )
        // Merge non-null fields: pushes are partial, so a message-only or vector-only frame must
        // not clear a known stage (or vice versa).
        is EkosEvent.NewPolarState -> s.copy(
            wirePolarStage = event.stage ?: s.wirePolarStage,
            // Wire stage wins over the optimistic Start/Stop flag — also catches runs that
            // finish, fail, or are started/stopped from the Ekos desktop.
            polarRunning = event.stage?.let { it != PAH_STAGE_IDLE } ?: s.polarRunning,
            wirePolarEnabled = event.enabled ?: s.wirePolarEnabled,
            wirePolarMessage = event.message ?: s.wirePolarMessage,
            wirePolarVector = event.vector ?: s.wirePolarVector,
            wirePolarUpdatedError = event.updatedError ?: s.wirePolarUpdatedError,
            wirePolarUpdatedAzError = event.updatedAZError ?: s.wirePolarUpdatedAzError,
            wirePolarUpdatedAltError = event.updatedALTError ?: s.wirePolarUpdatedAltError,
        )
        // A coarser echo of CCD_TEMPERATURE, which CoolerCard already reads directly. Decoded so
        // it doesn't fall through to Raw; nothing to apply.
        is EkosEvent.NewCameraState -> s

        // new_notification stream (see EkosEvent.NewNotification): newest first, deduplicated by
        // uuid, capped at MAX_NOTIFICATIONS.
        is EkosEvent.NewNotification -> {
            val message = event.message
            if (message == null) s else {
                val uuid = event.uuid ?: "$message-${System.nanoTime()}"
                if (s.wireNotifications.any { it.uuid == uuid }) s else {
                    val entry = Alert(
                        uuid = uuid,
                        message = message,
                        time = s.formatSiteTime(java.time.Instant.now()),
                        source = event.source ?: 0,
                        severity = event.severity ?: 1,
                    )
                    s.copy(wireNotifications = (listOf(entry) + s.wireNotifications).take(MAX_NOTIFICATIONS))
                }
            }
        }

        // Source of AppState.schedulerRunning. `log`-shaped pushes carry no status and are
        // ignored. Ekos::SchedulerState: IDLE=0/STARTUP=1/RUNNING=2/PAUSED=3/SHUTDOWN=4/
        // ABORTED=5/LOADING=6 — running for everything except IDLE/ABORTED.
        is EkosEvent.NewSchedulerState ->
            event.status?.let { s.copy(schedulerRunning = it != 0 && it != 5, schedulerStatus = it) } ?: s

        is EkosEvent.Devices -> s.copy(wireDevices = event.devices.map { it.toLiveDevice() })
        is EkosEvent.DeviceProperties -> event.properties.fold(s) { acc, prop ->
            if (shouldThrottleNumberPush(event.device, prop, acc)) acc
            else acc.withProperty(event.device, prop).withConnectionState(event.device, prop).withCcdInfo(event.device, prop)
                .withGainRange(event.device, prop)
        }.withValidBlockFilters()
        is EkosEvent.DeviceProperty -> if (shouldThrottleNumberPush(event.property.device, event.property, s)) s
        else s.withProperty(event.property.device, event.property)
            .withConnectionState(event.property.device, event.property)
            .withCcdInfo(event.property.device, event.property)
            .withGainRange(event.property.device, event.property)
            .withValidBlockFilters()

        // get_profiles is always the full list, so profiles is overwritten. Diffed against the
        // old s.profiles (still pre-delete here) to detect profile_delete's silent refusal.
        is EkosEvent.Profiles -> {
            val attemptedDelete = pendingProfileDelete
            pendingProfileDelete = null
            val refused = attemptedDelete != null && event.profiles.any { it.name == attemptedDelete }
            s.copy(
                profiles = event.profiles.map { it.toRigProfile() },
                selectedProfile = event.selectedProfile ?: s.selectedProfile,
                profileDeleteRefused = if (refused) attemptedDelete else null,
                // Every driver label saved in any profile on this Pi, unioned — see
                // AppState.realDeviceOptions. Available before Ekos is online, unlike wireDevices.
                wireKnownDrivers = event.profiles
                    .flatMap { it.drivers.entries }
                    .groupBy({ it.key }, { it.value })
                    .mapValues { (_, lists) -> lists.flatten().distinct() },
            )
        }
        // The UI models only two train slots (Primary/Secondary); trains 0 and 1 of the reply
        // map onto them. With one train, secondaryTrain keeps its default; a 3rd+ train is not
        // shown.
        is EkosEvent.Trains -> s.copy(
            wireTrains = event.trains,
            primaryTrain = event.trains.getOrNull(0)?.toTrainAssignment() ?: s.primaryTrain,
            secondaryTrain = event.trains.getOrNull(1)?.toTrainAssignment() ?: s.secondaryTrain,
            moduleTrainAssignments = resolveModuleTrainAssignments(s.moduleTrainAssignments, event.trains),
        )
        // train_get_profiles carries train IDs, not names, and can arrive before or after
        // train_get_all, so the raw ordinal→ID map is kept and re-resolved by whichever lands
        // second.
        is EkosEvent.TrainProfiles -> {
            lastTrainProfileAssignments = event.assignments
            s.copy(moduleTrainAssignments = resolveModuleTrainAssignments(s.moduleTrainAssignments, s.wireTrains))
        }
        is EkosEvent.Scopes -> s.copy(wireScopes = event.scopes.map { it.toScopeDef() })
        is EkosEvent.Drivers -> s.copy(wireDriverCatalog = event.drivers)
        // Options::self() values, fetched once at connect, so overwriting can't race an
        // optimistic edit. rotatorAutoControl is re-seeded from astrometryUseRotator here.
        is EkosEvent.OptionValues -> {
            fun boolOf(name: String) = event.options.firstOrNull { it.name == name }
                ?.value?.let { (it as? JsonPrimitive)?.booleanOrNull }
            val realRotatorControl = boolOf("astrometryUseRotator")
            s.copy(
                wireEkosRemoteNotifications = boolOf("ekosRemoteNotifications") ?: s.wireEkosRemoteNotifications,
                wireEkosRemoteSound = boolOf("ekosRemoteSound") ?: s.wireEkosRemoteSound,
                wireAstrometryUseRotator = realRotatorControl ?: s.wireAstrometryUseRotator,
                rotatorAutoControl = realRotatorControl ?: s.rotatorAutoControl,
            )
        }

        is EkosEvent.MountSettings -> s.copy(wireMountSettings = event.settings)
        // Seed coolTarget from the Capture module's setpoint. This reply arrives only once (the
        // eager fetch at connect), so it can't overwrite a later optimistic coolUp/coolDown.
        is EkosEvent.CaptureSettings -> s.copy(wireCaptureSettings = event.settings, coolTarget = event.settings.cameraTemperatureN)
        is EkosEvent.AlignSettings -> s.copy(wireAlignSettings = event.settings)
        is EkosEvent.GuideSettings -> s.copy(wireGuideSettings = event.settings)
        // Seed focPos from Ekos's tracked position (absTicksSpin; see WireFocusSettings). Arrives
        // only once at connect, same as the coolTarget seed above.
        is EkosEvent.FocusSettings -> s.copy(wireFocusSettings = event.settings, focPos = event.settings.absTicksSpin)
        is EkosEvent.SchedulerSettings -> s.copy(wireSchedulerSettings = event.settings)
        is EkosEvent.AstroAlmanac -> s.copy(
            wireDusk = event.dusk, wireDawn = event.dawn,
            wireMoonIllum = event.moonIllum ?: s.wireMoonIllum,
        )
        is EkosEvent.AstroLocation -> s.copy(wireSiteTz = event.tz)

        is EkosEvent.SchedulerJobs -> applySchedulerJobs(s, event)
        // No state of its own; sendFollowUpCommands continues the pushRealJob chain once the
        // resolved absolute path is known.
        is EkosEvent.SchedulerSaveSequenceFile -> s

        // A fresh astro_search_objects reply clears prior results; the follow-up
        // astro_get_objects_info/riseset replies (sendFollowUpCommands) repopulate them, merged
        // via lastAstroObjects/lastRiseset since they can arrive in either order.
        is EkosEvent.AstroSearchResult -> {
            lastSearchNames = event.names
            resetSearchResults(s)
        }
        is EkosEvent.AstroNames -> {
            allNames = event.names
            resetSearchResults(s)
        }
        is EkosEvent.AstroObjectInfo -> {
            lookupInFlight = false
            if (queuedLookupQuery == null) {
                lastLookupObject = event.obj
                s.copy(wireSearchResults = buildSearchResults())
            } else s // stale — sendFollowUpCommands sends the queued query instead
        }
        is EkosEvent.AstroObjectsInfo -> {
            lastAstroObjects = event.objects
            s.copy(wireSearchResults = buildSearchResults())
        }
        is EkosEvent.AstroObjectsRiseset -> {
            // This reply serves several consumers sharing one cache: the search-results merge and
            // ensureTargetRiseset's single-name fetch for the framed target. Upsert rather than
            // replace, so a 1-entry reply doesn't wipe the other rows' riseset data. A fresh
            // search resets the cache first (AstroSearchResult arm).
            val incomingNames = event.entries.map { it.name }.toSet()
            lastRiseset = lastRiseset.filterNot { it.name in incomingNames } + event.entries
            // Only claim this reply for ensureTargetRiseset if the pending name is in it, so an
            // unrelated search reply doesn't clear the pending fetch.
            val pending = pendingTargetRisesetName
            val matched = pending?.let { name -> event.entries.firstOrNull { it.name == name } }
            if (matched != null) pendingTargetRisesetName = null
            // "Moon" rides along on ensureTargetRiseset's request for the Moon altitude curve.
            // Kept once captured; it doesn't depend on which target is framed.
            val moonEntry = event.entries.firstOrNull { it.name == "Moon" }
            s.copy(
                wireSearchResults = buildSearchResults(),
                wireTargetRiseset = matched ?: s.wireTargetRiseset,
                wireMoonRiseset = moonEntry ?: s.wireMoonRiseset,
            )
        }

        is EkosEvent.Raw -> s
    }

    /** Names matching the typed query, before the [SEARCH_RESULT_CAP] cut. With a type: that
     *  search's names (all of them for a blank query). Without one: nothing until the query is
     *  [minLookupLength]+ chars, then every catalog's names, prefix matches first. */
    private fun matchingSearchNames(s: AppState): List<String> {
        val q = s.query.trim()
        if (s.searchType == null) {
            val names = allNames ?: return emptyList()
            if (q.length < minLookupLength) return emptyList()
            val (prefix, rest) = names.filter { it.contains(q, ignoreCase = true) }
                .partition { it.startsWith(q, ignoreCase = true) }
            return prefix + rest
        }
        val names = lastSearchNames ?: return emptyList()
        return if (q.isBlank()) names else names.filter { it.contains(q, ignoreCase = true) }
    }

    /** Clears resolved rows ahead of a fresh resolve — [sendSearchResolve] repopulates them. */
    private fun resetSearchResults(s: AppState): AppState {
        lastAstroObjects = emptyList()
        lastRiseset = emptyList()
        return s.copy(wireSearchResults = emptyList(), wireSearchMatchCount = matchingSearchNames(s).size)
    }

    /** Name-lookup match pinned first (unless the type search already has it), then the list. */
    private fun buildSearchResults(): List<Target> {
        val lookup = lastLookupObject?.takeIf { l -> lastAstroObjects.none { it.name == l.name } }
        return (listOfNotNull(lookup) + lastAstroObjects)
            .map { it.toTarget(lastRiseset.firstOrNull { rs -> rs.name == it.name }) }
    }

    /**
     * Resolves the raw ordinal→trainID map against [trains] into module-key→train-name, merged
     * onto [previous] rather than replacing it.
     *
     * `train_get_profiles` can carry a stale train ID left over from before the trains were
     * recreated, matching nothing in `train_get_all`. Such a module keeps whatever [previous]
     * had (an optimistic pick, or absent = shown unselected) instead of being dropped.
     *
     * Ekos's persistence may also lag a [setModuleTrain] pick, so a reply can resolve to the
     * pre-edit train. A module with an outstanding pick in [pendingModuleTrainAssignments] keeps
     * that pick until `resolved` agrees with it, at which point it's cleared.
     */
    private fun resolveModuleTrainAssignments(previous: Map<String, String>?, trains: List<WireTrain>?): Map<String, String>? {
        val raw = lastTrainProfileAssignments ?: return previous
        if (trains == null) return previous
        val resolved = raw.mapNotNull { (ordinal, trainId) ->
            val module = MODULE_KEY_BY_TRAIN_SETTING[ordinal] ?: return@mapNotNull null
            val name = trains.firstOrNull { it.id == trainId }?.name ?: return@mapNotNull null
            module to name
        }.toMap()
        pendingModuleTrainAssignments.entries.removeAll { (module, expected) -> resolved[module] == expected }
        return (previous ?: emptyMap()) + resolved + pendingModuleTrainAssignments
    }

    /**
     * Stores the `scheduler_get_jobs` reply as-is, plus two derived effects:
     * 1. If a sync is pending and the pushed name is present, mark that job `synced`. No index
     *    is cached: `WireSchedulerJob` has no server-assigned id and indices shift whenever the
     *    queue changes, so [AppState.wireJobFor] resolves the counterpart by name on demand.
     * 2. The synced job that is `SCHEDJOB_BUSY` gets its blocks' `doneCount` approximated from
     *    `completedCount`, a per-job total waterfall-filled across blocks in order (per-block
     *    progress would need `capture_get_sequences`).
     */
    private fun applySchedulerJobs(s: AppState, event: EkosEvent.SchedulerJobs): AppState {
        var next = s.copy(wireSchedulerJobs = event.jobs)

        val syncJobId = pendingSyncJobId
        val syncName = pendingSyncName
        if (syncJobId != null && syncName != null && event.jobs.any { it.name == syncName }) {
            next = next.mapJob(syncJobId) { it.copy(synced = true) }
            pendingSyncJobId = null
            pendingSyncName = null
        }

        val busyJob = event.jobs.firstOrNull { it.state == SchedulerJobStatus.BUSY }
        if (busyJob != null) {
            val busyJobId = next.jobs.firstOrNull { it.synced && next.targetNameFor(it) == busyJob.name }?.id
            if (busyJobId != null) {
                next = next.mapJob(busyJobId) { it.copy(blocks = distributeCompleted(it.blocks, busyJob.completedCount)) }
            }
        }
        return reconcileSyncedJobStatus(next, event.jobs)
    }

    /**
     * Marks a synced job unsynced once its name disappears from the Scheduler's list (stopped
     * or removed from the Ekos UI, etc.). Runs on every `scheduler_get_jobs` reply, which is
     * re-fetched on every scheduler state change (see [sendFollowUpCommands]).
     *
     * Only touches jobs already `synced`, matched by name ([AppState.targetNameFor]). A job that
     * ended in error stays `synced` and shows its terminal status, since Ekos still lists it.
     */
    private fun reconcileSyncedJobStatus(s: AppState, realJobs: List<WireSchedulerJob>): AppState {
        var next = s
        for (local in s.jobs) {
            if (!local.synced) continue
            val targetName = s.targetNameFor(local)
            if (realJobs.none { it.name == targetName }) {
                next = next.mapJob(local.id) { it.copy(synced = false) }
            }
        }
        return next
    }

    private fun distributeCompleted(blocks: List<Block>, completed: Int): List<Block> {
        var remaining = completed
        return blocks.map { b ->
            val done = minOf(b.subCount, remaining).coerceAtLeast(0)
            remaining = (remaining - done).coerceAtLeast(0)
            b.copy(doneCount = done)
        }
    }

    /**
     * Side effects that follow a push rather than mutate [AppState]. After `get_devices`, each
     * connected device's full property set is fetched once ([Commands.DEVICE_GET], non-compact
     * so slider bounds come along) and subscribed to ([Commands.DEVICE_PROPERTY_SUBSCRIBE]) so
     * [AppState.indiProps] stays live.
     *
     * Devices not yet connected get a CONNECTION-only subscribe: `get_devices` is sent the
     * moment Ekos goes online and can catch drivers still starting, and nothing re-fetches the
     * list later. Once CONNECTION reports connected, the full fetch + subscribe fires.
     */
    private fun sendFollowUpCommands(event: EkosEvent) {
        when (event) {
            is EkosEvent.Devices -> {
                fullyFetchedDevices.clear()
                event.devices.forEach { device -> subscribeDevice(device) }
            }
            is EkosEvent.DeviceProperty -> {
                fetchFullDeviceIfNewlyConnected(event.property.device, event.property)
                enforcePreferredPierSide(event.property.device, event.property)
            }
            is EkosEvent.DeviceProperties -> event.properties.forEach {
                fetchFullDeviceIfNewlyConnected(event.device, it)
                enforcePreferredPierSide(event.device, it)
            }
            // astro_search_objects has no free-text field, so the query is applied here against
            // the flat name list before resolving the matching subset's coordinates/riseset.
            is EkosEvent.AstroSearchResult -> if (_state.value.searchType != null) sendSearchResolve()
            is EkosEvent.AstroNames -> if (_state.value.searchType == null) sendSearchResolve()
            is EkosEvent.AstroObjectInfo -> {
                val queued = queuedLookupQuery
                if (queued != null) {
                    queuedLookupQuery = null
                    sendNameLookup(queued)
                } else event.obj?.let { obj ->
                    // Same exact-name riseset as sendSearchResolve, for the pinned row's "X° max".
                    client.sendCommand(Commands.ASTRO_GET_OBJECTS_RISESET, buildJsonObject {
                        putJsonArray("names") { add(obj.name) }
                        put("exact", true)
                    })
                }
            }
            // First automatic search, so the Plan tab has results without user input. The almanac
            // reply rides the online burst (EkosRemoteClient) and needs the same geo/clock as the
            // search, so it signals astro_* is answering; it's fetched once per connection.
            is EkosEvent.AstroAlmanac -> if (lastSearchNames == null) runSearch()
            // Continues pushRealJob's chain once the save reply carries the resolved path (see
            // PendingJobStart).
            is EkosEvent.SchedulerSaveSequenceFile -> {
                val pending = pendingJobStart
                if (pending != null && event.result) {
                    pendingJobStart = null
                    client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
                        put("nameEdit", pending.targetName)
                        put("sequenceEdit", event.path)
                        put("startupTimeConditionR", 0) // ASAP
                        put("schedulerAltitude", false)
                        pending.raDec?.let { (ra, dec) -> put("raBox", ra); put("decBox", dec) }
                        pending.trainName?.let { put("opticalTrainCombo", it) }
                    })
                    client.sendCommand(Commands.SCHEDULER_ADD_JOBS)
                    pendingSyncJobId = pending.jobId
                    pendingSyncName = pending.targetName
                    pendingSchedulerStart = PendingSchedulerStart(pending.targetName, pending.alsoStart)
                    client.sendCommand(Commands.SCHEDULER_GET_JOBS) // confirms the add landed, see applySchedulerJobs
                }
            }
            // Toggle the Scheduler on only once the add is confirmed by name, only if `alsoStart`
            // was requested (see PendingSchedulerStart), and only if not already running:
            // SCHEDULER_START_JOB is a toggle, so sending it while running stops it.
            is EkosEvent.SchedulerJobs -> {
                val waiting = pendingSchedulerStart
                if (waiting != null && event.jobs.any { it.name == waiting.name }) {
                    pendingSchedulerStart = null
                    if (waiting.alsoStart && !_state.value.schedulerRunning) {
                        client.sendCommand(Commands.SCHEDULER_START_JOB)
                    }
                }
            }
            // new_scheduler_state fires on any transition, whoever caused it. Re-fetching the job
            // list on each keeps reconcileSyncedJobStatus current, including for jobs stopped or
            // removed from the Ekos UI.
            is EkosEvent.NewSchedulerState -> client.sendCommand(Commands.SCHEDULER_GET_JOBS)
            else -> {}
        }
    }

    /**
     * Parses the "HHhMMmSSs ±DD°MM′SS″" coords string (shared by the `TARGETS` catalog and
     * astro-search results; see `formatRaHours`/`formatDecDegrees`) into `raBox`/`decBox`'s
     * colon-separated `"HH:MM:SS"` wire format. Returns null for anything else (e.g. a
     * free-text custom target), and [pushRealJob] then omits `raBox`/`decBox`.
     */
    private fun parseCoordsToRaDecBox(coords: String): Pair<String, String>? {
        val m = COORDS_REGEX.find(coords) ?: return null
        val (rh, rm, rs, sign, dd, dm, ds) = m.destructured
        return "$rh:$rm:$rs" to "$sign$dd:$dm:$ds"
    }

    private fun subscribeDevice(device: WireDevice) {
        if (device.connected) {
            fullyFetchedDevices += device.name
            client.sendCommand(Commands.DEVICE_GET, buildJsonObject { put("device", device.name) })
            client.sendCommand(Commands.DEVICE_PROPERTY_SUBSCRIBE, buildJsonObject { put("device", device.name) })
        } else {
            client.sendCommand(Commands.DEVICE_PROPERTY_SUBSCRIBE, buildJsonObject {
                put("device", device.name)
                putJsonArray("properties") { add("CONNECTION") }
            })
        }
    }

    /**
     * OnStep's "Preferred Pier Side" must be East for Ekos's meridian flip to re-slew (with Best
     * the flip is a no-op), but OnStep keeps it in RAM only and powers up on Best. The Pi's pier
     * guard (pi-tools/pier-guard) fixes it on every connect; this is the app-side backstop: if a
     * mount reports anything else, set East and raise a Mount alert. East in both hemispheres —
     * OnStep picks the side by hour angle (see the pier-guard README).
     */
    private fun enforcePreferredPierSide(device: String, property: WireProperty) {
        if (property !is WireProperty.Switch || property.name != PREFERRED_PIER_SIDE) return
        if (property.switches.isEmpty() || property.state == 2) return
        if (property.switches.any { it.name == PIER_SIDE_EAST && it.state == 1 }) return
        val now = System.currentTimeMillis()
        if (now - (pierSideFixSentAt[device] ?: 0L) < PIER_SIDE_RESEND_MS) return
        pierSideFixSentAt[device] = now
        client.sendCommand(Commands.DEVICE_PROPERTY_SET, buildJsonObject {
            put("device", device)
            put("property", PREFERRED_PIER_SIDE)
            putJsonArray("elements") { addJsonObject { put("name", PIER_SIDE_EAST); put("state", 1) } }
        })
        // Live pushes are compact (no labels) — map OnStep's element names ourselves.
        val was = property.switches.firstOrNull { it.state == 1 }
            ?.let { mapOf("1" to "West", "3" to "Best")[it.name] ?: it.label ?: it.name } ?: "unset"
        pushLocalAlert("$device preferred pier side was $was — set to East (needed for meridian flip)", source = 5, severity = 2)
    }

    /** A device that arrived not yet connected has only a CONNECTION subscribe (see
     *  [sendFollowUpCommands]); once it reports connected, fetch and subscribe in full. */
    private fun fetchFullDeviceIfNewlyConnected(device: String, property: WireProperty) {
        if (property !is WireProperty.Switch || property.name != "CONNECTION") return
        if (property.switches.none { it.name == "CONNECT" && it.state == 1 }) {
            // Disconnected — fetch again in full if it comes back.
            fullyFetchedDevices -= device
            return
        }
        if (!fullyFetchedDevices.add(device)) return
        client.sendCommand(Commands.DEVICE_GET, buildJsonObject { put("device", device) })
        client.sendCommand(Commands.DEVICE_PROPERTY_SUBSCRIBE, buildJsonObject { put("device", device) })
    }

    // ── Devices / property sheets ───────────────────────────────────────

    override fun toggleDevice(key: String) {
        val device = _state.value.wireDevices?.firstOrNull { it.name == key }
        if (device == null) {
            super.toggleDevice(key)
            return
        }
        val connecting = !device.connected
        client.sendCommand(Commands.DEVICE_PROPERTY_SET, buildJsonObject {
            put("device", key)
            put("property", "CONNECTION")
            putJsonArray("elements") {
                addJsonObject {
                    put("name", if (connecting) "CONNECT" else "DISCONNECT")
                    put("state", 1)
                }
            }
        })
        _state.update { s ->
            s.copy(wireDevices = s.wireDevices?.map { if (it.name == key) it.copy(connected = connecting) else it })
        }
    }

    override fun setIndiSwitch(deviceKey: String, propName: String, selected: Int) {
        val prop = currentIndiProp<IndiProperty.SwitchProp>(deviceKey, propName)
        val elementName = prop?.elementNames?.getOrNull(selected)
        if (elementName != null && _state.value.wireDevices != null) {
            client.sendCommand(Commands.DEVICE_PROPERTY_SET, buildJsonObject {
                put("device", deviceKey)
                put("property", propName)
                putJsonArray("elements") { addJsonObject { put("name", elementName); put("state", 1) } }
            })
        }
        super.setIndiSwitch(deviceKey, propName, selected)
    }

    override fun setIndiNumber(deviceKey: String, propName: String, value: Double) {
        val prop = currentIndiProp<IndiProperty.NumberProp>(deviceKey, propName)
        if (prop != null && _state.value.wireDevices != null) {
            client.sendCommand(Commands.DEVICE_PROPERTY_SET, buildJsonObject {
                put("device", deviceKey)
                put("property", propName)
                putJsonArray("elements") { addJsonObject { put("name", prop.elementName); put("value", value.coerceIn(prop.min, prop.max)) } }
            })
        }
        super.setIndiNumber(deviceKey, propName, value)
    }

    override fun setIndiText(deviceKey: String, propName: String, elementName: String, value: String) {
        if (_state.value.wireDevices != null) {
            client.sendCommand(Commands.DEVICE_PROPERTY_SET, buildJsonObject {
                put("device", deviceKey)
                put("property", propName)
                putJsonArray("elements") { addJsonObject { put("name", elementName); put("text", value) } }
            })
        }
        super.setIndiText(deviceKey, propName, elementName, value)
    }

    private inline fun <reified T : IndiProperty> currentIndiProp(deviceKey: String, propName: String): T? =
        (_state.value.indiProps[deviceKey] ?: emptyList())
            .firstOrNull { it.name == propName } as? T

    // ── Bench check ─────────────────────────────────────────────────────
    //
    // capture_preview/guide_capture/focus_in/out/mount_set_motion have no direct reply; status
    // arrives via new_capture_state/new_guide_state or the devices' subscribed property pushes.
    // The cooler uses the generic setIndiNumber path: CCD_TEMPERATURE_VALUE (RW) is the set
    // point, which most INDI CCD drivers chase automatically; CCD_COOLER_POWER is read-only.

    /** Image bytes arrive on the media channel; status via new_capture_state. */
    override fun snapMain() {
        client.sendCommand(Commands.CAPTURE_PREVIEW)
        super.snapMain()
    }

    /** Image bytes arrive on the media channel; status via new_guide_state. */
    override fun snapGuide() {
        client.sendCommand(Commands.GUIDE_CAPTURE)
        super.snapGuide()
    }

    /**
     * `CCD_TEMPERATURE` has no separate target element (only the current reading), so
     * [AppState.coolTarget] is the app's own last-commanded value; `CoolerCard` reads the sensor
     * separately via [com.nocturne.session.indiNumber].
     *
     * Two writes are needed: the INDI `CCD_TEMPERATURE` property drives the hardware, while the
     * Capture module's `cameraTemperatureN` is what the Ekos desktop shows as the target and
     * otherwise stays stale.
     */
    override fun applyCoolTarget() {
        val target = _state.value.coolTarget ?: return
        client.sendCommand(Commands.CAPTURE_SET_ALL_SETTINGS, buildJsonObject { put("cameraTemperatureN", target) })
        val camera = _state.value.primaryTrain.camera
        if (_state.value.wireDevices?.any { it.name == camera && it.connected } == true) {
            setIndiNumber(camera, "CCD_TEMPERATURE", target)
        }
    }

    override fun setTemperatureEnforced(enabled: Boolean) {
        client.sendCommand(Commands.CAPTURE_SET_ALL_SETTINGS, buildJsonObject { put("cameraTemperatureS", enabled) })
        super.setTemperatureEnforced(enabled)
    }

    override fun setCoolerEnabled(enabled: Boolean) =
        sendSwitchElement(_state.value.primaryTrain.camera, "CCD_COOLER", if (enabled) "COOLER_ON" else "COOLER_OFF")

    override fun setCameraFan(element: String) =
        sendSwitchElement(_state.value.primaryTrain.camera, CAMERA_FAN_PROP, element)

    override fun cancelPendingCapture() {
        client.sendCommand(Commands.CAPTURE_STOP)
    }

    /** Direct element write — `CCD_COOLER` is write-only on ToupTek, so it may never land in
     *  [AppState.indiProps] for [setIndiSwitch]'s index lookup. */
    private fun sendSwitchElement(device: String, property: String, element: String) {
        if (device.isBlank()) return
        client.sendCommand(Commands.DEVICE_PROPERTY_SET, buildJsonObject {
            put("device", device)
            put("property", property)
            putJsonArray("elements") { addJsonObject { put("name", element); put("state", 1) } }
        })
    }

    override fun focusStepIn() {
        client.sendCommand(Commands.FOCUS_IN, buildJsonObject { put("steps", 0) })
        super.focusStepIn()
    }
    override fun focusStepOut() {
        client.sendCommand(Commands.FOCUS_OUT, buildJsonObject { put("steps", 0) })
        super.focusStepOut()
    }

    /**
     * `focus_start`/`focus_stop` have empty request/response; progress arrives only via
     * `new_focus_state` (`AppState.wireFocusStatus`, per-frame HFR in `AppState.focusSamples`).
     * [AppState.focusRunning] is optimistic until that status lands, then follows it
     * ([FOCUS_IDLE_STATES]).
     */
    override fun startAutofocus() {
        client.sendCommand(Commands.FOCUS_START)
        super.startAutofocus()
    }
    override fun stopAutofocus() {
        client.sendCommand(Commands.FOCUS_STOP)
        super.stopAutofocus()
    }
    /**
     * FocusSheet's "Run autofocus now" button. Ekos has no separate "run once" command, so this
     * sends `focus_start` like [startAutofocus].
     */
    override fun runAutofocusNow() {
        client.sendCommand(Commands.FOCUS_START)
        super.runAutofocusNow()
    }

    override fun setRate(index: Int) {
        client.sendCommand(Commands.MOUNT_SET_SLEW_RATE, buildJsonObject { put("rate", index) })
        super.setRate(index)
    }

    /**
     * Keep/cut writes through to Room; Room's Flow (collected in `init`) then re-emits and
     * supersedes the optimistic local flip.
     */
    override fun toggleCut(id: String) {
        val idLong = id.toLongOrNull() ?: return super.toggleCut(id)
        val row = _state.value.frameRows.firstOrNull { it.id == idLong }
        if (row != null && frameRepo != null) {
            scope.launch { frameRepo.setKeep(idLong, !row.keep) }
        }
        super.toggleCut(id)
    }

    /** DPad keys "N"/"S"/"E"/"W" are mount_set_motion's `direction` values as-is. */
    override fun setSlewDir(key: String) {
        client.sendCommand(Commands.MOUNT_SET_MOTION, buildJsonObject { put("direction", key); put("action", true) })
        super.setSlewDir(key)
    }

    /** Reads the in-flight direction before super.stopSlew() clears it; mount_set_motion stops one axis at a time. */
    override fun stopSlew() {
        _state.value.slewDir?.let { dir ->
            client.sendCommand(Commands.MOUNT_SET_MOTION, buildJsonObject { put("direction", dir); put("action", false) })
        }
        super.stopSlew()
    }

    /** `mount_unpark` — no direct reply; watch new_mount_state. */
    override fun unparkMount() {
        client.sendCommand(Commands.MOUNT_UNPARK)
        super.unparkMount()
    }
    /** `mount_park` — no direct reply; watch new_mount_state. */
    override fun parkMount() {
        client.sendCommand(Commands.MOUNT_PARK)
        super.parkMount()
    }
    /**
     * `mount_set_tracking` is the Ekos-level command (works for any mount driver), not a raw INDI
     * write. Display state comes from [AppState.mountTrackingOn] (`TELESCOPE_TRACK_STATE`).
     */
    override fun setMountTracking(enabled: Boolean) {
        client.sendCommand(Commands.MOUNT_SET_TRACKING, buildJsonObject { put("enabled", enabled) })
        super.setMountTracking(enabled)
    }

    /** `align_solve` (`captureAndSolve()`) — no direct reply; watch new_align_state. */
    override fun plateSolveHere() {
        client.sendCommand(Commands.ALIGN_SOLVE)
        super.plateSolveHere()
    }

    override fun selectTarget(id: String) {
        super.selectTarget(id)
        ensureTargetRiseset(id)
        ensureReferenceImage(id)
    }

    /**
     * Altitude data for the Plan tab's chart. `astro_get_objects_riseset` resolves any
     * KStars-known name directly, with no prior `astro_search_objects` needed, so this covers
     * catalog and custom targets too. Skips the fetch if cached or in flight for this name, so
     * it's cheap to call on every [selectTarget].
     */
    override fun ensureTargetRiseset(targetId: String) {
        val target = _state.value.findTarget(targetId) ?: return
        val name = target.realLookupName
        // The Moon's curve rides along as a second name in the same request. A cached target
        // still refetches until the Moon has been captured this connection.
        if ((pendingTargetRisesetName == name || _state.value.wireTargetRiseset?.name == name) &&
            _state.value.wireMoonRiseset != null
        ) return
        pendingTargetRisesetName = name
        // "exact": true makes the reply echo the name we sent (message.cpp:2295); otherwise it
        // carries KStars' preferred name (e.g. for M 64), the match below fails, and the chart
        // silently keeps the previous target. Safe: the name is catalog-sourced, and findByName
        // still falls back to a fuzzy lookup if the exact form doesn't resolve
        // (catalogscomponent.cpp:334-338).
        client.sendCommand(Commands.ASTRO_GET_OBJECTS_RISESET, buildJsonObject {
            putJsonArray("names") { add(name); add("Moon") }
            put("exact", true)
        })
    }

    /**
     * Reference image for framing: the offline sky atlas if available, else an online cutout
     * (see [ReferenceImageClient]). FOV is 1.4× the camera's ([AppState.framingFovDeg]) so the
     * drawn FOV box doesn't sit on the edge, with a placeholder before CCD_INFO arrives. Skips
     * if this target is already cached.
     */
    override fun ensureReferenceImage(targetId: String) {
        val s = _state.value
        if (s.referenceImageForTargetId == targetId) return
        val target = s.findTarget(targetId) ?: return
        val (raDeg, decDeg) = target.raDecDegrees ?: return
        val fovDeg = (s.framingFovDeg?.let { maxOf(it.first, it.second) } ?: 2.4) * 1.4
        _state.update { it.copy(referenceImageForTargetId = targetId, referenceImageJpeg = null, referenceImageFovDeg = null, referenceImageOnlineSkipped = false) }
        scope.launch {
            // Offline atlas first (works with no internet, any FOV); CDS cutout as the fallback.
            val offline = skyAtlas?.render(raDeg, decDeg, fovDeg, _state.value.skyAtlas.completeOrder)
            val online = offline == null && allowOptionalInternet()
            val jpeg = offline ?: if (online) referenceImageClient.fetchCutout(raDeg, decDeg, fovDeg) else null
            // Apply only if the framed target hasn't changed meanwhile, so a slow fetch doesn't
            // land on a since-selected target.
            if (_state.value.referenceImageForTargetId == targetId) {
                _state.update {
                    it.copy(referenceImageJpeg = jpeg, referenceImageFovDeg = fovDeg.takeIf { jpeg != null }, referenceImageOffline = offline != null, referenceImageOnlineSkipped = offline == null && !online)
                }
            }
        }
    }

    /**
     * `mount_goto_target` resolves the name server-side via KStars' `findByName` (unlike
     * `scheduler_add_jobs`, which needs `raBox`/`decBox`). Not-found is a silent no-op; no
     * direct reply — watch `new_mount_state`.
     */
    override fun gotoTarget(targetId: String) {
        val target = _state.value.findTarget(targetId) ?: return
        gotoJob?.cancel()
        gotoJob = scope.launch {
            abortSlewIfMoving(target.realLookupName)
            client.sendCommand(Commands.MOUNT_GOTO_TARGET, buildJsonObject { put("target", target.realLookupName) })
        }
        super.gotoTarget(targetId)
    }

    // The goto (or goto-and-center sequence) in flight — a new goto cancels it.
    private var gotoJob: Job? = null
    private var lastGotoSentAt = 0L

    /**
     * OnStep refuses a goto while another slew is running ("Goto in progress") and aborts the
     * first one too, leaving the mount stopped. So a goto issued mid-slew first aborts the slew,
     * waits (≤10 s) for the mount to stop, then goes.
     */
    private suspend fun abortSlewIfMoving(nextTarget: String) {
        // The status push lags the command by a moment, so a goto sent in the last few seconds
        // counts as moving too (aborting an already-idle mount is harmless).
        val recentGoto = System.currentTimeMillis() - lastGotoSentAt < RECENT_GOTO_MS
        lastGotoSentAt = System.currentTimeMillis()
        if (_state.value.wireMountStatus !in MOUNT_MOVING_STATES && !recentGoto) return
        client.sendCommand(Commands.MOUNT_ABORT)
        pushLocalAlert("Mount was still slewing — stopped it before going to $nextTarget", source = 5, severity = 1)
        delay(1_500) // let the abort land and the status push catch up before trusting "Idle"
        withTimeoutOrNull(10_000) { _state.first { it.wireMountStatus !in MOUNT_MOVING_STATES } }
        delay(500)
    }

    private fun pushLocalAlert(message: String, source: Int, severity: Int) {
        val now = System.currentTimeMillis()
        _state.update { s ->
            val alert = Alert(
                uuid = "local-$now-${message.hashCode()}",
                message = message,
                time = s.formatSiteTime(java.time.Instant.now()),
                source = source,
                severity = severity,
            )
            s.copy(wireNotifications = (listOf(alert) + s.wireNotifications).take(MAX_NOTIFICATIONS))
        }
    }

    /**
     * Goto, then plate-solve with the Align solver action temporarily set to `slewR` (one of
     * three exclusive bools `nothingR`/`slewR`/`syncR`). With `slewR`, Ekos loops capture → solve
     * → re-slew until the error is under `alignAccuracyThreshold` or it gives up. The previous
     * action (from the last `align_get_all_settings`) is restored afterwards.
     *
     * Each step waits on status, not a timer: the slew on `new_mount_state`, the centering loop
     * on `new_align_state` reaching [ALIGN_DONE_STATES]. Ekos only re-solves after a re-slew
     * while the action is still `slewR` (`align_goto.cpp`), so it must stay set until the loop
     * ends.
     */
    override fun gotoAndCenter(targetId: String) {
        val target = _state.value.findTarget(targetId) ?: return
        val original = _state.value.wireAlignSettings
        gotoJob?.cancel()
        gotoJob = scope.launch {
            abortSlewIfMoving(target.realLookupName)
            client.sendCommand(Commands.MOUNT_GOTO_TARGET, buildJsonObject { put("target", target.realLookupName) })
            awaitSlewFinished()
            client.sendCommand(Commands.ALIGN_SET_ALL_SETTINGS, buildJsonObject {
                put("nothingR", false); put("slewR", true); put("syncR", false)
            })
            val result: String?
            try {
                delay(500)
                client.sendCommand(Commands.ALIGN_SOLVE)
                // First see the run start (the status may still read a previous run's "Complete"),
                // then wait for it to end — however many solve/re-slew passes that takes.
                val started = withTimeoutOrNull(ALIGN_START_TIMEOUT_MS) {
                    _state.first { it.wireAlignStatus !in ALIGN_DONE_STATES }
                }
                result = if (started == null) "did not start" else withTimeoutOrNull(ALIGN_RUN_TIMEOUT_MS) {
                    _state.first { it.wireAlignStatus in ALIGN_DONE_STATES }
                }?.wireAlignStatus ?: "timed out"
            } finally {
                // Also on cancel (a newer goto replaced this one): never leave slewR switched on.
                client.sendCommand(Commands.ALIGN_SET_ALL_SETTINGS, buildJsonObject {
                    put("nothingR", original?.nothingR ?: true)
                    put("slewR", original?.slewR ?: false)
                    put("syncR", original?.syncR ?: false)
                })
            }
            if (result == "Complete") {
                val err = _state.value.wireAlignSolution?.targetDiff?.let { " (error %.0f\")".format(it) } ?: ""
                pushLocalAlert("Centered on ${target.realLookupName}$err", source = 4, severity = 1)
            } else {
                pushLocalAlert("Centering on ${target.realLookupName} stopped: align $result", source = 4, severity = 2)
            }
        }
        super.gotoAndCenter(targetId)
    }

    /**
     * Waits for a just-sent goto to finish: first for the mount to report moving (the status
     * push lags the command, and a very short slew may finish before "Slewing" is ever seen —
     * hence the timeout), then for it to stop, then a short settle before imaging.
     */
    private suspend fun awaitSlewFinished() {
        withTimeoutOrNull(SLEW_START_TIMEOUT_MS) { _state.first { it.wireMountStatus in MOUNT_MOVING_STATES } }
        withTimeoutOrNull(SLEW_TIMEOUT_MS) { _state.first { it.wireMountStatus !in MOUNT_MOVING_STATES } }
        delay(SLEW_SETTLE_MS)
    }

    /**
     * `guide_start`/`guide_stop` have empty request/response. `guide_stop` maps server-side to
     * `abort()` — a hard abort, not a graceful stop. Progress via `new_guide_state`
     * (`AppState.wireGuideStatus`).
     */
    override fun startGuiding() {
        client.sendCommand(Commands.GUIDE_START)
        super.startGuiding()
    }
    override fun stopGuiding() {
        client.sendCommand(Commands.GUIDE_STOP)
        super.stopGuiding()
    }
    override fun loopGuide() {
        client.sendCommand(Commands.GUIDE_LOOP)
        super.loopGuide()
    }
    override fun clearGuideCalibration() {
        client.sendCommand(Commands.GUIDE_CLEAR)
    }

    /**
     * `polar_start`/`polar_stop` — no direct reply; watch `new_polar_state` (partial payloads,
     * see [EkosEvent.NewPolarState]). PAH runs its own multi-slew sequence server-side, so Stop
     * may interrupt it mid-motion.
     */
    override fun startPolarAlign() {
        client.sendCommand(Commands.POLAR_START)
        super.startPolarAlign()
    }
    override fun stopPolarAlign() {
        client.sendCommand(Commands.POLAR_STOP)
        super.stopPolarAlign()
    }
    override fun polarSlewDone() {
        client.sendCommand(Commands.POLAR_SLEW_DONE)
    }
    override fun polarRefresh() {
        val exposure = _state.value.wireAlignSettings?.pAHExposure ?: 2.0
        client.sendCommand(Commands.POLAR_REFRESH, buildJsonObject { put("value", exposure) })
    }
    override fun polarRefreshDone() {
        client.sendCommand(Commands.POLAR_REFRESHING_DONE)
        super.polarRefreshDone()
    }
    override fun polarSelectStar(x: Double, y: Double) {
        client.sendCommand(Commands.POLAR_SET_CROSSHAIR, buildJsonObject { put("x", x); put("y", y) })
    }
    override fun setPahDirection(direction: String) {
        sendAlignSetting("pAHDirection", JsonPrimitive(direction))
        super.setPahDirection(direction)
    }
    override fun setPahRotation(degrees: Int) {
        sendAlignSetting("pAHRotation", JsonPrimitive(degrees))
        super.setPahRotation(degrees)
    }
    override fun setPahMountSpeed(speed: String) {
        sendAlignSetting("pAHMountSpeed", JsonPrimitive(speed))
        super.setPahMountSpeed(speed)
    }
    override fun setPahManualSlew(enabled: Boolean) {
        sendAlignSetting("pAHManualSlew", JsonPrimitive(enabled))
        super.setPahManualSlew(enabled)
    }
    override fun setPahExposure(sec: Double) {
        sendAlignSetting("pAHExposure", JsonPrimitive(sec))
        super.setPahExposure(sec)
    }
    override fun setPahRefreshAlgorithm(algorithm: String) {
        sendAlignSetting("pAHRefreshAlgorithm", JsonPrimitive(algorithm))
        super.setPahRefreshAlgorithm(algorithm)
    }

    // ── Profiles / Optical Train ────────────────────────────────────────

    override fun startProfile(name: String) {
        client.sendCommand(Commands.PROFILE_START, buildJsonObject { put("name", name) })
        super.startProfile(name) // optimistic; new_connection_state.online confirms
    }

    override fun stopProfile() {
        client.sendCommand(Commands.PROFILE_STOP)
        super.stopProfile()
    }

    override fun deleteProfile(name: String) {
        pendingProfileDelete = name
        client.sendCommand(Commands.PROFILE_DELETE, buildJsonObject { put("name", name) })
        // No optimistic removal: the get_profiles reply that follows is authoritative.
    }

    /**
     * The base implementation doesn't touch `selectedDeviceNames`/`devOff`, so the wizard would
     * show leftover picker state. Maps `RigProfile.drivers` (family → labels) back to category
     * keys via [CATEGORY_TO_DRIVER_FAMILY]; [finishSetup] runs the same mapping forward.
     */
    override fun editProfile(name: String) {
        super.editProfile(name)
        val p = _state.value.profiles.firstOrNull { it.name == name } ?: return
        update { s ->
            if (s.setupEditingName != name) return@update s // base declined (Ekos running) — nothing to seed
            val selected = DEVICES.associate { d ->
                // "guide" has no family of its own — it's a second entry in drivers["CCDs"],
                // named only by the legacy `guider` field (see CATEGORY_TO_DRIVER_FAMILY).
                val label = if (d.key == "guide") p.guider.ifBlank { null }
                else CATEGORY_TO_DRIVER_FAMILY[d.key]?.let { family -> p.drivers[family]?.firstOrNull() }
                d.key to (label ?: "None")
            }
            s.copy(
                selectedDeviceNames = s.selectedDeviceNames + selected,
                devOff = selected.filterValues { it == "None" }.keys,
            )
        }
    }

    override fun finishSetup() {
        val s = _state.value
        // "guide" shares the "CCDs" family with "cam"; the explicit "guider" field is the only
        // signal for which entry is the guide camera. profile_update persists "guider" alongside
        // "drivers", despite the docs saying legacy fields are only read when drivers is absent.
        val drivers: Map<String, List<String>> = DEVICES.filter { it.key !in s.devOff }
            .groupBy({ CATEGORY_TO_DRIVER_FAMILY[it.key] ?: "Aux" }, { s.selectedDeviceNames[it.key] ?: it.name })
        val guiderLabel = s.selectedDeviceNames["guide"]?.takeIf { "guide" !in s.devOff }
        val payload = buildJsonObject {
            put("name", s.profileName)
            put("auto_connect", true)
            put("port_selector", false)
            put("mode", "local")
            put("use_web_manager", false)
            put("guiding", 0) // guider-type enum, unconfirmed meaning — leave at the known-safe default
            putJsonObject("drivers") { drivers.forEach { (family, labels) -> putJsonArray(family) { labels.forEach { add(it) } } } }
            if (guiderLabel != null) put("guider", guiderLabel)
        }
        client.sendCommand(if (s.setupEditingName != null) Commands.PROFILE_UPDATE else Commands.PROFILE_ADD, payload)
        // Not super.finishSetup(): that marks Ekos running, but this only sends
        // profile_add/update, never profile_start. Close the sheet; the following get_profiles
        // reply reconciles the profile list.
        update { it.copy(sheet = null, setupEditingName = null) }
    }

    override fun setTrainRole(slot: TrainSlot, role: TrainRole, value: String) {
        super.setTrainRole(slot, role, value)
        sendTrainUpdate(slot)
    }

    override fun setTrainReducer(slot: TrainSlot, value: Double) {
        super.setTrainReducer(slot, value)
        sendTrainUpdate(slot)
    }

    /**
     * `train_set` (`processTrainCommands` in `message.cpp`): each module's `setOpticalTrain(name)`
     * persists to the active profile's `ProfileSettings`. No direct reply, so
     * `train_get_profiles` is re-sent to confirm.
     */
    override fun setModuleTrain(module: String, trainName: String) {
        pendingModuleTrainAssignments[module] = trainName
        client.sendCommand(Commands.TRAIN_SET, buildJsonObject { put("module", module); put("name", trainName) })
        client.sendCommand(Commands.TRAIN_GET_PROFILES)
        super.setModuleTrain(module, trainName) // optimistic; confirmed reply reconciles above
    }

    /** Persists the reboot daemon's port in memory (for [rebootRig]) and to disk via [connectionRepo]. */
    override fun setRigRebootPort(port: Int) {
        rigRebootPort = port
        _state.update { it.copy(rigRebootPort = port, rigRebootState = RigRebootState.IDLE, rigRebootError = null) }
        scope.launch { connectionRepo?.saveRebootPort(port) }
    }

    /**
     * Sends the reboot request over the daemon's HTTP channel (see [RigRebootClient]), not the
     * EkosRemote wire. Success only means the Pi accepted it; the socket then drops and
     * [EkosRemoteClient]'s reconnect picks the session back up.
     */
    override fun rebootRig() {
        _state.update { it.copy(rigRebootState = RigRebootState.SENDING, rigRebootError = null) }
        scope.launch {
            val result = RigRebootClient(client.host, rigRebootPort).reboot()
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(rigRebootState = RigRebootState.SENT, rigRebootError = null) },
                    onFailure = { e -> s.copy(rigRebootState = RigRebootState.FAILED, rigRebootError = e.message ?: "request failed") },
                )
            }
        }
    }

    private suspend fun rescanSkyAtlas() {
        val local = skyAtlas?.scanLocal() ?: return
        _state.update { it.copy(skyAtlas = it.skyAtlas.copy(localTiles = local.tiles, completeOrder = local.completeOrder)) }
    }

    override fun refreshSkyAtlas() {
        val atlas = skyAtlas ?: return
        scope.launch {
            rescanSkyAtlas()
            val rig = runCatching { atlas.rigTiles(client.host, rigRebootPort) }
            _state.update { s ->
                s.copy(skyAtlas = s.skyAtlas.copy(
                    rigTiles = rig.getOrNull(),
                    error = rig.exceptionOrNull()?.let { "Rig atlas: ${it.message ?: "unreachable"}" },
                ))
            }
        }
    }

    /** Over the reboot daemon's `/hips/` (same host and port as [rebootRig]), 4 tiles at a time. */
    override fun copySkyAtlasFromRig() {
        val atlas = skyAtlas ?: return
        if (skyAtlasCopyJob?.isActive == true) return
        _state.update { it.copy(skyAtlas = it.skyAtlas.copy(copying = true, error = null, lastMissing = null)) }
        skyAtlasCopyJob = scope.launch {
            var missing: Int? = null
            var error: String? = null
            try {
                missing = atlas.copyFromRig(client.host, rigRebootPort) { done, _ ->
                    _state.update { it.copy(skyAtlas = it.skyAtlas.copy(copyDone = done)) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Copy stopped: ${e.message ?: "rig unreachable"}"
            } finally {
                // also runs after Cancel, so the sheet doesn't stay stuck on "Copying…"
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    rescanSkyAtlas()
                    _state.update { s ->
                        s.copy(skyAtlas = s.skyAtlas.copy(copying = false, lastMissing = missing, error = error))
                    }
                }
            }
        }
    }

    override fun cancelSkyAtlasCopy() {
        skyAtlasCopyJob?.cancel()
    }

    override fun clearSkyAtlas() {
        val atlas = skyAtlas ?: return
        skyAtlasCopyJob?.cancel()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            atlas.clear()
            rescanSkyAtlas()
        }
    }

    /**
     * Sets the KStars clock over the wire and the Pi OS clock via the reboot daemon's
     * `POST /set-time` (see [RigRebootClient.setTime]). Sends the phone's local wall-clock time
     * with no timezone conversion, assuming phone and rig are co-located.
     */
    override fun syncTimeToRig() {
        // KStars' internal clock over the EkosRemote wire; works without the reboot daemon.
        // See KSTARS_SET_TIME (Commands.kt).
        val now = java.time.LocalDateTime.now()
        client.sendCommand(
            Commands.KSTARS_SET_TIME,
            buildJsonObject {
                put("yr", now.year); put("mth", now.monthValue); put("day", now.dayOfMonth)
                put("hr", now.hour); put("min", now.minute); put("sec", now.second)
            },
        )

        // Pi OS clock — best-effort, needs the reboot daemon running on the Pi.
        _state.update { it.copy(piTimeSyncState = RigRebootState.SENDING, piTimeSyncError = null) }
        val iso = now.withNano(0).toString()
        scope.launch {
            val result = RigRebootClient(client.host, rigRebootPort).setTime(iso)
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(piTimeSyncState = RigRebootState.SENT, piTimeSyncError = null) },
                    onFailure = { e -> s.copy(piTimeSyncState = RigRebootState.FAILED, piTimeSyncError = e.message ?: "request failed") },
                )
            }
        }
    }

    /**
     * `kstars_set_location` (see [SessionController.syncLocationToRig] for why
     * `option_set`/`invoke_method` can't reach `KStars::setGPSLocation`). [tz] is only used if
     * KStars can't match the coordinates to a known city (`nearestLocation()`); it's the phone's
     * standard-time UTC offset, assuming phone and rig are co-located.
     */
    override fun syncLocationToRig(lat: Double, lon: Double, elevM: Double) {
        val tzHours = java.util.TimeZone.getDefault().rawOffset / 3_600_000.0
        client.sendCommand(
            Commands.KSTARS_SET_LOCATION,
            buildJsonObject { put("lat", lat); put("lon", lon); put("elev", elevM); put("tz", tzHours) },
        )
        super.syncLocationToRig(lat, lon, elevM)
    }

    /**
     * Mount settings (curated subset). Each setter sends `mount_set_all_settings` with only the
     * changed field — `Mount::setAllSettings` applies only keys present, so unmodelled fields are
     * untouched — and applies the local update via `super`.
     *
     * Fire-and-forget, with no `mount_get_all_settings` re-fetch: on a busy connection a get sent
     * right after the set can be processed first and revert the optimistic value. The one fetch
     * is in `EkosRemoteClient`'s online burst at connect.
     */
    private fun sendMountSetting(field: String, value: JsonElement) {
        client.sendCommand(Commands.MOUNT_SET_ALL_SETTINGS, buildJsonObject { put(field, value) })
    }

    override fun setMountMeridianFlip(enabled: Boolean) {
        sendMountSetting("executeMeridianFlip", JsonPrimitive(enabled))
        super.setMountMeridianFlip(enabled)
    }
    override fun setMountMeridianFlipOffset(deg: Double) {
        sendMountSetting("meridianFlipOffsetDegrees", JsonPrimitive(deg))
        super.setMountMeridianFlipOffset(deg)
    }
    override fun setMountAltLimitEnabled(enabled: Boolean) {
        sendMountSetting("enableAltitudeLimits", JsonPrimitive(enabled))
        super.setMountAltLimitEnabled(enabled)
    }
    override fun setMountAltLimitMin(deg: Double) {
        sendMountSetting("minimumAltLimit", JsonPrimitive(deg))
        super.setMountAltLimitMin(deg)
    }
    override fun setMountAltLimitMax(deg: Double) {
        sendMountSetting("maximumAltLimit", JsonPrimitive(deg))
        super.setMountAltLimitMax(deg)
    }
    override fun setMountAltLimitTrackingOnly(enabled: Boolean) {
        sendMountSetting("enableAltitudeLimitsTrackingOnly", JsonPrimitive(enabled))
        super.setMountAltLimitTrackingOnly(enabled)
    }
    override fun setMountHaLimitEnabled(enabled: Boolean) {
        sendMountSetting("enableHaLimit", JsonPrimitive(enabled))
        super.setMountHaLimitEnabled(enabled)
    }
    override fun setMountHaLimitMax(hours: Double) {
        sendMountSetting("maximumHaLimit", JsonPrimitive(hours))
        super.setMountHaLimitMax(hours)
    }
    override fun setMountParkEveryDay(enabled: Boolean) {
        sendMountSetting("parkEveryDay", JsonPrimitive(enabled))
        super.setMountParkEveryDay(enabled)
    }
    override fun setMountAutoParkTime(time: String) {
        sendMountSetting("autoParkTime", JsonPrimitive(time))
        super.setMountAutoParkTime(time)
    }

    /**
     * Camera settings (curated subset). Same fire-and-forget shape as [sendMountSetting]:
     * `capture_set_all_settings` (`Camera::setAllSettings`) touches only the keys present.
     */
    private fun sendCaptureSetting(field: String, value: JsonElement) {
        client.sendCommand(Commands.CAPTURE_SET_ALL_SETTINGS, buildJsonObject { put(field, value) })
    }

    override fun setCaptureFullFrame() {
        val size = _state.value.primaryImageSize() ?: return
        val w = size.sensorW ?: return
        val h = size.sensorH ?: return
        client.sendCommand(Commands.CAPTURE_SET_ALL_SETTINGS, buildJsonObject {
            put("captureFrameXN", 0); put("captureFrameYN", 0)
            put("captureFrameWN", w); put("captureFrameHN", h)
        })
        // optimistic; Ekos pushes its full settings back (settingsUpdated) right after
        _state.update { s ->
            s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(
                captureFrameXN = 0, captureFrameYN = 0, captureFrameWN = w, captureFrameHN = h,
            ))
        }
    }

    override fun setCameraSaveDir(path: String) {
        sendCaptureSetting("fileDirectoryT", JsonPrimitive(path))
        super.setCameraSaveDir(path)
    }
    override fun setCameraPlaceholderFormat(format: String) {
        sendCaptureSetting("placeholderFormatT", JsonPrimitive(format))
        super.setCameraPlaceholderFormat(format)
    }
    override fun setCameraGuideDeviationEnabled(enabled: Boolean) {
        sendCaptureSetting("enforceGuideDeviation", JsonPrimitive(enabled))
        super.setCameraGuideDeviationEnabled(enabled)
    }
    override fun setCameraGuideDeviation(arcsec: Double) {
        sendCaptureSetting("guideDeviation", JsonPrimitive(arcsec))
        super.setCameraGuideDeviation(arcsec)
    }
    override fun setCameraStartGuideDriftEnabled(enabled: Boolean) {
        sendCaptureSetting("enforceStartGuiderDrift", JsonPrimitive(enabled))
        super.setCameraStartGuideDriftEnabled(enabled)
    }
    override fun setCameraStartGuideDeviation(arcsec: Double) {
        sendCaptureSetting("startGuideDeviation", JsonPrimitive(arcsec))
        super.setCameraStartGuideDeviation(arcsec)
    }
    override fun setCameraDitherPerJobEnabled(enabled: Boolean) {
        sendCaptureSetting("enableDitherPerJob", JsonPrimitive(enabled))
        super.setCameraDitherPerJobEnabled(enabled)
    }
    override fun setCameraDitherPerJobFrequency(everyN: Int) {
        sendCaptureSetting("guideDitherPerJobFrequency", JsonPrimitive(everyN))
        super.setCameraDitherPerJobFrequency(everyN)
    }
    override fun setCameraRefocusEveryNEnabled(enabled: Boolean) {
        sendCaptureSetting("enforceRefocusEveryN", JsonPrimitive(enabled))
        super.setCameraRefocusEveryNEnabled(enabled)
    }
    override fun setCameraRefocusEveryN(minutes: Int) {
        sendCaptureSetting("refocusEveryN", JsonPrimitive(minutes))
        super.setCameraRefocusEveryN(minutes)
    }
    override fun setCameraRefocusOnTemperatureEnabled(enabled: Boolean) {
        sendCaptureSetting("enforceAutofocusOnTemperature", JsonPrimitive(enabled))
        super.setCameraRefocusOnTemperatureEnabled(enabled)
    }
    override fun setCameraMaxFocusTemperatureDelta(deltaC: Double) {
        sendCaptureSetting("maxFocusTemperatureDelta", JsonPrimitive(deltaC))
        super.setCameraMaxFocusTemperatureDelta(deltaC)
    }
    override fun setCameraFilter(filter: String) {
        sendCaptureSetting("FilterPosCombo", JsonPrimitive(filter))
        super.setCameraFilter(filter)
    }

    /**
     * Align settings (curated subset). Same fire-and-forget shape as [sendMountSetting]:
     * `align_set_all_settings` touches only the keys present.
     */
    private fun sendAlignSetting(field: String, value: JsonElement) {
        client.sendCommand(Commands.ALIGN_SET_ALL_SETTINGS, buildJsonObject { put(field, value) })
    }

    override fun setAlignExposure(sec: Double) {
        sendAlignSetting("alignExposure", JsonPrimitive(sec))
        super.setAlignExposure(sec)
    }
    override fun setAlignGain(gain: Double) {
        sendAlignSetting("alignGain", JsonPrimitive(gain))
        super.setAlignGain(gain)
    }
    override fun setAlignFilter(filter: String) {
        sendAlignSetting("alignFilter", JsonPrimitive(filter))
        super.setAlignFilter(filter)
    }
    override fun setAlignUseCurrentFilter(enabled: Boolean) {
        sendAlignSetting("alignUseCurrentFilter", JsonPrimitive(enabled))
        super.setAlignUseCurrentFilter(enabled)
    }
    override fun setAlignBinning(binning: String) {
        sendAlignSetting("alignBinning", JsonPrimitive(binning))
        super.setAlignBinning(binning)
    }
    override fun setAlignAccuracyThreshold(arcsec: Double) {
        sendAlignSetting("alignAccuracyThreshold", JsonPrimitive(arcsec))
        super.setAlignAccuracyThreshold(arcsec)
    }
    /**
     * `kcfg_AstrometryRotatorThreshold`, arcminutes, via `align_set_all_settings`. At `0`,
     * [AppState.wireRotatorThreshold]'s `diff <= threshold` check is practically never true, so
     * the readback would never show "within threshold".
     */
    override fun setAlignRotatorThreshold(arcmin: Double) {
        sendAlignSetting("kcfg_AstrometryRotatorThreshold", JsonPrimitive(arcmin))
        super.setAlignRotatorThreshold(arcmin)
    }

    /**
     * `align_set_target_pa`, request `{"angle": double}` (`message.cpp:928-935`). Sets
     * `Align::m_TargetPositionAngle`, as Load & Slew would from a reference image's solved PA.
     * Required before [setRotatorAutoControl]'s readback/auto-drive do anything:
     * `Align::checkIfRotationRequired()` only emits `manualRotatorChanged` or drives a rotator
     * once this is non-NaN.
     */
    override fun setRotatorAngle(deg: Double) {
        client.sendCommand(Commands.ALIGN_SET_TARGET_PA, buildJsonObject { put("angle", deg) })
        super.setRotatorAngle(deg)
    }

    /**
     * `align_set_astrometry_settings`'s `rotator_control` bool, written to
     * `Options::setAstrometryUseRotator` (message.cpp:890) — a separate path from
     * `align_set_all_settings`. This gates the whole rotator feature: with it off,
     * `checkIfRotationRequired()` (`align_goto.cpp:535`) never runs, so neither rotator
     * auto-drive nor the manual-diff readback (`align_manual_rotator_status`) happens. Needed
     * whether or not [com.nocturne.session.AppState.primaryTrain]`.rotator` is a device.
     *
     * `align_manual_rotator_toggle` is not used: it only shows/hides Ekos's dialog on the Pi's
     * own screen (`align_components.cpp:146`).
     */
    override fun setRotatorAutoControl(enabled: Boolean) {
        client.sendCommand(Commands.ALIGN_SET_ASTROMETRY_SETTINGS, buildJsonObject { put("rotator_control", enabled) })
        super.setRotatorAutoControl(enabled)
    }

    /** `option_set` — `Options::self()` reflection. */
    private fun sendOption(name: String, value: Boolean) {
        client.sendCommand(Commands.OPTION_SET, buildJsonObject {
            putJsonArray("options") { addJsonObject { put("name", name); put("value", value) } }
        })
    }
    override fun setEkosRemoteNotifications(enabled: Boolean) {
        sendOption("ekosRemoteNotifications", enabled)
        super.setEkosRemoteNotifications(enabled)
    }
    override fun setEkosRemoteSound(enabled: Boolean) {
        sendOption("ekosRemoteSound", enabled)
        super.setEkosRemoteSound(enabled)
    }

    /**
     * Main camera preview parameters. `capture_preview` (Bench "Snap main") takes no parameters;
     * Ekos uses the Capture module's `captureExposureN`/`captureGainN`/`captureBinHN`/
     * `captureBinVN` (see [com.nocturne.protocol.WireCaptureSettings]).
     */
    override fun setCapturePreviewExposure(sec: Double) {
        sendCaptureSetting("captureExposureN", JsonPrimitive(sec))
        super.setCapturePreviewExposure(sec)
    }
    override fun setCapturePreviewGain(gain: Double) {
        sendCaptureSetting("captureGainN", JsonPrimitive(gain))
        super.setCapturePreviewGain(gain)
    }
    override fun setCapturePreviewBinning(bin: Int) {
        // H and V together: the UI has a single square-binning control.
        sendCaptureSetting("captureBinHN", JsonPrimitive(bin))
        sendCaptureSetting("captureBinVN", JsonPrimitive(bin))
        super.setCapturePreviewBinning(bin)
    }

    /**
     * Guide camera preview parameters — as [setCapturePreviewExposure], but for `guide_capture`
     * and the Guide module's `guideExposure`/`guideGain`/`guideBinning`.
     */
    private fun sendGuideSetting(field: String, value: JsonElement) {
        client.sendCommand(Commands.GUIDE_SET_ALL_SETTINGS, buildJsonObject { put(field, value) })
    }
    override fun setGuidePreviewExposure(sec: Double) {
        sendGuideSetting("guideExposure", JsonPrimitive(sec))
        super.setGuidePreviewExposure(sec)
    }
    override fun setGuidePreviewGain(gain: Double) {
        sendGuideSetting("guideGain", JsonPrimitive(gain))
        super.setGuidePreviewGain(gain)
    }
    override fun setGuidePreviewBinning(binning: String) {
        sendGuideSetting("guideBinning", JsonPrimitive(binning))
        super.setGuidePreviewBinning(binning)
    }

    /** Guide settings (curated subset), fire-and-forget via `guide_set_all_settings`. */
    override fun setGuideAccuracyThreshold(arcsec: Double) {
        sendGuideSetting("guiderAccuracyThreshold", JsonPrimitive(arcsec))
        super.setGuideAccuracyThreshold(arcsec)
    }
    override fun setGuideDitherEnabled(enabled: Boolean) {
        sendGuideSetting("kcfg_DitherEnabled", JsonPrimitive(enabled))
        super.setGuideDitherEnabled(enabled)
    }
    override fun setGuideDitherPixels(px: Int) {
        sendGuideSetting("kcfg_DitherPixels", JsonPrimitive(px))
        super.setGuideDitherPixels(px)
    }
    override fun setGuideDitherThreshold(value: Double) {
        sendGuideSetting("kcfg_DitherThreshold", JsonPrimitive(value))
        super.setGuideDitherThreshold(value)
    }
    override fun setGuideReuseCalibration(enabled: Boolean) {
        sendGuideSetting("kcfg_ReuseGuideCalibration", JsonPrimitive(enabled))
        super.setGuideReuseCalibration(enabled)
    }
    override fun setGuideDarkFrame(enabled: Boolean) {
        sendGuideSetting("guideDarkFrame", JsonPrimitive(enabled))
        super.setGuideDarkFrame(enabled)
    }
    override fun setGuideSubframe(enabled: Boolean) {
        sendGuideSetting("guideSubframe", JsonPrimitive(enabled))
        super.setGuideSubframe(enabled)
    }
    override fun setGuideAutoStar(enabled: Boolean) {
        sendGuideSetting("guideAutoStar", JsonPrimitive(enabled))
        super.setGuideAutoStar(enabled)
    }
    override fun setGuideBoxSize(px: String) {
        sendGuideSetting("guideSquareSize", JsonPrimitive(px))
        super.setGuideBoxSize(px)
    }
    override fun setGuideDitherSettle(sec: Int) {
        sendGuideSetting("kcfg_DitherSettle", JsonPrimitive(sec))
        super.setGuideDitherSettle(sec)
    }
    override fun setGuideDitherTimeout(sec: Int) {
        sendGuideSetting("kcfg_DitherTimeout", JsonPrimitive(sec))
        super.setGuideDitherTimeout(sec)
    }
    override fun setGuideDitherMaxIterations(n: Int) {
        sendGuideSetting("kcfg_DitherMaxIterations", JsonPrimitive(n))
        super.setGuideDitherMaxIterations(n)
    }
    override fun setGuideDitherFailAbortsGuide(enabled: Boolean) {
        sendGuideSetting("kcfg_DitherFailAbortsAutoGuide", JsonPrimitive(enabled))
        super.setGuideDitherFailAbortsGuide(enabled)
    }
    override fun setGuideCalibrationPulse(ms: Int) {
        sendGuideSetting("kcfg_CalibrationPulseDuration", JsonPrimitive(ms))
        super.setGuideCalibrationPulse(ms)
    }
    override fun setGuideCalibrationMaxMove(px: Int) {
        sendGuideSetting("kcfg_CalibrationMaxMove", JsonPrimitive(px))
        super.setGuideCalibrationMaxMove(px)
    }
    override fun setGuideTwoAxis(enabled: Boolean) {
        sendGuideSetting("kcfg_TwoAxisEnabled", JsonPrimitive(enabled))
        super.setGuideTwoAxis(enabled)
    }
    override fun setGuideCalibrationBacklash(enabled: Boolean) {
        sendGuideSetting("kcfg_GuideCalibrationBacklash", JsonPrimitive(enabled))
        super.setGuideCalibrationBacklash(enabled)
    }
    override fun setGuideResetCalibration(enabled: Boolean) {
        sendGuideSetting("kcfg_ResetGuideCalibration", JsonPrimitive(enabled))
        super.setGuideResetCalibration(enabled)
    }
    override fun setGuideReverseDecOnFlip(enabled: Boolean) {
        sendGuideSetting("kcfg_ReverseDecOnPierSideChange", JsonPrimitive(enabled))
        super.setGuideReverseDecOnFlip(enabled)
    }
    override fun setGuideLostStarTimeout(sec: Int) {
        sendGuideSetting("kcfg_GuideLostStarTimeout", JsonPrimitive(sec))
        super.setGuideLostStarTimeout(sec)
    }

    /** Focus settings (curated subset), fire-and-forget via `focus_set_all_settings`. */
    private fun sendFocusSetting(field: String, value: JsonElement) {
        client.sendCommand(Commands.FOCUS_SET_ALL_SETTINGS, buildJsonObject { put(field, value) })
    }
    override fun setFocusExposure(sec: Double) {
        sendFocusSetting("focusExposure", JsonPrimitive(sec))
        super.setFocusExposure(sec)
    }
    override fun setFocusGain(gain: Double) {
        sendFocusSetting("focusGain", JsonPrimitive(gain))
        super.setFocusGain(gain)
    }
    override fun setFocusFilter(filter: String) {
        sendFocusSetting("focusFilter", JsonPrimitive(filter))
        super.setFocusFilter(filter)
    }
    override fun setFocusBacklash(steps: Int) {
        sendFocusSetting("focusBacklash", JsonPrimitive(steps))
        super.setFocusBacklash(steps)
    }
    override fun setFocusAlgorithm(algorithm: String) {
        sendFocusSetting("focusAlgorithm", JsonPrimitive(algorithm))
        super.setFocusAlgorithm(algorithm)
    }
    override fun setFocusTicks(ticks: Int) {
        sendFocusSetting("focusTicks", JsonPrimitive(ticks))
        super.setFocusTicks(ticks)
    }
    override fun setFocusMaxTravel(ticks: Int) {
        sendFocusSetting("focusMaxTravel", JsonPrimitive(ticks))
        super.setFocusMaxTravel(ticks)
    }
    override fun setFocusOutSteps(multiple: Double) {
        sendFocusSetting("focusOutSteps", JsonPrimitive(multiple))
        super.setFocusOutSteps(multiple)
    }
    override fun setFocusNumSteps(steps: Int) {
        sendFocusSetting("focusNumSteps", JsonPrimitive(steps))
        super.setFocusNumSteps(steps)
    }
    override fun setFocusWalk(walk: String) {
        sendFocusSetting("focusWalk", JsonPrimitive(walk))
        super.setFocusWalk(walk)
    }
    override fun setFocusAFOverscan(ticks: Int) {
        sendFocusSetting("focusAFOverscan", JsonPrimitive(ticks))
        super.setFocusAFOverscan(ticks)
    }
    override fun setFocusOverscanDelay(sec: Double) {
        sendFocusSetting("focusOverscanDelay", JsonPrimitive(sec))
        super.setFocusOverscanDelay(sec)
    }
    override fun setFocusMotionTimeout(sec: Int) {
        sendFocusSetting("focusMotionTimeout", JsonPrimitive(sec))
        super.setFocusMotionTimeout(sec)
    }
    override fun setFocusCaptureTimeout(sec: Int) {
        sendFocusSetting("focusCaptureTimeout", JsonPrimitive(sec))
        super.setFocusCaptureTimeout(sec)
    }
    override fun setFocusSettleTime(sec: Double) {
        sendFocusSetting("focusSettleTime", JsonPrimitive(sec))
        super.setFocusSettleTime(sec)
    }
    override fun setFocusDetection(method: String) {
        sendFocusSetting("focusDetection", JsonPrimitive(method))
        super.setFocusDetection(method)
    }
    override fun setFocusCurveFit(fit: String) {
        sendFocusSetting("focusCurveFit", JsonPrimitive(fit))
        super.setFocusCurveFit(fit)
    }
    override fun setFocusStarMeasure(measure: String) {
        sendFocusSetting("focusStarMeasure", JsonPrimitive(measure))
        super.setFocusStarMeasure(measure)
    }
    override fun setFocusTolerance(percent: Double) {
        sendFocusSetting("focusTolerance", JsonPrimitive(percent))
        super.setFocusTolerance(percent)
    }
    override fun setFocusR2Limit(limit: Double) {
        sendFocusSetting("focusR2Limit", JsonPrimitive(limit))
        super.setFocusR2Limit(limit)
    }
    override fun setFocusFramesCount(count: Int) {
        sendFocusSetting("focusFramesCount", JsonPrimitive(count))
        super.setFocusFramesCount(count)
    }
    override fun setFocusBinning(binning: String) {
        sendFocusSetting("focusBinning", JsonPrimitive(binning))
        super.setFocusBinning(binning)
    }

    /**
     * Scheduler-wide policy settings (curated subset, see [WireSchedulerSettings]).
     * Fire-and-forget like [sendMountSetting]; `scheduler_set_all_settings` touches only the
     * keys present. [pushRealJob] uses the same command for per-job fields when adding a job.
     */
    private fun sendSchedulerSetting(field: String, value: JsonElement) {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject { put(field, value) })
    }

    override fun setSchedulerStartAsap() {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("asapConditionR", true); put("startupTimeConditionR", false)
        })
        super.setSchedulerStartAsap()
    }
    override fun setSchedulerStartAtTime(iso: String) {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("asapConditionR", false); put("startupTimeConditionR", true); put("startupTimeEdit", iso)
        })
        super.setSchedulerStartAtTime(iso)
    }
    override fun setSchedulerLeadTime(minutes: Double) {
        sendSchedulerSetting("kcfg_LeadTime", JsonPrimitive(minutes))
        super.setSchedulerLeadTime(minutes)
    }
    override fun setSchedulerPreDawnTime(minutes: Double) {
        sendSchedulerSetting("kcfg_PreDawnTime", JsonPrimitive(minutes))
        super.setSchedulerPreDawnTime(minutes)
    }
    override fun setSchedulerAltitudeEnabled(enabled: Boolean) {
        sendSchedulerSetting("schedulerAltitude", JsonPrimitive(enabled))
        super.setSchedulerAltitudeEnabled(enabled)
    }
    override fun setSchedulerAltitudeValue(deg: Double) {
        sendSchedulerSetting("schedulerAltitudeValue", JsonPrimitive(deg))
        super.setSchedulerAltitudeValue(deg)
    }
    override fun setSchedulerMoonSeparationEnabled(enabled: Boolean) {
        sendSchedulerSetting("schedulerMoonSeparation", JsonPrimitive(enabled))
        super.setSchedulerMoonSeparationEnabled(enabled)
    }
    override fun setSchedulerMoonSeparationValue(deg: Double) {
        sendSchedulerSetting("schedulerMoonSeparationValue", JsonPrimitive(deg))
        super.setSchedulerMoonSeparationValue(deg)
    }
    override fun setSchedulerMoonAltitudeEnabled(enabled: Boolean) {
        sendSchedulerSetting("schedulerMoonAltitude", JsonPrimitive(enabled))
        super.setSchedulerMoonAltitudeEnabled(enabled)
    }
    override fun setSchedulerMoonAltitudeMaxValue(deg: Double) {
        sendSchedulerSetting("schedulerMoonAltitudeMaxValue", JsonPrimitive(deg))
        super.setSchedulerMoonAltitudeMaxValue(deg)
    }
    override fun setSchedulerTwilightEnabled(enabled: Boolean) {
        sendSchedulerSetting("schedulerTwilight", JsonPrimitive(enabled))
        super.setSchedulerTwilightEnabled(enabled)
    }
    override fun setSchedulerHorizonEnabled(enabled: Boolean) {
        sendSchedulerSetting("schedulerHorizon", JsonPrimitive(enabled))
        super.setSchedulerHorizonEnabled(enabled)
    }
    override fun setSchedulerDawnOffset(hours: Double) {
        sendSchedulerSetting("kcfg_DawnOffset", JsonPrimitive(hours))
        super.setSchedulerDawnOffset(hours)
    }
    override fun setSchedulerDuskOffset(hours: Double) {
        sendSchedulerSetting("kcfg_DuskOffset", JsonPrimitive(hours))
        super.setSchedulerDuskOffset(hours)
    }
    override fun setSchedulerTrackStep(enabled: Boolean) {
        sendSchedulerSetting("schedulerTrackStep", JsonPrimitive(enabled))
        super.setSchedulerTrackStep(enabled)
    }
    override fun setSchedulerFocusStep(enabled: Boolean) {
        sendSchedulerSetting("schedulerFocusStep", JsonPrimitive(enabled))
        super.setSchedulerFocusStep(enabled)
    }
    override fun setSchedulerAlignStep(enabled: Boolean) {
        sendSchedulerSetting("schedulerAlignStep", JsonPrimitive(enabled))
        super.setSchedulerAlignStep(enabled)
    }
    override fun setSchedulerGuideStep(enabled: Boolean) {
        sendSchedulerSetting("schedulerGuideStep", JsonPrimitive(enabled))
        super.setSchedulerGuideStep(enabled)
    }
    override fun setSchedulerCompleteSequences() {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("schedulerCompleteSequences", true); put("schedulerRepeatSequences", false)
            put("schedulerRepeatEverything", false); put("schedulerUntilTerminated", false); put("schedulerUntil", false)
        })
        super.setSchedulerCompleteSequences()
    }
    override fun setSchedulerRepeatSequences(limit: Int) {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("schedulerCompleteSequences", false); put("schedulerRepeatSequences", true); put("schedulerRepeatSequencesLimit", limit)
            put("schedulerRepeatEverything", false); put("schedulerUntilTerminated", false); put("schedulerUntil", false)
        })
        super.setSchedulerRepeatSequences(limit)
    }
    override fun setSchedulerRepeatEverything() {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("schedulerCompleteSequences", false); put("schedulerRepeatSequences", false)
            put("schedulerRepeatEverything", true); put("schedulerUntilTerminated", false); put("schedulerUntil", false)
        })
        super.setSchedulerRepeatEverything()
    }
    override fun setSchedulerUntilTerminated() {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("schedulerCompleteSequences", false); put("schedulerRepeatSequences", false)
            put("schedulerRepeatEverything", false); put("schedulerUntilTerminated", true); put("schedulerUntil", false)
        })
        super.setSchedulerUntilTerminated()
    }
    override fun setSchedulerUntil(iso: String) {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("schedulerCompleteSequences", false); put("schedulerRepeatSequences", false); put("schedulerRepeatEverything", false)
            put("schedulerUntilTerminated", false); put("schedulerUntil", true); put("schedulerUntilValue", iso)
        })
        super.setSchedulerUntil(iso)
    }
    override fun setSchedulerStartupEnabled(enabled: Boolean) {
        sendSchedulerSetting("schedulerStartupEnabled", JsonPrimitive(enabled))
        super.setSchedulerStartupEnabled(enabled)
    }
    override fun setSchedulerPreStartupScript(path: String) {
        sendSchedulerSetting("schedulerPreStartupScript", JsonPrimitive(path))
        super.setSchedulerPreStartupScript(path)
    }
    override fun setSchedulerPostStartupScript(path: String) {
        sendSchedulerSetting("schedulerPostStartupScript", JsonPrimitive(path))
        super.setSchedulerPostStartupScript(path)
    }
    override fun setSchedulerShutdownEnabled(enabled: Boolean) {
        sendSchedulerSetting("schedulerShutdownEnabled", JsonPrimitive(enabled))
        super.setSchedulerShutdownEnabled(enabled)
    }
    override fun setSchedulerPreShutdownScript(path: String) {
        sendSchedulerSetting("schedulerPreShutdownScript", JsonPrimitive(path))
        super.setSchedulerPreShutdownScript(path)
    }
    override fun setSchedulerPostShutdownScript(path: String) {
        sendSchedulerSetting("schedulerPostShutdownScript", JsonPrimitive(path))
        super.setSchedulerPostShutdownScript(path)
    }
    override fun setSchedulerPreemptiveShutdown(enabled: Boolean) {
        sendSchedulerSetting("kcfg_PreemptiveShutdown", JsonPrimitive(enabled))
        super.setSchedulerPreemptiveShutdown(enabled)
    }
    override fun setSchedulerPreemptiveShutdownTime(hours: Double) {
        sendSchedulerSetting("kcfg_PreemptiveShutdownTime", JsonPrimitive(hours))
        super.setSchedulerPreemptiveShutdownTime(hours)
    }
    override fun setSchedulerStopEkosAfterShutdown(enabled: Boolean) {
        sendSchedulerSetting("kcfg_StopEkosAfterShutdown", JsonPrimitive(enabled))
        super.setSchedulerStopEkosAfterShutdown(enabled)
    }
    override fun setSchedulerShutdownScriptTerminatesIndi(enabled: Boolean) {
        sendSchedulerSetting("kcfg_ShutdownScriptTerminatesINDI", JsonPrimitive(enabled))
        super.setSchedulerShutdownScriptTerminatesIndi(enabled)
    }
    override fun setSchedulerAbortDontRestart() {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("errorHandlingDontRestartButton", true); put("errorHandlingRestartImmediatelyButton", false); put("errorHandlingRestartQueueButton", false)
        })
        super.setSchedulerAbortDontRestart()
    }
    override fun setSchedulerAbortRestartImmediately() {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("errorHandlingDontRestartButton", false); put("errorHandlingRestartImmediatelyButton", true); put("errorHandlingRestartQueueButton", false)
        })
        super.setSchedulerAbortRestartImmediately()
    }
    override fun setSchedulerAbortRestartQueue() {
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("errorHandlingDontRestartButton", false); put("errorHandlingRestartImmediatelyButton", false); put("errorHandlingRestartQueueButton", true)
        })
        super.setSchedulerAbortRestartQueue()
    }
    override fun setSchedulerAbortRescheduleErrors(enabled: Boolean) {
        sendSchedulerSetting("errorHandlingRescheduleErrorsCB", JsonPrimitive(enabled))
        super.setSchedulerAbortRescheduleErrors(enabled)
    }
    override fun setSchedulerAbortDelay(minutes: Int) {
        sendSchedulerSetting("errorHandlingStrategyDelay", JsonPrimitive(minutes))
        super.setSchedulerAbortDelay(minutes)
    }
    override fun setSchedulerRememberJobProgress(enabled: Boolean) {
        sendSchedulerSetting("kcfg_RememberJobProgress", JsonPrimitive(enabled))
        super.setSchedulerRememberJobProgress(enabled)
    }
    override fun setSchedulerForceAlignmentBeforeJob(enabled: Boolean) {
        sendSchedulerSetting("kcfg_ForceAlignmentBeforeJob", JsonPrimitive(enabled))
        super.setSchedulerForceAlignmentBeforeJob(enabled)
    }

    /**
     * The `train_add`/`train_update` payload shape is undocumented, so this sends every field
     * `train_get_all` reports, with [id] when a matching train is known (else `train_add`).
     * The `train_get_all` auto-push reconciles whatever the server accepted.
     */
    private fun sendTrainUpdate(slot: TrainSlot) {
        val s = _state.value
        val t = s.train(slot)
        val wireTrain = s.wireTrains?.getOrNull(if (slot == TrainSlot.PRIMARY) 0 else 1)
        val payload = buildJsonObject {
            wireTrain?.let { put("id", it.id) }
            put("name", wireTrain?.name ?: if (slot == TrainSlot.PRIMARY) "Primary" else "Secondary")
            put("mount", t.mount)
            put("camera", t.camera)
            put("guider", t.guideVia)
            put("focuser", t.focuser)
            put("filterwheel", t.filterWheel)
            put("rotator", t.rotator)
            put("reducer", t.reducer.toString())
            put("dustcap", t.dustCap)
            put("lightbox", t.lightBox)
            put("scope", t.scope)
            put("adaptiveoptics", t.adaptiveOptics)
        }
        client.sendCommand(if (wireTrain != null) Commands.TRAIN_UPDATE else Commands.TRAIN_ADD, payload)
    }

    // ── Scopes catalog (separate from Optical Trains) ───────────────────

    override fun addScope(name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int) {
        client.sendCommand(Commands.SCOPE_ADD, buildJsonObject {
            put("model", name); put("vendor", vendor); put("type", type)
            put("focal_length", focalMm.toDouble()); put("aperture", apertureMm.toDouble())
        })
        super.addScope(name, vendor, type, focalMm, apertureMm) // optimistic; get_scopes auto-reply reconciles
    }

    override fun updateScope(id: String, name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int) {
        // Only a wire-known scope has a server id to update; a local-only scope is added instead.
        val isWireKnown = _state.value.wireScopes?.any { it.id == id } == true
        if (isWireKnown) {
            client.sendCommand(Commands.SCOPE_UPDATE, buildJsonObject {
                put("id", id); put("model", name); put("vendor", vendor); put("type", type)
                put("focal_length", focalMm.toDouble()); put("aperture", apertureMm.toDouble())
            })
        } else {
            client.sendCommand(Commands.SCOPE_ADD, buildJsonObject {
                put("model", name); put("vendor", vendor); put("type", type)
                put("focal_length", focalMm.toDouble()); put("aperture", apertureMm.toDouble())
            })
        }
        super.updateScope(id, name, vendor, type, focalMm, apertureMm)
    }

    override fun removeScope(id: String) {
        if (_state.value.wireScopes?.any { it.id == id } == true) {
            client.sendCommand(Commands.SCOPE_DELETE, buildJsonObject { put("id", id) })
        }
        super.removeScope(id)
    }

    // ── Plan tab astro_* search ─────────────────────────────────────────

    override fun setQuery(text: String) {
        super.setQuery(text)
        refilterSearch()
    }

    override fun clearQuery() {
        super.clearQuery()
        refilterSearch()
    }

    override fun toggleChip(index: Int) {
        super.toggleChip(index)
        runSearch()
    }

    override fun setSearchType(skyType: Int?) {
        super.setSearchType(skyType)
        runSearch()
    }

    override fun setSearchMaxMag(mag: Int) {
        super.setSearchMaxMag(mag)
        runSearch()
    }

    /**
     * Typed-query change: re-filter the cached name list locally (no new server-side search),
     * and look the typed text up directly by name across every catalog/type.
     */
    private fun refilterSearch() {
        lastLookupObject = null
        requestNameLookup(_state.value.query.trim())
        val haveNames = if (_state.value.searchType == null) allNames != null else lastSearchNames != null
        if (!haveNames) return runSearch()
        _state.update { resetSearchResults(it) }
        sendSearchResolve()
    }

    /** Below this, KStars' substring name match hits nearly everything — not worth a round-trip. */
    private val minLookupLength = 2

    private fun requestNameLookup(query: String) {
        val q = query.takeIf { it.length >= minLookupLength }
        when {
            lookupInFlight -> queuedLookupQuery = q ?: "" // "" = drop the in-flight reply, send nothing
            q != null -> sendNameLookup(q)
        }
    }

    private fun sendNameLookup(query: String) {
        if (query.isEmpty()) return
        lookupInFlight = true
        client.sendCommand(Commands.ASTRO_GET_OBJECT_INFO, buildJsonObject { put("object", query) })
    }

    /**
     * Resolves the brightest [SEARCH_RESULT_CAP] matching names' coords/size/riseset. The DSO
     * database hands names back brightest-first, so the cap keeps the most useful slice.
     */
    private fun sendSearchResolve() {
        val names = matchingSearchNames(_state.value).take(SEARCH_RESULT_CAP)
        if (names.isEmpty()) return
        client.sendCommand(Commands.ASTRO_GET_OBJECTS_INFO, buildJsonObject { putJsonArray("names") { names.forEach { add(it) } } })
        // "exact": true, as in ensureTargetRiseset (message.cpp:2295): otherwise a name KStars
        // resolves to a different preferred name misses buildSearchResults()'s name-keyed merge
        // and the row shows no "X° max".
        client.sendCommand(Commands.ASTRO_GET_OBJECTS_RISESET, buildJsonObject {
            putJsonArray("names") { names.forEach { add(it) } }
            put("exact", true)
        })
    }

    /**
     * `astro_search_objects` has no free-text field (only `type`/`direction`/`maxMagnitude`/
     * `minAlt`/`minDuration`/`minFOV`), so [AppState.searchType]/[AppState.searchMaxMag]/
     * [AppState.chips] drive the wire call and the typed query is applied client-side
     * ([matchingSearchNames]). `type` is a single `SkyObject::TYPE`. The DSO query is
     * `magnitude < maxMagnitude`, so objects with no catalogued magnitude (common for large
     * emission nebulae) never match a type search.
     */
    private fun runSearch() {
        val s = _state.value
        if (s.searchType == null) {
            // Name search: no server-side filtering; the full name list is the source.
            if (allNames == null) {
                if (!allNamesRequested) {
                    allNamesRequested = true
                    client.sendCommand(Commands.ASTRO_GET_NAMES)
                }
                _state.update { it.copy(wireSearchResults = emptyList(), wireSearchMatchCount = null) }
            } else {
                _state.update { resetSearchResults(it) }
                sendSearchResolve()
            }
            return
        }
        client.sendCommand(Commands.ASTRO_SEARCH_OBJECTS, buildJsonObject {
            put("type", s.searchType)
            put("maxMagnitude", s.searchMaxMag.toDouble())
            put("minAlt", if (s.chips.contains(1)) 40.0 else 15.0)
            put("minDuration", if (s.chips.contains(0)) 3600 else 0)
            put("minFOV", if (s.chips.contains(2)) 1.0 else 0.0)
        })
    }

    // ── Sequence tab — Scheduler + .esq ─────────────────────────────────

    /**
     * Ekos has no "edit a synced job" primitive: `scheduler_add_jobs` only adds from the
     * Scheduler's current form state. Pushing an unsynced job writes its `.esq`, points the
     * form at it, and adds it (`synced` flips once `scheduler_get_jobs` confirms, see
     * [applySchedulerJobs]). To edit a synced job: remove it, edit, push again.
     *
     * Pushing never starts the Scheduler; only [resumeSession]/[startNextJob] request that (see
     * [PendingSchedulerStart]). A duplicate target name is refused locally, since Ekos refuses it
     * too and the name match would otherwise latch onto the other job.
     *
     * `scheduler_add_jobs` needs `raBox`/`decBox` set directly: `nameEdit` over the wire doesn't
     * trigger the GUI's name resolution, and without coordinates the add is a silent no-op. An
     * `opticalTrainCombo` left at `"--"` fails the same way, so it's set to the capture module's
     * train. The rest of the chain runs in [sendFollowUpCommands]'s `SchedulerSaveSequenceFile`
     * arm because `sequenceEdit` needs the server-resolved absolute path; a relative path yields
     * a broken `file:` URI.
     */
    override fun pushJob(jobId: String) {
        val s = _state.value
        val job = s.jobs.firstOrNull { it.id == jobId } ?: return
        if (job.synced) return
        val targetName = s.targetNameFor(job)
        if (s.wireSchedulerJobs.orEmpty().any { it.name == targetName }) {
            update { it.copy(jobPushRefused = targetName) }
            return
        }
        update { it.copy(jobPushRefused = null) }
        pushRealJob(jobId, alsoStart = false)
    }

    /** The global Scheduler toggle. Not flipped optimistically: the label follows the `new_scheduler_state` push (see [AppState.schedulerRunning]). */
    override fun toggleScheduler() {
        client.sendCommand(Commands.SCHEDULER_START_JOB)
    }

    /** The save→settings→add→get→(start) chain — see [pushJob]. [resumeSession]/[startNextJob] also request the [alsoStart] tail. */
    private fun pushRealJob(jobId: String, alsoStart: Boolean = false) {
        val s = _state.value
        val job = s.jobs.firstOrNull { it.id == jobId } ?: return
        val target = s.findTarget(job.targetId)
        val targetName = s.targetNameFor(job)
        // Named after the target (e.g. "esq/M33_j3.esq"), sanitized like FrameFileWriter's
        // paths; the raw name goes to `nameEdit`, which Ekos sanitizes itself. A bare path
        // resolves relative to $HOME. It's not known whether the save handler creates a missing
        // directory, so `$HOME/esq/` must exist on the Pi or the save may fail silently.
        val path = "esq/${sanitizeFileToken(targetName)}.esq"
        client.sendCommand(Commands.SCHEDULER_SAVE_SEQUENCE_FILE, buildJsonObject {
            put(
                "filedata",
                EsqWriter.write(
                    job, targetName,
                    enforceRefocusEveryN = s.wireCaptureSettings?.enforceRefocusEveryN ?: true,
                    refocusEveryN = s.wireCaptureSettings?.refocusEveryN ?: 60,
                    enforceAutofocusOnTemperature = s.wireCaptureSettings?.enforceAutofocusOnTemperature ?: false,
                    maxFocusTemperatureDelta = s.wireCaptureSettings?.maxFocusTemperatureDelta ?: 1.0,
                    // Carried from the capture settings so the job's FITSDirectory and
                    // PlaceholderFormat match Ekos's.
                    fitsDirectory = s.wireCaptureSettings?.fileDirectoryT,
                    placeholderFormat = s.wireCaptureSettings?.placeholderFormatT ?: "",
                ),
            )
            put("path", path)
        })
        pendingJobStart = PendingJobStart(
            jobId = jobId,
            targetName = targetName,
            raDec = target?.coords?.let { parseCoordsToRaDecBox(it) },
            trainName = s.moduleTrainAssignments?.get("capture") ?: s.wireTrains?.firstOrNull()?.name,
            alsoStart = alsoStart,
        )
    }

    /**
     * Refused while the Scheduler is running (Ekos replies "Cannot delete currently running
     * job"). Gated on [AppState.schedulerRunning], not the job's state: a job reads `SCHEDULED`
     * after Ekos's evaluation on add even while the Scheduler is idle. On refusal nothing is sent
     * and the local row stays.
     */
    override fun removeJob(jobId: String) {
        val s = _state.value
        val job = s.jobs.firstOrNull { it.id == jobId } ?: return
        val targetName = s.targetNameFor(job)
        if (job.synced && s.schedulerRunning) {
            update { it.copy(jobRemoveRefused = targetName) }
            return
        }
        update { it.copy(jobRemoveRefused = null) }
        if (job.synced) removeRealJobByName(targetName)
        super.removeJob(jobId)
    }

    /** Same Scheduler-running check as [removeJob]. */
    override fun removeUnmanagedJob(name: String) {
        if (_state.value.schedulerRunning) {
            update { it.copy(jobRemoveRefused = name) }
            return
        }
        update { it.copy(jobRemoveRefused = null) }
        removeRealJobByName(name)
    }

    /**
     * Resolves indices by name against the latest `scheduler_get_jobs` reply rather than
     * caching them (`WireSchedulerJob` has no server-assigned id). Removes highest index first
     * so one removal doesn't shift the next. Re-fetches the job list afterwards: removing a job
     * that never started causes no `new_scheduler_state` push, and a stale list would show the
     * removed job again as "unmanaged".
     */
    private fun removeRealJobsByName(targetNames: Collection<String>) {
        val jobs = _state.value.wireSchedulerJobs.orEmpty()
        val indices = targetNames.mapNotNull { name -> jobs.indexOfLast { it.name == name }.takeIf { it >= 0 } }
            .distinct().sortedDescending()
        if (indices.isEmpty()) return
        indices.forEach { client.sendCommand(Commands.SCHEDULER_REMOVE_JOBS, buildJsonObject { put("index", it) }) }
        client.sendCommand(Commands.SCHEDULER_GET_JOBS)
    }

    private fun removeRealJobByName(targetName: String) = removeRealJobsByName(listOf(targetName))

    /**
     * Ekos has no session concept; the base `endSession()`/`finishNight()` only move local
     * navigation. These whole-night lifecycle actions deliberately stop+remove the Scheduler
     * jobs, unlike ordinary pushes.
     *
     * A Scheduler stop (`SchedulerProcess::stop`) aborts the active job's capture/focus/align/slew
     * and guiding and marks unfinished jobs aborted, but runs no shutdown: no park, no cooler.
     * The job Ekos is actually running counts even when no local job claims it (queue lost on
     * reinstall, job added in KStars), so the stop doesn't depend on the local `synced` flag.
     */
    override fun endSession() {
        val s = _state.value
        val names = endingJobNames(s)
        stopSchedulerIfRunning()
        removeRealJobsByName(names)
        super.endSession()
    }

    /** [endSession], then park the mount and turn the cooler off. Ekos' own shutdown queue stays disabled on the rig. */
    override fun finishNight() {
        val s = _state.value
        val names = endingJobNames(s) + s.jobs.filter { it.synced }.map { s.targetNameFor(it) }
        stopSchedulerIfRunning()
        removeRealJobsByName(names)
        if (s.guiding) client.sendCommand(Commands.GUIDE_STOP)
        client.sendCommand(Commands.MOUNT_PARK)
        setCoolerEnabled(false)
        super.finishNight()
    }

    /** The contract job if pushed, plus whatever Ekos' Scheduler is busy with. */
    private fun endingJobNames(s: AppState): Set<String> = buildSet {
        s.contractJob?.takeIf { it.synced }?.let { add(s.targetNameFor(it)) }
        s.wireSchedulerJobs.orEmpty().filter { it.state == SchedulerJobStatus.BUSY }.forEach { add(it.name) }
    }

    /**
     * `scheduler_start_job` toggles: only RUNNING turns it off, any other state (paused, startup,
     * shutdown) would start it. Server-side the stop is synchronous, so a remove sent right after lands.
     */
    private fun stopSchedulerIfRunning() {
        if (_state.value.schedulerStatus == SCHEDULER_STATE_RUNNING) client.sendCommand(Commands.SCHEDULER_START_JOB)
    }

    /**
     * [endSession] removed the job from Ekos, so resuming (or advancing to the next job)
     * pushes it again and starts the Scheduler (see [PendingSchedulerStart]).
     */
    override fun resumeSession() {
        val id = _state.value.lastEndedJobId
        super.resumeSession()
        if (id != null) pushRealJob(id, alsoStart = true)
    }

    override fun startNextJob() {
        val next = _state.value.jobs.firstOrNull { it.id != _state.value.lastEndedJobId }
        super.startNextJob()
        if (next != null) pushRealJob(next.id, alsoStart = true)
    }
}

private fun WireProfile.toRigProfile() = RigProfile(name = name, deviceKeys = drivers.values.flatten(), drivers = drivers, guider = guider)

private fun WireDevice.toLiveDevice() = LiveDevice(name = name, connected = connected, roles = bitmaskToRoles(interfaceMask))

/**
 * `scope_add`/`scope_update` have no `name` field, so the app's single name is sent as
 * `model`. On the way back, a train's `scope` field references [WireScope]'s `name`, not
 * `model`, so decoding prefers `name` and falls back to `model` if blank.
 */
private fun WireScope.toScopeDef() = ScopeDef(
    id = id,
    name = name.ifBlank { model },
    vendor = vendor,
    type = type,
    focalMm = focal_length.roundToInt(),
    apertureMm = aperture.roundToInt(),
)

/** Blank fields (an unassigned role in `train_get_all`) become "None". `internal` so tests can call it directly. */
internal fun WireTrain.toTrainAssignment() = TrainAssignment(
    mount = mount.ifBlank { "None" },
    camera = camera.ifBlank { "None" },
    rotator = rotator.ifBlank { "None" },
    guideVia = guider.ifBlank { "None" },
    dustCap = dustcap.ifBlank { "None" },
    scope = scope.ifBlank { "None" },
    filterWheel = filterwheel.ifBlank { "None" },
    focuser = focuser.ifBlank { "None" },
    reducer = reducer,
    lightBox = lightbox.ifBlank { "None" },
    adaptiveOptics = (adaptiveoptics ?: "").ifBlank { "None" },
)

/**
 * Mirrors a `CONNECTION` switch push into the device's `connected` flag in
 * [AppState.wireDevices], so a device `get_devices` caught mid-startup (see
 * [EkosRemoteController.sendFollowUpCommands]) shows as connected once it is.
 */
private fun AppState.withConnectionState(device: String, property: WireProperty): AppState {
    if (property !is WireProperty.Switch || property.name != "CONNECTION") return this
    val connected = property.switches.any { it.name == "CONNECT" && it.state == 1 }
    return copy(wireDevices = wireDevices?.map { if (it.name == device) it.copy(connected = connected) else it })
}

private const val CAMERA_FAN_PROP = "TC_FAN_SPEED" // ToupTek-family drivers (indi_toupbase)
/** `new_mount_state.status` values while the mount is moving (ISD::Mount status text). */
private val MOUNT_MOVING_STATES = setOf("Slewing", "Moving", "Parking")

/** `new_align_state.status` values (`ekos.h` `alignStates`) that end an Align run. "Successful"
 *  is not one: with `slewR` it's followed by a re-slew and another solve. */
private val ALIGN_DONE_STATES = setOf("Idle", "Complete", "Failed", "Aborted")
private const val SLEW_START_TIMEOUT_MS = 10_000L
private const val SLEW_TIMEOUT_MS = 5 * 60_000L
private const val SLEW_SETTLE_MS = 2_000L
private const val ALIGN_START_TIMEOUT_MS = 30_000L
// Ekos caps the loop at MAXIMUM_SOLVER_ITERATIONS (10) passes of exposure + solve + slew.
private const val ALIGN_RUN_TIMEOUT_MS = 15 * 60_000L
private const val RECENT_GOTO_MS = 5_000L
private const val PREFERRED_PIER_SIDE = "Preferred Pier Side"
private const val PIER_SIDE_EAST = "2" // lx200_OnStep.cpp: "1" West, "2" East, "3" Best
private const val PIER_SIDE_RESEND_MS = 10_000L

/** See [AppState.wireGainRangeByDevice]. */
private fun AppState.withGainRange(device: String, property: WireProperty): AppState {
    if (property !is WireProperty.Number) return this
    val el = when (property.name) {
        "CCD_GAIN" -> property.numbers.firstOrNull()
        "CCD_CONTROLS" -> property.numbers.firstOrNull {
            it.name.equals("gain", ignoreCase = true) || it.label.equals("gain", ignoreCase = true)
        }
        else -> null
    } ?: return this
    // Value-only pushes (live updates) carry no range — keep what device_get gave us.
    val min = el.min ?: return this
    val range = GainRange(min, el.max ?: min, el.step?.takeIf { it > 0 } ?: 1.0)
    return if (wireGainRangeByDevice[device] == range) this else copy(wireGainRangeByDevice = wireGainRangeByDevice + (device to range))
}

/**
 * Captures a camera's `CCD_INFO` into [AppState.wireCcdInfoByDevice] for the Framing card,
 * reading all three elements directly; the generic [withProperty] path keeps only one element.
 */
private fun AppState.withCcdInfo(device: String, property: WireProperty): AppState {
    if (property !is WireProperty.Number || property.name != "CCD_INFO") return this
    val maxX = property.numbers.firstOrNull { it.name == "CCD_MAX_X" }?.value?.roundToInt() ?: return this
    val maxY = property.numbers.firstOrNull { it.name == "CCD_MAX_Y" }?.value?.roundToInt() ?: return this
    val pixelUm = property.numbers.firstOrNull { it.name == "CCD_PIXEL_SIZE" }?.value ?: return this
    return copy(wireCcdInfoByDevice = wireCcdInfoByDevice + (device to CcdInfo(maxX, maxY, pixelUm)))
}

/**
 * Resets any block filter that isn't one of the filter wheel's current slots to the first slot,
 * since that name goes into the `.esq` `<Filter>` element sent to Ekos.
 * [SessionController.cycleBlockFilter] only offers [AppState.realFilterNames], but a block set
 * before the wheel connected or was renamed can hold a stale name. Runs on every
 * device-property push; valid filters are never touched.
 */
private fun AppState.withValidBlockFilters(): AppState {
    val names = realFilterNames ?: return this
    val fallback = names.first()
    var next = this
    for (job in jobs) {
        if (job.blocks.none { it.filter !in names }) continue
        next = next.mapJob(job.id) { j -> j.copy(blocks = j.blocks.map { if (it.filter in names) it else it.copy(filter = fallback) }) }
    }
    return next
}

/** Merges one property-vector push into [AppState.indiProps], keyed by device name. */
private fun AppState.withProperty(device: String, property: WireProperty): AppState {
    val current = indiProps[device] ?: emptyList()
    val existing = current.firstOrNull { it.name == property.name }
    val updated = property.toIndiProperty(existing)
    val next = if (existing != null) current.map { if (it.name == property.name) updated else it } else current + updated
    return copy(indiProps = indiProps + (device to next))
}

/**
 * Translates one wire property vector into [IndiProperty], merging over [existing] so a
 * compact push (no label/group/bounds) doesn't blank what an earlier non-compact `device_get`
 * populated. Only value/state are fresh on every push.
 */
internal fun WireProperty.toIndiProperty(existing: IndiProperty?): IndiProperty = when (this) {
    is WireProperty.Switch -> {
        val ex = existing as? IndiProperty.SwitchProp
        val selectedIndex = switches.indexOfFirst { it.state == 1 }
        // Compact pushes drop element labels too; keep each element's last known label.
        val knownLabels = ex?.let { it.elementNames.zip(it.options).toMap() } ?: emptyMap()
        IndiProperty.SwitchProp(
            name = name,
            label = label ?: ex?.label ?: name,
            group = group ?: ex?.group ?: "",
            options = switches.map { it.label ?: knownLabels[it.name] ?: it.name },
            selected = if (selectedIndex >= 0) selectedIndex else ex?.selected ?: 0,
            elementNames = switches.map { it.name },
        )
    }
    is WireProperty.Number -> {
        val ex = existing as? IndiProperty.NumberProp
        val el = numbers.firstOrNull()
        IndiProperty.NumberProp(
            name = name,
            label = label ?: ex?.label ?: name,
            group = group ?: ex?.group ?: "",
            value = el?.value ?: ex?.value ?: 0.0,
            min = el?.min ?: ex?.min ?: 0.0,
            max = el?.max ?: ex?.max ?: 0.0,
            step = el?.step ?: ex?.step ?: 1.0,
            format = el?.format ?: ex?.format ?: "%.1f",
            elementName = el?.name ?: ex?.elementName ?: name,
            state = state,
        )
    }
    is WireProperty.Text -> {
        val ex = existing as? IndiProperty.TextProp
        IndiProperty.TextProp(
            name = name,
            label = label ?: ex?.label ?: name,
            group = group ?: ex?.group ?: "",
            // Text-vector pushes are whole-vector snapshots, not per-element diffs; fall back to
            // the previous elements only if this push carried none.
            elements = texts.map { it.name to it.text }.ifEmpty { ex?.elements ?: emptyList() },
        )
    }
    is WireProperty.Light -> IndiProperty.LightProp(
        name = name,
        label = label ?: existing?.label ?: name,
        group = group ?: existing?.group ?: "",
        elements = lights.map { it.name to it.state },
    )
}

/**
 * Translates one `astro_get_objects_info` entry (plus its riseset entry, if that reply has
 * landed) into [Target]. `size`/`band`/`usable`/`fov` stay null; the wire has no equivalent.
 */
private fun WireAstroObject.toTarget(riseset: WireRiseset?): Target = Target(
    id = name,
    common = name,
    coords = "${formatRaHours(ra0)} ${formatDecDegrees(de0)}",
    max = riseset?.altitudes?.maxOrNull()?.let { kotlin.math.round(it).toInt() },
    peak = riseset?.transit,
    custom = false,
    ra0 = ra0,
    de0 = de0,
    magnitude = magnitude,
)

/** Matches "20h59m17s +44°31′44″" — see [EkosRemoteController.parseCoordsToRaDecBox]. */
private val COORDS_REGEX = Regex("""(\d+)h(\d+)m(\d+)s\s+([+-])(\d+)°(\d+)′(\d+)″""")

/** J2000 RA hours → "20h59m17s". */
fun formatRaHours(hours: Double): String {
    val h = hours.toInt()
    val remMin = (hours - h) * 60
    val m = remMin.toInt()
    val sec = ((remMin - m) * 60).let { if (it < 0) 0.0 else it }
    return "%02dh%02dm%02ds".format(h, m, sec.toInt())
}

/** J2000 Dec degrees → "+44°31′44″". */
fun formatDecDegrees(deg: Double): String {
    val sign = if (deg < 0) "-" else "+"
    val a = kotlin.math.abs(deg)
    val d = a.toInt()
    val remMin = (a - d) * 60
    val m = remMin.toInt()
    val sec = ((remMin - m) * 60).let { if (it < 0) 0.0 else it }
    return "$sign%02d°%02d′%02d″".format(d, m, sec.toInt())
}
