package com.nocturne.ui.home

import com.nocturne.transport.ConnectionState
import com.nocturne.transport.ConnectionStatus
import com.nocturne.transport.LinkStats
import com.nocturne.transport.PiHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RigVerdictTest {
    private val now = 1_000_000L
    private fun status(state: ConnectionState) = ConnectionStatus(state, "192.168.8.40")
    private fun link(lastMessageAgoMs: Long) = LinkStats().apply { lastMessageAtMs = now - lastMessageAgoMs }
    private val daemon = PiHealth(45.0, 100, 0.1, 1, null, emptyList(), rttMs = 10, receivedAtMs = now)

    @Test
    fun everythingAnsweringIsGreen() {
        val v = rigVerdict(status(ConnectionState.ONLINE), link(2_000), RigHealthSnapshot(beaconAtMs = now - 3_000, daemon = daemon, daemonChecked = true), now)
        assertEquals(Health.OK, v.health)
    }

    @Test
    fun aConnectedButSilentKStarsMeansFrozen() {
        val v = rigVerdict(status(ConnectionState.ONLINE), link(45_000), RigHealthSnapshot(daemon = daemon, daemonChecked = true), now)
        assertEquals(Health.BAD, v.health)
        assertTrue("frozen" in v.text)
    }

    @Test
    fun noSocketButADaemonMeansRestartKStarsNotThePi() {
        val v = rigVerdict(status(ConnectionState.CONNECTING), link(1_000), RigHealthSnapshot(daemon = daemon, daemonChecked = true), now)
        assertEquals(Health.BAD, v.health)
        assertTrue("restart KStars" in v.text)
    }

    @Test
    fun noSocketNoDaemonNoBeaconMeansThePiIsGone() {
        val v = rigVerdict(status(ConnectionState.CONNECTING), null, RigHealthSnapshot(beaconAtMs = now - 60_000, daemonChecked = true), now)
        assertEquals(Health.BAD, v.health)
        assertTrue("isn't reachable" in v.text)
    }

    @Test
    fun beforeTheFirstDaemonAnswerNothingIsClaimed() {
        val v = rigVerdict(status(ConnectionState.CONNECTING), null, RigHealthSnapshot(), now)
        assertEquals(Health.OK, v.health)
    }

    @Test
    fun aSocketWithoutADaemonIsOnlyAWarning() {
        val v = rigVerdict(status(ConnectionState.ONLINE), link(1_000), RigHealthSnapshot(daemonChecked = true, beaconAtMs = now), now)
        assertEquals(Health.WARN, v.health)
    }
}
