package com.nocturne.session

import com.nocturne.protocol.WireProperty
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

// Folding one INDI property push into [AppState]: connection state, gain range, sensor size, block filters.

/**
 * Mirrors a `CONNECTION` switch push into the device's `connected` flag in
 * [AppState.wireDevices], so a device `get_devices` caught mid-startup (see
 * [EkosRemoteController.sendFollowUpCommands]) shows as connected once it is.
 */
internal fun AppState.withConnectionState(device: String, property: WireProperty): AppState {
    if (property !is WireProperty.Switch || property.name != "CONNECTION") return this
    val connected = property.switches.any { it.name == "CONNECT" && it.state == 1 }
    return copy(wireDevices = wireDevices?.map { if (it.name == device) it.copy(connected = connected) else it })
}

/** See [AppState.wireGainRangeByDevice]. */
internal fun AppState.withGainRange(device: String, property: WireProperty): AppState {
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
internal fun AppState.withCcdInfo(device: String, property: WireProperty): AppState {
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
internal fun AppState.withValidBlockFilters(): AppState {
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
internal fun AppState.withProperty(device: String, property: WireProperty): AppState {
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
