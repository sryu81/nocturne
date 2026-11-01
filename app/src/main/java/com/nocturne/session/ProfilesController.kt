package com.nocturne.session

import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.WireTrain
import com.nocturne.protocol.MODULE_KEY_BY_TRAIN_SETTING
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Rig profiles (start, stop, edit, delete, the setup wizard's save), the optical trains and module-to-train
 * assignments, and the scope catalog. Pulled out of [EkosRemoteController]: it keeps the overrides that also
 * call `super` (the optimistic local update) and delegates the wire half here, in the same order as before.
 */
internal class ProfilesController(
    private val ctx: ControllerContext,
    /** The phone's log of what Ekos and the app did; null = not kept. */
    private val log: LogCollector?,
) {

    /** Name a `profile_delete` was just sent for — checked against the next [EkosEvent.Profiles]. */
    private var pendingProfileDelete: String? = null

    /** Raw `train_get_profiles` reply (ordinal → train ID) — see [resolveModuleTrainAssignments]. */
    private var lastTrainProfileAssignments: Map<String, Int>? = null

    /**
     * Module → train-name for an optimistic [setModuleTrain] pick not yet confirmed by the
     * server's own snapshot — see [resolveModuleTrainAssignments] for why this exists.
     */
    private val pendingModuleTrainAssignments = mutableMapOf<String, String>()

    /** Folds a profile, train or scope reply into [s]; null when [event] is none of those. */
    fun reduce(s: AppState, event: EkosEvent): AppState? = when (event) {
        is EkosEvent.Profiles -> {
            val attemptedDelete = pendingProfileDelete
            pendingProfileDelete = null
            val refused = attemptedDelete != null && event.profiles.any { it.name == attemptedDelete }
            s.copy(
                profiles = event.profiles.map { it.toRigProfile() },
                selectedProfile = event.selectedProfile ?: s.selectedProfile,
                profileDeleteRefused = if (refused) attemptedDelete else null,
                // Every driver label saved in any profile on this Pi, unioned — see
                // AppState.realDeviceOptions. Available before Ekos is online, unlike wireDevices.
                wireKnownDrivers = event.profiles
                    .flatMap { it.drivers.entries }
                    .groupBy({ it.key }, { it.value })
                    .mapValues { (_, lists) -> lists.flatten().distinct() },
            )
        }
        // The UI models only two train slots (Primary/Secondary); trains 0 and 1 of the reply
        // map onto them. With one train, secondaryTrain keeps its default; a 3rd+ train is not
        // shown.
        is EkosEvent.Trains -> s.copy(
            wireTrains = event.trains,
            primaryTrain = event.trains.getOrNull(0)?.toTrainAssignment() ?: s.primaryTrain,
            secondaryTrain = event.trains.getOrNull(1)?.toTrainAssignment() ?: s.secondaryTrain,
            moduleTrainAssignments = resolveModuleTrainAssignments(s.moduleTrainAssignments, event.trains),
        )
        // train_get_profiles carries train IDs, not names, and can arrive before or after
        // train_get_all, so the raw ordinal→ID map is kept and re-resolved by whichever lands
        // second.
        is EkosEvent.TrainProfiles -> {
            lastTrainProfileAssignments = event.assignments
            s.copy(moduleTrainAssignments = resolveModuleTrainAssignments(s.moduleTrainAssignments, s.wireTrains))
        }
        is EkosEvent.Scopes -> s.copy(wireScopes = event.scopes.map { it.toScopeDef() })
        else -> null
    }

    fun startProfile(name: String) {
        log?.record("App", "Start Ekos requested with profile $name")
        ctx.send(Commands.PROFILE_START, buildJsonObject { put("name", name) })
    }

    fun stopProfile() {
        log?.record("App", "Stop Ekos requested")
        ctx.send(Commands.PROFILE_STOP)
    }

    /** No optimistic update: Stop Ekos unlocks only when each device's CONNECTION push says so. */
    fun disconnectDevices() {
        ctx.state().wireDevices.orEmpty().filter { it.connected }.forEach { device ->
            ctx.send(Commands.DEVICE_PROPERTY_SET, buildJsonObject {
                put("device", device.name)
                put("property", "CONNECTION")
                putJsonArray("elements") { addJsonObject { put("name", "DISCONNECT"); put("state", 1) } }
            })
        }
    }

    fun deleteProfile(name: String) {
        pendingProfileDelete = name
        ctx.send(Commands.PROFILE_DELETE, buildJsonObject { put("name", name) })
        // No optimistic removal: the get_profiles reply that follows is authoritative.
    }

    /**
     * The base implementation doesn't touch `selectedDeviceNames`/`devOff`, so the wizard would
     * show leftover picker state. Maps `RigProfile.drivers` (family → labels) back to category
     * keys via [CATEGORY_TO_DRIVER_FAMILY]; [finishSetup] runs the same mapping forward. Runs after the
     * controller's `super.editProfile`.
     */
    fun seedEditedProfile(name: String) {
        val p = ctx.state().profiles.firstOrNull { it.name == name } ?: return
        ctx.update { s ->
            if (s.setupEditingName != name) return@update s // base declined (Ekos running) — nothing to seed
            val selected = DEVICES.associate { d ->
                // "guide" has no family of its own — it's a second entry in drivers["CCDs"],
                // named only by the legacy `guider` field (see CATEGORY_TO_DRIVER_FAMILY).
                val label = if (d.key == "guide") p.guider.ifBlank { null }
                else CATEGORY_TO_DRIVER_FAMILY[d.key]?.let { family -> p.drivers[family]?.firstOrNull() }
                d.key to (label ?: "None")
            }
            s.copy(
                selectedDeviceNames = s.selectedDeviceNames + selected,
                devOff = selected.filterValues { it == "None" }.keys,
            )
        }
    }

    fun finishSetup() {
        val s = ctx.state()
        // "guide" shares the "CCDs" family with "cam"; the explicit "guider" field is the only
        // signal for which entry is the guide camera. profile_update persists "guider" alongside
        // "drivers", despite the docs saying legacy fields are only read when drivers is absent.
        val drivers: Map<String, List<String>> = DEVICES.filter { it.key !in s.devOff }
            .groupBy({ CATEGORY_TO_DRIVER_FAMILY[it.key] ?: "Aux" }, { s.selectedDeviceNames[it.key] ?: it.name })
        val guiderLabel = s.selectedDeviceNames["guide"]?.takeIf { "guide" !in s.devOff }
        val payload = buildJsonObject {
            put("name", s.profileName)
            put("auto_connect", true)
            put("port_selector", false)
            put("mode", "local")
            put("use_web_manager", false)
            put("guiding", 0) // guider-type enum, unconfirmed meaning — leave at the known-safe default
            putJsonObject("drivers") { drivers.forEach { (family, labels) -> putJsonArray(family) { labels.forEach { add(it) } } } }
            if (guiderLabel != null) put("guider", guiderLabel)
        }
        ctx.send(if (s.setupEditingName != null) Commands.PROFILE_UPDATE else Commands.PROFILE_ADD, payload)
        // Not super.finishSetup(): that marks Ekos running, but this only sends
        // profile_add/update, never profile_start. Close the sheet; the following get_profiles
        // reply reconciles the profile list.
        ctx.update { it.copy(sheet = null, setupEditingName = null) }
    }

    /**
     * The `train_add`/`train_update` payload shape is undocumented, so this sends every field
     * `train_get_all` reports, with [id] when a matching train is known (else `train_add`).
     * The `train_get_all` auto-push reconciles whatever the server accepted.
     */
    fun sendTrainUpdate(slot: TrainSlot) {
        val s = ctx.state()
        val t = s.train(slot)
        val wireTrain = s.wireTrains?.getOrNull(if (slot == TrainSlot.PRIMARY) 0 else 1)
        val payload = buildJsonObject {
            wireTrain?.let { put("id", it.id) }
            put("name", wireTrain?.name ?: if (slot == TrainSlot.PRIMARY) "Primary" else "Secondary")
            put("mount", t.mount)
            put("camera", t.camera)
            put("guider", t.guideVia)
            put("focuser", t.focuser)
            put("filterwheel", t.filterWheel)
            put("rotator", t.rotator)
            put("reducer", t.reducer.toString())
            put("dustcap", t.dustCap)
            put("lightbox", t.lightBox)
            put("scope", t.scope)
            put("adaptiveoptics", t.adaptiveOptics)
        }
        ctx.send(if (wireTrain != null) Commands.TRAIN_UPDATE else Commands.TRAIN_ADD, payload)
    }

    // ── Scopes catalog (separate from Optical Trains) ───────────────────

    /**
     * `train_set` (`processTrainCommands` in `message.cpp`): each module's `setOpticalTrain(name)`
     * persists to the active profile's `ProfileSettings`. No direct reply, so
     * `train_get_profiles` is re-sent to confirm.
     */
    fun setModuleTrain(module: String, trainName: String) {
        pendingModuleTrainAssignments[module] = trainName
        ctx.send(Commands.TRAIN_SET, buildJsonObject { put("module", module); put("name", trainName) })
        ctx.send(Commands.TRAIN_GET_PROFILES)
    }

    fun addScope(name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int) {
        ctx.send(Commands.SCOPE_ADD, buildJsonObject {
            put("model", name); put("vendor", vendor); put("type", type)
            put("focal_length", focalMm.toDouble()); put("aperture", apertureMm.toDouble())
        })
    }

    fun updateScope(id: String, name: String, vendor: String, type: String, focalMm: Int, apertureMm: Int) {
        // Only a wire-known scope has a server id to update; a local-only scope is added instead.
        val isWireKnown = ctx.state().wireScopes?.any { it.id == id } == true
        if (isWireKnown) {
            ctx.send(Commands.SCOPE_UPDATE, buildJsonObject {
                put("id", id); put("model", name); put("vendor", vendor); put("type", type)
                put("focal_length", focalMm.toDouble()); put("aperture", apertureMm.toDouble())
            })
        } else {
            ctx.send(Commands.SCOPE_ADD, buildJsonObject {
                put("model", name); put("vendor", vendor); put("type", type)
                put("focal_length", focalMm.toDouble()); put("aperture", apertureMm.toDouble())
            })
        }
    }

    fun removeScope(id: String) {
        if (ctx.state().wireScopes?.any { it.id == id } == true) {
            ctx.send(Commands.SCOPE_DELETE, buildJsonObject { put("id", id) })
        }
    }

    /**
     * Resolves the raw ordinal→trainID map against [trains] into module-key→train-name, merged
     * onto [previous] rather than replacing it.
     *
     * `train_get_profiles` can carry a stale train ID left over from before the trains were
     * recreated, matching nothing in `train_get_all`. Such a module keeps whatever [previous]
     * had (an optimistic pick, or absent = shown unselected) instead of being dropped.
     *
     * Ekos's persistence may also lag a [setModuleTrain] pick, so a reply can resolve to the
     * pre-edit train. A module with an outstanding pick in [pendingModuleTrainAssignments] keeps
     * that pick until `resolved` agrees with it, at which point it's cleared.
     */
    private fun resolveModuleTrainAssignments(previous: Map<String, String>?, trains: List<WireTrain>?): Map<String, String>? {
        val raw = lastTrainProfileAssignments ?: return previous
        if (trains == null) return previous
        val resolved = raw.mapNotNull { (ordinal, trainId) ->
            val module = MODULE_KEY_BY_TRAIN_SETTING[ordinal] ?: return@mapNotNull null
            val name = trains.firstOrNull { it.id == trainId }?.name ?: return@mapNotNull null
            module to name
        }.toMap()
        pendingModuleTrainAssignments.entries.removeAll { (module, expected) -> resolved[module] == expected }
        return (previous ?: emptyMap()) + resolved + pendingModuleTrainAssignments
    }
}
