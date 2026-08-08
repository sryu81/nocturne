package com.nocturne.protocol

import com.nocturne.session.toTrainAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode tests for every [EkosEvent] case [EkosEventCodec] dispatches.
 * Fixtures marked "live capture" are byte-for-byte payloads pulled off a real
 * Pi running EkosRemote at `10.0.0.43:9000` (not hand-written per the docs) —
 * catches the class of bug where the docs/plan's assumed wire *type* for a
 * field turns out wrong (see [WireTrain]'s history: real `profile`/`reducer`
 * are numbers and `adaptiveoptics` can be JSON `null`, none of which the
 * hand-written model originally allowed for).
 */
class EkosEventCodecTest {

    @Test
    fun `decodes new_connection_state`() {
        // live capture
        val event = EkosEventCodec.decode("""{"payload":{"connected":true,"online":false},"type":"new_connection_state"}""")
        assertTrue(event is EkosEvent.NewConnectionState)
        event as EkosEvent.NewConnectionState
        assertTrue(event.connected)
        assertFalse(event.online)
    }

    @Test
    fun `decodes get_profiles with real multi-profile payload, extra legacy fields ignored`() {
        // live capture — 3 real profiles, each carrying legacy per-role fields (ccd/mount/focuser/...)
        // that WireProfile deliberately doesn't model; ignoreUnknownKeys must swallow them.
        val json = """{"payload":{"profiles":[
            {"ao":"","auto_connect":true,"aux1":"","aux2":"","aux3":"","aux4":"","ccd":"CCD Simulator","dome":"","driver_source":"system","drivers":{"CCDs":["CCD Simulator","Guide Simulator"],"Focusers":["Focuser Simulator"],"Telescopes":["Telescope Simulator"]},"filter":"","focuser":"Focuser Simulator","guider":"Guide Simulator","guiding":0,"mode":"local","mount":"Telescope Simulator","name":"Simulators","port_selector":false,"remote":"","remote_guiding_host":"","remote_guiding_port":-1,"remote_host":"","remote_port":0,"use_web_manager":true,"weather":""},
            {"ao":"","auto_connect":true,"aux1":"","aux2":"","aux3":"","aux4":"","ccd":"Sony DSLR","dome":"","driver_source":"system","drivers":{"CCDs":["Sony DSLR","ZWO CCD"],"Focusers":["ZWO EAF"],"Telescopes":["iOptron HEM27"]},"filter":"","focuser":"ZWO EAF","guider":"ZWO CCD","guiding":1,"mode":"local","mount":"iOptron HEM27","name":"Cat51_A7_HEM27_ASI385","port_selector":false,"remote":"","remote_guiding_host":"localhost","remote_guiding_port":4400,"remote_host":"","remote_port":0,"use_web_manager":true,"weather":""},
            {"ao":"","auto_connect":true,"aux1":"","aux2":"","aux3":"","aux4":"","ccd":"Toupcam","dome":"","driver_source":"system","drivers":{"CCDs":["Toupcam","Toupcam"],"Filter Wheels":["ZWO EFW"],"Focusers":["ZWO EAF"],"Telescopes":["LX200 OnStep"]},"filter":"ZWO EFW","focuser":"ZWO EAF","guider":"Toupcam","guiding":0,"mode":"local","mount":"LX200 OnStep","name":"Cat51_ATR2600MM_Onstep_IMX178","port_selector":false,"remote":"","remote_guiding_host":"","remote_guiding_port":-1,"remote_host":"","remote_port":-1,"use_web_manager":false,"weather":""}
        ],"selectedProfile":"Cat51_ATR2600MM_Onstep_IMX178"},"type":"get_profiles"}"""

        val event = EkosEventCodec.decode(json)
        assertTrue(event is EkosEvent.Profiles)
        event as EkosEvent.Profiles
        assertEquals("Cat51_ATR2600MM_Onstep_IMX178", event.selectedProfile)
        assertEquals(3, event.profiles.size)
        assertEquals(listOf("Simulators", "Cat51_A7_HEM27_ASI385", "Cat51_ATR2600MM_Onstep_IMX178"), event.profiles.map { it.name })
        // A real profile disambiguates the guide camera from the main one only via `guider`
        // (both live in the same drivers["CCDs"] list) — confirm that still round-trips.
        val hem27 = event.profiles[1]
        assertEquals(listOf("Sony DSLR", "ZWO CCD"), hem27.drivers["CCDs"])
        assertEquals("ZWO CCD", hem27.guider)
    }

