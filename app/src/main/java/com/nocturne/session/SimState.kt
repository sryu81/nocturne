package com.nocturne.session

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** Which detail sheet is open. */
enum class SheetType {
    GUIDE, FOCUS, ALERTS, PREFS, SETUP, BENCH, PA, DEVICE, SUMMARY,
}

/** Every mutable field the simulator drives, mirroring the prototype's state. */
data class SimState(
    val t: Int = 0,
    val sheet: SheetType? = null,
    val running: Boolean = true,
    val openBlock: Int = 1,
    val deviceKey: String = "mount",
    val focPos: Int = 18422,
    val coolTarget: Double = -10.0,
    val coolNow: Double = 12.4,
    val snappedMain: Boolean = false,
    val snappedGuide: Boolean = false,
    val rate: Int = 2,
    val slewDir: String? = null,
    val paStep: Int = 0,
    val paAlt: Double = 4.2,
    val paAz: Double = -1.8,
    val paRate: Int = 1,
    val query: String = "",
    val chips: List<Int> = listOf(0),
    val targetId: String = "NGC 7000",
    val setupStep: Int = 0,
    val opticMm: Int = 550,
    val guideOpticMm: Int = 240,
    val profileName: String = "Field · 550 mm",
    val ekosRunning: Boolean = true,
    val activeProfile: String? = "Field · 550 mm",
    val profiles: List<RigProfile> = DEFAULT_PROFILES,
    val selectedProfile: String? = "Field · 550 mm",
    val setupEditingName: String? = null,
    val rotatorAngle: Double = 118.4,
    val indiProps: Map<String, List<IndiProperty>> = DEFAULT_INDI_PROPS,
    val prefs: Map<String, Boolean> = mapOf(
        "guide" to true,
        "cloud" to true,
        "disconnect" to true,
        "flip" to true,
        "frameCut" to false,
        "seqEnd" to true,
    ),
    val cut: Set<String> = setOf("017", "023"),
    val devOff: Set<String> = setOf("rotator"),
)

// ── Catalog data (prototype script constants) ──────────────────────────────

val PA_SECS = listOf(1, 2, 5, 10)
val PLAN_CHIPS = listOf("Up tonight", "Alt > 40°", "Narrowband", "Fits FOV")

data class Target(
    val id: String,
    val common: String,
    val coords: String,
    val size: String,
    val band: String,
    val max: Int,
    val peak: String,
    val usable: String,
    val fov: Int,
)

val TARGETS = listOf(
    Target("NGC 7000", "North America", "20h59m17s +44°31′44″", "120′×100′", "Ha", 78, "01:12", "4h 22m", 1),
    Target("IC 1396", "Elephant Trunk", "21h39m06s +57°30′00″", "170′×140′", "Ha", 81, "01:44", "4h 40m", 0),
    Target("NGC 6888", "Crescent", "20h12m07s +38°21′18″", "18′×12′", "Ha OIII", 72, "00:18", "3h 50m", 1),
    Target("IC 1805", "Heart", "02h32m42s +61°27′00″", "150′×150′", "SHO", 84, "04:02", "2h 10m", 0),
    Target("M 31", "Andromeda", "00h42m44s +41°16′09″", "190′×60′", "LRGB", 76, "02:48", "3h 30m", 0),
    Target("M 27", "Dumbbell", "19h59m36s +22°43′16″", "8′×6′", "LRGB Ha", 58, "23:40", "2h 55m", 1),
    Target("NGC 7380", "Wizard", "22h47m00s +58°06′00″", "25′×25′", "SHO", 82, "02:26", "4h 05m", 1),
    Target("IC 5070", "Pelican", "20h50m48s +44°21′00″", "60′×50′", "Ha", 78, "01:04", "4h 18m", 1),
    Target("NGC 281", "Pacman", "00h52m59s +56°37′19″", "35′×30′", "SHO", 83, "03:20", "3h 05m", 1),
    Target("M 45", "Pleiades", "03h47m24s +24°07′00″", "110′×110′", "LRGB", 61, "04:10", "1h 20m", 0),
)

data class PrefDef(val key: String, val label: String, val desc: String)

