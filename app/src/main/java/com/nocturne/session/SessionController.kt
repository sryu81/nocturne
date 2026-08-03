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

    fun toggleRun()
    fun toggleBlock(id: String)
    fun addBlock()
    fun removeBlock(id: String)
    fun moveBlock(id: String, toIndex: Int)
    fun cycleBlockFilter(id: String)
    fun setBlockExposure(id: String, sec: Int)
    fun setBlockSubCount(id: String, count: Int)
    fun setBlockGain(id: String, gain: Int)
    fun setBlockOffset(id: String, offset: Int)
    fun setBlockBinning(id: String, bin: Int)
    fun setBlockDither(id: String, n: Int)
    fun toggleBlockForceAf(id: String)
    fun setAutofocusRefocusMin(min: Int)
    fun setAutofocusTempDelta(deltaC: Double)
    fun toggleAutofocusOnFilterChange()
    fun toggleChip(index: Int)
    fun setQuery(text: String)
    fun clearQuery()
    fun selectTarget(id: String)
    fun togglePref(key: String)
    fun toggleCut(id: String)
    fun toggleDevice(key: String)
    fun selectDeviceName(key: String, name: String)
    fun setTrainRole(slot: TrainSlot, role: TrainRole, value: String)
    fun setTrainReducer(slot: TrainSlot, value: Double)

    fun snapMain()
    fun snapGuide()
    fun jogFocus(delta: Int)
    fun setRate(index: Int)
    fun setSlewDir(key: String)
    fun stopSlew()
    fun coolUp()
    fun coolDown()

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
    fun openBench()
    fun openSetup()
    fun setupBack()
    fun finishSetup()
    fun setScopeName(name: String)
    fun setOpticMm(mm: Int)
    fun setScopeApertureMm(mm: Int)
    fun setGuideScopeName(name: String)
    fun setGuideOpticMm(mm: Int)
    fun setGuideScopeApertureMm(mm: Int)
    fun setProfileName(name: String)
}