    @Test
    fun `decodes train_get_all — regression for real numeric profile-reducer and nullable adaptiveoptics`() {
        // live capture — this exact payload used to throw SerializationException (profile/reducer
        // modeled as String, adaptiveoptics modeled as non-null) and silently fall back to Raw.
        val json = """{"payload":[
            {"adaptiveoptics":null,"camera":"ToupTek ATR2600M","dustcap":"None","filterwheel":"EFW 7×36 mm","focuser":"EAF","guider":"LX200 OnStep","id":11,"lightbox":"None","mount":"LX200 OnStep","name":"Primary","profile":5,"reducer":1,"rotator":"None","scope":"Field APO"},
            {"adaptiveoptics":null,"camera":"ToupTek ATR2600M","dustcap":"None","filterwheel":"EFW 7×36 mm","focuser":"EAF","guider":"EQ6-R Pro","id":12,"lightbox":"None","mount":"LX200 OnStep","name":"Secondary","profile":5,"reducer":1,"rotator":"None","scope":"Field APO"}
        ],"type":"train_get_all"}"""

        val event = EkosEventCodec.decode(json)
        assertTrue("expected Trains, got $event (decode must have thrown and fallen back to Raw)", event is EkosEvent.Trains)
        event as EkosEvent.Trains
        assertEquals(2, event.trains.size)
        val primary = event.trains[0]
        assertEquals(11, primary.id)
        assertEquals(5, primary.profile)
        assertEquals(1.0, primary.reducer, 0.0)
        assertNull(primary.adaptiveoptics)
        assertEquals("Field APO", primary.scope)

        // toTrainAssignment() must tolerate the null adaptiveoptics without crashing, falling back to "None".
        assertEquals("None", primary.toTrainAssignment().adaptiveOptics)
    }

    @Test
    fun `decodes astro_get_almanac as Raw — not modeled, must not crash`() {
        // live capture — astro_get_almanac has no typed EkosEvent case yet; must degrade to Raw, not throw.
        val json = """{"payload":{"Dawn":0.19,"Dusk":-0.085,"MoonIllum":0.585,"MoonPhase":-99.86,"MoonRise":0.968,"MoonSet":0.569,"SunMaxAlt":69.16,"SunMinAlt":-35.28,"SunRise":0.261,"SunSet":0.843},"type":"astro_get_almanac"}"""
        val event = EkosEventCodec.decode(json)
        assertTrue(event is EkosEvent.Raw)
        assertEquals("astro_get_almanac", (event as EkosEvent.Raw).type)
    }

    @Test
    fun `decodes astro_search_objects — flat name array`() {
        // live capture
        val event = EkosEventCodec.decode("""{"payload":["M 31","M 33","M 81","NGC 253"],"type":"astro_search_objects"}""")
        assertTrue(event is EkosEvent.AstroSearchResult)
        assertEquals(listOf("M 31", "M 33", "M 81", "NGC 253"), (event as EkosEvent.AstroSearchResult).names)
    }

