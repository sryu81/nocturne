package com.nocturne.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire halves and guards of the rig maintenance code. The daemon's HTTP calls (reboot, shutdown, the
 * Pi clock) need a server, so they are not exercised here.
 */
class RigMaintenanceControllerTest {

    private class Fixture(online: Boolean = true) {
        val state = MutableStateFlow(AppState())
        val sent = mutableListOf<Pair<String, JsonElement>>()
        val ctx = ControllerContext(
            sender = { type, payload -> sent += type to payload },
            link = { true },
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state.value },
            update = { f -> state.update(f) },
            host = "127.0.0.1",
            onlineWait = { online },
        )
        val rig = RigMaintenanceController(ctx, log = null, skyAtlas = null, connectionRepo = null)
    }

    @Test
    fun theLocationGoesToKStarsWithTheTimezoneOffset() {
        val f = Fixture()
        f.rig.syncLocationToRig(37.9949, -122.2602, 128.5)
        val (type, payload) = f.sent.single()
        assertEquals("kstars_set_location", type)
        val p = payload.jsonObject
        assertEquals(37.9949, p["lat"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(-122.2602, p["lon"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(128.5, p["elev"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(java.util.TimeZone.getDefault().rawOffset / 3_600_000.0, p["tz"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun theClockGoesToKStarsAsTheLocalWallClockParts() {
        val f = Fixture()
        f.rig.syncTimeToRig()
        val kstars = f.sent.first { it.first == "kstars_set_time" }.second.jsonObject
        val now = java.time.LocalDateTime.now()
        assertEquals(now.year, kstars["yr"]!!.jsonPrimitive.int)
        assertEquals(now.monthValue, kstars["mth"]!!.jsonPrimitive.int)
        assertEquals(now.dayOfMonth, kstars["day"]!!.jsonPrimitive.int)
        assertTrue(kstars.keys.containsAll(listOf("hr", "min", "sec")))
    }

    @Test
    fun theRebootPortIsKeptInTheState() {
        val f = Fixture()
        f.rig.setRigRebootPort(9055)
        assertEquals(9055, f.state.value.rigRebootPort)
        assertEquals(RigRebootState.IDLE, f.state.value.rigRebootState)
        assertEquals(null, f.state.value.rigRebootError)
    }

    @Test
    fun theConnectTimeSyncDoesNothingWhenEkosNeverComesOnline() {
        val f = Fixture(online = false)
        f.rig.syncRigOnConnect(Triple(37.99, -122.26, 128.0))
        assertEquals(emptyList<String>(), f.sent.map { it.first })              // no time, no location
        assertEquals(RigRebootState.IDLE, f.state.value.piTimeSyncState)
    }

    @Test
    fun withoutAnAtlasTheAtlasCommandsDoNothing() {
        val f = Fixture()
        val before = f.state.value
        f.rig.start()
        f.rig.refreshSkyAtlas()
        f.rig.copySkyAtlasFromRig()
        f.rig.clearSkyAtlas()
        f.rig.cancelSkyAtlasCopy()
        assertEquals(before, f.state.value)
        assertEquals(emptyList<String>(), f.sent.map { it.first })
    }
}
