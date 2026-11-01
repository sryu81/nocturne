package com.nocturne.session

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A job keeps its own copy of its target. Before that, adding a search result to the queue and then
 * searching again (or restarting the app) left the push with no coordinates, and the Scheduler add silently
 * did nothing.
 */
class JobTargetSnapshotTest {
    private val m31 = Target(id = "M 31", common = "Andromeda", coords = "00h42m44s +41°16′08″", ra0 = 0.712, de0 = 41.269)
    private val m33 = Target(id = "M 33", common = "Triangulum", coords = "01h33m50s +30°39′36″")
    private val sequenceJson = Json { ignoreUnknownKeys = true }   // the same settings SequenceRepository saves with

    private fun withResults(vararg t: Target) = AppState(wireSearchResults = t.toList())

    @Test
    fun aNewJobHoldsACopyOfItsTarget() {
        val job = withResults(m31, m33).newJobFor("M 31", nowMs = 1_000L)
        assertEquals(m31, job.target)
        assertEquals("M 31", job.targetId)
        assertEquals(1_000L, job.createdAtMs)
        assertEquals(1, job.blocks.size)
    }

    @Test
    fun theJobStillKnowsItsTargetAfterTheSearchResultsAreReplaced() {
        val job = withResults(m31).newJobFor("M 31")
        val later = AppState(wireSearchResults = listOf(m33), targetId = "M 33", targetSnapshot = m33, jobs = listOf(job))
        assertNull(later.findTarget("M 31"))                       // what the push used to see
        assertEquals(m31, later.targetOf(job))                     // what it sees now
        assertEquals("Andromeda_${job.id}_" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .format(java.time.Instant.ofEpochMilli(job.createdAtMs).atZone(java.time.ZoneId.systemDefault())), later.targetNameFor(job))
    }

    @Test
    fun theLiveTargetWinsWhenItCanStillBeFound() {
        val job = withResults(m31).newJobFor("M 31")
        val edited = m31.copy(coords = "00h42m00s +41°00′00″")
        val later = AppState(userTargets = listOf(edited), jobs = listOf(job))
        assertEquals(edited, later.targetOf(job))
    }

    @Test
    fun aTargetTheAppNeverKnewLeavesTheJobWithoutOne() {
        val job = AppState().newJobFor("Mystery")
        assertNull(job.target)
        assertNull(AppState(jobs = listOf(job)).targetOf(job))
    }

    @Test
    fun theCopyKeepsTheCoordinatesThePushNeeds() {
        val job = withResults(m31).newJobFor("M 31")
        val coords = AppState().targetOf(job)!!.coords
        assertTrue(Regex("""(\d+)h(\d+)m(\d+)s\s+([+-])(\d+)°(\d+)′(\d+)″""").containsMatchIn(coords))
    }

    @Test
    fun theCopySurvivesSavingAndLoadingTheQueue() {
        val job = withResults(m31).newJobFor("M 31")
        val back = sequenceJson.decodeFromString<List<SequenceJob>>(sequenceJson.encodeToString(listOf(job))).single()
        assertEquals(m31, back.target)
    }

    @Test
    fun aQueueSavedBeforeTheFieldStillLoads() {
        val old = """[{"id":"j1","targetId":"M 31","blocks":[],"blockSeq":1,"synced":true,"createdAtMs":5}]"""
        val job = sequenceJson.decodeFromString<List<SequenceJob>>(old).single()
        assertEquals("M 31", job.targetId)
        assertNull(job.target)
        assertTrue(job.synced)
    }
}