    @Test
    fun `decodes get_scopes — real Ekos auto-derives name from vendor+model+focal+fratio, not user input`() {
        // live capture. Confirms name != model — a scope's displayed `name` ("Skywatcher Esprit
        // 100ED 550@F/5.5") is server-generated from vendor/model/focal_length/aperture, not the
        // literal string a `scope_add` caller sent as `model` — see WireScope.toScopeDef()'s doc.
        val json = """{"payload":[
            {"aperture":120,"focal_length":700,"id":"1","model":"Primary","name":"Sample Primary 700@F/5.8","type":"Refractor","vendor":"Sample"},
            {"aperture":51,"focal_length":250,"id":"3","model":"Redcat 51","name":"William Optics Redcat 51 250@F/4.9","type":"Refractor","vendor":"William Optics"}
        ],"type":"get_scopes"}"""

        val event = EkosEventCodec.decode(json)
        assertTrue("expected Scopes, got $event", event is EkosEvent.Scopes)
        event as EkosEvent.Scopes
        assertEquals(2, event.scopes.size)
        val redcat = event.scopes[1]
        assertEquals("3", redcat.id)
        assertEquals("Redcat 51", redcat.model)
        assertEquals("William Optics Redcat 51 250@F/4.9", redcat.name)
        assertEquals("William Optics", redcat.vendor)
        assertEquals(250.0, redcat.focal_length, 0.0)
        assertEquals(51.0, redcat.aperture, 0.0)
    }

    @Test
    fun `decodes scheduler_get_jobs empty list`() {
        // live capture
        val event = EkosEventCodec.decode("""{"payload":{"jobs":[]},"type":"scheduler_get_jobs"}""")
        assertTrue(event is EkosEvent.SchedulerJobs)
        assertTrue((event as EkosEvent.SchedulerJobs).jobs.isEmpty())
    }

    @Test
    fun `decodes new_mount_state`() {
        // live capture (mount parked, tracking RA/DE at zenith)
        val json = """{"payload":{"status":"Tracking","target":"","slewRate":4,"pierSide":0},"type":"new_mount_state"}"""
        val event = EkosEventCodec.decode(json)
        assertTrue(event is EkosEvent.NewMountState)
        event as EkosEvent.NewMountState
        assertEquals("Tracking", event.status)
        assertEquals(4, event.slewRate)
    }

    @Test
    fun `decodes device_property_get switch vector`() {
        // per EkosRemote-Command-Reference.md §14 — switches/numbers/texts/lights sniffed by key presence
        val json = """{"payload":{"device":"CCD Simulator","name":"CONNECTION","state":0,
            "switches":[{"name":"CONNECT","state":0},{"name":"DISCONNECT","state":1}],
            "label":"Connection","group":"Main Control"},"type":"device_property_get"}"""
        val event = EkosEventCodec.decode(json)
        assertTrue(event is EkosEvent.DeviceProperty)
        val prop = (event as EkosEvent.DeviceProperty).property
        assertTrue(prop is WireProperty.Switch)
        prop as WireProperty.Switch
        assertEquals("CCD Simulator", prop.device)
        assertEquals(listOf("CONNECT", "DISCONNECT"), prop.switches.map { it.name })
    }

    @Test
    fun `decodes device_property_get number vector`() {
        val json = """{"payload":{"device":"CCD Simulator","name":"CCD_EXPOSURE","state":0,
            "numbers":[{"name":"CCD_EXPOSURE_VALUE","value":1.0,"min":0.0,"max":3600.0,"step":1.0}]},"type":"device_property_get"}"""
        val event = EkosEventCodec.decode(json)
        assertTrue(event is EkosEvent.DeviceProperty)
        val prop = ((event as EkosEvent.DeviceProperty).property as WireProperty.Number)
        assertEquals(3600.0, prop.numbers[0].max)
    }

    @Test
    fun `decodes get_devices — bare array wrapped, interface bitmask decodes into roles`() {
        val json = """{"payload":[{"name":"CCD Simulator","connected":true,"version":"1.0","interface":2}],"type":"get_devices"}"""
        val event = EkosEventCodec.decode(json)
        assertTrue(event is EkosEvent.Devices)
        val device = (event as EkosEvent.Devices).devices.single()
        assertEquals(setOf(DeviceRole.CCD), bitmaskToRoles(device.interfaceMask))
    }

    @Test
    fun `decodes train_get_profiles — ordinal-keyed assignment map`() {
        val json = """{"payload":{"0":11,"1":11,"2":11,"3":11,"4":12,"5":11,"6":11},"type":"train_get_profiles"}"""
        val event = EkosEventCodec.decode(json)
        assertTrue(event is EkosEvent.TrainProfiles)
        assertEquals(12, (event as EkosEvent.TrainProfiles).assignments[ProfileTrainSetting.GUIDE])
    }

