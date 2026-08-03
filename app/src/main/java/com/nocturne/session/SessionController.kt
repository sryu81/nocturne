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
    fun toggleBlock(index: Int)
    fun toggleChip(index: Int)
    fun setQuery(text: String)
    fun clearQuery()
    fun selectTarget(id: String)
    fun togglePref(key: String)
    fun toggleCut(id: String)
    fun toggleDevice(key: String)

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

    fun setIndiSwitch(deviceKey: String, propName: String, selected: Int)
    fun setIndiNumber(deviceKey: String, propName: String, value: Double)
    fun setIndiText(deviceKey: String, propName: String, value: String)

    fun openPa()
    fun paNext()
    fun setPaRate(index: Int)
    fun openBench()
    fun openSetup()
    fun setupNext()
    fun setupBack()
    fun finishSetup()
    fun setOpticMm(mm: Int)
    fun setGuideOpticMm(mm: Int)
    fun setProfileName(name: String)
}
