package com.nocturne.session

import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.WireDevice
import com.nocturne.protocol.WireProfile
import com.nocturne.protocol.WireProperty
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

    init {
        scope.launch {
            client.events.collect { event ->
                _state.update { s -> applyEvent(s, event) }
                sendFollowUpCommands(event)
            }
        }
        client.connect()
    }

    private fun applyEvent(s: SimState, event: EkosEvent): SimState = when (event) {
        // Connection progress is consumed straight from client.connectionStatus by the
        // connect screen / reconnect banner — not funneled through SimState at all.
        is EkosEvent.NewConnectionState -> s
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
        is EkosEvent.Trains -> s.copy(wireTrains = event.trains)
        is EkosEvent.SchedulerJobs -> s
        is EkosEvent.AstroSearchResult -> s
        is EkosEvent.AstroObjectsInfo -> s
        is EkosEvent.AstroObjectsRiseset -> s

        is EkosEvent.Raw -> s
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
        if (event !is EkosEvent.Devices) return
        event.devices.filter { it.connected }.forEach { device ->
            client.sendCommand(Commands.DEVICE_GET, buildJsonObject { put("device", device.name) })
            client.sendCommand(Commands.DEVICE_PROPERTY_SUBSCRIBE, buildJsonObject { put("device", device.name) })
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

private fun WireProfile.toRigProfile() = RigProfile(name = name, deviceKeys = drivers.values.flatten())

private fun WireDevice.toLiveDevice() = LiveDevice(name = name, connected = connected, roles = bitmaskToRoles(interfaceMask))

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
