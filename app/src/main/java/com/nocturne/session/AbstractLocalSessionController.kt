package com.nocturne.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The local UI/state half of [SessionController]: sheet navigation, job/block editing,
 * target search, prefs, device picker, train editor, profile wizard. [EkosRemoteController]
 * overrides whatever has a wire command; the rest stays local-only (mostly UI chrome, a few
 * items have no wire command at all).
 */
abstract class AbstractLocalSessionController : SessionController {

    protected val _state = MutableStateFlow(AppState())
    override val state: StateFlow<AppState> = _state.asStateFlow()

    protected inline fun update(crossinline f: (AppState) -> AppState) {
        _state.update(f)
    }

    override fun openSheet(sheet: SheetType) {
        update { s ->
            when (sheet) {
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
    override open fun setMountJ2000(enabled: Boolean) = update { it.copy(mountJ2000 = enabled) }
    override open fun setPushNotifications(enabled: Boolean) = update { it.copy(pushNotificationsEnabled = enabled) }
    override fun openLog() = update { it.copy(logOpen = true) }
    override fun closeLog() = update { it.copy(logOpen = false) }
    override fun openControlsModule(module: ControlsModule?) = update { it.copy(controlsModule = module) }
    override fun expandFrame(id: String) = update { it.copy(expandedFrameId = id) }
    override fun closeFrameExpand() = update { it.copy(expandedFrameId = null) }
    // Changing category also clears the drilled-into target so the picker never keeps a stale one.
    override fun selectFrameCategory(category: FrameCategory?) = update {
        it.copy(frameCategory = category, frameTarget = null)
    }
    override fun selectFrameTarget(target: String?) = update { it.copy(frameTarget = target) }

    override fun requestDeferFlip() = update { it.copy(pendingFlipConfirm = FlipConfirm.DEFER) }
    override fun requestFlipNow() = update { it.copy(pendingFlipConfirm = FlipConfirm.NOW) }
    override fun cancelFlipConfirm() = update { it.copy(pendingFlipConfirm = null) }

    override fun confirmFlipAction() = update { s ->
        val applied = when (s.pendingFlipConfirm) {
            // Matches Ekos's meridian-flip delay: push the deadline back 10 min.
            FlipConfirm.DEFER -> s.copy(flipDeferSec = s.flipDeferSec + 600)
            // The wire has no manual flip trigger (executeMeridianFlip is a setting, not an
            // RPC), so this only zeroes the countdown.
            FlipConfirm.NOW -> s.copy(flipDeferSec = s.t - 2530)
            null -> s
        }
        applied.copy(pendingFlipConfirm = null)
    }

    /**
     * Always creates a new job, even if the target already has one: one target can have several
     * jobs with different filters/exposures. [AppState.targetNameFor] adds a per-job suffix so
     * their Ekos names don't collide.
     */
    override fun addToSequence(targetId: String) {
        update { s ->
            val block = Block(
                id = "b1", filter = s.realFilterNames?.firstOrNull() ?: "", exposureSec = 300, subCount = 10, doneCount = 0,
                gain = 100, offset = 50, binning = 1, ditherEvery = 2,
            )
            val job = SequenceJob(
                id = "j${s.jobSeq}", targetId = targetId, blocks = listOf(block), blockSeq = 2,
                // Keeps the Scheduler name/`.esq` filename unique even if job.id repeats
                // (reinstall, queue reset); see AppState.targetNameFor.
                createdAtMs = System.currentTimeMillis(),
            )
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
     * Local navigation only; whether a job is running comes from Ekos (`AppState.wireJobFor`).
     * [EkosRemoteController] overrides these three to also drive the Scheduler
     * (stop+remove / push+start).
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
        s.copy(jobs = emptyList(), sheet = null, lastEndedJobId = null, lastActiveJobId = null)
    }

    override fun toggleBlock(jobId: String, blockId: String) = update { s ->
        s.copy(openBlockId = if (s.openBlockId == blockId) null else blockId)
    }

    override fun addBlock(jobId: String) = update { s ->
        val job = s.jobs.firstOrNull { it.id == jobId } ?: return@update s
        val names = s.realFilterNames.orEmpty()
        val used = job.blocks.map { it.filter }.toSet()
        // no filter until the wheel's slot names are known
        val filter = names.firstOrNull { it !in used } ?: names.getOrNull(job.blockSeq % names.size.coerceAtLeast(1)) ?: ""
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
        if (names.isEmpty()) return@update s
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

    override fun setSearchType(skyType: Int?) = update { it.copy(searchType = skyType) }

    override fun setSearchMaxMag(mag: Int) = update {
        it.copy(searchMaxMag = mag.coerceIn(SEARCH_MAG_RANGE.first, SEARCH_MAG_RANGE.last))
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

    override fun selectTarget(id: String) = update { it.copy(targetId = id, targetSnapshot = it.findTarget(id)) }
    // Both need the rig and a coroutine scope; EkosRemoteController implements them.
    override fun ensureTargetRiseset(targetId: String) {}
    override fun ensureReferenceImage(targetId: String) {}

    override fun setEkosRemoteNotifications(enabled: Boolean) = update { it.copy(wireEkosRemoteNotifications = enabled) }
    override fun setEkosRemoteSound(enabled: Boolean) = update { it.copy(wireEkosRemoteSound = enabled) }

    override fun toggleQuietHours() = update { it.copy(quietHoursEnabled = !it.quietHoursEnabled) }

    // Optimistic flip; EkosRemoteController also writes it to Room, whose Flow then supersedes it.
    override fun toggleCut(id: String) = update { s ->
        s.copy(frameRows = s.frameRows.map { if (it.id.toString() == id) it.copy(keep = !it.keep) else it })
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

    // [EkosRemoteController] overrides both to talk to the reboot daemon; without a rig a
    // reboot attempt reports failure rather than pretending to succeed.
    override open fun setRigRebootPort(port: Int) = update { s -> s.copy(rigRebootPort = port) }

    override open fun rebootRig() = update { s ->
        s.copy(rigRebootState = RigRebootState.FAILED, rigRebootError = "No real rig connected — nothing to reboot")
    }

    override open fun shutdownRig() = update { s ->
        s.copy(rigShutdownState = RigRebootState.FAILED, rigShutdownError = "No real rig connected — nothing to shut down")
    }

    // The sky atlas comes from the rig; EkosRemoteController overrides these.
    override open fun refreshSkyAtlas() {}
    override open fun copySkyAtlasFromRig() = update { s ->
        s.copy(skyAtlas = s.skyAtlas.copy(error = "No real rig connected — nothing to copy from"))
    }
    override open fun cancelSkyAtlasCopy() {}
    override open fun setCaptureFullFrame() {}
    override open fun clearSkyAtlas() {}

    override open fun syncTimeToRig() = update { s ->
        s.copy(piTimeSyncState = RigRebootState.FAILED, piTimeSyncError = "No real rig connected — nothing to sync")
    }

    override open fun syncLocationToRig(lat: Double, lon: Double, elevM: Double) {
        // No-op: needs the wire to send `kstars_set_location`.
    }

    // Module settings below only edit the wire*Settings structs, which stay null until the rig
    // replies; the sheets are gated on them being non-null.
    // Mount settings.
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

    // Camera settings.
    override open fun setCameraSaveDir(path: String) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(fileDirectoryT = path))
    }
    override open fun setCameraPlaceholderFormat(format: String) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(placeholderFormatT = format))
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

    // Align settings.
    override open fun setAlignExposure(sec: Double) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignExposure = sec))
    }
    override open fun setAlignGain(gain: Double) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignGain = gain))
    }
    override open fun setAlignFilter(filter: String) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignFilter = filter))
    }
    override open fun setAlignUseCurrentFilter(enabled: Boolean) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignUseCurrentFilter = enabled))
    }
    override open fun setAlignBinning(binning: String) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignBinning = binning))
    }
    override open fun setAlignAccuracyThreshold(arcsec: Double) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(alignAccuracyThreshold = arcsec))
    }
    override open fun setAlignRotatorThreshold(arcmin: Double) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(kcfg_AstrometryRotatorThreshold = arcmin))
    }

    // Rotator: local intent only; EkosRemoteController overrides this to also send it.
    override open fun setRotatorAutoControl(enabled: Boolean) =
        update { it.copy(rotatorAutoControl = enabled, wireAstrometryUseRotator = enabled) }

    // Guide settings.
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
    override open fun setGuideDarkFrame(enabled: Boolean) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(guideDarkFrame = enabled))
    }
    override open fun setGuideSubframe(enabled: Boolean) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(guideSubframe = enabled))
    }
    override open fun setGuideAutoStar(enabled: Boolean) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(guideAutoStar = enabled))
    }
    override open fun setGuideBoxSize(px: String) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(guideSquareSize = px))
    }
    override open fun setGuideDitherSettle(sec: Int) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_DitherSettle = sec))
    }
    override open fun setGuideDitherTimeout(sec: Int) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_DitherTimeout = sec))
    }
    override open fun setGuideDitherMaxIterations(n: Int) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_DitherMaxIterations = n))
    }
    override open fun setGuideDitherFailAbortsGuide(enabled: Boolean) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_DitherFailAbortsAutoGuide = enabled))
    }
    override open fun setGuideCalibrationPulse(ms: Int) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_CalibrationPulseDuration = ms))
    }
    override open fun setGuideCalibrationMaxMove(px: Int) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_CalibrationMaxMove = px))
    }
    override open fun setGuideTwoAxis(enabled: Boolean) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_TwoAxisEnabled = enabled))
    }
    override open fun setGuideCalibrationBacklash(enabled: Boolean) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_GuideCalibrationBacklash = enabled))
    }
    override open fun setGuideResetCalibration(enabled: Boolean) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_ResetGuideCalibration = enabled))
    }
    override open fun setGuideReverseDecOnFlip(enabled: Boolean) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_ReverseDecOnPierSideChange = enabled))
    }
    override open fun setGuideLostStarTimeout(sec: Int) = update { s ->
        s.copy(wireGuideSettings = s.wireGuideSettings?.copy(kcfg_GuideLostStarTimeout = sec))
    }

    // Focus settings.
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
    override open fun setFocusTicks(ticks: Int) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusTicks = ticks))
    }
    override open fun setFocusMaxTravel(ticks: Int) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusMaxTravel = ticks))
    }
    override open fun setFocusOutSteps(multiple: Double) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusOutSteps = multiple))
    }
    override open fun setFocusNumSteps(steps: Int) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusNumSteps = steps))
    }
    override open fun setFocusWalk(walk: String) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusWalk = walk))
    }
    override open fun setFocusAFOverscan(ticks: Int) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusAFOverscan = ticks))
    }
    override open fun setFocusOverscanDelay(sec: Double) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusOverscanDelay = sec))
    }
    override open fun setFocusMotionTimeout(sec: Int) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusMotionTimeout = sec))
    }
    override open fun setFocusCaptureTimeout(sec: Int) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusCaptureTimeout = sec))
    }
    override open fun setFocusSettleTime(sec: Double) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusSettleTime = sec))
    }
    override open fun setFocusDetection(method: String) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusDetection = method))
    }
    override open fun setFocusCurveFit(fit: String) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusCurveFit = fit))
    }
    override open fun setFocusStarMeasure(measure: String) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusStarMeasure = measure))
    }
    override open fun setFocusTolerance(percent: Double) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusTolerance = percent))
    }
    override open fun setFocusR2Limit(limit: Double) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusR2Limit = limit))
    }
    override open fun setFocusFramesCount(count: Int) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusFramesCount = count))
    }
    override open fun setFocusBinning(binning: String) = update { s ->
        s.copy(wireFocusSettings = s.wireFocusSettings?.copy(focusBinning = binning))
    }

    // Primary/guide preview capture params.
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

    // ── Scheduler settings (curated subset, see WireSchedulerSettings) ────
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
    override open fun setSchedulerForceAlignmentBeforeJob(enabled: Boolean) = update { s ->
        s.copy(wireSchedulerSettings = s.wireSchedulerSettings?.copy(kcfg_ForceAlignmentBeforeJob = enabled))
    }

    override fun snapMain() = update { it.copy(snappedMain = true) }
    override fun snapGuide() = update { it.copy(snappedGuide = true) }

    // Moves by `focusTicks` (the app sends steps=0); see [SessionController.focusStepIn].
    override open fun focusStepIn() = update { s ->
        val step = s.wireFocusSettings?.focusTicks ?: 200
        s.copy(focPos = s.focPos?.let { (it - step).coerceIn(0, 62000) })
    }
    override open fun focusStepOut() = update { s ->
        val step = s.wireFocusSettings?.focusTicks ?: 200
        s.copy(focPos = s.focPos?.let { (it + step).coerceIn(0, 62000) })
    }

    override fun setRate(index: Int) = update { it.copy(rate = index) }

    override fun setSlewDir(key: String) = update { s ->
        s.copy(slewDir = if (s.slewDir == key) null else key, mountSolved = false)
    }

    override fun stopSlew() = update { it.copy(slewDir = null) }

    override open fun setCoolTarget(value: Double) = update { it.copy(coolTarget = value.coerceIn(-50.0, 40.0)) }
    override open fun applyCoolTarget() {}
    override open fun setTemperatureEnforced(enabled: Boolean) = update { s ->
        s.copy(wireCaptureSettings = s.wireCaptureSettings?.copy(cameraTemperatureS = enabled))
    }
    override open fun setCoolerEnabled(enabled: Boolean) {}
    override open fun setCameraFan(element: String) {}
    override open fun cancelPendingCapture() {}

    // focusLastAfAt/focusTempAtLastAf feed focusNextAfMin's countdown and TEMP Δ; set
    // optimistically ahead of EkosRemoteController's FOCUS_START send.
    override fun runAutofocusNow() = update { s ->
        s.copy(focusLastAfAt = s.t, focusTempAtLastAf = s.eafTemp)
    }
    override fun startAutofocus() = update { it.copy(focusRunning = true) }
    override fun stopAutofocus() = update { it.copy(focusRunning = false) }

    override fun unparkMount() = update {
        it.copy(mountParked = false, slewDir = null, mountSolved = false)
    }
    override fun parkMount() = update { it.copy(mountParked = true, slewDir = null) }
    override fun setMountTracking(enabled: Boolean) = update { it.copy(mountTracking = enabled) }

    override fun plateSolveHere() = update { it.copy(mountSolved = true) }
    // No local mount-position model (mountAlt/mountAz are display-only), so a plain goto does
    // nothing; "& center" marks solved like plateSolveHere.
    override fun gotoTarget(targetId: String) {}
    override fun gotoAndCenter(targetId: String) = update { it.copy(mountSolved = true) }
    override fun startGuiding() = update { it.copy(guiding = true) }
    override fun stopGuiding() = update { it.copy(guiding = false) }
    override fun loopGuide() = update { it.copy(guiding = true) }
    override fun clearGuideCalibration() {}
    override fun startPolarAlign() = update { it.copy(polarRunning = true) }
    override fun stopPolarAlign() = update { it.copy(polarRunning = false) }
    override fun polarSlewDone() {}
    override fun polarRefresh() {}
    override fun polarRefreshDone() = update { it.copy(polarRunning = false) }
    override fun polarSelectStar(x: Double, y: Double) {}
    override open fun setPahDirection(direction: String) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(pAHDirection = direction))
    }
    override open fun setPahRotation(degrees: Int) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(pAHRotation = degrees))
    }
    override open fun setPahMountSpeed(speed: String) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(pAHMountSpeed = speed))
    }
    override open fun setPahManualSlew(enabled: Boolean) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(pAHManualSlew = enabled))
    }
    override open fun setPahExposure(sec: Double) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(pAHExposure = sec))
    }
    override open fun setPahRefreshAlgorithm(algorithm: String) = update { s ->
        s.copy(wireAlignSettings = s.wireAlignSettings?.copy(pAHRefreshAlgorithm = algorithm))
    }


    override open fun startProfile(name: String) = update { s ->
        if (s.profiles.none { it.name == name }) return@update s
        s.copy(ekosRunning = true, activeProfile = name, selectedProfile = name)
    }

    override open fun stopProfile() = update { it.copy(ekosRunning = false, activeProfile = null) }

    override fun selectProfile(name: String) = update { it.copy(selectedProfile = name) }

    override open fun homeMount(element: String) {}

    override open fun disconnectDevices() = update { s ->
        s.copy(wireDevices = s.wireDevices?.map { it.copy(connected = false) })
    }

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
        val current = s.indiProps[deviceKey] ?: emptyList()
        s.copy(indiProps = s.indiProps + (deviceKey to current.map { p ->
            if (p is IndiProperty.SwitchProp && p.name == propName) p.copy(selected = selected) else p
        }))
    }

    override open fun setIndiNumber(deviceKey: String, propName: String, value: Double) = update { s ->
        val current = s.indiProps[deviceKey] ?: emptyList()
        s.copy(indiProps = s.indiProps + (deviceKey to current.map { p ->
            if (p is IndiProperty.NumberProp && p.name == propName) p.copy(value = value.coerceIn(p.min, p.max)) else p
        }))
    }

    override open fun setIndiText(deviceKey: String, propName: String, elementName: String, value: String) = update { s ->
        val current = s.indiProps[deviceKey] ?: emptyList()
        s.copy(indiProps = s.indiProps + (deviceKey to current.map { p ->
            if (p is IndiProperty.TextProp && p.name == propName) {
                p.copy(elements = p.elements.map { if (it.first == elementName) elementName to value else it })
            } else p
        }))
    }

    override fun openSetup() = update { s ->
        // A new profile starts with nothing chosen, so Save never sends defaults or leftovers
        // from a previous edit.
        if (s.ekosRunning) s else s.copy(
            sheet = SheetType.SETUP, setupEditingName = null, profileName = "New profile",
            selectedDeviceNames = DEVICES.associate { it.key to "None" },
            devOff = DEVICES.map { it.key }.toSet(),
        )
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
