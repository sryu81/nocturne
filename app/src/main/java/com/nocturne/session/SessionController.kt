package com.nocturne.session

import kotlinx.coroutines.flow.StateFlow

/**
 * The interface the whole UI consumes. [EkosRemoteController] is the only implementation;
 * [AbstractLocalSessionController] is its base class for local UI-state mutation.
 */
interface SessionController {
    val state: StateFlow<AppState>

    fun openSheet(sheet: SheetType)
    fun openDevice(key: String)
    fun closeSheet()
    fun selectTab()
    fun openSubPreview()
    fun closeSubPreview()
    /** Turns phone notifications (and the foreground service behind them) on or off; persisted. */
    fun setPushNotifications(enabled: Boolean)
    /** Mount card: RA/Dec in J2000 (true) or of date, JNow (false, Ekos' default). */
    fun setMountJ2000(enabled: Boolean)

    /** The full-screen Log on top of the current tab; see [AppState.logOpen]. */
    fun openLog()
    fun closeLog()
    /** Opens a module page on the Controls tab; null returns to the module menu. */
    fun openControlsModule(module: ControlsModule?)
    fun expandFrame(id: String)
    fun closeFrameExpand()
    /** Frames tab top-level nav; null returns to the Preview/Plan picker. */
    fun selectFrameCategory(category: FrameCategory?)
    /** Frames tab target drill-down within Plan; null returns to the target list. */
    fun selectFrameTarget(target: String?)
    fun requestDeferFlip()
    fun requestFlipNow()
    fun confirmFlipAction()
    fun cancelFlipConfirm()

    fun addToSequence(targetId: String)
    /** Plain slew to a target's coordinates — no plate-solve correction. */
    fun gotoTarget(targetId: String)
    /** Slew, then plate-solve and let Align's solver action re-slew onto the solved position. */
    fun gotoAndCenter(targetId: String)
    fun removeJob(jobId: String)
    fun openJob(jobId: String)
    fun closeJob()
    /** Pushes this local job to Ekos's Scheduler queue (`scheduler_add_jobs`); does not start it. */
    fun pushJob(jobId: String)
    /** Toggles the whole Scheduler on/off (`scheduler_start_job` is a toggle, not a per-job start). */
    fun toggleScheduler()
    /** Removes a Scheduler job with no local counterpart (added in KStars, or left over). */
    fun removeUnmanagedJob(name: String)
    fun endSession()
    fun resumeSession()
    fun startNextJob()
    fun finishNight()
    fun toggleBlock(jobId: String, blockId: String)
    fun addBlock(jobId: String)
    fun removeBlock(jobId: String, blockId: String)
    fun moveBlock(jobId: String, blockId: String, toIndex: Int)
    /** Advances to the next of [names] (the wheel's slot names); no-op when empty. */
    fun cycleBlockFilter(jobId: String, blockId: String, names: List<String>)
    fun setBlockExposure(jobId: String, blockId: String, sec: Int)
    fun setBlockSubCount(jobId: String, blockId: String, count: Int)
    fun setBlockGain(jobId: String, blockId: String, gain: Int)
    fun setBlockOffset(jobId: String, blockId: String, offset: Int)
    fun setBlockBinning(jobId: String, blockId: String, bin: Int)
    /** `null` turns dithering off for this block; see [Block.ditherEvery]. */
    fun setBlockDither(jobId: String, blockId: String, n: Int?)
    fun toggleChip(index: Int)
    /** Plan tab search's object type: a `SkyObject::TYPE` from [SEARCH_TYPES], or null for a
     *  name search across every catalog. */
    fun setSearchType(skyType: Int?)
    /** Plan tab search's faintest magnitude, clamped to [SEARCH_MAG_RANGE]. */
    fun setSearchMaxMag(mag: Int)
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
    /** Fetches per-target altitude data for the Plan tab's chart, if not already cached. */
    fun ensureTargetRiseset(targetId: String)
    /** Loads the reference image for the Plan tab's Framing card, if not already cached for
     *  this target (see AppState.referenceImageJpeg). */
    fun ensureReferenceImage(targetId: String)
    fun toggleQuietHours()
    /** `option_set` on `Options::self()`'s `ekosRemoteNotifications`/`ekosRemoteSound`. */
    fun setEkosRemoteNotifications(enabled: Boolean)
    fun setEkosRemoteSound(enabled: Boolean)
    fun toggleCut(id: String)
    fun toggleDevice(key: String)
    fun selectDeviceName(key: String, name: String)
    fun setTrainRole(slot: TrainSlot, role: TrainRole, value: String)
    fun setTrainReducer(slot: TrainSlot, value: Double)
    /** Assigns which train (by name) one Ekos module uses (`train_set`); see [AppState.moduleTrainAssignments]. */
    fun setModuleTrain(module: String, trainName: String)