    @Test
    fun `decodes mount_get_all_settings — curated subset, extra real fields ignored`() {
        // live capture (M3.3) — real Mount::getAllSettings() reports 17 fields; only 10 are
        // modeled. ignoreUnknownKeys must drop the other 7 (locationSource/timeSource/
        // useGeographicUpdate/useTimeUpdate/leftRightCheckObject/upDownCheckObject) without
        // throwing.
        val json = """{"payload":{"autoParkTime":"03:00:00","enableAltitudeLimits":false,
            "enableAltitudeLimitsTrackingOnly":false,"enableHaLimit":false,"executeMeridianFlip":true,
            "leftRightCheckObject":false,"locationSource":"KStars","maximumAltLimit":90,
            "maximumHaLimit":2,"meridianFlipOffsetDegrees":1,"minimumAltLimit":0,
            "opticalTrainCombo":"Primary","parkEveryDay":false,"timeSource":"KStars",
            "upDownCheckObject":false,"useGeographicUpdate":true,"useTimeUpdate":true},
            "type":"mount_get_all_settings"}"""

        val event = EkosEventCodec.decode(json)
        assertTrue("expected MountSettings, got $event", event is EkosEvent.MountSettings)
        val settings = (event as EkosEvent.MountSettings).settings
        assertEquals(true, settings.executeMeridianFlip)
        assertEquals(1.0, settings.meridianFlipOffsetDegrees, 0.0)
        assertEquals(false, settings.enableAltitudeLimits)
        assertEquals(0.0, settings.minimumAltLimit, 0.0)
        assertEquals(90.0, settings.maximumAltLimit, 0.0)
        assertEquals(false, settings.enableHaLimit)
        assertEquals(2.0, settings.maximumHaLimit, 0.0)
        assertEquals(false, settings.parkEveryDay)
        assertEquals("03:00:00", settings.autoParkTime)
    }

    @Test
    fun `decodes capture_get_all_settings — curated subset, extra real fields ignored`() {
        // live capture (M3.3 phase 5 + the preview-controls/cooler-sync fix) — real
        // Camera::getAllSettings() reports 59 fields; only 12 are modeled. ignoreUnknownKeys
        // must drop the other 47 without throwing. Trimmed to a representative subset of the
        // full live payload (real values from the rig), not every one of the 59 fields — the
        // point is the curated 12 decode correctly and unknown keys don't break decode, not to
        // duplicate the full reference dump here.
        val json = """{"payload":{"FilterPosCombo":"L","cameraTemperatureN":-1,"cameraTemperatureS":true,
            "captureBinHN":1,"captureBinVN":1,"captureExposureN":1,"captureGainN":99,"captureTypeS":"Light",
            "enableDitherPerJob":true,"enforceGuideDeviation":false,"enforceStartGuiderDrift":false,
            "fileDirectoryT":"/home/soo/Pictures","guideDeviation":2,"guideDitherPerJobFrequency":0,
            "hFRDeviation":1,"opticalTrainCombo":"Primary","refocusEveryN":60,"startGuideDeviation":2,
            "targetNameT":""},
            "type":"capture_get_all_settings"}"""

        val event = EkosEventCodec.decode(json)
        assertTrue("expected CaptureSettings, got $event", event is EkosEvent.CaptureSettings)
        val settings = (event as EkosEvent.CaptureSettings).settings
        assertEquals("/home/soo/Pictures", settings.fileDirectoryT)
        assertEquals(false, settings.enforceGuideDeviation)
        assertEquals(2.0, settings.guideDeviation, 0.0)
        assertEquals(false, settings.enforceStartGuiderDrift)
        assertEquals(2.0, settings.startGuideDeviation, 0.0)
        assertEquals(true, settings.enableDitherPerJob)
        assertEquals(0, settings.guideDitherPerJobFrequency)
        assertEquals(1.0, settings.captureExposureN, 0.0)
        assertEquals(99.0, settings.captureGainN, 0.0)
        assertEquals(1, settings.captureBinHN)
        assertEquals(1, settings.captureBinVN)
        assertEquals(-1.0, settings.cameraTemperatureN, 0.0)
    }

