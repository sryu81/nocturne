package com.nocturne.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * M1 session driver. Reproduces the prototype's `data-dc-script` behavior
 * exactly: a 1 s ticker advances `t` (cooler ramp, PA knob easing), and every
 * widget derives from [SimState] — the wiggle trace generator, night-arc
 * fraction, RMS wobble, flip countdown, keep/cut toggling.
 */
class SimulatedController(private val scope: CoroutineScope) : SessionController {

    private val _state = MutableStateFlow(SimState())
    override val state: StateFlow<SimState> = _state.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(1000)
                _state.update { s -> tick(s) }
            }
        }
    }

    private fun tick(s: SimState): SimState {
        val d = s.coolTarget - s.coolNow
        val step = d.coerceIn(-1.4, 1.4)
        val coolNow = if (abs(d) < 0.05) s.coolTarget else s.coolNow + step

        val paAdjust = s.sheet == SheetType.PA && s.paStep == 2 && s.t % PA_SECS[s.paRate] == 0
        return s.copy(
            t = s.t + 1,
            coolNow = coolNow,
            paAlt = if (paAdjust) ease(s.paAlt) else s.paAlt,
            paAz = if (paAdjust) ease(s.paAz) else s.paAz,
        )
    }

    private inline fun update(crossinline f: (SimState) -> SimState) {
        _state.update(f)
    }

    override fun openSheet(sheet: SheetType) {
        update { s ->
            when (sheet) {
                SheetType.PA -> s.copy(sheet = sheet, paStep = 0)
                SheetType.SETUP -> if (s.ekosRunning) s else s.copy(sheet = sheet)
                else -> s.copy(sheet = sheet)
            }
        }
    }

    override fun openDevice(key: String) = update { it.copy(sheet = SheetType.DEVICE, deviceKey = key, deviceOrigin = it.sheet) }

    override fun closeSheet() = update { s ->
        if (s.sheet == SheetType.DEVICE && s.deviceOrigin != null) {
            s.copy(sheet = s.deviceOrigin, deviceOrigin = null)
        } else {
            s.copy(sheet = null, deviceOrigin = null)
        }
    }

    override fun selectTab() = update { it.copy(sheet = null) }

    override fun toggleRun() = update { it.copy(running = !it.running) }

    override fun toggleBlock(id: String) = update { s ->
        s.copy(openBlockId = if (s.openBlockId == id) null else id)
    }

    override fun addBlock() = update { s ->
        val used = s.blocks.map { it.filter }.toSet()
        val filter = FILTER_CYCLE.firstOrNull { it !in used } ?: FILTER_CYCLE[s.blockSeq % FILTER_CYCLE.size]
        val block = Block(
            id = "b${s.blockSeq}", filter = filter, exposureSec = 300, subCount = 10, doneCount = 0,
            gain = 100, offset = 50, binning = 1, ditherEvery = 2,
        )
        s.copy(blocks = s.blocks + block, blockSeq = s.blockSeq + 1, openBlockId = block.id)
    }

    override fun removeBlock(id: String) = update { s ->
        s.copy(
            blocks = s.blocks.filter { it.id != id },
            openBlockId = if (s.openBlockId == id) null else s.openBlockId,
        )
    }

    override fun moveBlock(id: String, toIndex: Int) = update { s ->
        val list = s.blocks.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from == -1) return@update s
        val clamped = toIndex.coerceIn(0, list.lastIndex)
        if (from == clamped) return@update s
        val item = list.removeAt(from)
        list.add(clamped, item)
        s.copy(blocks = list)
    }

    override fun cycleBlockFilter(id: String) = update { s ->
        s.mapBlock(id) { b ->
            val i = FILTER_CYCLE.indexOf(b.filter)
            b.copy(filter = FILTER_CYCLE[(i + 1).mod(FILTER_CYCLE.size)])
        }
    }

    override fun setBlockExposure(id: String, sec: Int) = update { s -> s.mapBlock(id) { it.copy(exposureSec = sec.coerceIn(1, 3600)) } }
    override fun setBlockSubCount(id: String, count: Int) = update { s -> s.mapBlock(id) { it.copy(subCount = count.coerceIn(0, 999)) } }
    override fun setBlockGain(id: String, gain: Int) = update { s -> s.mapBlock(id) { it.copy(gain = gain.coerceIn(0, 600)) } }
    override fun setBlockOffset(id: String, offset: Int) = update { s -> s.mapBlock(id) { it.copy(offset = offset.coerceIn(0, 255)) } }
    override fun setBlockBinning(id: String, bin: Int) = update { s -> s.mapBlock(id) { it.copy(binning = bin) } }
    override fun setBlockDither(id: String, n: Int) = update { s -> s.mapBlock(id) { it.copy(ditherEvery = n) } }

    // Stub: flips the local flag only. Actually firing `focus_start` when this
    // block becomes active needs real capture-state pushes — M2/M3.
    override fun toggleBlockForceAf(id: String) = update { s -> s.mapBlock(id) { it.copy(forceAfOnStart = !it.forceAfOnStart) } }

    override fun setAutofocusRefocusMin(min: Int) = update { it.copy(afRefocusMin = min.coerceIn(0, 240)) }
    override fun setAutofocusTempDelta(deltaC: Double) = update { it.copy(afTempDeltaC = deltaC.coerceIn(0.0, 10.0)) }
    override fun toggleAutofocusOnFilterChange() = update { it.copy(afOnFilterChange = !it.afOnFilterChange) }

    override fun toggleChip(index: Int) = update { s ->
        s.copy(chips = if (s.chips.contains(index)) s.chips.filter { it != index } else s.chips + index)
    }

    override fun setQuery(text: String) = update { it.copy(query = text) }

    override fun clearQuery() = update { it.copy(query = "") }

    override fun selectTarget(id: String) = update { it.copy(targetId = id) }

    override fun togglePref(key: String) = update { s ->
        s.copy(prefs = s.prefs + (key to !(s.prefs[key] ?: false)))
    }

    override fun toggleCut(id: String) = update { s ->
        s.copy(cut = if (s.cut.contains(id)) s.cut - id else s.cut + id)
    }

    override fun toggleDevice(key: String) = update { s ->
        s.copy(devOff = if (s.devOff.contains(key)) s.devOff - key else s.devOff + key)
    }

    override fun selectDeviceName(key: String, name: String) = update { s ->
        s.copy(
            selectedDeviceNames = s.selectedDeviceNames + (key to name),
            devOff = if (name == "None") s.devOff + key else s.devOff - key,
        )
    }

    override fun setTrainRole(slot: TrainSlot, role: TrainRole, value: String) = update { s ->
        s.withTrain(slot, s.train(slot).with(role, value))
    }

    override fun setTrainReducer(slot: TrainSlot, value: Double) = update { s ->
        s.withTrain(slot, s.train(slot).copy(reducer = value.coerceIn(0.1, 3.0)))
    }

    override fun snapMain() = update { it.copy(snappedMain = true) }
    override fun snapGuide() = update { it.copy(snappedGuide = true) }

    override fun jogFocus(delta: Int) = update { s ->
        s.copy(focPos = (s.focPos + delta).coerceIn(0, 62000))
    }

    override fun setRate(index: Int) = update { it.copy(rate = index) }

    override fun setSlewDir(key: String) = update { s ->
        s.copy(slewDir = if (s.slewDir == key) null else key)
    }

    override fun stopSlew() = update { it.copy(slewDir = null) }

    override fun coolUp() = update { it.copy(coolTarget = (it.coolTarget + 1).coerceAtMost(20.0)) }
    override fun coolDown() = update { it.copy(coolTarget = (it.coolTarget - 1).coerceAtLeast(-25.0)) }

    override fun openPa() = update { it.copy(sheet = SheetType.PA, paStep = 0) }
    override fun paNext() = update { it.copy(paStep = minOf(2, it.paStep + 1)) }
    override fun setPaRate(index: Int) = update { it.copy(paRate = index) }

    override fun startProfile(name: String) = update { s ->
        val p = s.profiles.firstOrNull { it.name == name } ?: return@update s
        s.copy(ekosRunning = true, activeProfile = name, selectedProfile = name, opticMm = p.opticMm, guideOpticMm = p.guideOpticMm)
    }

    override fun stopProfile() = update { it.copy(ekosRunning = false, activeProfile = null) }

    override fun selectProfile(name: String) = update { it.copy(selectedProfile = name) }

    override fun toggleEkos() {
        val name = _state.value.selectedProfile
        if (_state.value.ekosRunning) stopProfile() else name?.let { startProfile(it) }
    }

    override fun deleteProfile(name: String) = update { s ->
        val remaining = s.profiles.filter { it.name != name }
        s.copy(
            profiles = remaining,
            selectedProfile = if (s.selectedProfile == name) remaining.firstOrNull()?.name else s.selectedProfile,
        )
    }

    override fun editProfile(name: String) = update { s ->
        if (s.ekosRunning) return@update s
        val p = s.profiles.firstOrNull { it.name == name } ?: return@update s
        s.copy(
            sheet = SheetType.SETUP, setupEditingName = name,
            profileName = p.name, opticMm = p.opticMm, guideOpticMm = p.guideOpticMm,
        )
    }

    override fun setRotatorAngle(deg: Double) = update { it.copy(rotatorAngle = deg.mod(360.0)) }
    override fun setDomeOpen(open: Boolean) = update { it.copy(domeOpen = open) }

    override fun setIndiSwitch(deviceKey: String, propName: String, selected: Int) = update { s ->
        val current = s.indiProps[deviceKey] ?: DRIVER_INDI_PROPS[deviceKey] ?: emptyList()
        s.copy(indiProps = s.indiProps + (deviceKey to current.map { p ->
            if (p is IndiProperty.SwitchProp && p.name == propName) p.copy(selected = selected) else p
        }))
    }

    override fun setIndiNumber(deviceKey: String, propName: String, value: Double) = update { s ->
        val current = s.indiProps[deviceKey] ?: DRIVER_INDI_PROPS[deviceKey] ?: emptyList()
        s.copy(indiProps = s.indiProps + (deviceKey to current.map { p ->
            if (p is IndiProperty.NumberProp && p.name == propName) p.copy(value = value.coerceIn(p.min, p.max)) else p
        }))
    }

    override fun setIndiText(deviceKey: String, propName: String, value: String) = update { s ->
        val current = s.indiProps[deviceKey] ?: DRIVER_INDI_PROPS[deviceKey] ?: emptyList()
        s.copy(indiProps = s.indiProps + (deviceKey to current.map { p ->
            if (p is IndiProperty.TextProp && p.name == propName) p.copy(value = value) else p
        }))
    }

    override fun openBench() = update { it.copy(sheet = SheetType.BENCH) }
    override fun openSetup() = update { s ->
        if (s.ekosRunning) s else s.copy(
            sheet = SheetType.SETUP, setupEditingName = null,
            profileName = "New profile", opticMm = 550, guideOpticMm = 240,
        )
    }
    override fun setupBack() = update { it.copy(sheet = null) }
    override fun finishSetup() = update { s ->
        val updatedProfiles = if (s.setupEditingName != null) {
            s.profiles.map { p ->
                if (p.name == s.setupEditingName) p.copy(name = s.profileName, opticMm = s.opticMm, guideOpticMm = s.guideOpticMm) else p
            }
        } else {
            val chosenKeys = DEVICES.filter { it.key !in s.devOff }.map { it.key }
            s.profiles + RigProfile(s.profileName, s.opticMm, s.guideOpticMm, chosenKeys)
        }
        s.copy(
            sheet = null,
            profiles = updatedProfiles,
            ekosRunning = true,
            activeProfile = s.profileName,
            selectedProfile = s.profileName,
            setupEditingName = null,
        )
    }
    override fun setScopeName(name: String) = update { it.copy(scopeName = name) }
    override fun setOpticMm(mm: Int) = update { it.copy(opticMm = mm.coerceIn(1, 9999)) }
    override fun setScopeApertureMm(mm: Int) = update { it.copy(scopeApertureMm = mm.coerceIn(1, 999)) }
    override fun setGuideScopeName(name: String) = update { it.copy(guideScopeName = name) }
    override fun setGuideOpticMm(mm: Int) = update { it.copy(guideOpticMm = mm.coerceIn(1, 9999)) }
    override fun setGuideScopeApertureMm(mm: Int) = update { it.copy(guideScopeApertureMm = mm.coerceIn(1, 999)) }
    override fun setProfileName(name: String) = update { it.copy(profileName = name) }
}
