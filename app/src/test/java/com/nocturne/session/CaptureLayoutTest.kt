package com.nocturne.session

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CaptureLayoutTest {
    private val zone: ZoneId = ZoneOffset.ofHours(-7)

    private fun at(day: Int, hour: Int, minute: Int = 0): Instant =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant()

    /** Tonight's dusk and dawn as the almanac gives them on 2026-10-30 (19:41 to 06:03). */
    private val tonight = at(30, 19, 41) to at(31, 6, 3)

    /** The same night when Home's `nearestMidnight` picks the midnight just passed (small hours). */
    private val lastNight = at(29, 19, 41) to at(30, 6, 3)

    @Test
    fun anEveningJobBelongsToThatDate() {
        assertEquals(LocalDate.of(2026, 10, 30), CaptureLayout.nightDate(at(30, 20, 14), tonight, zone))
    }

    @Test
    fun anAfternoonJobPushedForTonightBelongsToToday() {
        assertEquals(LocalDate.of(2026, 10, 30), CaptureLayout.nightDate(at(30, 15), tonight, zone))
    }

    @Test
    fun framesAfterMidnightStayWithTheEveningBefore() {
        assertEquals(LocalDate.of(2026, 10, 29), CaptureLayout.nightDate(at(30, 1, 15), lastNight, zone))
    }

    @Test
    fun aMorningJobForTheComingEveningTakesTheNextNight() {
        // 11:00 on the 30th: the window is still the night that ended at 06:03.
        assertEquals(LocalDate.of(2026, 10, 30), CaptureLayout.nightDate(at(30, 11), lastNight, zone))
    }

    @Test
    fun withoutAnAlmanacItIsTheDateTwelveHoursAgo() {
        assertEquals(LocalDate.of(2026, 10, 30), CaptureLayout.nightDate(at(30, 20, 14), null, zone))
        assertEquals(LocalDate.of(2026, 10, 29), CaptureLayout.nightDate(at(30, 0, 30), null, zone))
        assertEquals(LocalDate.of(2026, 10, 30), CaptureLayout.nightDate(at(30, 12, 1), null, zone))
    }

    @Test
    fun theDateIsTheSitesNotTheUtcDate() {
        // 20:14 at UTC-7 is already the next day in UTC.
        assertEquals(LocalDate.of(2026, 10, 30), CaptureLayout.nightDate(at(30, 20, 14), tonight, zone))
    }

    @Test
    fun targetFoldersAreFileSafe() {
        assertEquals("M_31", CaptureLayout.targetFolder("M 31"))
        assertEquals("NGC_7193", CaptureLayout.targetFolder("NGC 7193"))
        assertEquals("Andromeda_Galaxy", CaptureLayout.targetFolder("  Andromeda Galaxy "))
        assertEquals("Unnamed", CaptureLayout.targetFolder("   "))
        assertEquals("Unnamed", CaptureLayout.targetFolder("///"))
        assertEquals("M_31", CaptureLayout.targetFolder("../M 31/.."))
    }

    @Test
    fun theDirectoryAndFormatFollowTheRule() {
        val night = LocalDate.of(2026, 10, 30)
        assertEquals("/home/soo/Nocturne/M_31/2026-10-30", CaptureLayout.fitsDirectory("M 31", night))
        assertEquals("/%T/%F/M_31_%T_%F_%e_%D", CaptureLayout.placeholderFormat("M 31"))
    }

    @Test
    fun aJobPathNeverLeavesTheRoot() {
        val dir = CaptureLayout.fitsDirectory("../../etc", LocalDate.of(2026, 10, 30))
        assertEquals("/home/soo/Nocturne/etc/2026-10-30", dir)
    }
}