val PREF_DEFS = listOf(
    PrefDef("guide", "Guiding degraded", "RMS > 1.0″ for 3 subs"),
    PrefDef("cloud", "Cloud or unsafe weather", "cloud > 30% · wind > 35 km/h · rain"),
    PrefDef("disconnect", "Device disconnect", "any linked device drops"),
    PrefDef("flip", "Meridian flip", "10 min before, and on completion"),
    PrefDef("frameCut", "Frame rejected", "HFR or star count outside limits"),
    PrefDef("seqEnd", "Sequence finished", "or paused for any reason"),
)

/** Sensor spec used to derive pixel scale + FOV from a focal length. */
private data class SensorSpec(val pixelUm: Double, val widthMm: Double, val heightMm: Double)

// ASI2600MM Pro: 3.76 µm, 6248×4176 px.
private val MAIN_SENSOR = SensorSpec(3.76, 23.5, 15.7)

// ASI174MM mini: 5.86 µm, 1280×960 px.
private val GUIDE_SENSOR = SensorSpec(5.86, 7.5, 5.6)

private fun pixelScaleArcsec(focalMm: Int, sensor: SensorSpec): Double =
    206.265 * sensor.pixelUm / focalMm

private fun fovDeg(dimMm: Double, focalMm: Int): Double =
    Math.toDegrees(2 * kotlin.math.atan(dimMm / (2.0 * focalMm)))

/** "1.24 ″/px · 2.4° × 1.6° FOV" for the main imaging optic. */
fun opticNote(focalMm: Int): String {
    val px = pixelScaleArcsec(focalMm, MAIN_SENSOR)
    val fovW = fovDeg(MAIN_SENSOR.widthMm, focalMm)
    val fovH = fovDeg(MAIN_SENSOR.heightMm, focalMm)
    return "${"%.2f".format(px)} ″/px · ${"%.1f".format(fovW)}° × ${"%.1f".format(fovH)}° FOV"
}

/** Same, for the guide optic (smaller guide-cam sensor). */
fun guideOpticNote(focalMm: Int): String {
    val px = pixelScaleArcsec(focalMm, GUIDE_SENSOR)
    val fovW = fovDeg(GUIDE_SENSOR.widthMm, focalMm)
    val fovH = fovDeg(GUIDE_SENSOR.heightMm, focalMm)
    return "${"%.2f".format(px)} ″/px · ${"%.1f".format(fovW)}° × ${"%.1f".format(fovH)}° FOV"
}

/** A saved equipment profile (`get_profiles`/`profile_add` per the wire protocol). */
data class RigProfile(
    val name: String,
    val opticMm: Int,
    val guideOpticMm: Int,
    val deviceKeys: List<String>,
)

val DEFAULT_PROFILES = listOf(
    RigProfile("Field · 550 mm", 550, 240, listOf("mount", "cam", "efw", "guide", "focus", "rotator", "weather")),
    RigProfile("Wide field · 250 mm", 250, 240, listOf("mount", "cam", "efw", "focus", "weather")),
    RigProfile("RC8 imaging · 1000 mm", 1000, 550, listOf("mount", "cam", "efw", "guide", "focus", "rotator", "weather")),
    RigProfile("Bench test · 550 mm", 550, 240, listOf("mount", "cam")),
)

val SimState.activeRigProfile: RigProfile? get() = profiles.firstOrNull { it.name == activeProfile }

data class Device(
    val key: String,
    val name: String,
    val detail: String,
    val req: Boolean,
    val cfg: List<Pair<String, String>>,
)

val DEVICES = listOf(
    Device("mount", "EQ6-R Pro", "tracking · sidereal · W of pier", true, listOf(
        "Driver" to "EQMOD ASCOM",
        "Port" to "USB · COM4 · 115 200",
        "Site" to "52.37 N · 4.89 E · 12 m",
        "Pier side" to "West · flip at +5 min",
    )),
    Device("cam", "ASI2600MM Pro", "−10.0 °C · cooler 68% · 42 subs", true, listOf(
        "Driver" to "ZWO ASCOM · native",
        "Set point" to "−10.0 °C · cooler 68%",
        "Readout" to "Mode 0 · gain 100 · off 50",
        "Pixel scale" to "3.76 µm · 1.24 ″/px @ 550 mm",
    )),
    Device("efw", "EFW 7×36 mm", "pos 2 · Ha 3 nm", false, listOf(
        "Driver" to "ZWO EFW",
        "Slots" to "L · R · G · B · Ha · OIII · SII",
        "Offsets" to "per-filter focus offsets on",
    )),
    Device("guide", "ASI174MM mini", "2 s · 38 SNR · 0.48″", false, listOf(
        "Driver" to "PHD2 · localhost:4400",
        "Scope" to "OAG · 550 mm · 4.86 ″/px",
        "Calibration" to "valid · 22:04",
    )),
    Device("focus", "EAF", "18 422 · −0.6 °C drift", false, listOf(
        "Driver" to "ZWO EAF",
        "Range" to "0 → 62 000 · backlash 90",
        "Temp comp" to "−12 steps / °C",
    )),
    Device("rotator", "Rotator", "118.4° · sky PA locked", false, listOf(
        "Driver" to "Pegasus Falcon",
        "Sky PA" to "locked to 118.4°",
        "Reverse" to "off",
    )),
    Device("weather", "Weather + all-sky", "safe · cloud 4% · wind 6 km/h", false, listOf(
        "Driver" to "Boltwood file watch",
        "Unsafe when" to "cloud > 30% · wind > 35 km/h · rain",
        "On unsafe" to "park + close roof",
    )),
)

