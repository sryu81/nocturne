package com.nocturne.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Every [SessionController] method that's pure local UI/state mutation with
 * no real-time wire dependency — sheet navigation, job/block list editing,
 * target search/catalog, prefs toggles, device-picker, train editor, rig
 * profile wizard, and so on. [EkosRemoteController] extends this and
 * overrides whichever of these have since gained a real wire-command
 * implementation; anything not overridden here is still local-only under a
 * real connection today (see docs/simulator-removal-plan.md's inventory for
 * the current list — most of it is harmless permanent local UI chrome, a
 * few items genuinely have no wire command to send at all).
 *
 * (Historical: this class used to also be [SimulatedController]'s entire
 * behavior, M1's demo driver — that class was removed 2026-08-22 once the
 * app went real-rig-only. This base class stays; only the "shared with a
 * fake driver" framing is gone.)
 */
abstract class AbstractLocalSessionController : SessionController {

    protected val _state = MutableStateFlow(SimState())
    override val state: StateFlow<SimState> = _state.asStateFlow()

    protected inline fun update(crossinline f: (SimState) -> SimState) {
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
            // an enable/disable setting, not an RPC) — Nocturne-only. Zeroes the countdown;
            // no real flip sequence fires.
            FlipConfirm.NOW -> s.copy(flipDeferSec = s.t - 2530)
            null -> s
        }
        applied.copy(pendingFlipConfirm = null)
    }

    /**
     * Always creates a new job, even if one already exists for this target — real user need,
     * found live (2026-08-23): the same target can want more than one queued job with a different
     * session profile (filters, exposures). Used to dedupe by `targetId` and just reopen the
     * existing job instead — real Ekos itself has no notion of "job per target" either (only a
     * `name` per job), so nothing on the real side required this restriction; it was purely this
     * app's own local model being stricter than it needed to be. See [SimState.targetNameFor]'s
     * own per-job suffix for how multiple jobs on the same target avoid colliding on Ekos's own
     * name once pushed.
     */
    override fun addToSequence(targetId: String) {
        update { s ->
            val block = Block(
                id = "b1", filter = (s.realFilterNames ?: FILTER_CYCLE).first(), exposureSec = 300, subCount = 10, doneCount = 0,
                gain = 100, offset = 50, binning = 1, ditherEvery = 2,
            )
            val job = SequenceJob(id = "j${s.jobSeq}", targetId = targetId, blocks = listOf(block), blockSeq = 2)
            s.copy(jobs = s.jobs + job, jobSeq = s.jobSeq + 1, activeJobId = job.id, openBlockId = null)
        }
    }

    override open fun removeJob(jobId: String) = update { s ->
        s.copy(
            jobs = s.jobs.filter { it.id != jobId },
            activeJobId = if (s.activeJobId == jobId) null else s.activeJobId,
            openBlockId = if (s.activeJobId == jobId) null else s.openBlockId,
            lastActiveJobId = if (s.lastActiveJobId == jobId) null else s.lastActiveJobId,
        )
    }

    override fun openJob(jobId: String) = update { it.copy(activeJobId = jobId, openBlockId = null) }
    override fun closeJob() = update { it.copy(activeJobId = null, openBlockId = null) }

    /**
     * Pure local navigation only — "is this job actually running" is answered by real Ekos state
     * (`SimState.wireJobFor`) since the push/start/stop split (2026-08-23), not a local flag, so
     * there's nothing per-job left to mutate here; these three just move `sheet`/`lastEndedJobId`/
     * `lastActiveJobId` around. [EkosRemoteController] overrides all three to also drive the real
     * Scheduler (stop+remove / push+start) around this same local navigation — a deliberate,
     * explicit whole-night lifecycle action, unlike the passive connect/add-to-sequence path.
     */
    override fun endSession() = update { s -> s.copy(sheet = SheetType.SUMMARY, lastEndedJobId = s.contractJob?.id) }

    override fun resumeSession() = update { s ->
        val id = s.lastEndedJobId ?: return@update s.copy(sheet = null)
        s.copy(sheet = null, lastEndedJobId = null, lastActiveJobId = id)
    }

    override fun startNextJob() = update { s ->
        val next = s.jobs.firstOrNull { it.id != s.lastEndedJobId } ?: return@update s
        s.copy(sheet = null, lastEndedJobId = null, lastActiveJobId = next.id)
    }

    override fun finishNight() = update { s ->
        s.copy(jobs = emptyList(), coolTarget = 20.0, mountParked = true, sheet = null, lastEndedJobId = null, lastActiveJobId = null)
    }

    override fun toggleBlock(jobId: String, blockId: String) = update { s ->
        s.copy(openBlockId = if (s.openBlockId == blockId) null else blockId)
    }

    override fun addBlock(jobId: String) = update { s ->
        val job = s.jobs.firstOrNull { it.id == jobId } ?: return@update s
        val names = s.realFilterNames ?: FILTER_CYCLE
        val used = job.blocks.map { it.filter }.toSet()
        val filter = names.firstOrNull { it !in used } ?: names[job.blockSeq % names.size]
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

    override fun cycleBlockFilter(jobId: String, blockId: String, names: List<String>) = update { s ->
        s.mapJobBlock(jobId, blockId) { b ->
            val i = names.indexOf(b.filter)
            b.copy(filter = names[(i + 1).mod(names.size)])
        }
    }

    override fun setBlockExposure(jobId: String, blockId: String, sec: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(exposureSec = sec.coerceIn(1, 3600)) } }
    override fun setBlockSubCount(jobId: String, blockId: String, count: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(subCount = count.coerceIn(0, 999)) } }
    override fun setBlockGain(jobId: String, blockId: String, gain: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(gain = gain.coerceIn(0, 600)) } }
    override fun setBlockOffset(jobId: String, blockId: String, offset: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(offset = offset.coerceIn(0, 255)) } }
    override fun setBlockBinning(jobId: String, blockId: String, bin: Int) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(binning = bin) } }
    override fun setBlockDither(jobId: String, blockId: String, n: Int?) = update { s -> s.mapJobBlock(jobId, blockId) { it.copy(ditherEvery = n) } }


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

    override open fun setQuery(text: String) = update { it.copy(query = text) }

    override fun clearQuery() = update { it.copy(query = "") }

    override fun selectTarget(id: String) = update { it.copy(targetId = id) }
    // Fixture has no real astro engine to query — AltitudeChart keeps its decorative curve.
    override fun ensureTargetRiseset(targetId: String) {}

    override fun togglePref(key: String) = update { s ->
        s.copy(prefs = s.prefs + (key to !(s.prefs[key] ?: false)))
    }

    override fun toggleQuietHours() = update { it.copy(quietHoursEnabled = !it.quietHoursEnabled) }

    override fun toggleCut(id: String) = update { s ->
        s.copy(cut = if (s.cut.contains(id)) s.cut - id else s.cut + id)
    }

    override open fun toggleDevice(key: String) = update { s ->
        s.copy(devOff = if (s.devOff.contains(key)) s.devOff - key else s.devOff + key)
    }

    override fun selectDeviceName(key: String, name: String) = update { s ->
        s.copy(
            selectedDeviceNames = s.selectedDeviceNames + (key to name),
            devOff = if (name == "None") s.devOff + key else s.devOff - key,
        )
    }

    override open fun setTrainRole(slot: TrainSlot, role: TrainRole, value: String) = update { s ->
        s.withTrain(slot, s.train(slot).with(role, value))
    }

    override open fun setTrainReducer(slot: TrainSlot, value: Double) = update { s ->
        s.withTrain(slot, s.train(slot).copy(reducer = value.coerceIn(0.1, 3.0)))
    }

    override open fun setModuleTrain(module: String, trainName: String) = update { s ->
        s.copy(moduleTrainAssignments = (s.moduleTrainAssignments ?: emptyMap()) + (module to trainName))
    }

    // Local-only bookkeeping; [EkosRemoteController] overrides both to actually talk to the
    // rig's companion reboot daemon. There's no real Pi under a bare local mutation, so a
    // reboot attempt fails honestly rather than pretending to succeed.
    override open fun setRigRebootConfig(port: Int, token: String) = update { s ->
        s.copy(rigRebootPort = port, rigRebootTokenSet = token.isNotBlank())
    }

    override open fun rebootRig() = update { s ->
        s.copy(rigRebootState = RigRebootState.FAILED, rigRebootError = "No real rig connected — nothing to reboot")
    }

    // Mount settings (M3.3): Nothing populates wireMountSettings here (there's no
    // real mount_get_all_settings reply to translate), so these are safe no-ops there — the
    // sheet itself is gated on wireMountSettings != null and never calls them before it's arrived.
    override open fun setMountMeridianFlip(enabled: Boolean) = update { s ->
        s.copy(wireMountSettings = s.wireMountSettings?.copy(executeMeridianFlip = enabled))
    }
    override open fun setMountMeridianFlipOffset(deg: Double) = update { s ->
        s.copy(wireMountSettings = s.wireMountSettings?.copy(meridianFlipOffsetDegrees = deg))
    }
    override open fun setMountAltLimitEnabled(enabled: Boolean) = update { s ->
        s.copy(wireMountSettings = s.wireMountSettings?.copy(enableAltitudeLimits = enabled))
    }
    override open fun setMountAltLimitMin(deg: Double) = update { s ->
        s.copy(wireMountSettings = s.wireMountSettings?.copy(minimumAltLimit = deg))
    }
    override open fun setMountAltLimitMax(deg: Double) = update { s ->
        s.copy(wireMountSettings = s.wireMountSettings?.copy(maximumAltLimit = deg))
    }
    override open fun setMountAltLimitTrackingOnly(enabled: Boolean) = update { s ->
        s.copy(wireMountSettings = s.wireMountSettings?.copy(enableAltitudeLimitsTrackingOnly = enabled))
    }
    override open fun setMountHaLimitEnabled(enabled: Boolean) = update { s ->
        s.copy(wireMountSettings = s.wireMountSettings?.copy(enableHaLimit = enabled))
    }
    override open fun setMountHaLimitMax(hours: Double) = update { s ->
        s.copy(wireMountSettings = s.wireMountSettings?.copy(maximumHaLimit = hours))
    }
    override open fun setMountParkEveryDay(enabled: Boolean) = update { s ->
        s.copy(wireMountSettings = s.wireMountSettings?.copy(parkEveryDay = enabled))
    }
    override open fun setMountAutoParkTime(time: String) = update { s ->
        s.copy(wireMountSettings = s.wireMountSettings?.copy(autoParkTime = time))
    }

    // Camera settings (M3.3): same shape as Mount settings above —
    // nothing populates wireCaptureSettings here (there's no real
    // capture_get_all_settings reply to translate), so these only ever touch a null field; the
    // sheet itself is gated on wireCaptureSettings != null and never calls them before it's arrived.
    override open fun setCameraSaveDir(path: String) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(fileDirectoryT = path))
    }
    override open fun setCameraGuideDeviationEnabled(enabled: Boolean) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(enforceGuideDeviation = enabled))
    }
    override open fun setCameraGuideDeviation(arcsec: Double) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(guideDeviation = arcsec))
    }
    override open fun setCameraStartGuideDriftEnabled(enabled: Boolean) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(enforceStartGuiderDrift = enabled))
    }
    override open fun setCameraStartGuideDeviation(arcsec: Double) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(startGuideDeviation = arcsec))
    }
    override open fun setCameraDitherPerJobEnabled(enabled: Boolean) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(enableDitherPerJob = enabled))
    }
    override open fun setCameraDitherPerJobFrequency(everyN: Int) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(guideDitherPerJobFrequency = everyN))
    }
    override open fun setCameraRefocusEveryNEnabled(enabled: Boolean) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(enforceRefocusEveryN = enabled))
    }
    override open fun setCameraRefocusEveryN(minutes: Int) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(refocusEveryN = minutes))
    }
    override open fun setCameraRefocusOnTemperatureEnabled(enabled: Boolean) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(enforceAutofocusOnTemperature = enabled))
    }
    override open fun setCameraMaxFocusTemperatureDelta(deltaC: Double) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(maxFocusTemperatureDelta = deltaC))
    }
    override open fun setCameraFilter(filter: String) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(FilterPosCombo = filter))
    }

    // Align settings (M3.3 phase 3): same shape as Mount/Camera settings
    // above — nothing populates wireAlignSettings here.
    override open fun setAlignExposure(sec: Double) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignExposure = sec))
    }
    override open fun setAlignGain(gain: Double) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignGain = gain))
    }
    override open fun setAlignFilter(filter: String) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignFilter = filter))
    }
    override open fun setAlignBinning(binning: String) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignBinning = binning))
    }
    override open fun setAlignAccuracyThreshold(arcsec: Double) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignAccuracyThreshold = arcsec))
    }

    // Guide settings (M3.3 phase 4): same shape as Align above —
    // nothing populates wireGuideSettings here, so these are dead in practice
    // (the settings card/sheet are real-rig-gated) but kept consistent with the pattern.
    override open fun setGuideAccuracyThreshold(arcsec: Double) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(guiderAccuracyThreshold = arcsec))
    }
    override open fun setGuideDitherEnabled(enabled: Boolean) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_DitherEnabled = enabled))
    }
    override open fun setGuideDitherPixels(px: Int) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_DitherPixels = px))
    }
    override open fun setGuideDitherThreshold(value: Double) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_DitherThreshold = value))
    }
    override open fun setGuideReuseCalibration(enabled: Boolean) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_ReuseGuideCalibration = enabled))
    }

    // Focus settings (M3.3 phase 6): same shape as Guide above.
    override open fun setFocusExposure(sec: Double) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusExposure = sec))
    }
    override open fun setFocusGain(gain: Double) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusGain = gain))
    }
    override open fun setFocusFilter(filter: String) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusFilter = filter))
    }
    override open fun setFocusBacklash(steps: Int) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusBacklash = steps))
    }
    override open fun setFocusAlgorithm(algorithm: String) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusAlgorithm = algorithm))
    }

    // Primary/guide preview capture params: same shape as every other
    // module setting above — nothing populates wireCaptureSettings/
    // wireGuideSettings's new fields any differently than the rest of those structs.
    override open fun setCapturePreviewExposure(sec: Double) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(captureExposureN = sec))
    }
    override open fun setCapturePreviewGain(gain: Double) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(captureGainN = gain))
    }
    override open fun setCapturePreviewBinning(bin: Int) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(captureBinHN = bin, captureBinVN = bin))
    }
    override open fun setGuidePreviewExposure(sec: Double) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(guideExposure = sec))
    }
    override open fun setGuidePreviewGain(gain: Double) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(guideGain = gain))
    }
    override open fun setGuidePreviewBinning(binning: String) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(guideBinning = binning))
    }

    // ── Scheduler settings (curated subset, see WireSchedulerSettings' own doc, M2026-08) ──
    override open fun setSchedulerStartAsap() = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(asapConditionR = true, startupTimeConditionR = false))
    }
    override open fun setSchedulerStartAtTime(iso: String) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(asapConditionR = false, startupTimeConditionR = true, startupTimeEdit = iso))
    }
    override open fun setSchedulerLeadTime(minutes: Double) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(kcfg_LeadTime = minutes))
    }
    override open fun setSchedulerPreDawnTime(minutes: Double) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(kcfg_PreDawnTime = minutes))
    }
    override open fun setSchedulerAltitudeEnabled(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerAltitude = enabled))
    }
    override open fun setSchedulerAltitudeValue(deg: Double) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerAltitudeValue = deg))
    }
    override open fun setSchedulerMoonSeparationEnabled(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerMoonSeparation = enabled))
    }
    override open fun setSchedulerMoonSeparationValue(deg: Double) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerMoonSeparationValue = deg))
    }
    override open fun setSchedulerMoonAltitudeEnabled(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerMoonAltitude = enabled))
    }
    override open fun setSchedulerMoonAltitudeMaxValue(deg: Double) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerMoonAltitudeMaxValue = deg))
    }
    override open fun setSchedulerTwilightEnabled(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerTwilight = enabled))
    }
    override open fun setSchedulerHorizonEnabled(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerHorizon = enabled))
    }
    override open fun setSchedulerDawnOffset(hours: Double) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(kcfg_DawnOffset = hours))
    }
    override open fun setSchedulerDuskOffset(hours: Double) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(kcfg_DuskOffset = hours))
    }
    override open fun setSchedulerTrackStep(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerTrackStep = enabled))
    }
    override open fun setSchedulerFocusStep(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerFocusStep = enabled))
    }
    override open fun setSchedulerAlignStep(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerAlignStep = enabled))
    }
    override open fun setSchedulerGuideStep(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerGuideStep = enabled))
    }
    override open fun setSchedulerCompleteSequences() = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(
            schedulerCompleteSequences = true, schedulerRepeatSequences = false,
            schedulerRepeatEverything = false, schedulerUntilTerminated = false, schedulerUntil = false,
        ))
    }
    override open fun setSchedulerRepeatSequences(limit: Int) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(
            schedulerCompleteSequences = false, schedulerRepeatSequences = true, schedulerRepeatSequencesLimit = limit,
            schedulerRepeatEverything = false, schedulerUntilTerminated = false, schedulerUntil = false,
        ))
    }
    override open fun setSchedulerRepeatEverything() = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(
            schedulerCompleteSequences = false, schedulerRepeatSequences = false,
            schedulerRepeatEverything = true, schedulerUntilTerminated = false, schedulerUntil = false,
        ))
    }
    override open fun setSchedulerUntilTerminated() = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(
            schedulerCompleteSequences = false, schedulerRepeatSequences = false,
            schedulerRepeatEverything = false, schedulerUntilTerminated = true, schedulerUntil = false,
        ))
    }
    override open fun setSchedulerUntil(iso: String) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(
            schedulerCompleteSequences = false, schedulerRepeatSequences = false, schedulerRepeatEverything = false,
            schedulerUntilTerminated = false, schedulerUntil = true, schedulerUntilValue = iso,
        ))
    }
    override open fun setSchedulerStartupEnabled(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerStartupEnabled = enabled))
    }
    override open fun setSchedulerPreStartupScript(path: String) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerPreStartupScript = path))
    }
    override open fun setSchedulerPostStartupScript(path: String) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerPostStartupScript = path))
    }
    override open fun setSchedulerShutdownEnabled(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerShutdownEnabled = enabled))
    }
    override open fun setSchedulerPreShutdownScript(path: String) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerPreShutdownScript = path))
    }
    override open fun setSchedulerPostShutdownScript(path: String) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(schedulerPostShutdownScript = path))
    }
    override open fun setSchedulerPreemptiveShutdown(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(kcfg_PreemptiveShutdown = enabled))
    }
    override open fun setSchedulerPreemptiveShutdownTime(hours: Double) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(kcfg_PreemptiveShutdownTime = hours))
    }
    override open fun setSchedulerStopEkosAfterShutdown(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(kcfg_StopEkosAfterShutdown = enabled))
    }
    override open fun setSchedulerShutdownScriptTerminatesIndi(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(kcfg_ShutdownScriptTerminatesINDI = enabled))
    }
    override open fun setSchedulerAbortDontRestart() = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(
            errorHandlingDontRestartButton = true, errorHandlingRestartImmediatelyButton = false, errorHandlingRestartQueueButton = false,
        ))
    }
    override open fun setSchedulerAbortRestartImmediately() = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(
            errorHandlingDontRestartButton = false, errorHandlingRestartImmediatelyButton = true, errorHandlingRestartQueueButton = false,
        ))
    }
    override open fun setSchedulerAbortRestartQueue() = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(
            errorHandlingDontRestartButton = false, errorHandlingRestartImmediatelyButton = false, errorHandlingRestartQueueButton = true,
        ))
    }
    override open fun setSchedulerAbortRescheduleErrors(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(errorHandlingRescheduleErrorsCB = enabled))
    }
    override open fun setSchedulerAbortDelay(minutes: Int) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(errorHandlingStrategyDelay = minutes))
    }
    override open fun setSchedulerRememberJobProgress(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(kcfg_RememberJobProgress = enabled))
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
    // but reactive rather than a frozen literal. Unrelated to startAutofocus/stopAutofocus
    // below — this is FocusSheet's dedicated one-shot "run once, bump HFR" fixture, kept
    // separate on purpose (see startAutofocus's own doc).
    override fun runAutofocusNow() = update { s ->
        val newHfr = 2.2 + kotlin.math.abs(kotlin.math.sin(s.t / 13.0)) * 0.15
        s.copy(
            focusLastBestPos = s.focPos,
            focusLastHfr = newHfr,
            focusLastAfAt = s.t,
            focusTempAtLastAf = s.eafTemp,
        )
    }
    override fun startAutofocus() = update { it.copy(focusRunning = true) }
    override fun stopAutofocus() = update { it.copy(focusRunning = false) }

    override fun unparkMount() = update {
        it.copy(mountParked = false, mountAlt = 49.2, mountAz = 71.6, slewDir = null, mountSolved = false)
    }
    override fun parkMount() = update { it.copy(mountParked = true, slewDir = null) }
    override fun setMountTracking(enabled: Boolean) = update { it.copy(mountTracking = enabled) }

    override fun plateSolveHere() = update { it.copy(mountSolved = true) }
    // No real mount-position model exists here (mountAlt/mountAz are display-only, never
    // driven by an actual slew) — nothing meaningful to simulate for a plain goto. "& center"
    // pretends the same solved-success gesture as plateSolveHere.
    override fun gotoTarget(targetId: String) {}
    override fun gotoAndCenter(targetId: String) = update { it.copy(mountSolved = true) }
    override fun startGuiding() = update { it.copy(guiding = true) }
    override fun stopGuiding() = update { it.copy(guiding = false) }
    override fun startPolarAlign() = update { it.copy(polarRunning = true) }
    override fun stopPolarAlign() = update { it.copy(polarRunning = false) }

    override fun openPa() = update { it.copy(sheet = SheetType.PA, paStep = 0) }
    override fun paNext() = update { it.copy(paStep = minOf(2, it.paStep + 1)) }
    override fun setPaRate(index: Int) = update { it.copy(paRate = index) }

    override open fun startProfile(name: String) = update { s ->
        if (s.profiles.none { it.name == name }) return@update s
        s.copy(ekosRunning = true, activeProfile = name, selectedProfile = name)
    }

    override open fun stopProfile() = update { it.copy(ekosRunning = false, activeProfile = null) }

    override fun selectProfile(name: String) = update { it.copy(selectedProfile = name) }

    override fun toggleEkos() {
        val name = _state.value.selectedProfile
        if (_state.value.ekosRunning) stopProfile() else name?.let { startProfile(it) }
    }

    override open fun deleteProfile(name: String) = update { s ->
        val remaining = s.profiles.filter { it.name != name }
        s.copy(
            profiles = remaining,
            selectedProfile = if (s.selectedProfile == name) remaining.firstOrNull()?.name else s.selectedProfile,
        )
    }

    override open fun editProfile(name: String) = update { s ->
        if (s.ekosRunning) return@update s
        val p = s.profiles.firstOrNull { it.name == name } ?: return@update s
        s.copy(sheet = SheetType.SETUP, setupEditingName = name, profileName = p.name)
    }

    override fun setRotatorAngle(deg: Double) = update { it.copy(rotatorAngle = deg.mod(360.0)) }
    override fun setDomeOpen(open: Boolean) = update { it.copy(domeOpen = open) }

    override open fun setIndiSwitch(deviceKey: String, propName: String, selected: Int) = update { s ->
        val current = s.indiProps[deviceKey] ?: DRIVER_INDI_PROPS[deviceKey] ?: emptyList()
        s.copy(indiProps = s.indiProps + (deviceKey to current.map { p ->
            if (p is IndiProperty.SwitchProp && p.name == propName) p.copy(selected = selected) else p
        }))
    }

    override open fun setIndiNumber(deviceKey: String, propName: String, value: Double) = update { s ->
        val current = s.indiProps[deviceKey] ?: DRIVER_INDI_PROPS[deviceKey] ?: emptyList()
        s.copy(indiProps = s.indiProps + (deviceKey to current.map { p ->
            if (p is IndiProperty.NumberProp && p.name == propName) p.copy(value = value.coerceIn(p.min, p.max)) else p
        }))
    }

    override open fun setIndiText(deviceKey: String, propName: String, elementName: String, value: String) = update { s ->
        val current = s.indiProps[deviceKey] ?: DRIVER_INDI_PROPS[deviceKey] ?: emptyList()
        s.copy(indiProps = s.indiProps + (deviceKey to current.map { p ->
            if (p is IndiProperty.TextProp && p.name == propName) {
                p.copy(elements = p.elements.map { if (it.first == elementName) elementName to value else it })
            } else p
        }))
    }

    override fun openSetup() = update { s ->
        if (s.ekosRunning) s else s.copy(sheet = SheetType.SETUP, setupEditingName = null, profileName = "New profile")
    }
    override fun setupBack() = update { it.copy(sheet = null) }
    override open fun finishSetup() = update { s ->
        val updatedProfiles = if (s.setupEditingName != null) {
            s.profiles.map { p -> if (p.name == s.setupEditingName) p.copy(name = s.profileName) else p }
        } else {
            val chosenKeys = DEVICES.filter { it.key !in s.devOff }.map { it.key }
            s.profiles + RigProfile(s.profileName, chosenKeys)
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
    override fun setProfileName(name: String) = update { it.copy(profileName = name) }

    override open fun addScope(name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int) = update { s ->
        if (name.isBlank()) return@update s
        val scope = ScopeDef(
            id = "scope_${s.scopeSeq}", name = name, vendor = vendor, type = type,
            focalMm = focalMm.coerceIn(1, 9999), apertureMm = apertureMm.coerceIn(1, 999),
        )
        s.copy(scopes = s.scopes + scope, scopeSeq = s.scopeSeq + 1, addingScope = false)
    }

    override open fun updateScope(id: String, name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int) = update { s ->
        s.copy(scopes = s.scopes.map {
            if (it.id == id) it.copy(
                name = name, vendor = vendor, type = type,
                focalMm = focalMm.coerceIn(1, 9999), apertureMm = apertureMm.coerceIn(1, 999),
            ) else it
        })
    }

    override open fun removeScope(id: String) = update { s ->
        s.copy(
            scopes = s.scopes.filter { it.id != id },
            editingScopeId = if (s.editingScopeId == id) null else s.editingScopeId,
        )
    }

    override fun startAddScope() = update { it.copy(addingScope = true, editingScopeId = null) }
    override fun cancelAddScope() = update { it.copy(addingScope = false) }
    override fun toggleEditScope(id: String) = update { s ->
        s.copy(editingScopeId = if (s.editingScopeId == id) null else id, addingScope = false)
    }
}
