package com.nocturne.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

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

        // Bench sheet's D-pad — modest per-tick step, not a real slew rate; just needs to visibly move.
        val rateStep = listOf(0.02, 0.05, 0.2, 0.6, 1.2).getOrElse(s.rate) { 0.2 }
        val mountAlt = when (s.slewDir) {
            "N" -> (s.mountAlt + rateStep).coerceIn(0.0, 90.0)
            "S" -> (s.mountAlt - rateStep).coerceIn(0.0, 90.0)
            else -> s.mountAlt
        }
        val mountAz = when (s.slewDir) {
            "E" -> (s.mountAz + rateStep).mod(360.0)
            "W" -> (s.mountAz - rateStep).mod(360.0)
            else -> s.mountAz
        }

        return s.copy(
            t = s.t + 1,
            coolNow = coolNow,
            paAlt = if (paAdjust) ease(s.paAlt) else s.paAlt,
            paAz = if (paAdjust) ease(s.paAz) else s.paAz,
            mountAlt = mountAlt,
            mountAz = mountAz,
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

    override fun openSubPreview() = update { it.copy(subPreviewExpanded = true) }
    override fun closeSubPreview() = update { it.copy(subPreviewExpanded = false) }
    override fun expandFrame(id: String) = update { it.copy(expandedFrameId = id) }
    override fun closeFrameExpand() = update { it.copy(expandedFrameId = null) }

    override fun requestDeferFlip() = update { it.copy(pendingFlipConfirm = FlipConfirm.DEFER) }
    override fun requestFlipNow() = update { it.copy(pendingFlipConfirm = FlipConfirm.NOW) }
    override fun cancelFlipConfirm() = update { it.copy(pendingFlipConfirm = null) }

    override fun confirmFlipAction() = update { s ->
        val applied = when (s.pendingFlipConfirm) {
            // Matches real Ekos's meridian-flip delay — push the deadline back 10 min.
            FlipConfirm.DEFER -> s.copy(flipDeferSec = s.flipDeferSec + 600)
            // No manual-trigger command exists on the real wire (executeMeridianFlip is
            // an enable/disable setting, not an RPC) — Nocturne-only, same as
            // forceAfOnStart. Zeroes the countdown; no real flip sequence fires.
            FlipConfirm.NOW -> s.copy(flipDeferSec = s.t - 2530)
            null -> s
        }
        applied.copy(pendingFlipConfirm = null)
    }

    override fun addToSequence(targetId: String) {
        val existing = _state.value.jobs.firstOrNull { it.targetId == targetId }
        if (existing != null) {
            update { it.copy(activeJobId = existing.id, openBlockId = null) }
            return
        }
        update { s ->
            val block = Block(
                id = "b1", filter = FILTER_CYCLE.first(), exposureSec = 300, subCount = 10, doneCount = 0,
                gain = 100, offset = 50, binning = 1, ditherEvery = 2,
            )
            val job = SequenceJob(id = "j${s.jobSeq}", targetId = targetId, blocks = listOf(block), blockSeq = 2, running = false)
            s.copy(jobs = s.jobs + job, jobSeq = s.jobSeq + 1, activeJobId = job.id, openBlockId = null)
        }
    }

    override fun removeJob(jobId: String) = update { s ->
        s.copy(
            jobs = s.jobs.filter { it.id != jobId },
            activeJobId = if (s.activeJobId == jobId) null else s.activeJobId,
            openBlockId = if (s.activeJobId == jobId) null else s.openBlockId,
            lastActiveJobId = if (s.lastActiveJobId == jobId) null else s.lastActiveJobId,
        )
    }

    override fun openJob(jobId: String) = update { it.copy(activeJobId = jobId, openBlockId = null) }
    override fun closeJob() = update { it.copy(activeJobId = null, openBlockId = null) }

    override fun toggleJobRun(jobId: String) = update { s ->
        val running = !(s.jobs.firstOrNull { it.id == jobId }?.running ?: false)
        s.mapJob(jobId) { it.copy(running = running) }
            .copy(lastActiveJobId = if (running) jobId else s.lastActiveJobId)
    }

    override fun endSession() = update { s ->
        val contract = s.contractJob
        val stopped = if (contract != null) s.mapJob(contract.id) { it.copy(running = false) } else s
        stopped.copy(sheet = SheetType.SUMMARY, lastEndedJobId = contract?.id)
    }

    override fun resumeSession() = update { s ->
        val id = s.lastEndedJobId ?: return@update s.copy(sheet = null)
        s.mapJob(id) { it.copy(running = true) }.copy(sheet = null, lastEndedJobId = null, lastActiveJobId = id)
    }

    override fun startNextJob() = update { s ->
        val next = s.jobs.firstOrNull { it.id != s.lastEndedJobId } ?: return@update s
        s.mapJob(next.id) { it.copy(running = true) }.copy(sheet = null, lastEndedJobId = null, lastActiveJobId = next.id)
    }

    override fun finishNight() = update { s ->
        s.copy(jobs = emptyList(), coolTarget = 20.0, mountParked = true, sheet = null, lastEndedJobId = null, lastActiveJobId = null)
    }

    override fun toggleBlock(jobId: String, blockId: String) = update { s ->
        s.copy(openBlockId = if (s.openBlockId == blockId) null else blockId)
    }

    override fun addBlock(jobId: String) = update { s ->
        val job = s.jobs.firstOrNull { it.id == jobId } ?: return@update s
        val used = job.blocks.map { it.filter }.toSet()
        val filter = FILTER_CYCLE.firstOrNull { it !in used } ?: FILTER_CYCLE[job.blockSeq % FILTER_CYCLE.size]
        val block = Block(
            id = "b${job.blockSeq}", filter = filter, exposureSec = 300, subCount = 10, doneCount = 0,
            gain = 100, offset = 50, binning = 1, ditherEvery = 2,
        )
        s.mapJob(jobId) { it.copy(blocks = it.blocks + block, blockSeq = it.blockSeq + 1) }.copy(openBlockId = block.id)
    }

    override fun removeBlock(jobId: String, blockId: String) = update { s ->
        s.mapJob(jobId) { it.copy(blocks = it.blocks.filter { b -> b.id != blockId }) }
            .copy(openBlockId = if (s.openBlockId == blockId) null else s.openBlockId)
    }

    override fun moveBlock(jobId: String, blockId: String, toIndex: Int) = update { s ->
        s.mapJob(jobId) { job ->
            val list = job.blocks.toMutableList()
            val from = list.indexOfFirst { it.id == blockId }
            if (from == -1) return@mapJob job
            val clamped = toIndex.coerceIn(0, list.lastIndex)
            if (from == clamped) return@mapJob job
            val item = list.removeAt(from)
            list.add(clamped, item)
            job.copy(blocks = list)
        }
    }

    override fun cycleBlockFilter(jobId: String, blockId: String) = update { s ->
        s.mapJobBlock(jobId, blockId) { b ->
            val i = FILTER_CYCLE.indexOf(b.filter)
            b.copy(filter = FILTER_CYCLE[(i + 1).mod(FILTER_CYCLE.size)])
        }
    }

    override fun setBlockExposure(jobId: String, blockId: String, sec: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(exposureSec = sec.coerceIn(1, 3600)) } }
    override fun setBlockSubCount(jobId: String, blockId: String, count: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(subCount = count.coerceIn(0, 999)) } }
    override fun setBlockGain(jobId: String, blockId: String, gain: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(gain = gain.coerceIn(0, 600)) } }
    override fun setBlockOffset(jobId: String, blockId: String, offset: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(offset = offset.coerceIn(0, 255)) } }
    override fun setBlockBinning(jobId: String, blockId: String, bin: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(binning = bin) } }
    override fun setBlockDither(jobId: String, blockId: String, n: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(ditherEvery = n) } }

    // Stub: flips the local flag only. Actually firing `focus_start` when this
    // block becomes active needs real capture-state pushes — M2/M3.
    override fun toggleBlockForceAf(jobId: String, blockId: String) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(forceAfOnStart = !it.forceAfOnStart) } }

    override fun setAutofocusRefocusMin(min: Int) = update { it.copy(afRefocusMin = min.coerceIn(0, 240)) }
    override fun setAutofocusTempDelta(deltaC: Double) = update { it.copy(afTempDeltaC = deltaC.coerceIn(0.0, 10.0)) }
    override fun toggleAutofocusOnFilterChange() = update { it.copy(afOnFilterChange = !it.afOnFilterChange) }

    override fun toggleChip(index: Int) = update { s ->
        s.copy(chips = if (s.chips.contains(index)) s.chips.filter { it != index } else s.chips + index)
    }

    override fun setUserCatalogName(name: String) = update { it.copy(userCatalogName = name) }

    override fun addUserTarget(name: String, coords: String) = update { s ->
        if (name.isBlank()) return@update s
        val target = Target(id = "custom_${s.userTargetSeq}", common = name, coords = coords, custom = true)
        s.copy(
            userTargets = s.userTargets + target,
            userTargetSeq = s.userTargetSeq + 1,
            addingUserTarget = false,
        )
    }

    override fun editUserTarget(id: String, name: String, coords: String) = update { s ->
        s.copy(userTargets = s.userTargets.map { if (it.id == id) it.copy(common = name, coords = coords) else it })
    }

    override fun removeUserTarget(id: String) = update { s ->
        s.copy(
            userTargets = s.userTargets.filter { it.id != id },
            editingUserTargetId = if (s.editingUserTargetId == id) null else s.editingUserTargetId,
        )
    }

    override fun startAddUserTarget() = update { it.copy(addingUserTarget = true, editingUserTargetId = null) }
    override fun cancelAddUserTarget() = update { it.copy(addingUserTarget = false) }

    override fun toggleEditUserTarget(id: String) = update { s ->
        s.copy(editingUserTargetId = if (s.editingUserTargetId == id) null else id, addingUserTarget = false)
    }

    override fun setQuery(text: String) = update { it.copy(query = text) }

    override fun clearQuery() = update { it.copy(query = "") }

    override fun selectTarget(id: String) = update { it.copy(targetId = id) }

    override fun togglePref(key: String) = update { s ->
        s.copy(prefs = s.prefs + (key to !(s.prefs[key] ?: false)))
    }

    override fun toggleQuietHours() = update { it.copy(quietHoursEnabled = !it.quietHoursEnabled) }

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
        s.copy(slewDir = if (s.slewDir == key) null else key, mountSolved = false)
    }

    override fun stopSlew() = update { it.copy(slewDir = null) }

    override fun coolUp() = update { it.copy(coolTarget = (it.coolTarget + 1).coerceAtMost(20.0)) }
    override fun coolDown() = update { it.copy(coolTarget = (it.coolTarget - 1).coerceAtLeast(-25.0)) }

    // Deterministic-but-ticking, same style as `rms`/`fNow` — not a real focus sweep,
    // but reactive rather than a frozen literal.
    override fun runAutofocusNow() = update { s ->
        val newHfr = 2.2 + abs(sin(s.t / 13.0)) * 0.15
        s.copy(
            focusLastBestPos = s.focPos,
            focusLastHfr = newHfr,
            focusLastAfAt = s.t,
            focusTempAtLastAf = s.eafTemp,
        )
    }

    override fun unparkMount() = update {
        it.copy(mountParked = false, mountAlt = 49.2, mountAz = 71.6, slewDir = null, mountSolved = false)
    }

    override fun plateSolveHere() = update { it.copy(mountSolved = true) }

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
