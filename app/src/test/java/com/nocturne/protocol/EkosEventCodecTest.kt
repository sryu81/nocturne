package com.nocturne.protocol

import com.nocturne.session.toTrainAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode tests for every [EkosEvent] case [EkosEventCodec] dispatches. Fixtures marked
 * "live capture" are verbatim payloads from the rig, so they pin the actual wire types.
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
        // live capture — profiles carry legacy per-role fields (ccd/mount/focuser/...) that
        // WireProfile doesn't model; ignoreUnknownKeys must swallow them.
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
        // The guide camera is told apart from the main one only via `guider` (both are in
        // drivers["CCDs"]).
        val hem27 = event.profiles[1]
        assertEquals(listOf("Sony DSLR", "ZWO CCD"), hem27.drivers["CCDs"])
        assertEquals("ZWO CCD", hem27.guider)
    }

    @Test
    fun `decodes train_get_all — regression for real numeric profile-reducer and nullable adaptiveoptics`() {
        // live capture — profile/reducer are numbers and adaptiveoptics is null.
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
    fun `decodes astro_get_almanac — curated to dusk-dawn, capitalized keys via SerialName`() {
        // live capture — only dusk/dawn/moon illumination are modeled; other fields are ignored.
        val json = """{"payload":{"Dawn":0.19,"Dusk":-0.085,"MoonIllum":0.585,"MoonPhase":-99.86,"MoonRise":0.968,"MoonSet":0.569,"SunMaxAlt":69.16,"SunMinAlt":-35.28,"SunRise":0.261,"SunSet":0.843},"type":"astro_get_almanac"}"""
        val event = EkosEventCodec.decode(json)
        assertTrue(event is EkosEvent.AstroAlmanac)
        assertEquals(-0.085, (event as EkosEvent.AstroAlmanac).dusk, 0.0)
        assertEquals(0.19, event.dawn, 0.0)
    }

    @Test
    fun `decodes astro_get_location — curated to tz alone`() {
        // live capture
        val json = """{"payload":{"elevation":9.27,"latitude":37.7775,"longitude":-122.4108,"name":"San Francisco","tz":-7,"tz0":-8},"type":"astro_get_location"}"""
        val event = EkosEventCodec.decode(json)
        assertTrue(event is EkosEvent.AstroLocation)
        assertEquals(-7.0, (event as EkosEvent.AstroLocation).tz, 0.0)
    }

    @Test
    fun `decodes astro_search_objects — flat name array`() {
        // live capture
        val event = EkosEventCodec.decode("""{"payload":["M 31","M 33","M 81","NGC 253"],"type":"astro_search_objects"}""")
        assertTrue(event is EkosEvent.AstroSearchResult)
        assertEquals(listOf("M 31", "M 33", "M 81", "NGC 253"), (event as EkosEvent.AstroSearchResult).names)
    }

    @Test
    fun `decodes get_drivers — DriverInfo toJson shape, family uses DeviceFamilyLabels`() {
        // shape from kstars indi/driverinfo.h DriverInfo::toJson (fork 3.8.3)
        val json = """{"payload":[
            {"binary":"indi_lx200_OnStep","family":"Telescopes","label":"LX200 OnStep","manufacturer":"OnStep","name":"LX200 OnStep","skel":"","version":"1.26"},
            {"binary":"indi_toupcam_ccd","family":"CCDs","label":"ToupTek","manufacturer":"ToupTek","name":"ToupTek","skel":"","version":"1.0"},
            {"binary":"indi_asi_wheel","family":"Filter Wheels","label":"ZWO EFW","manufacturer":"","name":"ZWO EFW","skel":"","version":"1.0"}
        ],"type":"get_drivers"}"""

        val event = EkosEventCodec.decode(json)
        assertTrue("expected Drivers, got $event", event is EkosEvent.Drivers)
        event as EkosEvent.Drivers
        assertEquals(3, event.drivers.size)
        assertEquals("LX200 OnStep", event.drivers[0].label)
        assertEquals("Telescopes", event.drivers[0].family)
        assertEquals("indi_toupcam_ccd", event.drivers[1].binary)
        assertEquals("", event.drivers[2].manufacturer)
    }

    @Test
    fun `decodes get_scopes — real Ekos auto-derives name from vendor+model+focal+fratio, not user input`() {
        // live capture. A scope's `name` ("Skywatcher Esprit 100ED 550@F/5.5") is generated by
        // the server from vendor/model/focal_length/aperture; it is not the `model` sent.
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
        // switches/numbers/texts/lights sniffed by key presence (docs/PROTOCOL.md)
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
    fun `decodes device_property_add like a get, for properties defined after Ekos settled`() {
        // The fork pushes these (Message::processNewProperty) once settled; a camera defines its
        // cooler properties about a minute after CONNECTION flips, so this is how the app finds out.
        val json = """{"payload":{"device":"ToupTek ATR2600M","name":"CCD_TEMPERATURE","state":0,
            "numbers":[{"name":"CCD_TEMPERATURE_VALUE","value":9.9,"min":-50.0,"max":50.0,"step":0.1}]},"type":"device_property_add"}"""
        val event = EkosEventCodec.decode(json) as EkosEvent.DeviceProperty
        val prop = event.property as WireProperty.Number
        assertEquals("ToupTek ATR2600M", prop.device)
        assertEquals("CCD_TEMPERATURE", prop.name)
        assertEquals(9.9, prop.numbers[0].value, 0.0)
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
        // live capture — 17 fields, 10 modeled; ignoreUnknownKeys must drop the rest.
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
        // live capture, trimmed to a representative subset of the 59 fields: the modeled ones
        // plus some unknown keys that must be dropped.
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
    fun `decodes guide_get_all_settings — curated subset incl phase-4 fields, guideBinning is a string`() {
        // live capture — 84 fields; ignoreUnknownKeys must drop the unmodeled ones. The
        // kcfg_Dither*/kcfg_ReuseGuideCalibration keys are in this reply itself, not behind
        // guide_set_calibration_settings.
        val json = """{"payload":{"dECGuideEnabled":true,"guideAutoStar":true,"guideBinning":"1x1",
            "guideDarkFrame":false,"guideExposure":3.5,"guideGain":99,"guiderAccuracyThreshold":2,
            "kcfg_DitherEnabled":false,"kcfg_DitherPixels":2,"kcfg_DitherThreshold":1,
            "kcfg_GuideAlgorithm":"SEP Multi Star (recommended)","kcfg_ReuseGuideCalibration":true,
            "opticalTrainCombo":"Secondary"},
            "type":"guide_get_all_settings"}"""

        val event = EkosEventCodec.decode(json)
        assertTrue("expected GuideSettings, got $event", event is EkosEvent.GuideSettings)
        val settings = (event as EkosEvent.GuideSettings).settings
        assertEquals(3.5, settings.guideExposure, 0.0)
        assertEquals(99.0, settings.guideGain, 0.0)
        assertEquals("1x1", settings.guideBinning)
        assertEquals(2.0, settings.guiderAccuracyThreshold, 0.0)
        assertFalse(settings.kcfg_DitherEnabled)
        assertEquals(2, settings.kcfg_DitherPixels)
        assertEquals(1.0, settings.kcfg_DitherThreshold, 0.0)
        assertTrue(settings.kcfg_ReuseGuideCalibration)
    }

    @Test
    fun `decodes focus_get_all_settings — curated subset incl phase-6 fields, focusAlgorithm is a string`() {
        // live capture — 84 fields; ignoreUnknownKeys must drop the unmodeled ones.
        // focusAlgorithm is a string ("Linear 1 Pass"), not an enum index.
        val json = """{"payload":{"absTicksSpin":29535,"defaultFocusTemperatureSource":"ZWO EAF",
            "focusAlgorithm":"Linear 1 Pass","focusBacklash":0,"focusBinning":"1x1",
            "focusCurveFit":"Hyperbola","focusExposure":2,"focusFilter":"L","focusGain":99,
            "focusMaxTravel":100000,"focusTicks":250,"opticalTrainCombo":"Primary"},
            "type":"focus_get_all_settings"}"""

        val event = EkosEventCodec.decode(json)
        assertTrue("expected FocusSettings, got $event", event is EkosEvent.FocusSettings)
        val settings = (event as EkosEvent.FocusSettings).settings
        assertEquals(29535, settings.absTicksSpin)
        assertEquals(2.0, settings.focusExposure, 0.0)
        assertEquals(99.0, settings.focusGain, 0.0)
        assertEquals("L", settings.focusFilter)
        assertEquals(0, settings.focusBacklash)
        assertEquals("Linear 1 Pass", settings.focusAlgorithm)
        assertEquals(250, settings.focusTicks)
        assertEquals(100000, settings.focusMaxTravel)
    }

    @Test
    fun `decodes align_get_all_settings — curated subset, alignBinning is a string not a number`() {
        // live capture — 98 fields; ignoreUnknownKeys must drop the unmodeled ones.
        // alignBinning is a combo string ("1x1"), not a number.
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
        // Polar Alignment Assistant widgets ride the same reply (PAA is a child of Align).
        assertEquals("West", settings.pAHDirection)
        assertEquals(3.0, settings.pAHExposure!!, 0.0)
        assertEquals(30, settings.pAHRotation)
        assertEquals(null, settings.pAHManualSlew)
    }

    @Test
    fun `decodes astro_get_names — bare string array`() {
        // Payload is a flat array (~44k names, ~520 KB live).
        val event = EkosEventCodec.decode(
            """{"payload":["51  Andromedae","Andromeda Galaxy","M 31","Whirlpool Galaxy"],"type":"astro_get_names"}""",
        )
        assertTrue("expected AstroNames, got $event", event is EkosEvent.AstroNames)
        assertEquals(listOf("51  Andromedae", "Andromeda Galaxy", "M 31", "Whirlpool Galaxy"), (event as EkosEvent.AstroNames).names)
    }

    @Test
    fun `decodes new_polar_state — partial payloads (stage-only, message-only) must not fall back to Raw`() {
        // Pushes arrive as independent partial shapes ({"stage"}, {"message"}, {"vector"},
        // {"enabled"}); each must decode rather than fall to Raw.
        val stageOnly = EkosEventCodec.decode("""{"payload":{"stage":"select star"},"type":"new_polar_state"}""")
        assertTrue(stageOnly is EkosEvent.NewPolarState)
        assertEquals("select star", (stageOnly as EkosEvent.NewPolarState).stage)
        assertNull(stageOnly.enabled)
        assertNull(stageOnly.message)

        val messageOnly = EkosEventCodec.decode("""{"payload":{"message":"Rotate the mount"},"type":"new_polar_state"}""")
        assertTrue(messageOnly is EkosEvent.NewPolarState)
        assertEquals("Rotate the mount", (messageOnly as EkosEvent.NewPolarState).message)
        assertNull(messageOnly.stage)

        val enabledOnly = EkosEventCodec.decode("""{"payload":{"enabled":true},"type":"new_polar_state"}""")
        assertTrue(enabledOnly is EkosEvent.NewPolarState)
        assertEquals(true, (enabledOnly as EkosEvent.NewPolarState).enabled)
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

    @Test
    fun `decodes astro_get_object_info hit — extra object flag ignored`() {
        // shape per message.cpp's ASTRO_GET_OBJECT_INFO branch, not a live capture
        val event = EkosEventCodec.decode("""{"payload":{"name":"M 31","designations":["Andromeda Galaxy"],"magnitude":3.4,"ra0":0.712,"de0":41.27,"ra":0.72,"de":41.4,"object":true},"type":"astro_get_object_info"}""")
        event as EkosEvent.AstroObjectInfo
        assertEquals("M 31", event.obj?.name)
        assertEquals(41.27, event.obj!!.de0, 1e-9)
    }

    @Test
    fun `decodes astro_get_object_info miss as null obj`() {
        val event = EkosEventCodec.decode("""{"payload":{"name":"zzqq","object":false},"type":"astro_get_object_info"}""")
        event as EkosEvent.AstroObjectInfo
        assertNull(event.obj)
    }

    @Test
    fun `decodes new_guide_state with and without status`() {
        val event = EkosEventCodec.decode("""{"payload":{"status":"Calibrating"},"type":"new_guide_state"}""")
        event as EkosEvent.NewGuideState
        assertEquals("Calibrating", event.status)
        // The guide-stats shape carries no status — must still decode.
        val stats = EkosEventCodec.decode("""{"payload":{"rmsRA":0.61,"rmsDE":0.55},"type":"new_guide_state"}""")
        stats as EkosEvent.NewGuideState
        assertNull(stats.status)
        assertEquals(0.61, stats.rmsRA!!, 1e-9)
        // live capture (fork guideStats, Simulators profile)
        val sample = EkosEventCodec.decode("""{"payload":{"decError":122.5135222954195,"decPulse":-101,"numStars":0,"raError":-120.23391303781872,"raPulse":98,"skyBg":0,"snr":0},"type":"new_guide_state"}""")
        sample as EkosEvent.NewGuideState
        assertNull(sample.status)
        assertEquals(-120.23391303781872, sample.raError!!, 1e-9)
        assertEquals(-101, sample.decPulse)
        assertEquals(0, sample.numStars)
    }

    @Test
    fun `decodes guide_get_all_settings expanded fields`() {
        val event = EkosEventCodec.decode("""{"payload":{"guideDarkFrame":true,"guideSubframe":false,"guideAutoStar":true,"kcfg_DitherSettle":5,"kcfg_CalibrationPulseDuration":750,"kcfg_ReverseDecOnPierSideChange":true,"guideSquareSize":"32"},"type":"guide_get_all_settings"}""")
        event as EkosEvent.GuideSettings
        assertTrue(event.settings.guideDarkFrame)
        assertTrue(event.settings.guideAutoStar)
        assertEquals(5, event.settings.kcfg_DitherSettle)
        assertEquals(750, event.settings.kcfg_CalibrationPulseDuration)
        assertTrue(event.settings.kcfg_ReverseDecOnPierSideChange)
        assertEquals(45, event.settings.kcfg_DitherTimeout)
        assertEquals("32", event.settings.guideSquareSize)
    }

    @Test
    fun `decodes every new_focus_state shape`() {
        val status = EkosEventCodec.decode("""{"payload":{"status":"In Progress"},"type":"new_focus_state"}""")
        status as EkosEvent.NewFocusState
        assertEquals("In Progress", status.status)
        // Manager::updateCurrentHFR — one per measured focus frame, no status.
        val frame = EkosEventCodec.decode("""{"payload":{"hfr":2.41,"pos":29535},"type":"new_focus_state"}""")
        frame as EkosEvent.NewFocusState
        assertNull(frame.status)
        assertEquals(2.41, frame.hfr!!, 1e-9)
        assertEquals(29535, frame.pos)
        val title = EkosEventCodec.decode("""{"payload":{"title":"Hyperbola (W): R²=0.98"},"type":"new_focus_state"}""")
        title as EkosEvent.NewFocusState
        assertEquals("Hyperbola (W): R²=0.98", title.title)
        val plotInit = EkosEventCodec.decode("""{"payload":{"focusinitHFRPlot":true},"type":"new_focus_state"}""")
        assertTrue(plotInit is EkosEvent.NewFocusState)
    }
}
