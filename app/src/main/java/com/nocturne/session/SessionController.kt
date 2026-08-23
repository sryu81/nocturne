package com.nocturne.session

import kotlinx.coroutines.flow.StateFlow

/**
 * The single interface the whole UI consumes (PLAN §4.3). [EkosRemoteController] is the only
 * implementation — the app is real-rig-only (the earlier [SimulatedController] demo driver was
 * removed 2026-08-22, see docs/simulator-removal-plan.md); [AbstractLocalSessionController]
 * remains as its base class for pure local UI-state mutation.
 */
interface SessionController {
    val state: StateFlow<SimState>

    fun openSheet(sheet: SheetType)
    fun openDevice(key: String)
    fun closeSheet()
    fun selectTab()
    fun openSubPreview()
    fun closeSubPreview()
    fun expandFrame(id: String)
    fun closeFrameExpand()
    fun requestDeferFlip()
    fun requestFlipNow()
    fun confirmFlipAction()
    fun cancelFlipConfirm()

    fun addToSequence(targetId: String)
    /** Plain slew to a target's coordinates — no plate-solve correction. */
    fun gotoTarget(targetId: String)
    /** Slew, then plate-solve and let the real Align module's own solver-action re-slew onto the solved position. */
    fun gotoAndCenter(targetId: String)
    fun removeJob(jobId: String)
    fun openJob(jobId: String)
    fun closeJob()
    /** Pushes this local job to Ekos's real Scheduler queue (`scheduler_add_jobs`) — does not start it. */
    fun pushJob(jobId: String)
    /** Toggles the real Scheduler as a whole on/off (`scheduler_start_job` — a real toggle, not per-job start/stop). */
    fun toggleScheduler()
    /** Removes a real Scheduler job that has no local counterpart (added directly in KStars, or left over). */
    fun removeUnmanagedJob(name: String)
    fun endSession()
    fun resumeSession()
    fun startNextJob()
    fun finishNight()
    fun toggleBlock(jobId: String, blockId: String)
    fun addBlock(jobId: String)
    fun removeBlock(jobId: String, blockId: String)
    fun moveBlock(jobId: String, blockId: String, toIndex: Int)
    /** [names] to cycle through — real filter-wheel slot names when known ([SimState.realFilterNames]), else the fixture [FILTER_CYCLE]; the caller decides, this just advances through whatever it's given. */
    fun cycleBlockFilter(jobId: String, blockId: String, names: List<String>)
    fun setBlockExposure(jobId: String, blockId: String, sec: Int)
    fun setBlockSubCount(jobId: String, blockId: String, count: Int)
    fun setBlockGain(jobId: String, blockId: String, gain: Int)
    fun setBlockOffset(jobId: String, blockId: String, offset: Int)
    fun setBlockBinning(jobId: String, blockId: String, bin: Int)
    /** `null` turns dithering off for this block — see [Block.ditherEvery]'s own doc. */
    fun setBlockDither(jobId: String, blockId: String, n: Int?)
    fun setAutofocusRefocusMin(min: Int)
    fun setAutofocusTempDelta(deltaC: Double)
    fun toggleAutofocusOnFilterChange()
    fun toggleChip(index: Int)
    fun setUserCatalogName(name: String)
    fun addUserTarget(name: String, coords: String)
    fun editUserTarget(id: String, name: String, coords: String)
    fun removeUserTarget(id: String)
    fun startAddUserTarget()
    fun cancelAddUserTarget()
    fun toggleEditUserTarget(id: String)
    fun setQuery(text: String)
    fun clearQuery()
    fun selectTarget(id: String)
    /** Fetch real per-target altitude data for the Plan tab's altitude chart, if not already cached. */
    fun ensureTargetRiseset(targetId: String)
    fun togglePref(key: String)
    fun toggleQuietHours()
    fun toggleCut(id: String)
    fun toggleDevice(key: String)
    fun selectDeviceName(key: String, name: String)
    fun setTrainRole(slot: TrainSlot, role: TrainRole, value: String)
    fun setTrainReducer(slot: TrainSlot, value: Double)
    /** Assigns which real train (by name) one Ekos module uses — real `train_set` mechanism, see [SimState.moduleTrainAssignments]. */
    fun setModuleTrain(module: String, trainName: String)

