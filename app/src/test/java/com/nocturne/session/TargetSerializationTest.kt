package com.nocturne.session

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [Target] is persisted as JSON by `TargetRepository` (user catalogue) — confirms it round-trips. */
class TargetSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun customTargetRoundTrips() {
        val targets = listOf(
            Target(id = "custom_3", common = "Sh2-129", coords = "21h11m48s +59°59′00″", custom = true),
            Target(id = "M 31", common = "M 31", coords = "00h42m44s +41°16′09″", ra0 = 0.712, de0 = 41.27, magnitude = 3.4),
        )
        assertEquals(targets, json.decodeFromString<List<Target>>(json.encodeToString(targets)))
    }

    @Test
    fun findTargetFallsBackToSnapshotOnceSearchMovesOn() {
        val picked = Target(id = "NGC 891", common = "NGC 891", coords = "02h22m33s +42°20′57″")
        val s = AppState(targetId = picked.id, targetSnapshot = picked, wireSearchResults = emptyList())
        assertEquals(picked, s.findTarget("NGC 891"))
        assertNull(s.findTarget("NGC 7000"))
    }
}
