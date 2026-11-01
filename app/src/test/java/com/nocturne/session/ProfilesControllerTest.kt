package com.nocturne.session

import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.ProfileTrainSetting
import com.nocturne.protocol.WireProfile
import com.nocturne.protocol.WireScope
import com.nocturne.protocol.WireTrain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the profile, train and scope behaviour that used to sit inside [EkosRemoteController]. */
class ProfilesControllerTest {

    private class Fixture(initial: AppState = AppState()) {
        val state = MutableStateFlow(initial)
        val sent = mutableListOf<Pair<String, JsonElement>>()
        val ctx = ControllerContext(
            sender = { type, payload -> sent += type to payload },
            link = { true },
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state.value },
            update = { f -> state.update(f) },
        )
        val profiles = ProfilesController(ctx, log = null)

        fun reply(event: EkosEvent) {
            state.update { s -> profiles.reduce(s, event) ?: s }
        }

        fun commands(type: String) = sent.filter { it.first == type }.map { it.second.jsonObject }
    }

    private fun profile(name: String, vararg drivers: Pair<String, List<String>>) =
        WireProfile(name = name, drivers = mapOf(*drivers))

    private fun train(id: Int, name: String) = WireTrain(id = id, name = name, camera = "ToupTek ATR2600M")

    @Test
    fun theProfileListIsMappedAndTheKnownDriversAreUnioned() {
        val f = Fixture()
        f.reply(
            EkosEvent.Profiles(
                selectedProfile = "RedCat",
                profiles = listOf(
                    profile("RedCat", "CCDs" to listOf("ToupTek", "G3M178M"), "Telescopes" to listOf("LX200 OnStep")),
                    profile("Sim", "CCDs" to listOf("ToupTek", "CCD Simulator")),
                ),
            ),
        )
        val s = f.state.value
        assertEquals(listOf("RedCat", "Sim"), s.profiles.map { it.name })
        assertEquals("RedCat", s.selectedProfile)
        assertEquals(listOf("ToupTek", "G3M178M", "CCD Simulator"), s.wireKnownDrivers!!["CCDs"])   // distinct, in order
        assertEquals(listOf("LX200 OnStep"), s.wireKnownDrivers!!["Telescopes"])
    }

    @Test
    fun aDeleteThatTheNextListStillShowsIsReportedOnceAsRefused() {
        val f = Fixture()
        f.profiles.deleteProfile("Active")
        assertEquals("Active", f.commands("profile_delete").single()["name"]!!.jsonPrimitive.content)

        f.reply(EkosEvent.Profiles(profiles = listOf(profile("Active"), profile("Other"))))
        assertEquals("Active", f.state.value.profileDeleteRefused)        // it is still there: Ekos refused

        f.reply(EkosEvent.Profiles(profiles = listOf(profile("Active"), profile("Other"))))
        assertNull(f.state.value.profileDeleteRefused)                   // reported only once
    }

    @Test
    fun aDeleteThatWorkedIsNotReported() {
        val f = Fixture()
        f.profiles.deleteProfile("Old")
        f.reply(EkosEvent.Profiles(profiles = listOf(profile("Keep"))))
        assertNull(f.state.value.profileDeleteRefused)
    }

    @Test
    fun theModuleAssignmentsResolveWhicheverTrainReplyArrivesFirst() {
        val trains = listOf(train(7, "Primary"), train(9, "Secondary"))
        val assignments = mapOf(ProfileTrainSetting.CAPTURE to 7, ProfileTrainSetting.GUIDE to 9, ProfileTrainSetting.PRIMARY to 7)
        val a = Fixture().also { it.reply(EkosEvent.Trains(trains)); it.reply(EkosEvent.TrainProfiles(assignments)) }
        val b = Fixture().also { it.reply(EkosEvent.TrainProfiles(assignments)); it.reply(EkosEvent.Trains(trains)) }
        for (f in listOf(a, b)) {
            assertEquals(mapOf("capture" to "Primary", "guide" to "Secondary"), f.state.value.moduleTrainAssignments)
        }
    }

    @Test
    fun aStaleTrainIdLeavesThePreviousChoiceAlone() {
        val f = Fixture()
        f.reply(EkosEvent.Trains(listOf(train(7, "Primary"))))
        f.reply(EkosEvent.TrainProfiles(mapOf(ProfileTrainSetting.CAPTURE to 7)))
        f.reply(EkosEvent.TrainProfiles(mapOf(ProfileTrainSetting.CAPTURE to 99)))   // matches no train
        assertEquals("Primary", f.state.value.moduleTrainAssignments!!["capture"])
    }

    @Test
    fun anOptimisticPickSurvivesAnOlderReplyUntilTheRigAgrees() {
        val f = Fixture()
        f.reply(EkosEvent.Trains(listOf(train(7, "Primary"), train(9, "Secondary"))))
        f.reply(EkosEvent.TrainProfiles(mapOf(ProfileTrainSetting.CAPTURE to 7)))

        f.profiles.setModuleTrain("capture", "Secondary")
        assertEquals(listOf("train_set", "train_get_profiles"), f.sent.map { it.first })
        assertEquals("Secondary", f.commands("train_set").single()["name"]!!.jsonPrimitive.content)

        f.reply(EkosEvent.TrainProfiles(mapOf(ProfileTrainSetting.CAPTURE to 7)))     // persistence lags: the old train
        assertEquals("Secondary", f.state.value.moduleTrainAssignments!!["capture"])
        f.reply(EkosEvent.TrainProfiles(mapOf(ProfileTrainSetting.CAPTURE to 9)))     // now it agrees
        assertEquals("Secondary", f.state.value.moduleTrainAssignments!!["capture"])
        f.reply(EkosEvent.TrainProfiles(mapOf(ProfileTrainSetting.CAPTURE to 7)))     // pick is spent: the rig's word wins
        assertEquals("Primary", f.state.value.moduleTrainAssignments!!["capture"])
    }

    @Test
    fun theScopeCatalogIsMapped() {
        val f = Fixture()
        f.reply(EkosEvent.Scopes(listOf(WireScope(id = "3", model = "Redcat 51", vendor = "Askar", type = "Refractor", name = "Redcat 51", focal_length = 250.0, aperture = 51.0))))
        val scope = f.state.value.wireScopes!!.single()
        assertEquals("3", scope.id)
        assertEquals(250, scope.focalMm)
    }

    @Test
    fun aTrainIsAddedWhenTheRigHasNoneAndUpdatedWhenItHasOne() {
        val noTrains = Fixture()
        noTrains.profiles.sendTrainUpdate(TrainSlot.PRIMARY)
        val add = noTrains.commands("train_add").single()
        assertEquals("Primary", add["name"]!!.jsonPrimitive.content)
        assertTrue(!add.containsKey("id"))

        val withTrain = Fixture(AppState(wireTrains = listOf(train(7, "RedCat51"))))
        withTrain.profiles.sendTrainUpdate(TrainSlot.PRIMARY)
        val update = withTrain.commands("train_update").single()
        assertEquals(7, update["id"]!!.jsonPrimitive.content.toInt())
        assertEquals("RedCat51", update["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun aScopeEditGoesToTheRigOnlyWhenTheRigKnowsIt() {
        val known = Fixture(AppState(wireScopes = listOf(ScopeDef(id = "3", name = "A", focalMm = 250, apertureMm = 51))))
        known.profiles.updateScope("3", "A2", "V", "T", 300, 60)
        assertEquals("3", known.commands("scope_update").single()["id"]!!.jsonPrimitive.content)

        val local = Fixture(AppState(wireScopes = emptyList()))
        local.profiles.updateScope("local-1", "B", "V", "T", 300, 60)
        assertEquals(0, local.commands("scope_update").size)
        assertEquals("B", local.commands("scope_add").single()["model"]!!.jsonPrimitive.content)   // a local-only scope is added

        known.profiles.removeScope("3")
        assertEquals(1, known.commands("scope_delete").size)
        local.profiles.removeScope("local-1")
        assertEquals(0, local.commands("scope_delete").size)
    }

    @Test
    fun startAndStopSendTheProfileCommands() {
        val f = Fixture()
        f.profiles.startProfile("RedCat")
        f.profiles.stopProfile()
        assertEquals(listOf("profile_start", "profile_stop"), f.sent.map { it.first })
        assertEquals("RedCat", f.commands("profile_start").single()["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun disconnectOnlyTouchesTheConnectedDevices() {
        val f = Fixture(AppState(wireDevices = listOf(LiveDevice("Mount", true, emptySet()), LiveDevice("Camera", false, emptySet()), LiveDevice("Focuser", true, emptySet()))))
        f.profiles.disconnectDevices()
        val devices = f.commands("device_property_set").map { it["device"]!!.jsonPrimitive.content }
        assertEquals(listOf("Mount", "Focuser"), devices)
    }

    @Test
    fun finishingTheWizardAddsAProfileOrUpdatesTheOneBeingEdited() {
        val base = AppState(
            profileName = "New rig", sheet = SheetType.SETUP,
            devOff = setOf("rot"), selectedDeviceNames = mapOf("mount" to "LX200 OnStep", "cam" to "ToupTek", "guide" to "G3M178M"),
        )
        val add = Fixture(base)
        add.profiles.finishSetup()
        val p = add.commands("profile_add").single()
        assertEquals("New rig", p["name"]!!.jsonPrimitive.content)
        assertEquals("G3M178M", p["guider"]!!.jsonPrimitive.content)
        assertTrue(p["drivers"]!!.jsonObject.containsKey("Telescopes"))
        assertNull(add.state.value.sheet)                                 // the sheet closes
        assertNull(add.state.value.setupEditingName)

        val edit = Fixture(base.copy(setupEditingName = "Old rig"))
        edit.profiles.finishSetup()
        assertEquals(1, edit.commands("profile_update").size)
        assertEquals(0, edit.commands("profile_add").size)
    }

    @Test
    fun editingAProfileSeedsTheWizardFromItsDrivers() {
        val f = Fixture(
            AppState(
                profiles = listOf(RigProfile(name = "RedCat", deviceKeys = emptyList(), drivers = mapOf("Telescopes" to listOf("LX200 OnStep"), "CCDs" to listOf("ToupTek")), guider = "G3M178M")),
                setupEditingName = "RedCat",
            ),
        )
        f.profiles.seedEditedProfile("RedCat")
        val s = f.state.value
        assertEquals("LX200 OnStep", s.selectedDeviceNames["mount"])
        assertEquals("ToupTek", s.selectedDeviceNames["cam"])
        assertEquals("G3M178M", s.selectedDeviceNames["guide"])
        assertTrue("mount" !in s.devOff)
        assertTrue("efw" in s.devOff)                                     // nothing saved for it
    }

    @Test
    fun editingIsLeftAloneWhenTheBaseDeclinedIt() {
        val f = Fixture(AppState(profiles = listOf(RigProfile(name = "RedCat", deviceKeys = emptyList(), drivers = mapOf("CCDs" to listOf("ToupTek")))), setupEditingName = null))
        val before = f.state.value
        f.profiles.seedEditedProfile("RedCat")
        assertEquals(before, f.state.value)
    }

    @Test
    fun otherEventsAreNotClaimed() {
        val f = Fixture()
        assertNull(f.profiles.reduce(f.state.value, EkosEvent.NewConnectionState(connected = true, online = true)))
    }
}