    fun snapMain()
    fun snapGuide()
    fun jogFocus(delta: Int)
    fun setRate(index: Int)
    fun setSlewDir(key: String)
    fun stopSlew()
    fun coolUp()
    fun coolDown()
    fun runAutofocusNow()
    fun startAutofocus()
    fun stopAutofocus()
    fun unparkMount()
    fun parkMount()
    fun setMountTracking(enabled: Boolean)
    fun plateSolveHere()
    fun startGuiding()
    fun stopGuiding()
    fun startPolarAlign()
    fun stopPolarAlign()

    fun startProfile(name: String)
    fun stopProfile()
    fun selectProfile(name: String)
    fun toggleEkos()
    fun deleteProfile(name: String)
    fun editProfile(name: String)
    fun setRotatorAngle(deg: Double)
    fun setDomeOpen(open: Boolean)

    fun setIndiSwitch(deviceKey: String, propName: String, selected: Int)
    fun setIndiNumber(deviceKey: String, propName: String, value: Double)
    /** [elementName] picks which element of the vector to write — a text vector can hold several (e.g. `FILTER_NAME`, one per filter-wheel slot). */
    fun setIndiText(deviceKey: String, propName: String, elementName: String, value: String)

    fun openPa()
    fun paNext()
    fun setPaRate(index: Int)
    fun openSetup()
    fun setupBack()
    fun finishSetup()
    fun setProfileName(name: String)

    /**
     * The Scopes catalog (M3.1) — real Ekos's own separate telescopes/lenses
     * dialog (`get_scopes`/`scope_add`/`scope_update`/`scope_delete`), not
     * bundled into the rig Profile or Optical Train editors. A train's Scope
     * role picks one of these by name (see [setTrainRole]/[TrainRole.SCOPE]).
     */
    fun addScope(name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int)
    fun updateScope(id: String, name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int)
    fun removeScope(id: String)
    fun startAddScope()
    fun cancelAddScope()
    fun toggleEditScope(id: String)

    /**
     * Configures the rig's companion reboot daemon (port + shared-secret token, see
     * `pi-tools/reboot-daemon/`) — a channel entirely separate from the EkosRemote wire, since
     * that wire has no OS-level reboot command and can't be relied on anyway when it's a hung
     * Ekos process that needs the reboot. Persisted and wired for real under
     * [EkosRemoteController].
     */
    fun setRigRebootConfig(port: Int, token: String)

    /** Sends the actual reboot request. See [SimState.rigRebootState] for the result. */
    fun rebootRig()

    // ── M3.3: Mount settings (curated subset, see docs/M3.3-plan.md) ──────
    fun setMountMeridianFlip(enabled: Boolean)
    fun setMountMeridianFlipOffset(deg: Double)
    fun setMountAltLimitEnabled(enabled: Boolean)
    fun setMountAltLimitMin(deg: Double)
    fun setMountAltLimitMax(deg: Double)
    fun setMountAltLimitTrackingOnly(enabled: Boolean)
    fun setMountHaLimitEnabled(enabled: Boolean)
    fun setMountHaLimitMax(hours: Double)
    fun setMountParkEveryDay(enabled: Boolean)
    fun setMountAutoParkTime(time: String)

    // ── M3.3: Camera settings (curated subset, see docs/M3.3-plan.md) ─────
    fun setCameraSaveDir(path: String)
    fun setCameraGuideDeviationEnabled(enabled: Boolean)
    fun setCameraGuideDeviation(arcsec: Double)
    fun setCameraStartGuideDriftEnabled(enabled: Boolean)
    fun setCameraStartGuideDeviation(arcsec: Double)
    fun setCameraDitherPerJobEnabled(enabled: Boolean)
    fun setCameraDitherPerJobFrequency(everyN: Int)

    // ── M3.3: Align settings (curated subset, see docs/M3.3-plan.md) ──────
    fun setAlignExposure(sec: Double)
    fun setAlignGain(gain: Double)
    fun setAlignFilter(filter: String)
    fun setAlignBinning(binning: String)
    fun setAlignAccuracyThreshold(arcsec: Double)