/**
 * Generic INDI property vector — mirrors the wire protocol's `device_get`/
 * `device_property_get` shapes (Switch/Number/Text/Light) so the UI can render
 * any device's controls the same way, without per-device-specific screens.
 */
sealed class IndiProperty {
    abstract val name: String
    abstract val label: String
    abstract val group: String

    data class SwitchProp(
        override val name: String, override val label: String, override val group: String,
        val options: List<String>,
        val selected: Int,
    ) : IndiProperty()

    data class NumberProp(
        override val name: String, override val label: String, override val group: String,
        val value: Double, val min: Double, val max: Double, val step: Double, val format: String = "%.1f",
    ) : IndiProperty()

    data class TextProp(
        override val name: String, override val label: String, override val group: String,
        val value: String,
    ) : IndiProperty()

    /** Read-only — IPState per element: 0 Idle, 1 Ok, 2 Busy, 3 Alert. */
    data class LightProp(
        override val name: String, override val label: String, override val group: String,
        val elements: List<Pair<String, Int>>,
    ) : IndiProperty()
}

val DEFAULT_INDI_PROPS: Map<String, List<IndiProperty>> = mapOf(
    "mount" to listOf(
        IndiProperty.SwitchProp("TELESCOPE_SLEW_RATE", "Slew rate", "Main Control", listOf("Guide", "Centering", "Find", "Max"), 2),
        IndiProperty.SwitchProp("TELESCOPE_PARK", "Parking", "Main Control", listOf("Park", "Unpark"), 1),
    ),
    "cam" to listOf(
        IndiProperty.NumberProp("CCD_TEMPERATURE", "CCD temperature", "Main Control", -10.0, -40.0, 20.0, 0.5, "%.1f °C"),
        IndiProperty.SwitchProp("CCD_COOLER", "Cooler", "Main Control", listOf("On", "Off"), 0),
        IndiProperty.NumberProp("CCD_GAIN", "Gain", "Options", 100.0, 0.0, 600.0, 1.0, "%.0f"),
    ),
    "efw" to listOf(
        IndiProperty.NumberProp("FILTER_SLOT", "Filter slot", "Main Control", 2.0, 1.0, 7.0, 1.0, "%.0f"),
    ),
    "guide" to listOf(
        IndiProperty.TextProp("PHD2_HOST", "PHD2 host", "Options", "localhost:4400"),
    ),
    "focus" to listOf(
        IndiProperty.NumberProp("FOCUS_SPEED", "Focuser speed", "Main Control", 5.0, 1.0, 10.0, 1.0, "%.0f"),
        IndiProperty.SwitchProp("FOCUS_MOTION", "Motion", "Main Control", listOf("Inward", "Outward"), 0),
    ),
    "rotator" to listOf(
        IndiProperty.NumberProp("ABS_ROTATOR_ANGLE", "Angle", "Main Control", 118.4, 0.0, 360.0, 0.1, "%.1f°"),
        IndiProperty.SwitchProp("ROTATOR_REVERSE", "Reverse", "Options", listOf("Enabled", "Disabled"), 1),
    ),
    "weather" to listOf(
        IndiProperty.LightProp("WEATHER_STATUS", "Weather status", "Main Control", listOf("Cloud" to 1, "Wind" to 1, "Rain" to 1)),
    ),
)

