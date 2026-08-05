package com.nocturne.session

import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.WireAstroObject
import com.nocturne.protocol.WireDevice
import com.nocturne.protocol.WireProfile
import com.nocturne.protocol.WireProperty
import com.nocturne.protocol.WireRiseset
import com.nocturne.protocol.WireTrain
import com.nocturne.protocol.SchedulerJobStatus
import com.nocturne.protocol.bitmaskToRoles
import com.nocturne.transport.EkosRemoteClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
    scope: CoroutineScope,
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

    /**
     * Job/target name a `scheduler_add_jobs` sync was just sent for (M3 §5)
     * — matched against the next `scheduler_get_jobs` reply's `name` field
     * (the same string sent as `nameEdit`) to capture the new job's real
     * `wireIndex` and flip `synced`. There's no other correlation available
     * — `scheduler_add_jobs` has no direct reply of its own.
     */
    private var pendingSyncJobId: String? = null
    private var pendingSyncName: String? = null

    init {
        // SimState.jobs/activeJobId/lastActiveJobId/openBlockId default to DEFAULT_JOBS — one
        // job pre-marked running = true, a SimulatedController demo convenience. A real
        // connection has no session queued until the user actually adds one (Plan tab "Add to
        // sequence"), so start empty rather than showing a fabricated "already imaging" job
        // nobody started.
        _state.update {
            it.copy(jobs = emptyList(), activeJobId = null, jobSeq = 1, lastActiveJobId = null, openBlockId = null)
        }
        scope.launch {
            client.events.collect { event ->
                _state.update { s -> applyEvent(s, event) }
                sendFollowUpCommands(event)
            }
        }
        client.connect()
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
        is EkosEvent.NewMountState -> s.copy(
            wireMountStatus = event.status,
            wireMountTarget = event.target,
            wireMountSlewRate = event.slewRate,
            wireMountPierSide = event.pierSide,
        )
        is EkosEvent.NewFocusState -> s.copy(wireFocusStatus = event.status)
        is EkosEvent.NewGuideState -> s.copy(wireGuideStatus = event.status)
        is EkosEvent.NewAlignState -> s.copy(wireAlignStatus = event.status)
        is EkosEvent.NewPolarState -> s.copy(wirePolarStage = event.stage)

        is EkosEvent.Devices -> s.copy(wireDevices = event.devices.map { it.toLiveDevice() })
        is EkosEvent.DeviceProperties -> event.properties.fold(s) { acc, prop -> acc.withProperty(event.device, prop) }
        is EkosEvent.DeviceProperty -> s.withProperty(event.property.device, event.property)

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
        )
        is EkosEvent.SchedulerJobs -> applySchedulerJobs(s, event)

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
     */
    private fun sendFollowUpCommands(event: EkosEvent) {
        when (event) {
            is EkosEvent.Devices -> event.devices.filter { it.connected }.forEach { device ->
                client.sendCommand(Commands.DEVICE_GET, buildJsonObject { put("device", device.name) })
                client.sendCommand(Commands.DEVICE_PROPERTY_SUBSCRIBE, buildJsonObject { put("device", device.name) })
            }
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
            else -> {}
        }
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
                val label = CATEGORY_TO_DRIVER_FAMILY[d.key]?.let { family -> p.drivers[family]?.firstOrNull() }
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
        val drivers: Map<String, List<String>> = DEVICES.filter { it.key !in s.devOff }
            .groupBy({ CATEGORY_TO_DRIVER_FAMILY[it.key] ?: "Aux" }, { s.selectedDeviceNames[it.key] ?: it.name })
        val payload = buildJsonObject {
            put("name", s.profileName)
            put("auto_connect", true)
            put("port_selector", false)
            put("mode", "local")
            put("use_web_manager", false)
            put("guiding", 0)
            putJsonObject("drivers") { drivers.forEach { (family, labels) -> putJsonArray(family) { labels.forEach { add(it) } } } }
        }
        client.sendCommand(if (s.setupEditingName != null) Commands.PROFILE_UPDATE else Commands.PROFILE_ADD, payload)
        super.finishSetup() // optimistic; get_profiles auto-reply reconciles
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
     */
    override fun toggleJobRun(jobId: String) {
        val job = _state.value.jobs.firstOrNull { it.id == jobId } ?: return
        if (job.running) {
            if (job.synced && job.wireIndex != null) {
                client.sendCommand(Commands.SCHEDULER_REMOVE_JOBS, buildJsonObject { put("index", job.wireIndex) })
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
        // Minimum viable field set (plan §"Protocol facts") — not the full ~40-field scheduler form.
        client.sendCommand(Commands.SCHEDULER_SET_ALL_SETTINGS, buildJsonObject {
            put("nameEdit", targetName)
            put("sequenceEdit", path)
            put("startupTimeConditionR", 0) // ASAP
            put("schedulerAltitude", false)
        })
        client.sendCommand(Commands.SCHEDULER_ADD_JOBS)
        pendingSyncJobId = jobId
        pendingSyncName = targetName
        client.sendCommand(Commands.SCHEDULER_GET_JOBS) // confirms + captures wireIndex, see applySchedulerJobs
        super.toggleJobRun(jobId) // optimistic running=true; synced flips true once the reply above lands
    }

    override fun removeJob(jobId: String) {
        val job = _state.value.jobs.firstOrNull { it.id == jobId }
        if (job?.synced == true && job.wireIndex != null) {
            client.sendCommand(Commands.SCHEDULER_REMOVE_JOBS, buildJsonObject { put("index", job.wireIndex) })
        }
        super.removeJob(jobId)
    }
}

/**
 * `ProfileInfo.drivers` family keys (`EkosRemote-Command-Reference.md` §3
 * confirms `"Telescopes"`/`"CCDs"`/`"Focusers"`/`"Filter Wheels"` only — the
 * rest are this app's best-effort guess at the remaining `INDI::DriverInterface`
 * family names Ekos's own driver-selection combo uses, unverified against
 * source. `finishSetup`'s wire payload is fire-and-refresh either way
 * (auto-replies with a fresh `get_profiles`), so a wrong family key here
 * just means that device lands under "Aux" until corrected in real Ekos.
 */
private val CATEGORY_TO_DRIVER_FAMILY = mapOf(
    "mount" to "Telescopes",
    "cam" to "CCDs",
    "guide" to "Guiders",
    "efw" to "Filter Wheels",
    "focus" to "Focusers",
    "rotator" to "Rotators",
    "dome" to "Domes",
    "weather" to "Weather",
)

private fun WireProfile.toRigProfile() = RigProfile(name = name, deviceKeys = drivers.values.flatten(), drivers = drivers)

private fun WireDevice.toLiveDevice() = LiveDevice(name = name, connected = connected, roles = bitmaskToRoles(interfaceMask))

/** Blank string fields (unassigned role, per `train_get_all`'s own convention) become "None". */
private fun WireTrain.toTrainAssignment() = TrainAssignment(
    mount = mount.ifBlank { "None" },
    camera = camera.ifBlank { "None" },
    rotator = rotator.ifBlank { "None" },
    guideVia = guider.ifBlank { "None" },
    dustCap = dustcap.ifBlank { "None" },
    scope = scope.ifBlank { "None" },
    filterWheel = filterwheel.ifBlank { "None" },
    focuser = focuser.ifBlank { "None" },
    reducer = reducer.toDoubleOrNull() ?: 1.0,
    lightBox = lightbox.ifBlank { "None" },
    adaptiveOptics = adaptiveoptics.ifBlank { "None" },
)

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
