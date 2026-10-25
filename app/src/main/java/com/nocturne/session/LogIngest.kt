package com.nocturne.session

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class LogLevel { INFO, WARN, ERROR }

/** One log entry as stored: [raw] is the original line, the stable identity used to skip repeats. */
data class LogLine(
    val timeMs: Long,
    val source: String,
    val level: LogLevel,
    val text: String,
    val raw: String,
)

/**
 * Turns Ekos' module log pushes into [LogLine]s. Each module sends its whole log on every new
 * line, newest line first, each line prefixed `yyyy-MM-ddThh:mm:ss ` (`Capture::appendLogText`
 * and the other modules), so a push is parsed once and only the lines not seen before are kept
 * ([ModuleLogTracker]).
 */
object LogIngest {
    private val STAMP = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}) (.*)$""")

    /** Entries of [blob] in the order sent (newest first). A line without a timestamp is the rest of the entry before it, so a multi-line message stays one entry. */
    fun parseModuleLog(source: String, blob: String, receivedAtMs: Long, zone: ZoneId): List<LogLine> {
        val entries = mutableListOf<Pair<String, String>>() // raw, stamp
        blob.split('\n').forEach { line ->
            val m = STAMP.matchEntire(line)
            when {
                m != null -> entries += line to m.groupValues[1]
                line.isNotBlank() && entries.isNotEmpty() -> {
                    val (raw, stamp) = entries.last()
                    entries[entries.lastIndex] = "$raw\n$line" to stamp
                }
            }
        }
        return entries.map { (raw, stamp) ->
            val text = raw.substring(stamp.length + 1)
            val timeMs = runCatching { LocalDateTime.parse(stamp).atZone(zone).toInstant().toEpochMilli() }.getOrDefault(receivedAtMs)
            LogLine(timeMs, source, classify(text), text, raw)
        }
    }

    private val INDI_TAG = Regex("""\[(INFO|WARNING|ERROR)]""")
    private val INDI_STAMP = Regex("""^\d{4}-\d{2}-\d{2}T[\d:.]+:\s*""")

    /** An INDI driver message as the fork forwards it, without the fork's own `new_log`: "ZWO EFW: [INFO] ...". */
    fun deviceMessage(device: String, message: String, nowMs: Long): LogLine? {
        val text = message.replace(INDI_STAMP, "").trim()
        if (text.isEmpty()) return null
        val level = when (INDI_TAG.find(text)?.groupValues?.get(1)) {
            "ERROR" -> LogLevel.ERROR
            "WARNING" -> LogLevel.WARN
            "INFO" -> LogLevel.INFO
            else -> classify(text)
        }
        val shown = "$device: $text"
        return LogLine(nowMs, "INDI", level, shown, "indi:$nowMs:$shown")
    }

    /** A line of the fork's `new_log`/`get_logs`; the fork's level wins, else the keyword guess. */
    fun forkLine(line: com.nocturne.protocol.WireLogLine): LogLine = LogLine(
        timeMs = line.time,
        source = line.module,
        level = when (line.level) {
            0 -> LogLevel.INFO
            1 -> LogLevel.WARN
            2 -> LogLevel.ERROR
            else -> classify(line.text)
        },
        text = line.text,
        raw = "fork:${line.id}",
    )

    private val ERROR_WORDS = listOf("error", "fail", "abort", "critical", "crash", "exception")
    private val WARN_WORDS = listOf(
        "warn", "timeout", "timed out", "cannot", "can't", "unable", "lost", "refus", "retry", "ignor",
        "not available", "invalid",
    )

    /** Keyword guess, since the module logs carry no level. */
    fun classify(text: String): LogLevel {
        val t = text.lowercase()
        return when {
            ERROR_WORDS.any { it in t } -> LogLevel.ERROR
            WARN_WORDS.any { it in t } -> LogLevel.WARN
            else -> LogLevel.INFO
        }
    }

    /** `new_notification` severity: 0 debug, 1 info, 2 warn, 3 alert. */
    fun alertLevel(severity: Int): LogLevel = when {
        severity >= 3 -> LogLevel.ERROR
        severity == 2 -> LogLevel.WARN
        else -> LogLevel.INFO
    }

    /**
     * The night a moment belongs to, named by the evening it began: shifting back 12 hours keeps
     * everything after midnight with the evening before. "2026-10-24" is the night of 24→25 Oct.
     */
    fun nightKey(timeMs: Long, zone: ZoneId): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(timeMs - 12 * 3_600_000L), zone).toString()
}

/**
 * Remembers each module's newest line so a push that repeats the whole history yields only what
 * is new. The first push of a connection yields everything; the store ignores lines it already
 * has, so a reconnect doesn't duplicate them.
 */
class ModuleLogTracker {
    private val newest = mutableMapOf<String, String>()

    /** [lines] newest first, as sent. Returns the unseen ones, oldest first. */
    fun fresh(source: String, lines: List<LogLine>): List<LogLine> {
        if (lines.isEmpty()) return emptyList()
        val seen = newest[source]
        val boundary = if (seen == null) -1 else lines.indexOfFirst { it.raw == seen }
        newest[source] = lines.first().raw
        // A log that was cleared or trimmed past the remembered line has no boundary: take it all.
        return (if (boundary < 0) lines else lines.subList(0, boundary)).asReversed()
    }
}
