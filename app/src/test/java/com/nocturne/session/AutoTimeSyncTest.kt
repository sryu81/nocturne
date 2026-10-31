package com.nocturne.session

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTimeSyncTest {
    @Test
    fun aClockWithinTheToleranceIsLeftAlone() {
        assertEquals(AutoTimeSync.CLOCK_OK, autoTimeSync(0, false))
        assertEquals(AutoTimeSync.CLOCK_OK, autoTimeSync(-2, true))
        assertEquals(AutoTimeSync.CLOCK_OK, autoTimeSync(2, false))
    }

    @Test
    fun aClockOffByMoreIsSyncedWhenNothingRuns() {
        assertEquals(AutoTimeSync.SYNC, autoTimeSync(3, false))
        assertEquals(AutoTimeSync.SYNC, autoTimeSync(-3600, false))
    }

    @Test
    fun aRunningJobBlocksAClockStep() {
        assertEquals(AutoTimeSync.RUNNING, autoTimeSync(90, true))
    }

    @Test
    fun anUnknownClockIsNotGuessed() {
        assertEquals(AutoTimeSync.UNKNOWN, autoTimeSync(null, false))
    }
}
