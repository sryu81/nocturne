package com.nocturne.session

import kotlinx.coroutines.flow.StateFlow

/**
 * The single interface the whole UI consumes (PLAN §4.3). [EkosRemoteController] is the only
 * implementation — the app is real-rig-only (the earlier [SimulatedController] demo driver was
 * removed 2026-08-22, see docs/simulator-removal-plan.md); [AbstractLocalSessionController]
 * remains as its base class for pure local UI-state mutation.
 */
interface SessionController {
    val state: StateFlow<AppState>

    fun openSheet(sheet: SheetType)
    fun openDevice(key: String)
    fun closeSheet()
    fun selectTab()
    fun openSubPreview()
    fun closeSubPreview()
    fun expandFrame(id: String)
    fun closeFrameExpand()
    /** Frames tab top-level nav (M4.5 Part C) — null returns to the Preview/Plan picker. */
    fun selectFrameCategory(category: FrameCategory?)
    /** Frames tab target drill-down within Plan (M4.5 Part C) — null returns to the target list. */
    fun selectFrameTarget(target: String?)
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
    /** [names] to cycle through — real filter-wheel slot names when known ([AppState.realFilterNames]), else the fixture [FILTER_CYCLE]; the caller decides, this just advances through whatever it's given. */
    fun cycleBlockFilter(jobId: String, blockId: String, names: List<String>)
    fun setBlockExposure(jobId: String, blockId: String, sec: Int)
    fun setBlockSubCount(jobId: String, blockId: String, count: Int)
    fun setBlockGain(jobId: String, blockId: String, gain: Int)
    fun setBlockOffset(jobId: String, blockId: String, offset: Int)
    fun setBlockBinning(jobId: String, blockId: String, bin: Int)
    /** `null` turns dithering off for this block — see [Block.ditherEvery]'s own doc. */
    fun setBlockDither(jobId: String, blockId: String, n: Int?)
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
    /** Fetch a real DSS reference-image cutout for the Plan tab's Framing card, if not already
     * cached for this target (M5, docs/STATUS.md — see AppState.referenceImageJpeg's own doc). */
    fun ensureReferenceImage(targetId: String)
    fun toggleQuietHours()
    /** Real `option_set` — `Options::self()`'s `ekosRemoteNotifications`/`ekosRemoteSound`
     * (M4.5 half B, docs/STATUS.md). Replaced the old per-category `togglePref` fixture, which had
     * no matching real settings and zero consumers anywhere in this app. */
    fun setEkosRemoteNotifications(enabled: Boolean)
    fun setEkosRemoteSound(enabled: Boolean)
    fun toggleCut(id: String)
    fun toggleDevice(key: String)
    fun selectDeviceName(key: String, name: String)
    fun setTrainRole(slot: TrainSlot, role: TrainRole, value: String)
    fun setTrainReducer(slot: TrainSlot, value: Double)
    /** Assigns which real train (by name) one Ekos module uses — real `train_set` mechanism, see [AppState.moduleTrainAssignments]. */
    fun setModuleTrain(module: String, trainName: String)

    fun snapMain()
    fun snapGuide()
    /**
     * Real `focus_in`/`focus_out`, sent with `steps=0` deliberately — **not** a lazy default.
     * Confirmed live (2026-09): the fork's manual-move handling always substitutes the Focus
     * Mechanics "Initial Step Size" (`WireFocusSettings.focusTicks`) for whatever `steps` value
     * arrives, regardless of its value — matches real Ekos's own manual focus-in/out buttons
     * exactly (`focus.cpp`'s `handleFocusButtonEvent` hardcodes `ms=-1`, always triggering the
     * same fallback). Sending an honest `0` instead of a computed delta that gets silently
     * discarded anyway. Superseded the old fixed-amount jog row (`-1000..+1000`) and the typed-
     * absolute-position control entirely — see docs/FORK-BACKLOG.md/memory for why neither could
     * ever have worked once this was confirmed (every relative move this wire can send always
     * lands on `focusTicks`, no exceptions found).
     */
    fun focusStepIn()
    fun focusStepOut()
    fun setRate(index: Int)
    fun setSlewDir(key: String)
    fun stopSlew()
    /** Real camera cooler setpoint, typed directly (2026-09, replaced the old +/-1° buttons per
     *  user request — "put the value directly"). See [com.nocturne.session.EkosRemoteController]'s
     *  override doc for the 2 real things this writes (raw `CCD_TEMPERATURE` device property +
     *  Capture module's own `cameraTemperatureN`/`S`). */
    fun setCoolTarget(value: Double)
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
    /** Plan tab's `RotatorRow` slider — real `align_set_target_pa` in [EkosRemoteController]
     * (M5, docs/STATUS.md); local-only mirror in [AbstractLocalSessionController]. */
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

    /** Sends the actual reboot request. See [AppState.rigRebootState] for the result. */
    fun rebootRig()

    /**
     * Sets the real Pi's OS system clock (`date -s`+`hwclock -w`, via the same reboot-daemon
     * channel as [rebootRig] — no EkosRemote/INDI property controls this at all, it's the same
     * class of OS-level gap as the reboot itself, see `pi-tools/reboot-daemon/`'s own doc).
     * Requires the daemon token to already be configured ([setRigRebootConfig]); reuses
     * [AppState.rigRebootState] for progress/result, same as [rebootRig]. Sends the phone's own
     * current local wall-clock time — see [syncLocationToRig]'s doc for the same "co-located,
     * same timezone" assumption this relies on.
     */
    fun syncTimeToRig()