/** Frames grid — 12 sub previews with per-sub HFR. */
data class Frame(
    val id: String,
    val hfr: Double,
    val cut: Boolean,
)

val FRAME_IDS = listOf("011", "012", "013", "014", "015", "016", "017", "018", "019", "020", "021", "022")
val FRAME_HFRS = listOf(2.28, 2.31, 2.30, 2.35, 2.41, 2.33, 2.94, 2.44, 2.38, 2.36, 2.29, 2.32)

data class Block(
    val filter: String,
    val spec: String,
    val meta: String,
    val pct: Float,
)

val BLOCKS = listOf(
    Block("Ha", "300 s × 40", "12 done · 2h 00m left", 0.30f),
    Block("OIII", "300 s × 30", "queued · 2h 30m", 0f),
    Block("SII", "300 s × 20", "queued · 1h 40m", 0f),
)

data class Alert(
    val text: String,
    val time: String,
    val warn: Boolean,
    val cut: Boolean,
    val cloud: Boolean,
    val ok: Boolean,
    val iconKind: AlertIcon,
)

enum class AlertIcon { FLIP, SCISSORS, CLOUD, CHECKS }

val ALERTS = listOf(
    Alert("Meridian flip in 42 min — mount will pause, re-guide, resume", "02:18", warn = true, cut = false, cloud = false, ok = false, AlertIcon.FLIP),
    Alert("Sub 017 cut — HFR 2.94 above 2.80 threshold", "01:06", warn = false, cut = true, cloud = false, ok = false, AlertIcon.SCISSORS),
    Alert("Cloud passed, guiding recovered after 14 min", "01:18", warn = false, cut = false, cloud = true, ok = false, AlertIcon.CLOUD),
    Alert("Autofocus complete — 18 422, HFR 2.27", "00:41", warn = false, cut = false, cloud = false, ok = true, AlertIcon.CHECKS),
)

// ── Derived values (mirror the prototype renderVals math) ─────────────────

private fun hhmm(s: Int): String {
    val m = floor(s / 60.0).toInt()
    val r = s % 60
    return "$m:${r.toString().padStart(2, '0')}"
}

val SimState.elapsed: Int get() = (128 + t) % 300
val SimState.expRemain: String get() = hhmm(300 - elapsed)
val SimState.flipIn: String get() {
    val total = 2530 - t
    val m = floor(total / 60.0).toInt()
    val r = total % 60
    return "T−$m:${r.toString().padStart(2, '0')}"
}
val SimState.rms: Double get() = 0.48 + sin(t / 7.0) * 0.04
val SimState.fNow: Double get() = 0.485 + t / 9000.0
val SimState.paTotal: Double get() = hypot(paAlt, paAz)
val SimState.coolAtSetPoint: Boolean get() = abs(coolNow - coolTarget) < 0.2
val SimState.coolBarPct: Int get() {
    val raw = ((12.4 - coolNow) / (12.4 - coolTarget) * 100).roundToInt()
    return raw.coerceIn(2, 100)
}
val SimState.coolPowerPct: Int get() = min(99, (abs(coolNow - 12.4) * 3 + 8).roundToInt())
val SimState.frames: List<Frame> get() = FRAME_IDS.mapIndexed { i, id ->
    Frame(id, FRAME_HFRS[i], cut.contains(id))
}
val SimState.rejectCount: Int get() = frames.count { it.cut }
val SimState.keepCount: Int get() = 42 - rejectCount
val SimState.ready: Boolean get() = ekosRunning && isOn("mount") && isOn("cam")
fun SimState.isOn(key: String): Boolean = ekosRunning && key !in devOff
val SimState.missing: String get() {    val parts = buildList {
        if (!isOn("mount")) add("mount")
        if (!isOn("cam")) add("camera")
    }
    return parts.joinToString(" + ")
}

/** The prototype's trace generator: x = i + t*0.6 + seed, summed sines. */
fun wiggle(t: Int, seed: Int, n: Int, amp: Double): List<Double> =
    (0 until n).map { i ->
        val x = i + t * 0.6 + seed
        (sin(x * 0.7) + sin(x * 0.31) * 0.7 + sin(x * 1.9) * 0.35) / 2.05 * amp
    }

/** Ease a polar-align axis toward zero, exactly like the prototype. */
fun ease(v: Double): Double =
    if (abs(v) < 0.15) 0.0 else (v - sign(v) * 0.22)