    // ── M3.3 phase 4: Guide settings (curated subset, see docs/M3.3-plan.md) ──
    // Named setGuide* (not setGuidePreview*, see below) to avoid colliding with the
    // pre-existing Bench "Snap guide" preview setters.
    fun setGuideAccuracyThreshold(arcsec: Double)
    fun setGuideDitherEnabled(enabled: Boolean)
    fun setGuideDitherPixels(px: Int)
    fun setGuideDitherThreshold(value: Double)
    fun setGuideReuseCalibration(enabled: Boolean)

    // ── M3.3 phase 6: Focus settings (curated subset, see docs/M3.3-plan.md) ──
    fun setFocusExposure(sec: Double)
    fun setFocusGain(gain: Double)
    fun setFocusFilter(filter: String)
    fun setFocusBacklash(steps: Int)
    fun setFocusAlgorithm(algorithm: String)

    // ── Bench "Snap main"/"Snap guide" preview capture params ──────────────
    fun setCapturePreviewExposure(sec: Double)
    fun setCapturePreviewGain(gain: Double)
    fun setCapturePreviewBinning(bin: Int)
    fun setGuidePreviewExposure(sec: Double)
    fun setGuidePreviewGain(gain: Double)
    fun setGuidePreviewBinning(binning: String)

    // ── Scheduler settings (curated subset, see WireSchedulerSettings' own doc, M2026-08) ──
    // Startup condition (mutually exclusive pair — selecting one clears the other, same
    // shape as the Completion-condition/Aborted-job groups below)
    fun setSchedulerStartAsap()
    fun setSchedulerStartAtTime(iso: String)
    fun setSchedulerLeadTime(minutes: Double)
    fun setSchedulerPreDawnTime(minutes: Double)
    // Constraints (+ per-job step defaults, same tab in real Ekos)
    fun setSchedulerAltitudeEnabled(enabled: Boolean)
    fun setSchedulerAltitudeValue(deg: Double)
    fun setSchedulerMoonSeparationEnabled(enabled: Boolean)
    fun setSchedulerMoonSeparationValue(deg: Double)
    fun setSchedulerMoonAltitudeEnabled(enabled: Boolean)
    fun setSchedulerMoonAltitudeMaxValue(deg: Double)
    fun setSchedulerTwilightEnabled(enabled: Boolean)
    fun setSchedulerHorizonEnabled(enabled: Boolean)
    fun setSchedulerDawnOffset(hours: Double)
    fun setSchedulerDuskOffset(hours: Double)
    fun setSchedulerTrackStep(enabled: Boolean)
    fun setSchedulerFocusStep(enabled: Boolean)
    fun setSchedulerAlignStep(enabled: Boolean)
    fun setSchedulerGuideStep(enabled: Boolean)
    // Completion condition
    fun setSchedulerCompleteSequences()
    fun setSchedulerRepeatSequences(limit: Int)
    fun setSchedulerRepeatEverything()
    fun setSchedulerUntilTerminated()
    fun setSchedulerUntil(iso: String)
    // Observatory startup/shutdown procedure
    fun setSchedulerStartupEnabled(enabled: Boolean)
    fun setSchedulerPreStartupScript(path: String)
    fun setSchedulerPostStartupScript(path: String)
    fun setSchedulerShutdownEnabled(enabled: Boolean)
    fun setSchedulerPreShutdownScript(path: String)
    fun setSchedulerPostShutdownScript(path: String)
    fun setSchedulerPreemptiveShutdown(enabled: Boolean)
    fun setSchedulerPreemptiveShutdownTime(hours: Double)
    fun setSchedulerStopEkosAfterShutdown(enabled: Boolean)
    fun setSchedulerShutdownScriptTerminatesIndi(enabled: Boolean)
    // Aborted-job handling
    fun setSchedulerAbortDontRestart()
    fun setSchedulerAbortRestartImmediately()
    fun setSchedulerAbortRestartQueue()
    fun setSchedulerAbortRescheduleErrors(enabled: Boolean)
    fun setSchedulerAbortDelay(minutes: Int)
}
