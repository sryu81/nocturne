package com.nocturne.session

import com.nocturne.data.ConnectionRepository
import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.WireAstroObject
import com.nocturne.protocol.WireDevice
import com.nocturne.protocol.WireProfile
import com.nocturne.protocol.WireProperty
import com.nocturne.protocol.WireRiseset
import com.nocturne.protocol.WireScope
import com.nocturne.protocol.WireTrain
import com.nocturne.protocol.MODULE_KEY_BY_TRAIN_SETTING
import com.nocturne.protocol.SchedulerJobStatus
import com.nocturne.protocol.bitmaskToRoles
import com.nocturne.transport.EkosRemoteClient
import com.nocturne.transport.RigRebootClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/**
 * M3 session driver for a real EkosRemote connection. Runs no ticker — `t`
 * and every simulator-only field stay frozen at [SimState]'s defaults;
 * real telemetry arrives instead as [EkosEvent] pushes on [client], applied
 * here to the wire-mirror fields (`wireCaptureStatus`, `wireDevices`, ...).
 * The pure local-UI methods (sheet nav, block editing, prefs, ...) inherited
 * unchanged from [AbstractLocalSessionController] stay optimistic-local-only
 * — only the dozen or so methods overridden below actually send a wire
 * command, each following the same optimistic-then-reconcile pattern: fire
 * the command, then apply the same local `_state.update` the simulator would
 * have made, so the UI moves immediately rather than waiting on a push
 * round-trip; the later push (or refreshed `get_*` reply) reconciles it.
 */
