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
                SheetType.SETUP -> if (s.ekosRunning) s else s.copy(sheet = sheet, setupStep = 0)
                else -> s.copy(sheet = sheet)
            }
        }
    }

    override fun openDevice(key: String) = update { it.copy(sheet = SheetType.DEVICE, deviceKey = key) }

    override fun closeSheet() = update { it.copy(sheet = null) }

    override fun selectTab() = update { it.copy(sheet = null) }

    override fun toggleRun() = update { it.copy(running = !it.running) }

    override fun toggleBlock(index: Int) = update { s ->
        s.copy(openBlock = if (s.openBlock == index) -1 else index)
    }

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
            sheet = SheetType.SETUP, setupStep = 0, setupEditingName = name,
            profileName = p.name, opticMm = p.opticMm, guideOpticMm = p.guideOpticMm,
        )
    }

    override fun setRotatorAngle(deg: Double) = update { it.copy(rotatorAngle = deg.mod(360.0)) }

    override fun setIndiSwitch(deviceKey: String, propName: String, selected: Int) = update { s ->
        s.copy(indiProps = s.indiProps.mapValues { (key, props) ->
            if (key != deviceKey) props else props.map { p ->
                if (p is IndiProperty.SwitchProp && p.name == propName) p.copy(selected = selected) else p
            }
        })
    }

    override fun setIndiNumber(deviceKey: String, propName: String, value: Double) = update { s ->
        s.copy(indiProps = s.indiProps.mapValues { (key, props) ->
            if (key != deviceKey) props else props.map { p ->
                if (p is IndiProperty.NumberProp && p.name == propName) p.copy(value = value.coerceIn(p.min, p.max)) else p
            }
        })
    }

    override fun setIndiText(deviceKey: String, propName: String, value: String) = update { s ->
        s.copy(indiProps = s.indiProps.mapValues { (key, props) ->
            if (key != deviceKey) props else props.map { p ->
                if (p is IndiProperty.TextProp && p.name == propName) p.copy(value = value) else p
            }
        })
    }

    override fun openBench() = update { it.copy(sheet = SheetType.BENCH) }
    override fun openSetup() = update { s ->
        if (s.ekosRunning) s else s.copy(
            sheet = SheetType.SETUP, setupStep = 0, setupEditingName = null,
            profileName = "New profile", opticMm = 550, guideOpticMm = 240,
        )
    }
    override fun setupNext() = update { it.copy(setupStep = minOf(3, it.setupStep + 1)) }
    override fun setupBack() = update { s ->
        if (s.setupStep == 0) s.copy(sheet = null) else s.copy(setupStep = s.setupStep - 1)
    }
    override fun finishSetup() = update { s ->
        val updatedProfiles = if (s.setupEditingName != null) {
            s.profiles.map { p ->
                if (p.name == s.setupEditingName) p.copy(name = s.profileName, opticMm = s.opticMm, guideOpticMm = s.guideOpticMm) else p
            }
        } else {
            s.profiles + RigProfile(s.profileName, s.opticMm, s.guideOpticMm, DEVICES.map { it.key })
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
    override fun setOpticMm(mm: Int) = update { it.copy(opticMm = mm.coerceIn(1, 9999)) }
    override fun setGuideOpticMm(mm: Int) = update { it.copy(guideOpticMm = mm.coerceIn(1, 9999)) }
    override fun setProfileName(name: String) = update { it.copy(profileName = name) }
}
