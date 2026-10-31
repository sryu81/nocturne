package com.nocturne.session

import com.nocturne.protocol.WireAstroObject
import com.nocturne.protocol.WireDevice
import com.nocturne.protocol.WireProfile
import com.nocturne.protocol.WireRiseset
import com.nocturne.protocol.WireScope
import com.nocturne.protocol.WireTrain
import com.nocturne.protocol.bitmaskToRoles
import kotlin.math.roundToInt

// Wire-model to app-model mappers used by the event reducer and the controller.

internal fun WireProfile.toRigProfile() = RigProfile(name = name, deviceKeys = drivers.values.flatten(), drivers = drivers, guider = guider)

internal fun WireDevice.toLiveDevice() = LiveDevice(name = name, connected = connected, roles = bitmaskToRoles(interfaceMask))

/**
 * `scope_add`/`scope_update` have no `name` field, so the app's single name is sent as
 * `model`. On the way back, a train's `scope` field references [WireScope]'s `name`, not
 * `model`, so decoding prefers `name` and falls back to `model` if blank.
 */
internal fun WireScope.toScopeDef() = ScopeDef(
    id = id,
    name = name.ifBlank { model },
    vendor = vendor,
    type = type,
    focalMm = focal_length.roundToInt(),
    apertureMm = aperture.roundToInt(),
)

/** Blank fields (an unassigned role in `train_get_all`) become "None". `internal` so tests can call it directly. */
internal fun WireTrain.toTrainAssignment() = TrainAssignment(
    mount = mount.ifBlank { "None" },
    camera = camera.ifBlank { "None" },
    rotator = rotator.ifBlank { "None" },
    guideVia = guider.ifBlank { "None" },
    dustCap = dustcap.ifBlank { "None" },
    scope = scope.ifBlank { "None" },
    filterWheel = filterwheel.ifBlank { "None" },
    focuser = focuser.ifBlank { "None" },
    reducer = reducer,
    lightBox = lightbox.ifBlank { "None" },
    adaptiveOptics = (adaptiveoptics ?: "").ifBlank { "None" },
)

/**
 * Translates one `astro_get_objects_info` entry (plus its riseset entry, if that reply has
 * landed) into [Target]. `size`/`band`/`usable`/`fov` stay null; the wire has no equivalent.
 */
internal fun WireAstroObject.toTarget(riseset: WireRiseset?): Target = Target(
    id = name,
    common = name,
    coords = "${formatRaHours(ra0)} ${formatDecDegrees(de0)}",
    max = riseset?.altitudes?.maxOrNull()?.let { kotlin.math.round(it).toInt() },
    peak = riseset?.transit,
    custom = false,
    ra0 = ra0,
    de0 = de0,
    magnitude = magnitude,
)

/** Matches "20h59m17s +44°31′44″" — see [EkosRemoteController.parseCoordsToRaDecBox]. */
internal val COORDS_REGEX = Regex("""(\d+)h(\d+)m(\d+)s\s+([+-])(\d+)°(\d+)′(\d+)″""")
