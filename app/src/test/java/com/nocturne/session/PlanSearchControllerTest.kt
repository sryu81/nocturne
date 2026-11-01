package com.nocturne.session

import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.WireAstroObject
import com.nocturne.protocol.WireRiseset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Plan tab's search behaviour (`astro_*` replies and the commands they trigger), which used to
 * live in the middle of [EkosRemoteController] where nothing could reach it.
 */
class PlanSearchControllerTest {

    private class Fixture(initial: AppState = AppState()) {
        val state = MutableStateFlow(initial)
        val sent = mutableListOf<Pair<String, JsonElement>>()
        val ctx = ControllerContext(
            sender = { type, payload -> sent += type to payload },
            link = { true },
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state.value },
            update = { f -> state.update(f) },
        )
        val search = PlanSearchController(ctx, skyAtlas = null, allowOptionalInternet = { false })

        /** What the controller does for a reply: fold it into the state, then run its follow-ups. */
        fun reply(event: EkosEvent) {
            state.update { s -> search.reduce(s, event) ?: s }
            search.onEvent(event)
        }

        fun commands(type: String) = sent.filter { it.first == type }.map { it.second.jsonObject }
    }

    private fun galaxy(name: String) = WireAstroObject(name = name, ra0 = 10.0, de0 = 41.0, magnitude = 3.4)

    private fun riseset(name: String, peak: Double, transit: String = "23:10") =
        WireRiseset(name = name, date = "2026-10-31", rise = "17:00", set = "05:00", transit = transit, altitudes = listOf(10.0, peak, 20.0))

    @Test
    fun aNameSearchAsksForEveryNameOnlyOnce() {
        val f = Fixture(AppState(searchType = null, query = "m3"))
        f.search.runSearch()
        f.search.runSearch()
        assertEquals(1, f.commands("astro_get_names").size)
        assertEquals(emptyList<Target>(), f.state.value.wireSearchResults)
        assertNull(f.state.value.wireSearchMatchCount)
    }

    @Test
    fun theNamesArrivingResolveTheMatchingOnesWithPrefixMatchesFirst() {
        val f = Fixture(AppState(searchType = null, query = "m3"))
        f.reply(EkosEvent.AstroNames(listOf("NGC7000", "XM3", "M31", "M3", "M33")))
        assertEquals(4, f.state.value.wireSearchMatchCount)       // M31, M3, M33 and XM3 contain "m3"
        val asked = f.commands("astro_get_objects_info").single()["names"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("M31", "M3", "M33", "XM3"), asked)     // the three prefix matches, then the rest
        val riseset = f.commands("astro_get_objects_riseset").single()
        assertTrue(riseset["exact"]!!.jsonPrimitive.boolean)       // otherwise a renamed object misses the merge
        assertEquals(asked, riseset["names"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun aShortTypedNameLooksUpNothing() {
        val f = Fixture(AppState(searchType = null, query = "m"))
        f.reply(EkosEvent.AstroNames(listOf("M31", "M3")))
        assertEquals(0, f.state.value.wireSearchMatchCount)
        assertEquals(0, f.commands("astro_get_objects_info").size)
    }

    @Test
    fun aTypeSearchSendsItsFiltersAndTheChipsChangeThem() {
        val f = Fixture(AppState(searchType = 8, searchMaxMag = 9, chips = listOf(1, 2)))
        f.search.runSearch()
        val p = f.commands("astro_search_objects").single()
        assertEquals(8, p["type"]!!.jsonPrimitive.int)
        assertEquals(9.0, p["maxMagnitude"]!!.jsonPrimitive.double, 0.0)
        assertEquals(40.0, p["minAlt"]!!.jsonPrimitive.double, 0.0)      // chip 1: higher altitude
        assertEquals(0, p["minDuration"]!!.jsonPrimitive.int)
        assertEquals(1.0, p["minFOV"]!!.jsonPrimitive.double, 0.0)       // chip 2: fits the camera field

        val g = Fixture(AppState(searchType = 8, chips = listOf(0)))
        g.search.runSearch()
        val q = g.commands("astro_search_objects").single()
        assertEquals(15.0, q["minAlt"]!!.jsonPrimitive.double, 0.0)
        assertEquals(3600, q["minDuration"]!!.jsonPrimitive.int)         // chip 0: up for an hour
        assertEquals(0.0, q["minFOV"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun resultsMergeWhicheverReplyArrivesFirst() {
        val a = Fixture().also { it.reply(EkosEvent.AstroObjectsInfo(listOf(galaxy("M31")))); it.reply(EkosEvent.AstroObjectsRiseset(listOf(riseset("M31", 72.4)))) }
        val b = Fixture().also { it.reply(EkosEvent.AstroObjectsRiseset(listOf(riseset("M31", 72.4)))); it.reply(EkosEvent.AstroObjectsInfo(listOf(galaxy("M31")))) }
        for (f in listOf(a, b)) {
            val row = f.state.value.wireSearchResults!!.single()
            assertEquals("M31", row.id)
            assertEquals(72, row.max)
            assertEquals("23:10", row.peak)
        }
    }

    @Test
    fun aOneNameRisesetReplyKeepsTheOtherRowsData() {
        val f = Fixture()
        f.reply(EkosEvent.AstroObjectsInfo(listOf(galaxy("M31"), galaxy("M33"))))
        f.reply(EkosEvent.AstroObjectsRiseset(listOf(riseset("M31", 60.0), riseset("M33", 50.0))))
        f.reply(EkosEvent.AstroObjectsRiseset(listOf(riseset("M31", 75.0))))     // e.g. the framed target's own fetch
        val rows = f.state.value.wireSearchResults!!.associateBy { it.id }
        assertEquals(75, rows["M31"]!!.max)
        assertEquals(50, rows["M33"]!!.max)
    }

    @Test
    fun aFreshSearchReplyClearsTheResolvedRowsThenResolvesTheNewNames() {
        val f = Fixture(AppState(searchType = 8))
        f.reply(EkosEvent.AstroObjectsInfo(listOf(galaxy("M31"))))
        f.reply(EkosEvent.AstroSearchResult(listOf("M33", "M81")))
        assertEquals(emptyList<Target>(), f.state.value.wireSearchResults)
        assertEquals(2, f.state.value.wireSearchMatchCount)
        val asked = f.commands("astro_get_objects_info").single()["names"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("M33", "M81"), asked)
    }

    @Test
    fun theChartFetchAsksForTheTargetAndTheMoonUntilBothAreCached() {
        val f = Fixture(AppState(userTargets = listOf(Target(id = "M31", common = "Andromeda", coords = "00h42m +41°16′"))))
        f.search.ensureTargetRiseset("M31")
        val first = f.commands("astro_get_objects_riseset").single()
        assertEquals(listOf("M31", "Moon"), first["names"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(first["exact"]!!.jsonPrimitive.boolean)

        f.reply(EkosEvent.AstroObjectsRiseset(listOf(riseset("M31", 70.0), riseset("Moon", 30.0))))
        assertEquals("M31", f.state.value.wireTargetRiseset?.name)
        assertEquals("Moon", f.state.value.wireMoonRiseset?.name)

        f.search.ensureTargetRiseset("M31")                                     // cached with the Moon: no new request
        assertEquals(1, f.commands("astro_get_objects_riseset").size)
    }

    @Test
    fun anUnrelatedRisesetReplyDoesNotClaimThePendingChartFetch() {
        val f = Fixture(AppState(userTargets = listOf(Target(id = "M31", common = "Andromeda", coords = "x"))))
        f.search.ensureTargetRiseset("M31")
        f.reply(EkosEvent.AstroObjectsRiseset(listOf(riseset("M33", 50.0))))
        assertNull(f.state.value.wireTargetRiseset)
        f.reply(EkosEvent.AstroObjectsRiseset(listOf(riseset("M31", 70.0))))
        assertEquals("M31", f.state.value.wireTargetRiseset?.name)
    }

    @Test
    fun aTypedNameIsLookedUpAndALaterOneWaitsForTheFirstReply() {
        val f = Fixture(AppState(searchType = 8, query = "andromeda"))
        f.search.refilterSearch()
        assertEquals("andromeda", f.commands("astro_get_object_info").single()["object"]!!.jsonPrimitive.content)

        f.state.update { it.copy(query = "triangulum") }
        f.search.refilterSearch()
        assertEquals(1, f.commands("astro_get_object_info").size)               // one in flight at a time

        val before = f.state.value
        f.reply(EkosEvent.AstroObjectInfo(galaxy("M31")))                       // the in-flight reply is stale now
        assertEquals(before, f.state.value)
        val asked = f.commands("astro_get_object_info").map { it["object"]!!.jsonPrimitive.content }
        assertEquals(listOf("andromeda", "triangulum"), asked)                  // the queued query goes out next
    }

    @Test
    fun aLookupMatchIsPinnedFirstAndGetsItsOwnRisesetRequest() {
        val f = Fixture(AppState(searchType = 8))
        f.reply(EkosEvent.AstroObjectsInfo(listOf(galaxy("M33"))))
        f.reply(EkosEvent.AstroObjectInfo(galaxy("M31")))
        assertEquals(listOf("M31", "M33"), f.state.value.wireSearchResults!!.map { it.id })
        val ask = f.commands("astro_get_objects_riseset").last()
        assertEquals(listOf("M31"), ask["names"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(ask["exact"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun theAlmanacStartsTheFirstSearchOnlyWhenNothingHasBeenSearched() {
        val f = Fixture(AppState(searchType = null))
        f.reply(EkosEvent.AstroAlmanac())
        assertEquals(1, f.commands("astro_get_names").size)

        val g = Fixture(AppState(searchType = 8))
        g.reply(EkosEvent.AstroSearchResult(listOf("M31")))
        g.sent.clear()
        g.reply(EkosEvent.AstroAlmanac())
        assertEquals(0, g.sent.count { it.first == "astro_search_objects" })
    }

    @Test
    fun otherEventsAreNotClaimed() {
        val f = Fixture()
        assertNull(f.search.reduce(f.state.value, EkosEvent.NewConnectionState(connected = true, online = true)))
    }
}
