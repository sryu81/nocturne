package com.nocturne.session

/** How loudly a phone notification should speak; [CRITICAL] still gets through quiet hours. */
enum class PushLevel { INFO, WARN, CRITICAL }

/** [key] is stable for one condition, so posting it again replaces the earlier notification. */
data class PushNotification(val key: String, val title: String, val text: String, val level: PushLevel)

/**
 * Decides what is worth a phone notification while the app is in a pocket: Ekos alerts of
 * warning level or above, a module failing while a job runs, the link to the rig staying broken,
 * and a full disk. Pure, so the decisions can be tested; the foreground service feeds it and posts
 * the results. The Pi's under-voltage flags are deliberately not used: a Pi 5 sets them on a
 * harmless supply.
 */
class NotificationRules {
    private val seenAlerts = mutableSetOf<String>()
    private var previous: AppState? = null
    private var brokenSinceMs: Long? = null
    private var brokenReported = false
    private val diskReportedAtMs = mutableMapOf<String, Long>()

    /** Call with each new [AppState]. The first call only remembers what already exists. */
    fun onState(state: AppState): List<PushNotification> {
        val before = previous
        previous = state
        val fresh = state.wireNotifications.filter { seenAlerts.add(it.uuid) }
        if (before == null) return emptyList()

        val out = mutableListOf<PushNotification>()
        for (alert in fresh) {
            if (alert.severity < 2) continue
            out += PushNotification(
                key = "alert:${alert.uuid}",
                title = alert.sourceLabel,
                text = alert.message,
                level = if (alert.severity >= 3) PushLevel.CRITICAL else PushLevel.WARN,
            )
        }
        // "Aborted" is also what Ekos reports after a deliberate stop, so a module only counts as
        // failing while the Scheduler runs a job both before and after the change.
        if (before.schedulerRunning && state.schedulerRunning) {
            for (label in MODULE_STATUS_LABELS) {
                if (label == "Sched") continue
                val now = state.moduleStatus(label) ?: continue
                if (now == before.moduleStatus(label) || !isFailure(now)) continue
                out += PushNotification("module:$label:$now", "$label: $now", "$label reported \"$now\" while a job is running.", PushLevel.CRITICAL)
            }
        }
        return out
    }

    /**
     * [problem] is the health view's verdict text when the link is broken, else null. It has to
     * persist for [BROKEN_AFTER_MS] before it is reported, and a recovery is reported once.
     */
    fun onLink(problem: String?, nowMs: Long): List<PushNotification> {
        if (problem == null) {
            val wasReported = brokenReported
            brokenSinceMs = null
            brokenReported = false
            return if (wasReported) listOf(PushNotification("link", "Rig link restored", "The rig is answering again.", PushLevel.INFO)) else emptyList()
        }
        val since = brokenSinceMs ?: nowMs.also { brokenSinceMs = it }
        if (brokenReported || nowMs - since < BROKEN_AFTER_MS) return emptyList()
        brokenReported = true
        return listOf(PushNotification("link", "Rig link problem", problem, PushLevel.CRITICAL))
    }

    /** [name] a volume ("SD card", "USB /mnt/astro"), [freeFraction] 0..1. Reported at most hourly per volume. */
    fun onDisk(name: String, freeFraction: Double, nowMs: Long): List<PushNotification> {
        if (freeFraction >= DISK_LOW_FRACTION) {
            diskReportedAtMs.remove(name)
            return emptyList()
        }
        val last = diskReportedAtMs[name]
        if (last != null && nowMs - last < DISK_REPEAT_MS) return emptyList()
        diskReportedAtMs[name] = nowMs
        return listOf(PushNotification("disk:$name", "$name is nearly full", "%.0f%% free: captures may fail.".format(freeFraction * 100), PushLevel.WARN))
    }

    private fun isFailure(status: String): Boolean =
        status.contains("fail", true) || status.contains("abort", true) || status.contains("error", true)

    companion object {
        const val BROKEN_AFTER_MS = 30_000L
        const val DISK_LOW_FRACTION = 0.05
        const val DISK_REPEAT_MS = 3_600_000L
    }
}

/** What reaches the phone with quiet hours on: only the critical ones. */
fun List<PushNotification>.forQuietHours(quiet: Boolean): List<PushNotification> =
    if (quiet) filter { it.level == PushLevel.CRITICAL } else this