    /**
     * Sets KStars' own real geographic location (`GeoLocation`/`Options.Latitude/Longitude/
     * Elevation`) via the new `kstars_set_location` wire command (2026-09) — a direct in-process
     * call to `KStars::setGPSLocation`, real and immediately effective for every subsequent
     * almanac/riseset/meridian-flip calculation this session. The existing `option_set`/generic
     * `invoke_method` hatches can't reach this: `option_set` only writes the *persisted default*,
     * effective on next restart, and `invoke_method`'s `findObject()` can't resolve the top-level
     * `KStars` singleton itself (only named children) — see the new command's own comment in the
     * fork's `message.cpp` for the full finding. [lat]/[lon] in degrees, [elevM] in meters — real
     * values read from the phone's own GPS by the UI layer (`MaintenanceSheet`), passed in
     * already-resolved since this interface stays platform-agnostic. Assumes phone and rig are
     * physically co-located (same site, same timezone) — true for the normal "syncing before
     * tonight's session" use case this exists for, not meant for remote/offsite control.
     */
    fun syncLocationToRig(lat: Double, lon: Double, elevM: Double)

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
    /** See [com.nocturne.protocol.WireCaptureSettings.placeholderFormatT]'s own doc. */
    fun setCameraPlaceholderFormat(format: String)
    fun setCameraGuideDeviationEnabled(enabled: Boolean)
    fun setCameraGuideDeviation(arcsec: Double)
    fun setCameraStartGuideDriftEnabled(enabled: Boolean)
    fun setCameraStartGuideDeviation(arcsec: Double)
    fun setCameraDitherPerJobEnabled(enabled: Boolean)
    fun setCameraDitherPerJobFrequency(everyN: Int)
    /** Real "Refocus every:" trigger (2026-08-23, merged in from the old Autofocus-rules sheet) — see [com.nocturne.protocol.WireCaptureSettings.enforceRefocusEveryN]'s own doc. */
    fun setCameraRefocusEveryNEnabled(enabled: Boolean)
    fun setCameraRefocusEveryN(minutes: Int)
    /** Real "Refocus if ΔT° >:" trigger — same real source as the pair above. */
    fun setCameraRefocusOnTemperatureEnabled(enabled: Boolean)
    fun setCameraMaxFocusTemperatureDelta(deltaC: Double)
    /** See [com.nocturne.protocol.WireCaptureSettings.FilterPosCombo]'s own doc. */
    fun setCameraFilter(filter: String)

    // ── M3.3: Align settings (curated subset, see docs/M3.3-plan.md) ──────
    fun setAlignExposure(sec: Double)
    fun setAlignGain(gain: Double)
    fun setAlignFilter(filter: String)
    /** Real `alignUseCurrentFilter` checkbox — see [com.nocturne.protocol.WireAlignSettings]'s own
     * doc. When on, a solve never changes the filter (uses whatever's actually loaded); when off,
     * [setAlignFilter]'s fixed choice force-switches the filter on every solve. */
    fun setAlignUseCurrentFilter(enabled: Boolean)
    fun setAlignBinning(binning: String)
    fun setAlignAccuracyThreshold(arcsec: Double)
    /** `kcfg_AstrometryRotatorThreshold` (M5, docs/STATUS.md), arcminutes — see
     * [com.nocturne.protocol.WireAlignSettings.kcfg_AstrometryRotatorThreshold]'s own doc for why
     * this is a normal reflection field (real read+write) unlike `rotator_control`. */
    fun setAlignRotatorThreshold(arcmin: Double)

    // ── M5: rotator angle-readback + auto-drive (docs/STATUS.md M5 steps 4/5) ──
    /** `align_set_astrometry_settings`'s `rotator_control` bool — master gate for the whole
     * rotator feature (real-hardware auto-drive, or the no-hardware manual-diff readback via
     * [com.nocturne.session.AppState.wireRotatorCurrentPA]) — see [EkosRemoteController]'s own
     * doc for why it's not just the auto-drive half. */
    fun setRotatorAutoControl(enabled: Boolean)

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
    fun setFocusTicks(ticks: Int)
    fun setFocusMaxTravel(ticks: Int)
    // Mechanics + Process, practical subset (2026-09) — see WireFocusSettings' own field docs.
    fun setFocusOutSteps(multiple: Double)
    fun setFocusNumSteps(steps: Int)
    fun setFocusWalk(walk: String)
    fun setFocusAFOverscan(ticks: Int)
    fun setFocusOverscanDelay(sec: Double)
    fun setFocusMotionTimeout(sec: Int)
    fun setFocusCaptureTimeout(sec: Int)
    fun setFocusSettleTime(sec: Double)
    fun setFocusDetection(method: String)
    fun setFocusCurveFit(fit: String)
    fun setFocusStarMeasure(measure: String)
    fun setFocusTolerance(percent: Double)
    fun setFocusR2Limit(limit: Double)
    fun setFocusFramesCount(count: Int)
    fun setFocusBinning(binning: String)

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
    /** See [com.nocturne.protocol.WireSchedulerSettings.kcfg_RememberJobProgress]'s own doc. */
    fun setSchedulerRememberJobProgress(enabled: Boolean)
    /** See [com.nocturne.protocol.WireSchedulerSettings.kcfg_ForceAlignmentBeforeJob]'s own doc. */
    fun setSchedulerForceAlignmentBeforeJob(enabled: Boolean)
}
