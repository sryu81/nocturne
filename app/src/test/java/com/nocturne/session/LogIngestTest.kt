package com.nocturne.session

import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.EkosEventCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LogIngestTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(s: String) = LocalDateTime.parse(s).atZone(utc).toInstant().toEpochMilli()

    // Newest first, as Ekos sends it.
    private val blob = "2026-10-24T20:30:05 Capture complete\n2026-10-24T20:27:00 Starting capture of 180 s"

    @Test
    fun parsesNewestFirstWithTimestamps() {
        val lines = LogIngest.parseModuleLog("Capture", blob, receivedAtMs = 1, zone = utc)
        assertEquals(listOf("Capture complete", "Starting capture of 180 s"), lines.map { it.text })
        assertEquals(ms("2026-10-24T20:30:05"), lines[0].timeMs)
        assertEquals("2026-10-24T20:30:05 Capture complete", lines[0].raw)
    }

    @Test
    fun aLineWithoutTimestampContinuesTheEntryBeforeIt() {
        val lines = LogIngest.parseModuleLog("Align", "2026-10-24T20:30:05 Solver failed:\nno stars found\n2026-10-24T20:29:00 Solving", 1, utc)
        assertEquals(2, lines.size)
        assertEquals("Solver failed:\nno stars found", lines[0].text)
    }

    @Test
    fun missingTimestampFallsBackToReceiveTimeAndNothingElseBreaks() {
        assertTrue(LogIngest.parseModuleLog("Capture", "no stamp here", 5, utc).isEmpty())
        val badDate = LogIngest.parseModuleLog("Capture", "2026-13-45T99:99:99 odd", 7, utc)
        assertEquals(7L, badDate.single().timeMs)
    }

    @Test
    fun classifiesByKeyword() {
        assertEquals(LogLevel.ERROR, LogIngest.classify("Capture failed: sensor error"))
        assertEquals(LogLevel.ERROR, LogIngest.classify("Job aborted"))
        assertEquals(LogLevel.WARN, LogIngest.classify("Cannot delete currently running job"))
        assertEquals(LogLevel.WARN, LogIngest.classify("Driver timeout"))
        assertEquals(LogLevel.INFO, LogIngest.classify("Starting capture"))
    }

    @Test
    fun nightKeyKeepsAfterMidnightWithTheEvening() {
        assertEquals("2026-10-24", LogIngest.nightKey(ms("2026-10-24T22:00:00"), utc))
        assertEquals("2026-10-24", LogIngest.nightKey(ms("2026-10-25T03:30:00"), utc))
        assertEquals("2026-10-25", LogIngest.nightKey(ms("2026-10-25T15:00:00"), utc))
    }

    @Test
    fun trackerYieldsOnlyNewLinesOldestFirst() {
        val tracker = ModuleLogTracker()
        val first = LogIngest.parseModuleLog("Capture", blob, 1, utc)
        assertEquals(listOf("Starting capture of 180 s", "Capture complete"), tracker.fresh("Capture", first).map { it.text })

        val grown = LogIngest.parseModuleLog("Capture", "2026-10-24T20:31:00 Next frame\n$blob", 2, utc)
        assertEquals(listOf("Next frame"), tracker.fresh("Capture", grown).map { it.text })

        assertTrue(tracker.fresh("Capture", grown).isEmpty())
    }

    @Test
    fun trackerKeepsModulesApartAndTakesEverythingWhenTheLogWasCleared() {
        val tracker = ModuleLogTracker()
        tracker.fresh("Capture", LogIngest.parseModuleLog("Capture", blob, 1, utc))
        assertEquals(2, tracker.fresh("Guide", LogIngest.parseModuleLog("Guide", blob, 1, utc)).size)
        val cleared = LogIngest.parseModuleLog("Capture", "2026-10-24T21:00:00 Fresh start", 3, utc)
        assertEquals(listOf("Fresh start"), tracker.fresh("Capture", cleared).map { it.text })
    }

    @Test
    fun logShapedPushesDecodeAsStateEventsWithTheirLog() {
        val capture = EkosEventCodec.decode("""{"payload":{"log":"2026-10-24T20:30:05 hi"},"type":"new_capture_state"}""")
        assertEquals(EkosEvent.NewCaptureState(status = null, log = "2026-10-24T20:30:05 hi"), capture)
        val status = EkosEventCodec.decode("""{"payload":{"status":"Capturing"},"type":"new_capture_state"}""")
        assertEquals(EkosEvent.NewCaptureState(status = "Capturing"), status)
        val guide = EkosEventCodec.decode("""{"payload":{"log":"x"},"type":"new_guide_state"}""") as EkosEvent.NewGuideState
        assertEquals("x", guide.log)
    }
}