    fun snapMain()
    fun snapGuide()
    /**
     * `focus_in`/`focus_out` with `steps=0`. Ekos's manual move ignores `steps` and always uses
     * the Focus Mechanics "Initial Step Size" (`WireFocusSettings.focusTicks`), same as its own
     * in/out buttons (`focus.cpp`'s `handleFocusButtonEvent` passes `ms=-1`), so a computed
     * delta would be discarded anyway.
     */
    fun focusStepIn()
    fun focusStepOut()
    fun setRate(index: Int)
    fun setSlewDir(key: String)
    /** Aborts everything the mount is doing (Ekos' Stop and the INDI Abort): goto, park, manual motion. */
    fun stopSlew()
    /** Writes one named element of the mount's `TELESCOPE_HOME` (`GO` or `SET`); no-op if the driver lacks it. */
    fun homeMount(element: String)
    /** Edits the cooler set point locally; nothing is sent until [applyCoolTarget] (the Set
     *  button), so partial keystrokes ("2" on the way to "20") never reach the camera. */
    fun setCoolTarget(value: Double)
    /** Set button: sends [AppState.coolTarget] to the camera (`CCD_TEMPERATURE`) and Capture's
     *  `cameraTemperatureN`, like Ekos's own Set button. Doesn't touch enforce. */
    fun applyCoolTarget()
    /** Capture's "enforce temperature" (`cameraTemperatureS`). */
    fun setTemperatureEnforced(enabled: Boolean)
    /** `CCD_COOLER` on/off on the primary camera. */
    fun setCoolerEnabled(enabled: Boolean)
    /** Camera fan switch element (ToupTek `TC_FAN_SPEED`: `INDI_DISABLED`/`INDI_ENABLED`/`FAN_SPEEDn`). */
    fun setCameraFan(element: String)
    /** `capture_stop` — cancels a preview stuck in preparation (see [Commands.CAPTURE_STOP]). */
    fun cancelPendingCapture()
    fun runAutofocusNow()
    fun startAutofocus()
    fun stopAutofocus()
    fun unparkMount()
    fun parkMount()
    fun setMountTracking(enabled: Boolean)
    fun plateSolveHere()
    fun startGuiding()
    fun stopGuiding()
    /** `guide_loop` — continuous guide-cam frames (Ekos's Loop button); [stopGuiding] ends it. */
    fun loopGuide()
    /** `guide_clear` — drop the stored calibration so the next start recalibrates. */
    fun clearGuideCalibration()
    fun startPolarAlign()
    fun stopPolarAlign()
    /** Manual-slew mode: tell PAH the hand rotation is done (`polar_slew_done`). */
    fun polarSlewDone()
    /** Start the adjust phase (`polar_refresh`) using the current refresh exposure. */
    fun polarRefresh()
    /** Finish the adjust phase (`polar_refreshing_done`). */
    fun polarRefreshDone()
    /** Pick the correction star at ([x], [y]) — 0..1 fractions of the align image. */
    fun polarSelectStar(x: Double, y: Double)
    // Polar Alignment Assistant settings — see WireAlignSettings' pAH* fields.
    fun setPahDirection(direction: String)
    fun setPahRotation(degrees: Int)
    fun setPahMountSpeed(speed: String)
    fun setPahManualSlew(enabled: Boolean)
    fun setPahExposure(sec: Double)
    fun setPahRefreshAlgorithm(algorithm: String)

    fun startProfile(name: String)
    fun stopProfile()
    fun selectProfile(name: String)
    fun toggleEkos()
    /** Ekos' Disconnect button: every connected INDI device is told to disconnect (closes the
     *  cameras properly, so a cooler stops). Stop Ekos is only offered once all have reported it. */
    fun disconnectDevices()
    fun deleteProfile(name: String)
    fun editProfile(name: String)
    /** Plan tab's `RotatorRow` slider: `align_set_target_pa` in [EkosRemoteController]; a local
     *  mirror in [AbstractLocalSessionController]. */
    fun setRotatorAngle(deg: Double)
    fun setDomeOpen(open: Boolean)

