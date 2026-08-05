package com.nocturne.session

import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.WireDevice
import com.nocturne.protocol.WireProperty
import com.nocturne.protocol.bitmaskToRoles
import com.nocturne.transport.EkosRemoteClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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

        // M3 §3/§4/§5 — profiles/trains/scheduler/astro apply here next.
        is EkosEvent.Profiles -> s
        is EkosEvent.Trains -> s
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
}

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