class EkosRemoteController(
    private val client: EkosRemoteClient,
    private val scope: CoroutineScope,
    /** For persisting/loading the rig's reboot-daemon config (host/port already live on
     *  [client]; only the daemon's own port + shared token are stored here) — see
     *  [setRigRebootConfig]/[rebootRig]. Optional: pass null and reboot config just won't
     *  survive process death (used by tests that don't need it). */
    private val connectionRepo: ConnectionRepository? = null,
) : AbstractLocalSessionController() {

    /** Name a `profile_delete` was just sent for — checked against the next [EkosEvent.Profiles]. */
    private var pendingProfileDelete: String? = null

    // Plan tab search bookkeeping (M3 §4) — astro_get_objects_info/riseset can reply in
    // either order, so the latest of each is kept here and merged into wireSearchResults
    // whichever arrives; see buildSearchResults(). pendingSearchQuery is the free-text query
    // astro_search_objects itself has no field for (it only filters by type/alt/mag/FOV) — the
    // reply's flat name list is client-side substring-filtered against it before resolving.
    private var lastAstroObjects: List<WireAstroObject> = emptyList()
    private var lastRiseset: List<WireRiseset> = emptyList()
    private var pendingSearchQuery: String = ""

    /** Raw `train_get_profiles` reply (ordinal → train ID) — see [resolveModuleTrainAssignments]. */
    private var lastTrainProfileAssignments: Map<String, Int>? = null

    /**
     * Module → train-name for an optimistic [setModuleTrain] pick not yet confirmed by the
     * server's own snapshot — see [resolveModuleTrainAssignments] for why this exists.
     */
    private val pendingModuleTrainAssignments = mutableMapOf<String, String>()

    /**
     * Job/target name a `scheduler_add_jobs` sync was just sent for (M3 §5)
     * — matched against the next `scheduler_get_jobs` reply's `name` field
     * (the same string sent as `nameEdit`) to capture the new job's real
     * `wireIndex` and flip `synced`. There's no other correlation available
     * — `scheduler_add_jobs` has no direct reply of its own.
     */
    private var pendingSyncJobId: String? = null
    private var pendingSyncName: String? = null

    /**
     * A job/target name a `scheduler_start_job` should follow once the pending sync above
     * actually lands (matched by name against the next `scheduler_get_jobs` reply) — set right
     * alongside [pendingSyncJobId]/[pendingSyncName] but consumed separately in
     * [sendFollowUpCommands], since [applySchedulerJobs] already clears those two on match before
     * [sendFollowUpCommands] runs. Toggling the Scheduler on is only safe once the add is
     * confirmed real, not merely attempted — see [toggleJobRun]'s doc for why the add can fail.
     */
    private var pendingSchedulerStart: String? = null

    /**
     * Optimistic mirror of whether `scheduler_start_job` (a toggle, not exclusively "start" — see
     * its own doc in the command reference) currently has the real Scheduler running — no wire
     * push cleanly exposes this as a plain boolean, and blindly re-sending the toggle without
     * knowing its current state would risk starting it instead of stopping it. Set true right
     * after we send it to actually start a job; consulted (and cleared) before ever removing a
     * job, since **real Ekos silently refuses `scheduler_remove_jobs` while the Scheduler is
     * actively running** — confirmed live (2026-08-09) via the Scheduler's own log:
     * `"Cannot delete currently running job 'M 104'"`. Stays false under [SimulatedController].
     */
    private var schedulerRunning = false

    /**
     * [toggleJobRun]'s save→settings→add→get→start chain is split at this point because
     * `sequenceEdit` needs the *server-resolved* absolute path from the save reply, not a
     * client-guessed one — see [EkosEvent.SchedulerSaveSequenceFile]'s doc. Holds everything the
     * rest of the chain needs once that reply lands.
     */
    private data class PendingJobStart(val jobId: String, val targetName: String, val raDec: Pair<String, String>?, val trainName: String?)
    private var pendingJobStart: PendingJobStart? = null

    /** In-memory mirror of the reboot daemon's port/token — [SimState] only ever exposes
     *  whether a token is set ([SimState.rigRebootTokenSet]), never the token itself. */
    private var rigRebootPort: Int = 9001
    private var rigRebootToken: String? = null

    init {
        // SimState.jobs/activeJobId/lastActiveJobId/openBlockId default to DEFAULT_JOBS — one
        // job pre-marked running = true, a SimulatedController demo convenience. A real
        // connection has no session queued until the user actually adds one (Plan tab "Add to
        // sequence"), so start empty rather than showing a fabricated "already imaging" job
        // nobody started.
        _state.update {
            it.copy(
                jobs = emptyList(), activeJobId = null, jobSeq = 1, lastActiveJobId = null, openBlockId = null,
                isRealRig = true,
            )
        }
        scope.launch {
            client.events.collect { event ->
                _state.update { s -> applyEvent(s, event) }
                sendFollowUpCommands(event)
            }
        }
        client.connect()

        connectionRepo?.let { repo ->
            scope.launch {
                val saved = repo.current()
                rigRebootPort = saved.rebootPort
                rigRebootToken = saved.rebootToken?.ifBlank { null }
                _state.update {
                    it.copy(
                        rigRebootPort = saved.rebootPort,
                        rigRebootTokenSet = rigRebootToken != null,
                        rigRebootAvailable = rigRebootToken != null,
                    )
                }
            }
        }
    }

    private fun applyEvent(s: SimState, event: EkosEvent): SimState = when (event) {
        // Socket/dial progress itself is consumed straight from client.connectionStatus by
        // the connect screen / reconnect banner — not funneled through SimState. But `online`
        // specifically doubles as SimState.ekosRunning (Gear tab's Start/Stop Ekos, ReadyBanner,
        // etc. all read that) — SimState defaults ekosRunning = true (M1 fixture default, a
        // profile already running), which is wrong the moment a real socket opens and Ekos
        // hasn't started yet (see NocturneApp — the shell is now reachable at that point
        // specifically so Start Ekos is tappable). activeProfile mirrors whatever get_profiles
        // already told us is selected, only while actually online.
        is EkosEvent.NewConnectionState -> s.copy(
            ekosRunning = event.online,
            activeProfile = if (event.online) s.selectedProfile else null,
        )
        is EkosEvent.NewCaptureState -> s.copy(wireCaptureStatus = event.status)
        // Merges non-null fields rather than overwriting — see NewMountState's own doc: the
        // far-more-frequent coordinate-shaped push carries none of these 4 fields, and must not
        // null out a previously-known status/pierSide when it lands.
        is EkosEvent.NewMountState -> s.copy(
            wireMountStatus = event.status ?: s.wireMountStatus,
            wireMountTarget = event.target ?: s.wireMountTarget,
            wireMountSlewRate = event.slewRate ?: s.wireMountSlewRate,
            wireMountPierSide = event.pierSide ?: s.wireMountPierSide,
        )
        is EkosEvent.NewFocusState -> s.copy(wireFocusStatus = event.status)
        is EkosEvent.NewGuideState -> s.copy(wireGuideStatus = event.status)
        is EkosEvent.NewAlignState -> s.copy(wireAlignStatus = event.status)
        // Merges non-null fields rather than overwriting — real pushes are partial (see
        // NewPolarState's own doc), so a message-only or vector-only frame must not null out a
        // previously-known stage (or vice versa).
        is EkosEvent.NewPolarState -> s.copy(
            wirePolarStage = event.stage ?: s.wirePolarStage,
            wirePolarEnabled = event.enabled ?: s.wirePolarEnabled,
            wirePolarMessage = event.message ?: s.wirePolarMessage,
        )

        is EkosEvent.Devices -> s.copy(wireDevices = event.devices.map { it.toLiveDevice() })
        is EkosEvent.DeviceProperties -> event.properties.fold(s) { acc, prop ->
            acc.withProperty(event.device, prop).withConnectionState(event.device, prop).withCcdInfo(event.device, prop)
        }
        is EkosEvent.DeviceProperty -> s.withProperty(event.property.device, event.property)
            .withConnectionState(event.property.device, event.property)
            .withCcdInfo(event.property.device, event.property)

        // `state.profiles` is directly overwritten, not additive — get_profiles is always
        // the authoritative full list (plan §3). Diffed against the *old* s.profiles (still
        // pre-delete at this point) to catch profile_delete's documented silent refusal.
        is EkosEvent.Profiles -> {
            val attemptedDelete = pendingProfileDelete
            pendingProfileDelete = null
            val refused = attemptedDelete != null && event.profiles.any { it.name == attemptedDelete }
            s.copy(
                profiles = event.profiles.map { it.toRigProfile() },
                selectedProfile = event.selectedProfile ?: s.selectedProfile,
                profileDeleteRefused = if (refused) attemptedDelete else null,
                // Every driver label ever saved across any profile on this Pi, unioned — see
                // SimState.realDeviceOptions. Available pre-online, unlike wireDevices.
                wireKnownDrivers = event.profiles
                    .flatMap { it.drivers.entries }
                    .groupBy({ it.key }, { it.value })
                    .mapValues { (_, lists) -> lists.flatten().distinct() },
            )
        }
        // Nocturne's UI only ever models 2 train slots (Primary/Secondary) — a real rig can
        // have any number of named trains under a real name (not "Primary"/"Secondary"), so
        // this maps position 0/1 in the reply onto those two slots, best-effort. A rig with
        // only 1 real train leaves secondaryTrain at its fixture default, same as a rig with
        // more than 2 leaves the 3rd+ train invisible — known limitation, not silently wrong.
        is EkosEvent.Trains -> s.copy(
            wireTrains = event.trains,
            primaryTrain = event.trains.getOrNull(0)?.toTrainAssignment() ?: s.primaryTrain,
            secondaryTrain = event.trains.getOrNull(1)?.toTrainAssignment() ?: s.secondaryTrain,
            moduleTrainAssignments = resolveModuleTrainAssignments(s.moduleTrainAssignments, event.trains),
        )
        // train_get_profiles carries train IDs, not names — and can arrive before or after
        // train_get_all — so the raw ordinal→ID map is kept separately and re-resolved to
        // names against whichever wireTrains is current whichever reply lands second.
        is EkosEvent.TrainProfiles -> {
            lastTrainProfileAssignments = event.assignments
            s.copy(moduleTrainAssignments = resolveModuleTrainAssignments(s.moduleTrainAssignments, s.wireTrains))
        }
        is EkosEvent.Scopes -> s.copy(wireScopes = event.scopes.map { it.toScopeDef() })

        is EkosEvent.MountSettings -> s.copy(wireMountSettings = event.settings)
        // coolTarget seeded from the real Capture module's own setpoint the moment this reply
        // arrives — fixes cooler setpoint not matching real Ekos at the start of a session (see
        // WireCaptureSettings.cameraTemperatureN's doc for why this was missing). Safe to just
        // overwrite unconditionally: this reply only ever arrives once (the eager fetch at
        // connect, no reconcile-after-write per the established fire-and-forget norm), so there's
        // no risk of it stomping a later optimistic coolUp/coolDown edit.
        is EkosEvent.CaptureSettings -> s.copy(wireCaptureSettings = event.settings, coolTarget = event.settings.cameraTemperatureN)
        is EkosEvent.AlignSettings -> s.copy(wireAlignSettings = event.settings)
        is EkosEvent.GuideSettings -> s.copy(wireGuideSettings = event.settings)
        // focPos seeded from Ekos's own tracked position (absTicksSpin) the moment this reply
        // arrives — fixes Bench Focuser showing a real but different number than Ekos's own
        // Focus tab (see WireFocusSettings's doc). Safe unconditionally, same reasoning as the
        // coolTarget seed above: this reply only ever arrives once, before any optimistic
        // jogFocus edit could race against it.
        is EkosEvent.FocusSettings -> s.copy(wireFocusSettings = event.settings, focPos = event.settings.absTicksSpin)
        is EkosEvent.AstroAlmanac -> s.copy(wireDusk = event.dusk, wireDawn = event.dawn)
        is EkosEvent.AstroLocation -> s.copy(wireSiteTz = event.tz)

        is EkosEvent.SchedulerJobs -> applySchedulerJobs(s, event)
        // No state of its own — purely a trigger for sendFollowUpCommands to continue the
        // toggleJobRun chain once the resolved absolute path is known (see the event's own doc).
        is EkosEvent.SchedulerSaveSequenceFile -> s

        // A fresh astro_search_objects reply clears prior results (loading state) — the
        // follow-up astro_get_objects_info/riseset calls (sendFollowUpCommands below)
        // repopulate wireSearchResults once they land, merged via lastAstroObjects/lastRiseset
        // since the two replies can arrive in either order.
        is EkosEvent.AstroSearchResult -> {
            lastAstroObjects = emptyList()
            lastRiseset = emptyList()
            s.copy(wireSearchResults = emptyList())
        }
        is EkosEvent.AstroObjectsInfo -> {
            lastAstroObjects = event.objects
            s.copy(wireSearchResults = buildSearchResults())
        }
        is EkosEvent.AstroObjectsRiseset -> {
            lastRiseset = event.entries
            s.copy(wireSearchResults = buildSearchResults())
        }

        is EkosEvent.Raw -> s
    }

    private fun buildSearchResults(): List<Target> =
        lastAstroObjects.map { it.toTarget(lastRiseset.firstOrNull { rs -> rs.name == it.name }) }

    /**
     * Resolves the raw ordinal→trainID map against [trains] into module-key→train-name, merged
     * onto [previous] rather than replacing it wholesale.
     *
     * Confirmed live against a real rig: `train_get_profiles` can carry a *stale* train ID for a
     * module — left over from before its optical trains were recreated/renamed — that doesn't
     * match any entry in the current `train_get_all` list (e.g. Focus/Mount/Align pointing at a
     * long-gone id `8` while the real trains are now `11`/`12`). A wholesale replace would drop
     * that module from the map entirely (no selection ever shows, looks unselectable), *and* would
     * clobber a just-made optimistic pick from [setModuleTrain] the instant the next (still-stale,
     * since real Ekos's own persistence for that module may not have caught up yet)
     * `train_get_profiles` reply lands. Merging means: a module that resolves updates normally: one
     * that doesn't resolve simply keeps whatever [previous] already had — the optimistic value if
     * the user just picked one, or absent (shows as unselected, honestly — it isn't pointing at
     * either currently known train) if they haven't touched it yet.
     *
     * A resolved module isn't always fresher than that, though: since real Ekos's own persistence
     * for a module can still be catching up when *this very* `train_get_profiles` reply lands (see
     * [setModuleTrain]), `resolved` can carry a snapshot that's stale but still points at a real,
     * currently-known train — which isn't caught by the orphaned-ID case above, and would silently
     * overwrite the optimistic pick with the pre-edit one (looks like the tap "didn't take"/"snapped
     * back"). So a module with an outstanding pick in [pendingModuleTrainAssignments] keeps
     * that pick pinned over whatever `resolved` says, until `resolved` actually agrees with it —
     * at which point it's confirmed and cleared.
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
     * `scheduler_get_jobs` reply — always stored as-is, plus two derived effects:
     * 1. If a sync is pending ([pendingSyncJobId]), find the just-added job by
     *    the `nameEdit` we sent (matching [WireSchedulerJob.name]) and capture
     *    its `wireIndex`, flipping `synced = true`.
     * 2. Whichever synced job is currently `SCHEDJOB_BUSY` gets its blocks'
     *    `doneCount` approximated from the wire job's `completedCount` — a
     *    per-job total, not per-block, so it's waterfall-filled across blocks
     *    in order (real per-block progress needs `capture_get_sequences`,
     *    undocumented shape, deferred past M3 — see plan §"Protocol facts").
     */
    private fun applySchedulerJobs(s: SimState, event: EkosEvent.SchedulerJobs): SimState {
        var next = s.copy(wireSchedulerJobs = event.jobs)

        val syncJobId = pendingSyncJobId
        val syncName = pendingSyncName
        if (syncJobId != null && syncName != null) {
            val idx = event.jobs.indexOfLast { it.name == syncName }
            if (idx >= 0) {
                next = next.mapJob(syncJobId) { it.copy(synced = true, wireIndex = idx) }
                pendingSyncJobId = null
                pendingSyncName = null
            }
        }

        val busyIndex = event.jobs.indexOfFirst { it.state == SchedulerJobStatus.BUSY }
        if (busyIndex >= 0) {
            val busyJobId = next.jobs.firstOrNull { it.wireIndex == busyIndex }?.id
            if (busyJobId != null) {
                val completed = event.jobs[busyIndex].completedCount
                next = next.mapJob(busyJobId) { it.copy(blocks = distributeCompleted(it.blocks, completed)) }
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
     * Side effects that follow a push rather than mutate [SimState] directly
     * — right after `get_devices` arrives, fetch each connected device's full
     * property set once ([Commands.DEVICE_GET], non-compact by default, so
     * slider bounds come along) and subscribe to live updates
     * ([Commands.DEVICE_PROPERTY_SUBSCRIBE]) so [SimState.indiProps] stays
     * live from then on.
     *
     * A device that `get_devices` snapshotted as not-yet-connected still gets a narrow
     * CONNECTION-only subscribe here — Start Ekos brings INDI drivers up over real wall-clock
     * time, so `get_devices` (sent the instant Ekos itself goes online, see EkosRemoteClient)
     * can catch some devices before they've finished connecting, and nothing else ever
     * re-fetches the full list afterward. Without this, such a device stayed permanently stuck
     * showing "not connected" in the Gear tab even once it was alive in real Ekos. Once that
     * CONNECTION push reports connected (below, mirroring the branch above), the full
     * DEVICE_GET + subscribe fires for real.
     */
    private fun sendFollowUpCommands(event: EkosEvent) {
        when (event) {
            is EkosEvent.Devices -> event.devices.forEach { device ->
                if (device.connected) {
                    client.sendCommand(Commands.DEVICE_GET, buildJsonObject { put("device", device.name) })
                    client.sendCommand(Commands.DEVICE_PROPERTY_SUBSCRIBE, buildJsonObject { put("device", device.name) })
                } else {
                    client.sendCommand(Commands.DEVICE_PROPERTY_SUBSCRIBE, buildJsonObject {
                        put("device", device.name)
                        putJsonArray("properties") { add("CONNECTION") }
                    })
                }
            }
            is EkosEvent.DeviceProperty -> fetchFullDeviceIfNewlyConnected(event.property.device, event.property)
            is EkosEvent.DeviceProperties -> event.properties.forEach { fetchFullDeviceIfNewlyConnected(event.device, it) }
            // astro_search_objects carries no free-text field — the query is applied here,
            // client-side, against the flat name list before resolving the (usually much
            // smaller) matching subset's coordinates/riseset.
            is EkosEvent.AstroSearchResult -> {
                val q = pendingSearchQuery
                val matchingNames = if (q.isBlank()) event.names else event.names.filter { it.contains(q, ignoreCase = true) }
                if (matchingNames.isNotEmpty()) {
                    val namesPayload = buildJsonObject { putJsonArray("names") { matchingNames.forEach { add(it) } } }
                    client.sendCommand(Commands.ASTRO_GET_OBJECTS_INFO, namesPayload)
                    client.sendCommand(Commands.ASTRO_GET_OBJECTS_RISESET, namesPayload)
                }
            }
            // Continues toggleJobRun's chain — see PendingJobStart's doc for why this waits for
            // the resolved absolute path rather than sending sequenceEdit right after the save.
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
                    pendingSchedulerStart = pending.targetName
                    client.sendCommand(Commands.SCHEDULER_GET_JOBS) // confirms + captures wireIndex, see applySchedulerJobs
                }
            }
            // Only toggle the real Scheduler on once the add above is confirmed to have actually
            // taken (matched by name) — never blindly, in case it silently failed again.
            is EkosEvent.SchedulerJobs -> {
                val waitingName = pendingSchedulerStart
                if (waitingName != null && event.jobs.any { it.name == waitingName }) {
                    pendingSchedulerStart = null
                    client.sendCommand(Commands.SCHEDULER_START_JOB)
                    schedulerRunning = true
                }
            }
            else -> {}
        }
    }

    /**
     * Parses the "HHhMMmSSs ±DD°MM′SS″" coords string — the exact shape both the fixture
     * `TARGETS` catalog and real astro-search results share (`formatRaHours`/`formatDecDegrees`
     * above generate it literally) — into `raBox`/`decBox`'s own wire format. Confirmed live:
     * colon-separated `"HH:MM:SS"` input round-trips correctly through `scheduler_add_jobs`.
     * Returns null for anything that doesn't match (e.g. a free-text custom target) rather than
     * guessing — [toggleJobRun] simply omits `raBox`/`decBox` in that case.
     */
    private fun parseCoordsToRaDecBox(coords: String): Pair<String, String>? {
        val m = COORDS_REGEX.find(coords) ?: return null
        val (rh, rm, rs, sign, dd, dm, ds) = m.destructured
        return "$rh:$rm:$rs" to "$sign$dd:$dm:$ds"
    }

    /** See [sendFollowUpCommands]'s doc — a device that arrived not-yet-connected only gets a
     *  narrow CONNECTION subscribe up front; once that reports it's actually connected, fetch
     *  its full property set + subscribe to everything, same as an already-connected device got
     *  from the initial `get_devices` snapshot. */
    private fun fetchFullDeviceIfNewlyConnected(device: String, property: WireProperty) {
        if (property !is WireProperty.Switch || property.name != "CONNECTION") return
        if (property.switches.none { it.name == "CONNECT" && it.state == 1 }) return
        client.sendCommand(Commands.DEVICE_GET, buildJsonObject { put("device", device) })
        client.sendCommand(Commands.DEVICE_PROPERTY_SUBSCRIBE, buildJsonObject { put("device", device) })
    }

    // ── Devices / property sheets (M3 §2) ───────────────────────────────

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

    override fun setIndiText(deviceKey: String, propName: String, value: String) {
        val prop = currentIndiProp<IndiProperty.TextProp>(deviceKey, propName)
        if (prop != null && _state.value.wireDevices != null) {
            client.sendCommand(Commands.DEVICE_PROPERTY_SET, buildJsonObject {
                put("device", deviceKey)
                put("property", propName)
                putJsonArray("elements") { addJsonObject { put("name", prop.elementName); put("text", value) } }
            })
        }
        super.setIndiText(deviceKey, propName, value)
    }

    private inline fun <reified T : IndiProperty> currentIndiProp(deviceKey: String, propName: String): T? =
        (_state.value.indiProps[deviceKey] ?: DRIVER_INDI_PROPS[deviceKey] ?: emptyList())
            .firstOrNull { it.name == propName } as? T

    // ── Bench check (M3.2) ──────────────────────────────────────────────
    //
    // capture_preview/guide_capture/focus_in/out/mount_set_motion all have no direct reply
    // (plan §"Protocol facts" pattern repeats here) — status arrives via the new_capture_state/
    // new_guide_state pushes (already wired, M2) or the cooler/focuser's own subscribed
    // property pushes; none of it needs a bespoke reconciliation path. Cooler itself reuses
    // the existing generic setIndiNumber/indiNumber machinery (M3 §2) rather than a new one —
    // CCD_TEMPERATURE is just another Number property, confirmed live against a real
    // ToupTek ATR2600M: {"CCD_TEMPERATURE": [{"name":"CCD_TEMPERATURE_VALUE", perm RW}],
    // "CCD_COOLER_POWER": [{"name":"COOLER_POWER", perm RO}]} — writing CCD_TEMPERATURE_VALUE
    // is the real "set point" (most INDI CCD drivers auto-engage the cooler to chase it).

    /** No image data arrives on this channel — real preview bytes need the Media channel (M4, not built). Status still updates via new_capture_state. */
    override fun snapMain() {
        client.sendCommand(Commands.CAPTURE_PREVIEW)
        super.snapMain()
    }

    /** Same limitation as [snapMain] — real guide-frame bytes need the Media channel (M4). Status via new_guide_state. */
    override fun snapGuide() {
        client.sendCommand(Commands.GUIDE_CAPTURE)
        super.snapGuide()
    }

    /**
     * The real `CCD_TEMPERATURE` vector has no separate "target" element (confirmed live —
     * only `CCD_TEMPERATURE_VALUE`, the current reading, is exposed) — `super.coolUp/coolDown()`
     * still owns [SimState.coolTarget] as Nocturne's own client-side "last commanded" bookkeeping
     * (same field/semantics as [SimulatedController]'s fixture), it just now also pushes that
     * number to the real camera afterward. `CoolerCard` reads the live sensor value separately,
     * via [com.nocturne.session.indiNumber].
     *
     * Two *separate* real things need writing, confirmed live (user report: "setpoint in Ekos
     * wasn't synced" — it wasn't, this was the gap): the raw INDI `CCD_TEMPERATURE` device
     * property (what actually drives the cooler hardware — [setIndiNumber] below), and the
     * Capture module's own "Set Temperature" widget (`cameraTemperatureN`/`cameraTemperatureS`,
     * `capture_get/set_all_settings` — what the Ekos *desktop UI* actually displays as the
     * target). Writing only the device property, as the first cut of this did, changes the
     * hardware for real but leaves the number shown on the real Ekos screen stale at whatever
     * it was before — confirmed live: `cameraTemperatureN` sat at `0` even after the driver
     * itself had already reached -9.3°C from a direct property write. `cameraTemperatureS` is
     * the "enforce/use this" checkbox — set true unconditionally here since actively dialing a
     * target via Nocturne's cooler card only makes sense with it enabled.
     */
    override fun coolUp() {
        super.coolUp()
        pushCoolerSetpoint()
    }

    override fun coolDown() {
        super.coolDown()
        pushCoolerSetpoint()
    }

    private fun pushCoolerSetpoint() {
        val target = _state.value.coolTarget
        client.sendCommand(Commands.CAPTURE_SET_ALL_SETTINGS, buildJsonObject {
            put("cameraTemperatureN", target)
            put("cameraTemperatureS", true)
        })
        val camera = _state.value.primaryTrain.camera
        if (_state.value.wireDevices?.any { it.name == camera && it.connected } == true) {
            setIndiNumber(camera, "CCD_TEMPERATURE", target)
        }
    }

    override fun jogFocus(delta: Int) {
        client.sendCommand(if (delta > 0) Commands.FOCUS_OUT else Commands.FOCUS_IN, buildJsonObject { put("steps", kotlin.math.abs(delta)) })
        super.jogFocus(delta)
    }

    /**
     * `focus_start`/`focus_stop` — empty request/response each (confirmed against the reference);
     * progress arrives only via `new_focus_state` (already wired, `SimState.wireFocusStatus`).
     * Doesn't attempt to string-match the real status text — no documented enum of values exists
     * anywhere for it, only shown verbatim in the UI alongside [SimState.focusRunning]'s own
     * optimistic button state. Distinct from `runAutofocusNow` below (FocusSheet's older one-shot
     * fixture-only button) — both now fire the same real command, kept as separate methods since
     * their fixture behaviors differ and neither call site should have to know about the other.
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
     * FocusSheet's older, separate "Run autofocus now" button (dedicated Focus tab, not
     * Controls) had no real override at all before this — real rigs silently got
     * `AbstractLocalSessionController`'s fixture HFR-tick. Fixed for free here: same underlying
     * `focus_start` command as [startAutofocus] above, since real Ekos has no distinct "run once"
     * vs "start" concept — `focus_start` is the only real entry point either way.
     */
    override fun runAutofocusNow() {
        client.sendCommand(Commands.FOCUS_START)
        super.runAutofocusNow()
    }

    override fun setRate(index: Int) {
        client.sendCommand(Commands.MOUNT_SET_SLEW_RATE, buildJsonObject { put("rate", index) })
        super.setRate(index)
    }

    /** DPad's own key strings are already "N"/"S"/"E"/"W" — exactly mount_set_motion's `direction` values, no translation needed. */
    override fun setSlewDir(key: String) {
        client.sendCommand(Commands.MOUNT_SET_MOTION, buildJsonObject { put("direction", key); put("action", true) })
        super.setSlewDir(key)
    }

    /** Must read the in-flight direction *before* super.stopSlew() clears it — mount_set_motion stops one axis at a time, matching the DPad's one-direction-at-a-time model. */
    override fun stopSlew() {
        _state.value.slewDir?.let { dir ->
            client.sendCommand(Commands.MOUNT_SET_MOTION, buildJsonObject { put("direction", dir); put("action", false) })
        }
        super.stopSlew()
    }

    /** `mount_unpark` — no direct reply, watch new_mount_state (already wired, M2). */
    override fun unparkMount() {
        client.sendCommand(Commands.MOUNT_UNPARK)
        super.unparkMount()
    }
    /** `mount_park` — no direct reply, watch new_mount_state (already wired, M2). */
    override fun parkMount() {
        client.sendCommand(Commands.MOUNT_PARK)
        super.parkMount()
    }
    /**
     * `mount_set_tracking` — the universal Ekos-level command (works for any mount driver),
     * not a raw INDI property write. Real on/off state for display is read separately via
     * [SimState.mountTrackingOn] (the mount's own `TELESCOPE_TRACK_STATE`), same read/write
     * split as [parkMount]/[unparkMount] vs [SimState.mountParkedReal].
     */
    override fun setMountTracking(enabled: Boolean) {
        client.sendCommand(Commands.MOUNT_SET_TRACKING, buildJsonObject { put("enabled", enabled) })
        super.setMountTracking(enabled)
    }

    /** `align_solve` (`captureAndSolve()`) — no direct reply, watch new_align_state (already wired, M2). */
    override fun plateSolveHere() {
        client.sendCommand(Commands.ALIGN_SOLVE)
        super.plateSolveHere()
    }

    /**
     * Real `mount_goto_target` — resolved server-side via KStars' own fuzzy object-name lookup
     * (`findByName`), the same resolution the Scheduler's `nameEdit` gets for free (unlike
     * `scheduler_add_jobs`, which needs `raBox`/`decBox` set directly — see `toggleJobRun`'s own
     * doc for that near-miss; this command is documented to do its own name→coordinate
     * resolution). Not-found is a silent no-op server-side, no error surfaces back. No direct
     * reply — watch `new_mount_state`.
     */
    override fun gotoTarget(targetId: String) {
        val target = _state.value.findTarget(targetId) ?: return
        client.sendCommand(Commands.MOUNT_GOTO_TARGET, buildJsonObject { put("target", target.common.ifBlank { target.id }) })
        super.gotoTarget(targetId)
    }

    /**
     * Goto, then plate-solve with the Align module's solver-action temporarily switched to
     * `slewR` — confirmed live (`align_get_all_settings`): real Ekos exposes this as 3 mutually
     * exclusive bools (`nothingR`/`slewR`/`syncR`, default `nothingR`), and setting `slewR` makes
     * the server itself re-slew onto the solved position after `align_solve` — no client-side
     * pointing-offset math needed, unlike a naive read of the reference alone would suggest.
     * Restores whatever solver-action the user had before (from the last real
     * `align_get_all_settings` snapshot), not just `nothingR`, so this doesn't silently change
     * their normal Align-tab default.
     *
     * **Known simplification, not yet live-tuned**: this app doesn't decode a real "slew
     * finished" or "solve finished" signal (`new_mount_state`/`new_align_state`'s real status
     * vocabularies aren't enumerated anywhere in this codebase yet), so the steps are separated by
     * fixed heuristic delays rather than a real completion wait. Needs live testing to tune the
     * delays (or replace them with a real status-string wait) before trusting this for anything
     * time-sensitive — flagged, not silently assumed correct.
     */
    override fun gotoAndCenter(targetId: String) {
        val target = _state.value.findTarget(targetId) ?: return
        val original = _state.value.wireAlignSettings
        client.sendCommand(Commands.MOUNT_GOTO_TARGET, buildJsonObject { put("target", target.common.ifBlank { target.id }) })
        scope.launch {
            delay(8_000) // heuristic slew-settle wait — see this function's own doc
            client.sendCommand(Commands.ALIGN_SET_ALL_SETTINGS, buildJsonObject {
                put("nothingR", false); put("slewR", true); put("syncR", false)
            })
            delay(500)
            client.sendCommand(Commands.ALIGN_SOLVE)
            delay(15_000) // heuristic solve+reslew-settle wait
            client.sendCommand(Commands.ALIGN_SET_ALL_SETTINGS, buildJsonObject {
                put("nothingR", original?.nothingR ?: true)
                put("slewR", original?.slewR ?: false)
                put("syncR", original?.syncR ?: false)
            })
        }
        super.gotoAndCenter(targetId)
    }

    /**
     * `guide_start`/`guide_stop` — empty request/response each. `guide_stop` maps server-side to
     * `abort()` (confirmed against the reference) — a hard abort, not a distinct graceful-stop
     * concept, worth remembering before anyone expects a gentler pause here. Progress via
     * `new_guide_state` (already wired, `SimState.wireGuideStatus`), shown raw in the UI —
     * same no-string-matching approach as [startAutofocus].
     */
    override fun startGuiding() {
        client.sendCommand(Commands.GUIDE_START)
        super.startGuiding()
    }
    override fun stopGuiding() {
        client.sendCommand(Commands.GUIDE_STOP)
        super.stopGuiding()
    }

    /**
     * `polar_start`/`polar_stop` — no direct reply, watch `new_polar_state` (M2, hardened for
     * partial payloads — see [EkosEvent.NewPolarState]'s doc). Real PAH drives its own multi-slew
     * capture/solve/rotate sequence server-side once started — Stop must be assumed to interrupt
     * it mid-motion, there's no documented graceful-completion signal to wait for instead.
     */
    override fun startPolarAlign() {
        client.sendCommand(Commands.POLAR_START)
        super.startPolarAlign()
    }
    override fun stopPolarAlign() {
        client.sendCommand(Commands.POLAR_STOP)
        super.stopPolarAlign()
    }

    // ── Profiles / Optical Train (M3 §3) ────────────────────────────────

    override fun startProfile(name: String) {
        client.sendCommand(Commands.PROFILE_START, buildJsonObject { put("name", name) })
        super.startProfile(name) // optimistic; new_connection_state.online is the real confirmation
    }

    override fun stopProfile() {
        client.sendCommand(Commands.PROFILE_STOP)
        super.stopProfile()
    }

    override fun deleteProfile(name: String) {
        pendingProfileDelete = name
        client.sendCommand(Commands.PROFILE_DELETE, buildJsonObject { put("name", name) })
        // No optimistic local removal — get_profiles (always authoritative, see applyEvent)
        // arrives moments later and is the only source of truth here either way.
    }

    /**
     * The base implementation only opens the sheet + copies the profile
     * name — it never touches `selectedDeviceNames`/`devOff`, so the wizard
     * would otherwise show whatever picker state happened to be left over
     * from a previous edit, not this profile's real driver selections.
     * Reverses `RigProfile.drivers` (family → labels) back into Nocturne's
     * category keys via [CATEGORY_TO_DRIVER_FAMILY] so the picker actually
     * reflects the real profile before [finishSetup] can send an update
     * that's just this same reversal run forward again.
     */
    override fun editProfile(name: String) {
        super.editProfile(name)
        val p = _state.value.profiles.firstOrNull { it.name == name } ?: return
        update { s ->
            if (s.setupEditingName != name) return@update s // base declined (Ekos running) — nothing to seed
            val selected = DEVICES.associate { d ->
                // "guide" has no real family of its own — it's a second entry in drivers["CCDs"],
                // named only by the legacy `guider` field (see CATEGORY_TO_DRIVER_FAMILY's doc).
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
        // "guide" shares the "CCDs" family with "cam" (see CATEGORY_TO_DRIVER_FAMILY) — a guide
        // camera is a second entry in drivers["CCDs"], with the explicit "guider" field as the
        // only real signal for which entry that is. Confirmed via a live round-trip against the
        // "Simulators" profile: sending "guider" alongside "drivers" in the same profile_update
        // persisted both correctly — the docs' "legacy fields only consulted if drivers absent"
        // caveat does not apply to this field.
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
        // Deliberately not super.finishSetup() — its ekosRunning = true/activeProfile = ... is
        // SimulatedController fiction ("saving a profile starts a session"). This only ever sent
        // profile_add/update, never profile_start, so claiming Ekos is now running would be a
        // false optimistic update, not an accurate one. Just close the sheet; the real
        // profiles/selectedProfile list reconciles from the auto get_profiles reply moments later.
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
     * The real per-module assignment mechanism (`train_set`, confirmed
     * against `message.cpp`'s `processTrainCommands` — each module's own
     * `setOpticalTrain(name)` persists it to `ProfileSettings` for whichever
     * profile is currently active). No direct reply, so `train_get_profiles`
     * is re-sent to confirm — same fire-and-refresh pattern as everywhere
     * else on this wire.
     */
    override fun setModuleTrain(module: String, trainName: String) {
        pendingModuleTrainAssignments[module] = trainName
        client.sendCommand(Commands.TRAIN_SET, buildJsonObject { put("module", module); put("name", trainName) })
        client.sendCommand(Commands.TRAIN_GET_PROFILES)
        super.setModuleTrain(module, trainName) // optimistic; confirmed reply reconciles above
    }

    /**
     * Persists the reboot daemon's port + shared token (pasted from the Pi-side install
     * script's one-time printout) both in memory (for [rebootRig] to use immediately) and to
     * disk via [connectionRepo] (so it survives app restart without re-pasting).
     */
    override fun setRigRebootConfig(port: Int, token: String) {
        val trimmed = token.trim().ifBlank { null }
        rigRebootPort = port
        rigRebootToken = trimmed
        _state.update {
            it.copy(
                rigRebootPort = port,
                rigRebootTokenSet = trimmed != null,
                rigRebootAvailable = trimmed != null,
                rigRebootState = RigRebootState.IDLE,
                rigRebootError = null,
            )
        }
        if (trimmed != null) scope.launch { connectionRepo?.saveRebootConfig(port, trimmed) }
    }

    /**
     * Fires the reboot request over the daemon's separate HTTP channel (not the EkosRemote
     * wire — see [RigRebootClient]). A successful POST only means the Pi *accepted* the
     * request; the socket dropping shortly after is expected, and [EkosRemoteClient]'s own
     * backoff/reconnect (already fires on any close) picks the session back up once the Pi's
     * back — no separate "app restart" step needed on this end.
     */
    override fun rebootRig() {
        val token = rigRebootToken
        if (token == null) {
            _state.update { it.copy(rigRebootState = RigRebootState.FAILED, rigRebootError = "Set the reboot token first") }
            return
        }
        _state.update { it.copy(rigRebootState = RigRebootState.SENDING, rigRebootError = null) }
        scope.launch {
            val result = RigRebootClient(client.host, rigRebootPort, token).reboot()
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(rigRebootState = RigRebootState.SENT, rigRebootError = null) },
                    onFailure = { e -> s.copy(rigRebootState = RigRebootState.FAILED, rigRebootError = e.message ?: "request failed") },
                )
            }
        }
    }

    /**
     * Mount settings (M3.3, curated subset — see docs/M3.3-plan.md). Each setter sends
     * `mount_set_all_settings` with just the one changed field (server applies only keys
     * present in the map — confirmed against `Mount::setAllSettings` in the real source, so
     * this can't clobber the other 16 real fields Nocturne doesn't model) and applies the same
     * local update the simulator would via `super` — fire-and-forget, **no** immediate
     * `mount_get_all_settings` re-fetch, matching [pushCoolerSetpoint]'s own precedent.
     *
     * Confirmed live this *needs* to be fire-and-forget, not optimistic-then-reconcile like
     * [setModuleTrain]: sending the get immediately after the set raced on the app's real
     * (busy — other pushes interleaving) connection and lost a write — a toggle-off was
     * silently reverted back to on by the very re-fetch meant to confirm it, confirmed by an
     * independent probe showing the real value never actually changed. The identical
     * set-then-get pair worked instantly over an isolated low-traffic connection, so this is a
     * server-side command-ordering race under load, not a Nocturne logic bug — avoided
     * entirely by not re-fetching. The one-time `mount_get_all_settings` sent at connect
     * (`EkosRemoteClient`'s online burst) is the only fetch; these setters trust their own
     * optimistic write.
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
     * Camera settings (M3.3, curated subset). Same fire-and-forget shape as [sendMountSetting]
     * — `capture_set_all_settings` only touches the keys present in the map (confirmed against
     * `Camera::setAllSettings` in the real source, same partial-write safety already relied on
     * for the cooler-setpoint fix), and no immediate `capture_get_all_settings` re-fetch, per
     * the same command-ordering-race lesson documented on [sendMountSetting].
     */
    private fun sendCaptureSetting(field: String, value: JsonElement) {
        client.sendCommand(Commands.CAPTURE_SET_ALL_SETTINGS, buildJsonObject { put(field, value) })
    }

    override fun setCameraSaveDir(path: String) {
        sendCaptureSetting("fileDirectoryT", JsonPrimitive(path))
        super.setCameraSaveDir(path)
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

    /**
     * Align settings (M3.3 phase 3, curated subset). Same fire-and-forget shape as
     * [sendMountSetting]/[sendCaptureSetting] — `align_set_all_settings` only touches the keys
     * present in the map, and no immediate `align_get_all_settings` re-fetch, per the same
     * command-ordering-race lesson documented on [sendMountSetting].
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
    override fun setAlignBinning(binning: String) {
        sendAlignSetting("alignBinning", JsonPrimitive(binning))
        super.setAlignBinning(binning)
    }
    override fun setAlignAccuracyThreshold(arcsec: Double) {
        sendAlignSetting("alignAccuracyThreshold", JsonPrimitive(arcsec))
        super.setAlignAccuracyThreshold(arcsec)
    }

    /**
     * Primary camera's live preview capture parameters — `capture_preview` (Bench "Snap main")
     * takes no parameters of its own; real Ekos fires it using whatever the Capture module's own
     * `captureExposureN`/`captureGainN`/`captureBinHN`/`captureBinVN` are currently set to (see
     * [com.nocturne.protocol.WireCaptureSettings]'s doc). Same fire-and-forget shape as every
     * other module setting this session.
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
        // Both H and V in one shot — Nocturne only ever exposes a single square-binning control,
        // matching the Sequence block editor's own Binning row (one Int, not H/V separately).
        sendCaptureSetting("captureBinHN", JsonPrimitive(bin))
        sendCaptureSetting("captureBinVN", JsonPrimitive(bin))
        super.setCapturePreviewBinning(bin)
    }

    /**
     * Guide camera's live preview capture parameters — same reasoning as
     * [setCapturePreviewExposure] above, but for `guide_capture` (Bench "Snap guide") and the
     * Guide module's own `guideExposure`/`guideGain`/`guideBinning`.
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

    /**
     * Guide settings (M3.3 phase 4, curated subset) — same fire-and-forget shape as
     * [setAlignExposure] etc, reusing [sendGuideSetting] above (same command,
     * `guide_set_all_settings`, as the preview setters) rather than a second helper.
     */
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

    /**
     * Focus settings (M3.3 phase 6, curated subset) — same fire-and-forget shape as
     * [setGuideAccuracyThreshold] etc. `applyEvent`'s `FocusSettings` arm needs no change: it
     * already does a full-struct `s.copy(wireFocusSettings = event.settings, ...)`, which picks
     * up these new fields automatically.
     */
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

    /**
     * `train_add`/`train_update` payload shape is undocumented (plan §"Protocol
     * facts") — sends every field `train_get_all` reports back, keyed by [id]
     * when a matching real train is already known, else falls back to
     * `train_add`. Either way a `train_get_all` auto-push reconciles whatever
     * the server actually accepted.
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

    // ── Scopes catalog (M3.1) — separate from Optical Trains, see plan §"Protocol facts" ──

    override fun addScope(name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int) {
        client.sendCommand(Commands.SCOPE_ADD, buildJsonObject {
            put("model", name); put("vendor", vendor); put("type", type)
            put("focal_length", focalMm.toDouble()); put("aperture", apertureMm.toDouble())
        })
        super.addScope(name, vendor, type, focalMm, apertureMm) // optimistic; get_scopes auto-reply reconciles
    }

    override fun updateScope(id: String, name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int) {
        // Only a real (wire-known) scope has a server id to update against — a still-local-only
        // scope (added before this connection, or under SimulatedController) has no wire
        // counterpart yet, so falls back to add instead of an update with a made-up id.
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

    // ── Plan tab astro_* search (M3 §4) ─────────────────────────────────

    override fun setQuery(text: String) {
        super.setQuery(text)
        runSearch()
    }

    override fun toggleChip(index: Int) {
        super.toggleChip(index)
        runSearch()
    }

    /**
     * `astro_search_objects` has no free-text field — only `type`/`direction`/
     * `maxMagnitude`/`minAlt`/`minDuration`/`minFOV` — so [SimState.chips]
     * (which map fairly directly) drive the wire call, while the typed query
     * is applied client-side once names come back ([sendFollowUpCommands]).
     * `type` is a single-value filter server-side (`SkyObject::TYPE`), so the
     * "Narrowband" chip — which locally means "any of Ha/SHO/OIII" — can only
     * approximate to one type (`GASEOUS_NEBULA`); a real multi-type OR isn't
     * expressible in one call.
     */
    private fun runSearch() {
        val s = _state.value
        pendingSearchQuery = s.query.trim()
        client.sendCommand(Commands.ASTRO_SEARCH_OBJECTS, buildJsonObject {
            put("type", if (s.chips.contains(2)) 5 else 8) // 5=GASEOUS_NEBULA, 8=GALAXY (default)
            put("minAlt", if (s.chips.contains(1)) 40.0 else 15.0)
            put("minDuration", if (s.chips.contains(0)) 3600 else 0)
            put("minFOV", if (s.chips.contains(3)) 1.0 else 0.0)
        })
    }

    // ── Sequence tab — Scheduler + .esq (M3 §5) ─────────────────────────

    /**
     * Real Ekos has no "edit a synced job" wire primitive — `scheduler_add_jobs`
     * only ever adds from the Scheduler's current form state, there's no
     * update. So: starting a not-yet-synced job writes its `.esq`, points the
     * Scheduler's form at it, and adds it (`synced` flips true once
     * `scheduler_get_jobs` confirms it — see [applySchedulerJobs]). Stopping a
     * synced job removes it from the real Scheduler entirely and clears
     * `synced`/`wireIndex` locally, unlocking the block editor again — the
     * only way to "edit" a synced job is stop, edit, restart fresh.
     *
     * **Confirmed live (2026-08-09) that the original minimum field set silently added nothing**:
     * `scheduler_add_jobs` needs `raBox`/`decBox` populated directly — typing a name into
     * `nameEdit` over the wire doesn't trigger the GUI's own name-resolution lookup the way a
     * human clicking "Find" would, so the job's coordinates stayed blank and the add was silently
     * a no-op (`scheduler_get_jobs` came back `{"jobs": []}` every time). `opticalTrainCombo` left
     * at its default `"--"` is a second, independent way the same add can fail. Fixed: `raBox`/
     * `decBox` from the target's own coords (parsed, see [parseCoordsToRaDecBox]) and
     * `opticalTrainCombo` from the capture module's real assigned train. The rest of the chain
     * (settings/add/get) is deferred to [sendFollowUpCommands]'s `SchedulerSaveSequenceFile` arm
     * because `sequenceEdit` also needs the *server-resolved* absolute path from that reply, not
     * the bare relative filename saved here (confirmed live: a relative `sequenceEdit` value
     * produces a broken, unresolved `file:` URI, not a home-relative one like the save itself).
     * Finally, real Ekos never actually *runs* an added job on its own — `scheduler_start_job`
     * (`SCHEDULER_START_JOB`) is a separate toggle that was never sent at all before this fix, so
     * even a successfully-added job would just sit there; now sent once [pendingSchedulerStart]'s
     * name is confirmed present in a `scheduler_get_jobs` reply (not sent blindly right after
     * `scheduler_add_jobs`, in case the add silently failed again for some other reason).
     */
    override fun toggleJobRun(jobId: String) {
        val job = _state.value.jobs.firstOrNull { it.id == jobId } ?: return
        if (job.running) {
            if (job.synced && job.wireIndex != null) {
                stopSchedulerThenRemove(job.wireIndex)
            }
            super.toggleJobRun(jobId)
            update { s -> s.mapJob(jobId) { it.copy(synced = false, wireIndex = null) } }
            return
        }

        val s = _state.value
        val target = s.findTarget(job.targetId)
        val targetName = target?.common ?: target?.id ?: job.targetId
        val path = "nocturne_$jobId.esq"
        client.sendCommand(Commands.SCHEDULER_SAVE_SEQUENCE_FILE, buildJsonObject {
            put("filedata", EsqWriter.write(job, targetName, s.afRefocusMin, s.afTempDeltaC))
            put("path", path)
        })
        pendingJobStart = PendingJobStart(
            jobId = jobId,
            targetName = targetName,
            raDec = target?.coords?.let { parseCoordsToRaDecBox(it) },
            trainName = s.moduleTrainAssignments?.get("capture") ?: s.wireTrains?.firstOrNull()?.name,
        )
        super.toggleJobRun(jobId) // optimistic running=true; synced flips true once the chain below confirms
    }

    override fun removeJob(jobId: String) {
        val job = _state.value.jobs.firstOrNull { it.id == jobId }
        if (job?.synced == true && job.wireIndex != null) {
            stopSchedulerThenRemove(job.wireIndex)
        }
        super.removeJob(jobId)
    }

    /** See [schedulerRunning]'s doc — a running real Scheduler must be toggled off first. */
    private fun stopSchedulerThenRemove(wireIndex: Int) {
        if (schedulerRunning) {
            client.sendCommand(Commands.SCHEDULER_START_JOB) // toggle: stops it, since it's running
            schedulerRunning = false
        }
        client.sendCommand(Commands.SCHEDULER_REMOVE_JOBS, buildJsonObject { put("index", wireIndex) })
    }
}

private fun WireProfile.toRigProfile() = RigProfile(name = name, deviceKeys = drivers.values.flatten(), drivers = drivers, guider = guider)

private fun WireDevice.toLiveDevice() = LiveDevice(name = name, connected = connected, roles = bitmaskToRoles(interfaceMask))

/**
 * `scope_add`/`scope_update`'s request has no separate `name` field (only
 * `model`/`vendor`/`type`/`aperture`/`focal_length` — plan §"Protocol facts")
 * — [EkosRemoteController.addScope]/[updateScope] send the app's single
 * name field as `model`. On the way back, `name` (which real Ekos returns
 * alongside `model` in [WireScope]) is what a train's `scope` field actually
 * references (confirmed live: `"scope":"Field APO"` matched `name`, not
 * `model`), so decoding prefers it, falling back to `model` if ever blank.
 */
private fun WireScope.toScopeDef() = ScopeDef(
    id = id,
    name = name.ifBlank { model },
    vendor = vendor,
    type = type,
    focalMm = focal_length.roundToInt(),
    apertureMm = aperture.roundToInt(),
)

/** Blank string fields (unassigned role, per `train_get_all`'s own convention) become "None". `internal`, not `private`, so [EkosEventCodecTest]-style regression tests can call it directly. */
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
 * Mirrors a `CONNECTION` switch push into [SimState.wireDevices]'s `connected` flag for the
 * matching device — the only place other than the initial `get_devices` snapshot (and the
 * optimistic flip in [EkosRemoteController.toggleDevice]) that field ever changes. Needed
 * because a device `get_devices` caught mid-startup (see [EkosRemoteController.sendFollowUpCommands])
 * otherwise stays shown as disconnected forever even after it finishes connecting for real.
 */
private fun SimState.withConnectionState(device: String, property: WireProperty): SimState {
    if (property !is WireProperty.Switch || property.name != "CONNECTION") return this
    val connected = property.switches.any { it.name == "CONNECT" && it.state == 1 }
    return copy(wireDevices = wireDevices?.map { if (it.name == device) it.copy(connected = connected) else it })
}

/**
 * Captures a real camera's `CCD_INFO` vector into [SimState.wireCcdInfoByDevice] for Plan tab's
 * Framing card — see that field's doc for why this reads all three elements directly instead of
 * going through the generic (lossy, single-element) [withProperty]/[indiProps] path.
 */
private fun SimState.withCcdInfo(device: String, property: WireProperty): SimState {
    if (property !is WireProperty.Number || property.name != "CCD_INFO") return this
    val maxX = property.numbers.firstOrNull { it.name == "CCD_MAX_X" }?.value?.roundToInt() ?: return this
    val maxY = property.numbers.firstOrNull { it.name == "CCD_MAX_Y" }?.value?.roundToInt() ?: return this
    val pixelUm = property.numbers.firstOrNull { it.name == "CCD_PIXEL_SIZE" }?.value ?: return this
    return copy(wireCcdInfoByDevice = wireCcdInfoByDevice + (device to CcdInfo(maxX, maxY, pixelUm)))
}

/** Merges one real property-vector push into [SimState.indiProps], keyed by real device name. */
private fun SimState.withProperty(device: String, property: WireProperty): SimState {
    val current = indiProps[device] ?: emptyList()
    val existing = current.firstOrNull { it.name == property.name }
    val updated = property.toIndiProperty(existing)
    val next = if (existing != null) current.map { if (it.name == property.name) updated else it } else current + updated
    return copy(indiProps = indiProps + (device to next))
}

/**
 * Translates one wire property vector into the app's [IndiProperty] shape,
 * merging over [existing] so a compact push (which omits label/group/bounds)
 * doesn't blank out what an earlier non-compact `device_get` already
 * populated — only the live value/state fields are guaranteed fresh on every
 * push; everything else falls back to what's already known, then to a bare
 * default.
 */
private fun WireProperty.toIndiProperty(existing: IndiProperty?): IndiProperty = when (this) {
    is WireProperty.Switch -> {
        val ex = existing as? IndiProperty.SwitchProp
        val selectedIndex = switches.indexOfFirst { it.state == 1 }
        IndiProperty.SwitchProp(
            name = name,
            label = label ?: ex?.label ?: name,
            group = group ?: ex?.group ?: "",
            options = switches.map { it.label ?: it.name },
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
        )
    }
    is WireProperty.Text -> {
        val ex = existing as? IndiProperty.TextProp
        val el = texts.firstOrNull()
        IndiProperty.TextProp(
            name = name,
            label = label ?: ex?.label ?: name,
            group = group ?: ex?.group ?: "",
            value = el?.text ?: ex?.value ?: "",
            elementName = el?.name ?: ex?.elementName ?: name,
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
 * Translates one `astro_get_objects_info` entry (+ its matching riseset entry,
 * if that reply has landed yet) into the app's [Target] shape. `size`/`band`/
 * `usable`/`fov` stay null — the wire has no equivalent for the fixture
 * catalog's precomputed display values.
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
private fun formatRaHours(hours: Double): String {
    val h = hours.toInt()
    val remMin = (hours - h) * 60
    val m = remMin.toInt()
    val sec = ((remMin - m) * 60).let { if (it < 0) 0.0 else it }
    return "%02dh%02dm%02ds".format(h, m, sec.toInt())
}

/** J2000 Dec degrees → "+44°31′44″". */
private fun formatDecDegrees(deg: Double): String {
    val sign = if (deg < 0) "-" else "+"
    val a = kotlin.math.abs(deg)
    val d = a.toInt()
    val remMin = (a - d) * 60
    val m = remMin.toInt()
    val sec = ((remMin - m) * 60).let { if (it < 0) 0.0 else it }
    return "$sign%02d°%02d′%02d″".format(d, m, sec.toInt())
}