    fun setIndiSwitch(deviceKey: String, propName: String, selected: Int)
    fun setIndiNumber(deviceKey: String, propName: String, value: Double)
    /** [elementName] picks which element of the vector to write — a text vector can hold several (e.g. `FILTER_NAME`, one per filter-wheel slot). */
    fun setIndiText(deviceKey: String, propName: String, elementName: String, value: String)

    fun openSetup()
    fun setupBack()
    fun finishSetup()
    fun setProfileName(name: String)

    /**
     * The Scopes catalog: Ekos's separate telescopes/lenses list
     * (`get_scopes`/`scope_add`/`scope_update`/`scope_delete`). A train's Scope role
     * picks one of these by name (see [setTrainRole]/[TrainRole.SCOPE]).
     */
    fun addScope(name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int)
    fun updateScope(id: String, name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int)
    fun removeScope(id: String)
    fun startAddScope()
    fun cancelAddScope()
    fun toggleEditScope(id: String)

    /**
     * Sets the port of the rig's reboot daemon (same host, see `pi-tools/reboot-daemon/`). It
     * is separate from the EkosRemote wire, which has no OS-level reboot and is unusable when
     * a hung Ekos is the reason to reboot.
     */
    fun setRigRebootPort(port: Int)

    /** Sends the actual reboot request. See [AppState.rigRebootState] for the result. */
    fun rebootRig()

    /** Powers the Pi off through the same daemon; it stays off until power is cycled. See [AppState.rigShutdownState]. */
    fun shutdownRig()

    /** Re-reads the offline sky atlas on the phone and asks the rig how much it can serve. */
    fun refreshSkyAtlas()

    /** Copies missing sky atlas tiles from the rig (reboot daemon `/hips/`); resumable. */
    fun copySkyAtlasFromRig()

    fun cancelSkyAtlasCopy()

    /** Sets Ekos's capture frame to the main camera's whole sensor and saves it with the optical
     *  train (`capture_set_all_settings` persists; Ekos's Reset button doesn't, so a train
     *  refresh would bring the old crop back). See [AppState.primaryImageSize]. */
    fun setCaptureFullFrame()

    /** Deletes the phone's copy of the sky atlas. */
    fun clearSkyAtlas()

    /**
     * Sets the Pi's system clock to the phone's time (`date -s` + `hwclock -w` via the reboot
     * daemon; no EkosRemote/INDI property does this). Result in [AppState.piTimeSyncState].
     * Assumes phone and rig share a site and timezone, as in [syncLocationToRig].
     */
    fun syncTimeToRig()

    /**
     * Sets KStars' geographic location via `kstars_set_location` (`KStars::setGPSLocation`),
     * effective immediately for almanac/riseset/meridian-flip math. `option_set` only writes
     * the persisted default (applied on restart), and `invoke_method` can't resolve the
     * `KStars` singleton. [lat]/[lon] in degrees, [elevM] in meters, from the phone's GPS.
     * Assumes phone and rig are co-located.
     */
    fun syncLocationToRig(lat: Double, lon: Double, elevM: Double)

    // ── Mount settings (curated subset) ──────────────────────────────────
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

    // ── Camera settings (curated subset) ─────────────────────────────────
    fun setCameraSaveDir(path: String)
    /** See [com.nocturne.protocol.WireCaptureSettings.placeholderFormatT]. */
    fun setCameraPlaceholderFormat(format: String)
    fun setCameraGuideDeviationEnabled(enabled: Boolean)
    fun setCameraGuideDeviation(arcsec: Double)
    fun setCameraStartGuideDriftEnabled(enabled: Boolean)
    fun setCameraStartGuideDeviation(arcsec: Double)
    fun setCameraDitherPerJobEnabled(enabled: Boolean)
    fun setCameraDitherPerJobFrequency(everyN: Int)
    /** "Refocus every:" trigger; see [com.nocturne.protocol.WireCaptureSettings.enforceRefocusEveryN]. */
    fun setCameraRefocusEveryNEnabled(enabled: Boolean)
    fun setCameraRefocusEveryN(minutes: Int)
    /** "Refocus if ΔT° >:" trigger, same source as the pair above. */
    fun setCameraRefocusOnTemperatureEnabled(enabled: Boolean)
    fun setCameraMaxFocusTemperatureDelta(deltaC: Double)
    /** See [com.nocturne.protocol.WireCaptureSettings.FilterPosCombo]. */
    fun setCameraFilter(filter: String)