    @Test
    fun `decodes guide_get_all_settings — partial subset, guideBinning is a string`() {
        // live capture (Bench "Snap guide" preview controls) — real Guide::getAllSettings()
        // reports 84 fields; only 3 are modeled so far (guideExposure/gain/binning — enough for
        // Bench's Snap guide to configure a preview; the rest land in M3.3 phase 4). Same
        // guideBinning-is-a-string confirmation as WireAlignSettings.alignBinning.
        val json = """{"payload":{"dECGuideEnabled":true,"guideAutoStar":true,"guideBinning":"1x1",
            "guideDarkFrame":false,"guideExposure":1,"guideGain":99,"guiderAccuracyThreshold":2,
            "kcfg_DitherEnabled":false,"kcfg_ReuseGuideCalibration":true,"opticalTrainCombo":"Secondary"},
            "type":"guide_get_all_settings"}"""

        val event = EkosEventCodec.decode(json)
        assertTrue("expected GuideSettings, got $event", event is EkosEvent.GuideSettings)
        val settings = (event as EkosEvent.GuideSettings).settings
        assertEquals(1.0, settings.guideExposure, 0.0)
        assertEquals(99.0, settings.guideGain, 0.0)
        assertEquals("1x1", settings.guideBinning)
    }

    @Test
    fun `decodes align_get_all_settings — curated subset, alignBinning is a string not a number`() {
        // live capture (M3.3 phase 3) — real Align::getAllSettings() reports 98 fields; only 5
        // are modeled. ignoreUnknownKeys must drop the other 93 without throwing. alignBinning
        // was confirmed live as a combo-box string ("1x1"), not a number — the whole reason this
        // test (and the live probe that produced it) exists: the M3.3 plan draft assumed Int
        // before probing and would have silently degraded every real reply to Raw forever.
        val json = """{"payload":{"FlipRotationNotAllowed":false,"alignAccuracyThreshold":30,
            "alignBinning":"1x1","alignDarkFrame":false,"alignExposure":3,"alignFilter":"L",
            "alignGain":99,"alignISO":"","alignSettlingTime":1500,"alignUseCurrentFilter":false,
            "index_4107":false,"kcfg_AstrometryTimeout":180,"opticalTrainCombo":"Primary",
            "pAHDirection":"West","pAHExposure":3,"pAHRotation":30},
            "type":"align_get_all_settings"}"""

        val event = EkosEventCodec.decode(json)
        assertTrue("expected AlignSettings, got $event", event is EkosEvent.AlignSettings)
        val settings = (event as EkosEvent.AlignSettings).settings
        assertEquals(3.0, settings.alignExposure, 0.0)
        assertEquals(99.0, settings.alignGain, 0.0)
        assertEquals("L", settings.alignFilter)
        assertEquals("1x1", settings.alignBinning)
        assertEquals(30.0, settings.alignAccuracyThreshold, 0.0)
    }

    @Test
    fun `unrecognized type falls back to Raw, not a crash`() {
        val event = EkosEventCodec.decode("""{"payload":{"foo":"bar"},"type":"some_future_command"}""")
        assertTrue(event is EkosEvent.Raw)
        assertEquals("some_future_command", (event as EkosEvent.Raw).type)
    }

    @Test
    fun `malformed json falls back to unparsable Raw, not a crash`() {
        val event = EkosEventCodec.decode("""not json at all""")
        assertTrue(event is EkosEvent.Raw)
        assertEquals("<unparsable>", (event as EkosEvent.Raw).type)
    }

    @Test
    fun `bitmaskToRoles ORs multiple roles — guide camera is CCD or GUIDER`() {
        assertEquals(setOf(DeviceRole.CCD, DeviceRole.GUIDER), bitmaskToRoles(DeviceRole.CCD.bit or DeviceRole.GUIDER.bit))
        assertEquals(emptySet<DeviceRole>(), bitmaskToRoles(0))
    }
}
