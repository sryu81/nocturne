package com.nocturne.session

import com.nocturne.data.LogRepository
import com.nocturne.protocol.EkosEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Feeds the phone's log ([LogRepository]) from what the connection sees: each module's log push
 * (only the new lines of it), Ekos notifications, and entries the app writes about its own
 * connection and actions ([record]). Nothing here changes what the app does; it only remembers.
 */
class LogCollector(
    private val repo: LogRepository,
    private val scope: CoroutineScope,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val tracker = ModuleLogTracker()

    fun onEvent(event: EkosEvent, nowMs: Long = System.currentTimeMillis()) {
        when (event) {
            is EkosEvent.NewCaptureState -> module("Capture", event.log, nowMs)
            is EkosEvent.NewFocusState -> module("Focus", event.log, nowMs)
            is EkosEvent.NewGuideState -> module("Guide", event.log, nowMs)
            is EkosEvent.NewAlignState -> module("Align", event.log, nowMs)
            is EkosEvent.NewSchedulerState -> module("Scheduler", event.log, nowMs)
            is EkosEvent.NewNotification -> {
                val message = event.message ?: return
                val source = Alert("", message, "", event.source ?: 0, event.severity ?: 1).sourceLabel
                write(listOf(LogLine(nowMs, "Alert", LogIngest.alertLevel(event.severity ?: 1), "$source: $message", "alert:${event.uuid ?: "$nowMs:$message"}")))
            }
            else -> {}
        }
    }

    /** An entry the app itself writes: a connection change, or something it just sent. */
    fun record(source: String, text: String, level: LogLevel = LogLevel.INFO, nowMs: Long = System.currentTimeMillis()) {
        write(listOf(LogLine(nowMs, source, level, text, "$source:$nowMs:$text")))
    }

    private fun module(source: String, blob: String?, nowMs: Long) {
        if (blob.isNullOrBlank()) return
        write(tracker.fresh(source, LogIngest.parseModuleLog(source, blob, nowMs, zone)))
    }

    private fun write(lines: List<LogLine>) {
        if (lines.isEmpty()) return
        scope.launch { repo.add(lines) { LogIngest.nightKey(it, zone) } }
    }
}