    // ── Align settings (curated subset) ──────────────────────────────────
    fun setAlignExposure(sec: Double)
    fun setAlignGain(gain: Double)
    fun setAlignFilter(filter: String)
    /** `alignUseCurrentFilter`: when on, a solve uses whatever filter is loaded; when off,
     *  [setAlignFilter]'s choice is switched in on every solve. */
    fun setAlignUseCurrentFilter(enabled: Boolean)
    fun setAlignBinning(binning: String)
    fun setAlignAccuracyThreshold(arcsec: Double)
    /** `kcfg_AstrometryRotatorThreshold`, arcminutes; see
     *  [com.nocturne.protocol.WireAlignSettings.kcfg_AstrometryRotatorThreshold]. */
    fun setAlignRotatorThreshold(arcmin: Double)

    // ── Rotator angle readback + auto-drive ──────────────────────────────
    /** `align_set_astrometry_settings`'s `rotator_control`: gates the whole rotator feature,
     *  both hardware auto-drive and the manual readback via
     *  [com.nocturne.session.AppState.wireRotatorCurrentPA]. */
    fun setRotatorAutoControl(enabled: Boolean)

    // ── Guide settings (curated subset) ──────────────────────────────────
    // setGuide*, distinct from the Bench "Snap guide" setGuidePreview* setters below.
    fun setGuideAccuracyThreshold(arcsec: Double)
    fun setGuideDitherEnabled(enabled: Boolean)
    fun setGuideDitherPixels(px: Int)
    fun setGuideDitherThreshold(value: Double)
    fun setGuideReuseCalibration(enabled: Boolean)
    fun setGuideDarkFrame(enabled: Boolean)
    fun setGuideSubframe(enabled: Boolean)
    fun setGuideAutoStar(enabled: Boolean)
    fun setGuideBoxSize(px: String)
    fun setGuideDitherSettle(sec: Int)
    fun setGuideDitherTimeout(sec: Int)
    fun setGuideDitherMaxIterations(n: Int)
    fun setGuideDitherFailAbortsGuide(enabled: Boolean)
    fun setGuideCalibrationPulse(ms: Int)
    fun setGuideCalibrationMaxMove(px: Int)
    fun setGuideTwoAxis(enabled: Boolean)
    fun setGuideCalibrationBacklash(enabled: Boolean)
    fun setGuideResetCalibration(enabled: Boolean)
    fun setGuideReverseDecOnFlip(enabled: Boolean)
    fun setGuideLostStarTimeout(sec: Int)

    // ── Focus settings (curated subset) ──────────────────────────────────
    fun setFocusExposure(sec: Double)
    fun setFocusGain(gain: Double)
    fun setFocusFilter(filter: String)
    fun setFocusBacklash(steps: Int)
    fun setFocusAlgorithm(algorithm: String)
    fun setFocusTicks(ticks: Int)
    fun setFocusMaxTravel(ticks: Int)
    // Mechanics + Process subset; see WireFocusSettings' field docs.
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

    // ── Scheduler settings (curated subset, see WireSchedulerSettings) ────
    // Startup condition (mutually exclusive, like the Completion and Aborted-job groups)
    fun setSchedulerStartAsap()
    fun setSchedulerStartAtTime(iso: String)
    fun setSchedulerLeadTime(minutes: Double)
    fun setSchedulerPreDawnTime(minutes: Double)
    // Constraints (+ per-job step defaults, same tab in Ekos)
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
    /** See [com.nocturne.protocol.WireSchedulerSettings.kcfg_RememberJobProgress]. */
    fun setSchedulerRememberJobProgress(enabled: Boolean)
    /** See [com.nocturne.protocol.WireSchedulerSettings.kcfg_ForceAlignmentBeforeJob]. */
    fun setSchedulerForceAlignmentBeforeJob(enabled: Boolean)
}
