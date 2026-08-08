package com.nocturne.session

import kotlinx.coroutines.flow.StateFlow

/**
 * The single interface the whole UI consumes (PLAN §4.3). M1 ships
 * [SimulatedController]; M2 swaps in [EkosRemoteController] over the wire
 * protocol behind the same contract.
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
    fun removeJob(jobId: String)
    fun openJob(jobId: String)
    fun closeJob()
    fun toggleJobRun(jobId: String)
    fun endSession()
    fun resumeSession()
    fun startNextJob()
    fun finishNight()
    fun toggleBlock(jobId: String, blockId: String)
    fun addBlock(jobId: String)
    fun removeBlock(jobId: String, blockId: String)
    fun moveBlock(jobId: String, blockId: String, toIndex: Int)
    fun cycleBlockFilter(jobId: String, blockId: String)
    fun setBlockExposure(jobId: String, blockId: String, sec: Int)
    fun setBlockSubCount(jobId: String, blockId: String, count: Int)
    fun setBlockGain(jobId: String, blockId: String, gain: Int)
    fun setBlockOffset(jobId: String, blockId: String, offset: Int)
    fun setBlockBinning(jobId: String, blockId: String, bin: Int)
    fun setBlockDither(jobId: String, blockId: String, n: Int)
    fun toggleBlockForceAf(jobId: String, blockId: String)
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
    fun unparkMount()
    fun plateSolveHere()

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
    fun setIndiText(deviceKey: String, propName: String, value: String)

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
     * Ekos process that needs the reboot. No-op-ish under [SimulatedController] (nothing to
     * reboot); persisted and wired for real under [EkosRemoteController].
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
}
