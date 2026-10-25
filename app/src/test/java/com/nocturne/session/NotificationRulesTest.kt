package com.nocturne.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {
    private fun alert(id: String, severity: Int, source: Int = 5, msg: String = "m") = Alert(id, msg, "00:00", source, severity)
    private fun state(vararg alerts: Alert, running: Boolean = false, guide: String? = null) =
        AppState(wireNotifications = alerts.toList(), schedulerRunning = running, schedulerStatus = if (running) 2 else 0, wireGuideStatus = guide)

    @Test
    fun theFirstStateOnlyRemembersExistingAlerts() {
        val rules = NotificationRules()
        assertTrue(rules.onState(state(alert("a", 3))).isEmpty())
        assertTrue(rules.onState(state(alert("a", 3))).isEmpty())
    }

    @Test
    fun newWarningAndAlertSeverityNotifyButInfoDoesNot() {
        val rules = NotificationRules()
        rules.onState(state())
        val out = rules.onState(state(alert("1", 1), alert("2", 2, msg = "slew slow"), alert("3", 3, msg = "flip failed")))
        assertEquals(listOf("alert:2", "alert:3"), out.map { it.key }.sorted())
        assertEquals(PushLevel.WARN, out.first { it.key == "alert:2" }.level)
        assertEquals(PushLevel.CRITICAL, out.first { it.key == "alert:3" }.level)
        assertEquals("Mount", out.first().title)
        // the same alert again is not repeated
        assertTrue(rules.onState(state(alert("3", 3))).isEmpty())
    }

    @Test
    fun aModuleFailureCountsOnlyWhileAJobRuns() {
        val rules = NotificationRules()
        rules.onState(state(running = true, guide = "Guiding"))
        val lost = rules.onState(state(running = true, guide = "Aborted"))
        assertEquals(listOf("module:Guide:Aborted"), lost.map { it.key })
        assertEquals(PushLevel.CRITICAL, lost.single().level)

        // a deliberate stop: the scheduler stops at the same time as the module aborts
        val rules2 = NotificationRules()
        rules2.onState(state(running = true, guide = "Guiding"))
        assertTrue(rules2.onState(state(running = false, guide = "Aborted")).isEmpty())
        // idle rig, user stopped guiding by hand
        val rules3 = NotificationRules()
        rules3.onState(state(guide = "Guiding"))
        assertTrue(rules3.onState(state(guide = "Aborted")).isEmpty())
    }

    @Test
    fun aBrokenLinkIsReportedOnlyAfterItPersistsAndRecoveryOnce() {
        val rules = NotificationRules()
        assertTrue(rules.onLink("The Pi isn't reachable", 0).isEmpty())
        assertTrue(rules.onLink("The Pi isn't reachable", 20_000).isEmpty())
        val down = rules.onLink("The Pi isn't reachable", 31_000)
        assertEquals(PushLevel.CRITICAL, down.single().level)
        assertTrue(rules.onLink("The Pi isn't reachable", 60_000).isEmpty())
        val up = rules.onLink(null, 70_000)
        assertEquals(PushLevel.INFO, up.single().level)
        assertTrue(rules.onLink(null, 80_000).isEmpty())
    }

    @Test
    fun aBriefGlitchNeverNotifiesNorClaimsRecovery() {
        val rules = NotificationRules()
        rules.onLink("x", 0)
        assertTrue(rules.onLink(null, 5_000).isEmpty())
    }

    @Test
    fun aFullDiskIsReportedHourlyAndResetsWhenSpaceReturns() {
        val rules = NotificationRules()
        assertEquals(1, rules.onDisk("SD card", 0.03, 0).size)
        assertTrue(rules.onDisk("SD card", 0.03, 1_000_000).isEmpty())
        assertEquals(1, rules.onDisk("SD card", 0.03, 3_700_000).size)
        assertTrue(rules.onDisk("SD card", 0.5, 4_000_000).isEmpty())
        assertEquals(1, rules.onDisk("SD card", 0.02, 4_100_000).size)
        assertTrue(rules.onDisk("USB /mnt/astro", 0.9, 0).isEmpty())
    }

    @Test
    fun quietHoursKeepOnlyTheCriticalOnes() {
        val all = listOf(
            PushNotification("a", "t", "x", PushLevel.INFO),
            PushNotification("b", "t", "x", PushLevel.WARN),
            PushNotification("c", "t", "x", PushLevel.CRITICAL),
        )
        assertEquals(listOf("c"), all.forQuietHours(true).map { it.key })
        assertEquals(3, all.forQuietHours(false).size)
    }
}
