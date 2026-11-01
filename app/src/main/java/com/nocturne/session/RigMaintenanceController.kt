package com.nocturne.session

import com.nocturne.data.ConnectionRepository
import com.nocturne.protocol.Commands
import com.nocturne.transport.EkosRemoteClient
import com.nocturne.hips.SkyAtlas
import com.nocturne.transport.RigRebootClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Rig maintenance over the Pi's maintenance daemon (reboot, shutdown, the Pi clock), the connect-time clock
 * and location sync, and the offline sky atlas. Pulled out of [EkosRemoteController]; the controller keeps
 * `syncLocationToRig`'s `super` call and delegates the rest.
 */
internal class RigMaintenanceController(
    private val ctx: ControllerContext,
    /** The phone's log of what Ekos and the app did; null = not kept. */
    private val log: LogCollector?,
    /** Offline sky atlas copied from the rig; null = none. */
    private val skyAtlas: SkyAtlas?,
    /** Persists the daemon's port. Null in tests. */
    private val connectionRepo: ConnectionRepository?,
) {

    private var skyAtlasCopyJob: Job? = null

    /** In-memory mirror of the reboot daemon's port, for [rebootRig]/[syncTimeToRig]. */
    private var rigRebootPort: Int = 9001

    /** Scans the phone's tiles once at start, when there is an atlas. */
    fun start() {
        if (skyAtlas != null) ctx.scope.launch { rescanSkyAtlas() }
    }

    /** The saved port, read from disk after the controller starts; [setRigRebootPort] is the user's change. */
    fun restoreRebootPort(port: Int) {
        rigRebootPort = port
    }

    /** Persists the reboot daemon's port in memory (for [rebootRig]) and to disk via [connectionRepo]. */
    fun setRigRebootPort(port: Int) {
        rigRebootPort = port
        ctx.update { it.copy(rigRebootPort = port, rigRebootState = RigRebootState.IDLE, rigRebootError = null) }
        ctx.scope.launch { connectionRepo?.saveRebootPort(port) }
    }

    /**
     * Sends the reboot request over the daemon's HTTP channel (see [RigRebootClient]), not the
     * EkosRemote wire. Success only means the Pi accepted it; the socket then drops and
     * [EkosRemoteClient]'s reconnect picks the session back up.
     */
    fun rebootRig() {
        log?.record("App", "Reboot of the Pi requested")
        ctx.update { it.copy(rigRebootState = RigRebootState.SENDING, rigRebootError = null) }
        ctx.scope.launch {
            val result = RigRebootClient(ctx.host, rigRebootPort).reboot()
            ctx.update { s ->
                result.fold(
                    onSuccess = { s.copy(rigRebootState = RigRebootState.SENT, rigRebootError = null) },
                    onFailure = { e -> s.copy(rigRebootState = RigRebootState.FAILED, rigRebootError = e.message ?: "request failed") },
                )
            }
        }
    }

    /** Same daemon and channel as [rebootRig]; success only means the Pi accepted the request. */
    fun shutdownRig() {
        log?.record("App", "Shutdown of the Pi requested")
        ctx.update { it.copy(rigShutdownState = RigRebootState.SENDING, rigShutdownError = null) }
        ctx.scope.launch {
            val result = RigRebootClient(ctx.host, rigRebootPort).shutdown()
            ctx.update { s ->
                result.fold(
                    onSuccess = { s.copy(rigShutdownState = RigRebootState.SENT, rigShutdownError = null) },
                    onFailure = { e -> s.copy(rigShutdownState = RigRebootState.FAILED, rigShutdownError = e.message ?: "request failed") },
                )
            }
        }
    }

    private suspend fun rescanSkyAtlas() {
        val local = skyAtlas?.scanLocal() ?: return
        ctx.update { it.copy(skyAtlas = it.skyAtlas.copy(localTiles = local.tiles, completeOrder = local.completeOrder)) }
    }

    fun refreshSkyAtlas() {
        val atlas = skyAtlas ?: return
        ctx.scope.launch {
            rescanSkyAtlas()
            val rig = runCatching { atlas.rigTiles(ctx.host, rigRebootPort) }
            ctx.update { s ->
                s.copy(skyAtlas = s.skyAtlas.copy(
                    rigTiles = rig.getOrNull(),
                    error = rig.exceptionOrNull()?.let { "Rig atlas: ${it.message ?: "unreachable"}" },
                ))
            }
        }
    }

    /** Over the reboot daemon's `/hips/` (same host and port as [rebootRig]), 4 tiles at a time. */
    fun copySkyAtlasFromRig() {
        val atlas = skyAtlas ?: return
        if (skyAtlasCopyJob?.isActive == true) return
        ctx.update { it.copy(skyAtlas = it.skyAtlas.copy(copying = true, error = null, lastMissing = null)) }
        skyAtlasCopyJob = ctx.scope.launch {
            var missing: Int? = null
            var error: String? = null
            try {
                missing = atlas.copyFromRig(ctx.host, rigRebootPort) { done, _ ->
                    ctx.update { it.copy(skyAtlas = it.skyAtlas.copy(copyDone = done)) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Copy stopped: ${e.message ?: "rig unreachable"}"
            } finally {
                // also runs after Cancel, so the sheet doesn't stay stuck on "Copying…"
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    rescanSkyAtlas()
                    ctx.update { s ->
                        s.copy(skyAtlas = s.skyAtlas.copy(copying = false, lastMissing = missing, error = error))
                    }
                }
            }
        }
    }

    fun cancelSkyAtlasCopy() {
        skyAtlasCopyJob?.cancel()
    }

    fun clearSkyAtlas() {
        val atlas = skyAtlas ?: return
        skyAtlasCopyJob?.cancel()
        ctx.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            atlas.clear()
            rescanSkyAtlas()
        }
    }

    /**
     * Sets the KStars clock over the wire and the Pi OS clock via the reboot daemon's
     * `POST /set-time` (see [RigRebootClient.setTime]). Sends the phone's local wall-clock time
     * with no timezone conversion, assuming phone and rig are co-located.
     */
    fun syncTimeToRig() {
        // KStars' internal clock over the EkosRemote wire; works without the reboot daemon.
        // See KSTARS_SET_TIME (Commands.kt).
        val now = java.time.LocalDateTime.now()
        ctx.send(
            Commands.KSTARS_SET_TIME,
            buildJsonObject {
                put("yr", now.year); put("mth", now.monthValue); put("day", now.dayOfMonth)
                put("hr", now.hour); put("min", now.minute); put("sec", now.second)
            },
        )

        // Pi OS clock — best-effort, needs the reboot daemon running on the Pi.
        ctx.update { it.copy(piTimeSyncState = RigRebootState.SENDING, piTimeSyncError = null) }
        val iso = now.withNano(0).toString()
        ctx.scope.launch {
            val result = RigRebootClient(ctx.host, rigRebootPort).setTime(iso)
            ctx.update { s ->
                result.fold(
                    onSuccess = { s.copy(piTimeSyncState = RigRebootState.SENT, piTimeSyncError = null) },
                    onFailure = { e -> s.copy(piTimeSyncState = RigRebootState.FAILED, piTimeSyncError = e.message ?: "request failed") },
                )
            }
        }
    }

    /**
     * The automatic part of [syncTimeToRig]/[syncLocationToRig] at connect. Waits for the link and
     * a few seconds for Ekos' states (the Scheduler's running state is only known then), reads the
     * Pi's clock from the daemon, and syncs time only when it is off by more than
     * [CLOCK_SKEW_OK_SEC] and nothing is running: a clock step under a running Scheduler job could
     * upset it, and a reconnect mid-session must not do that.
     */
    fun syncRigOnConnect(location: Triple<Double, Double, Double>?) {
        ctx.scope.launch {
            if (!ctx.awaitOnline(AUTO_SYNC_LINK_TIMEOUT_MS)) return@launch
            delay(AUTO_SYNC_SETTLE_MS)
            val running = ctx.state().schedulerIsRunning()
            val skew = RigRebootClient(ctx.host, rigRebootPort).health()?.clockSkewSec(System.currentTimeMillis())
            when (autoTimeSync(skew, running)) {
                AutoTimeSync.SYNC -> {
                    log?.record("App", "Rig clock was ${skew}s off the phone's; syncing time")
                    syncTimeToRig()
                }
                AutoTimeSync.CLOCK_OK -> log?.record("App", "Rig clock is within ${CLOCK_SKEW_OK_SEC}s of the phone's, time not changed")
                AutoTimeSync.RUNNING -> log?.record("App", "Rig clock is ${skew}s off the phone's but a job is running, time not changed", LogLevel.WARN)
                AutoTimeSync.UNKNOWN -> log?.record("App", "Rig clock unknown (maintenance daemon not answering), time not synced", LogLevel.WARN)
            }
            if (location == null) {
                log?.record("App", "Location not synced: no phone location permission or fix (Home > Rig maintenance syncs it by hand)", LogLevel.WARN)
            } else if (running) {
                log?.record("App", "A job is running, location not changed")
            } else {
                log?.record("App", "Syncing KStars location from the phone: %.4f, %.4f".format(location.first, location.second))
                syncLocationToRig(location.first, location.second, location.third)
            }
        }
    }

    /** The wire half of the location sync; the controller calls `super` after it. */
    fun syncLocationToRig(lat: Double, lon: Double, elevM: Double) {
        val tzHours = java.util.TimeZone.getDefault().rawOffset / 3_600_000.0
        ctx.send(
            Commands.KSTARS_SET_LOCATION,
            buildJsonObject { put("lat", lat); put("lon", lon); put("elev", elevM); put("tz", tzHours) },
        )
    }
}

private const val AUTO_SYNC_LINK_TIMEOUT_MS = 30_000L
private const val AUTO_SYNC_SETTLE_MS = 6_000L
