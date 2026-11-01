package com.nocturne.session

import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.WireAstroObject
import com.nocturne.protocol.WireRiseset
import com.nocturne.transport.ReferenceImageClient
import com.nocturne.hips.SkyAtlas
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The Plan tab's target search and framing data (`astro_*` commands): the name lists, the resolved rows with
 * their rise and set times, the typed-name lookup, the altitude chart's data and the framing reference image.
 * Pulled out of [EkosRemoteController], which keeps the `super`-calling overrides and delegates the rest.
 *
 * It owns the state those replies build up. [reduce] folds a reply into [AppState] (called from the
 * controller's event reducer) and [onEvent] sends what a reply calls for (called from its follow-ups).
 */
internal class PlanSearchController(
    private val ctx: ControllerContext,
    /** Offline sky atlas for framing, copied from the rig. Null = online cutouts only. */
    private val skyAtlas: SkyAtlas?,
    /** Asked before optional internet use (online framing cutout). */
    private val allowOptionalInternet: () -> Boolean,
) {

    /** Plain internet fetch for reference images; independent of the wire protocol. */
    private val referenceImageClient = ReferenceImageClient()

    /** Name a standalone [ensureTargetRiseset] fetch is in flight for — see that function's doc. */
    private var pendingTargetRisesetName: String? = null

    // Plan tab search. astro_get_objects_info and riseset replies can arrive in either order, so
    // the latest of each is kept and merged by buildSearchResults(). astro_search_objects has no
    // name filter (only type/alt/mag/FOV), so its flat name list (lastSearchNames) is
    // substring-filtered locally against AppState.query, and re-filtered on each keystroke.
    private var lastAstroObjects: List<WireAstroObject> = emptyList()
    private var lastRiseset: List<WireRiseset> = emptyList()
    private var lastSearchNames: List<String>? = null
    // No-type name search: every name KStars knows (astro_get_names), fetched once per
    // connection on first need and filtered locally like lastSearchNames.
    private var allNames: List<String>? = null
    private var allNamesRequested = false
    // Typed-name lookup (astro_get_object_info): finds names the type search misses (other type,
    // below the altitude cutoff, no catalogued magnitude). One request in flight at a time;
    // replies carry the resolved name, not the typed one, so a query typed mid-flight is queued
    // and the in-flight reply discarded. lastLookupObject is the current query's match, if any.
    private var lastLookupObject: WireAstroObject? = null
    private var lookupInFlight = false
    private var queuedLookupQuery: String? = null

    /** Folds one search reply into [s]; null when [event] is not a search reply. */
    fun reduce(s: AppState, event: EkosEvent): AppState? = when (event) {
        is EkosEvent.AstroSearchResult -> {
            lastSearchNames = event.names
            resetSearchResults(s)
        }
        is EkosEvent.AstroNames -> {
            allNames = event.names
            resetSearchResults(s)
        }
        is EkosEvent.AstroObjectInfo -> {
            lookupInFlight = false
            if (queuedLookupQuery == null) {
                lastLookupObject = event.obj
                s.copy(wireSearchResults = buildSearchResults())
            } else s // stale — sendFollowUpCommands sends the queued query instead
        }
        is EkosEvent.AstroObjectsInfo -> {
            lastAstroObjects = event.objects
            s.copy(wireSearchResults = buildSearchResults())
        }
        is EkosEvent.AstroObjectsRiseset -> {
            // This reply serves several consumers sharing one cache: the search-results merge and
            // ensureTargetRiseset's single-name fetch for the framed target. Upsert rather than
            // replace, so a 1-entry reply doesn't wipe the other rows' riseset data. A fresh
            // search resets the cache first (AstroSearchResult arm).
            val incomingNames = event.entries.map { it.name }.toSet()
            lastRiseset = lastRiseset.filterNot { it.name in incomingNames } + event.entries
            // Only claim this reply for ensureTargetRiseset if the pending name is in it, so an
            // unrelated search reply doesn't clear the pending fetch.
            val pending = pendingTargetRisesetName
            val matched = pending?.let { name -> event.entries.firstOrNull { it.name == name } }
            if (matched != null) pendingTargetRisesetName = null
            // "Moon" rides along on ensureTargetRiseset's request for the Moon altitude curve.
            // Kept once captured; it doesn't depend on which target is framed.
            val moonEntry = event.entries.firstOrNull { it.name == "Moon" }
            s.copy(
                wireSearchResults = buildSearchResults(),
                wireTargetRiseset = matched ?: s.wireTargetRiseset,
                wireMoonRiseset = moonEntry ?: s.wireMoonRiseset,
            )
        }

        else -> null
    }

    /** What a search reply calls for next: resolve the names, send a queued lookup, run the first search. */
    fun onEvent(event: EkosEvent) {
        when (event) {
            is EkosEvent.AstroSearchResult -> if (ctx.state().searchType != null) sendSearchResolve()
            is EkosEvent.AstroNames -> if (ctx.state().searchType == null) sendSearchResolve()
            is EkosEvent.AstroObjectInfo -> {
                val queued = queuedLookupQuery
                if (queued != null) {
                    queuedLookupQuery = null
                    sendNameLookup(queued)
                } else event.obj?.let { obj ->
                    // Same exact-name riseset as sendSearchResolve, for the pinned row's "X° max".
                    ctx.send(Commands.ASTRO_GET_OBJECTS_RISESET, buildJsonObject {
                        putJsonArray("names") { add(obj.name) }
                        put("exact", true)
                    })
                }
            }
            // First automatic search, so the Plan tab has results without user input. The almanac
            // reply rides the online burst (EkosRemoteClient) and needs the same geo/clock as the
            // search, so it signals astro_* is answering; it's fetched once per connection.
            is EkosEvent.AstroAlmanac -> if (lastSearchNames == null) runSearch()
            else -> Unit
        }
    }

    /** Names matching the typed query, before the [SEARCH_RESULT_CAP] cut. With a type: that
     *  search's names (all of them for a blank query). Without one: nothing until the query is
     *  [minLookupLength]+ chars, then every catalog's names, prefix matches first. */
    private fun matchingSearchNames(s: AppState): List<String> {
        val q = s.query.trim()
        if (s.searchType == null) {
            val names = allNames ?: return emptyList()
            if (q.length < minLookupLength) return emptyList()
            val (prefix, rest) = names.filter { it.contains(q, ignoreCase = true) }
                .partition { it.startsWith(q, ignoreCase = true) }
            return prefix + rest
        }
        val names = lastSearchNames ?: return emptyList()
        return if (q.isBlank()) names else names.filter { it.contains(q, ignoreCase = true) }
    }

    /** Clears resolved rows ahead of a fresh resolve — [sendSearchResolve] repopulates them. */
    private fun resetSearchResults(s: AppState): AppState {
        lastAstroObjects = emptyList()
        lastRiseset = emptyList()
        return s.copy(wireSearchResults = emptyList(), wireSearchMatchCount = matchingSearchNames(s).size)
    }

    /** Name-lookup match pinned first (unless the type search already has it), then the list. */
    private fun buildSearchResults(): List<Target> {
        val lookup = lastLookupObject?.takeIf { l -> lastAstroObjects.none { it.name == l.name } }
        return (listOfNotNull(lookup) + lastAstroObjects)
            .map { it.toTarget(lastRiseset.firstOrNull { rs -> rs.name == it.name }) }
    }

    /**
     * Typed-query change: re-filter the cached name list locally (no new server-side search),
     * and look the typed text up directly by name across every catalog/type.
     */
    fun refilterSearch() {
        lastLookupObject = null
        requestNameLookup(ctx.state().query.trim())
        val haveNames = if (ctx.state().searchType == null) allNames != null else lastSearchNames != null
        if (!haveNames) return runSearch()
        ctx.update { resetSearchResults(it) }
        sendSearchResolve()
    }

    /** Below this, KStars' substring name match hits nearly everything — not worth a round-trip. */
    private val minLookupLength = 2

    private fun requestNameLookup(query: String) {
        val q = query.takeIf { it.length >= minLookupLength }
        when {
            lookupInFlight -> queuedLookupQuery = q ?: "" // "" = drop the in-flight reply, send nothing
            q != null -> sendNameLookup(q)
        }
    }

    private fun sendNameLookup(query: String) {
        if (query.isEmpty()) return
        lookupInFlight = true
        ctx.send(Commands.ASTRO_GET_OBJECT_INFO, buildJsonObject { put("object", query) })
    }

    /**
     * Resolves the brightest [SEARCH_RESULT_CAP] matching names' coords/size/riseset. The DSO
     * database hands names back brightest-first, so the cap keeps the most useful slice.
     */
    private fun sendSearchResolve() {
        val names = matchingSearchNames(ctx.state()).take(SEARCH_RESULT_CAP)
        if (names.isEmpty()) return
        ctx.send(Commands.ASTRO_GET_OBJECTS_INFO, buildJsonObject { putJsonArray("names") { names.forEach { add(it) } } })
        // "exact": true, as in ensureTargetRiseset (message.cpp:2295): otherwise a name KStars
        // resolves to a different preferred name misses buildSearchResults()'s name-keyed merge
        // and the row shows no "X° max".
        ctx.send(Commands.ASTRO_GET_OBJECTS_RISESET, buildJsonObject {
            putJsonArray("names") { names.forEach { add(it) } }
            put("exact", true)
        })
    }

    /**
     * `astro_search_objects` has no free-text field (only `type`/`direction`/`maxMagnitude`/
     * `minAlt`/`minDuration`/`minFOV`), so [AppState.searchType]/[AppState.searchMaxMag]/
     * [AppState.chips] drive the wire call and the typed query is applied client-side
     * ([matchingSearchNames]). `type` is a single `SkyObject::TYPE`. The DSO query is
     * `magnitude < maxMagnitude`, so objects with no catalogued magnitude (common for large
     * emission nebulae) never match a type search.
     */
    fun runSearch() {
        val s = ctx.state()
        if (s.searchType == null) {
            // Name search: no server-side filtering; the full name list is the source.
            if (allNames == null) {
                if (!allNamesRequested) {
                    allNamesRequested = true
                    ctx.send(Commands.ASTRO_GET_NAMES)
                }
                ctx.update { it.copy(wireSearchResults = emptyList(), wireSearchMatchCount = null) }
            } else {
                ctx.update { resetSearchResults(it) }
                sendSearchResolve()
            }
            return
        }
        ctx.send(Commands.ASTRO_SEARCH_OBJECTS, buildJsonObject {
            put("type", s.searchType)
            put("maxMagnitude", s.searchMaxMag.toDouble())
            put("minAlt", if (s.chips.contains(1)) 40.0 else 15.0)
            put("minDuration", if (s.chips.contains(0)) 3600 else 0)
            put("minFOV", if (s.chips.contains(2)) 1.0 else 0.0)
        })
    }

    // ── Sequence tab — Scheduler + .esq ─────────────────────────────────

    /**
     * Altitude data for the Plan tab's chart. `astro_get_objects_riseset` resolves any
     * KStars-known name directly, with no prior `astro_search_objects` needed, so this covers
     * catalog and custom targets too. Skips the fetch if cached or in flight for this name, so
     * it's cheap to call on every [selectTarget].
     */
    fun ensureTargetRiseset(targetId: String) {
        val target = ctx.state().findTarget(targetId) ?: return
        val name = target.realLookupName
        // The Moon's curve rides along as a second name in the same request. A cached target
        // still refetches until the Moon has been captured this connection.
        if ((pendingTargetRisesetName == name || ctx.state().wireTargetRiseset?.name == name) &&
            ctx.state().wireMoonRiseset != null
        ) return
        pendingTargetRisesetName = name
        // "exact": true makes the reply echo the name we sent (message.cpp:2295); otherwise it
        // carries KStars' preferred name (e.g. for M 64), the match below fails, and the chart
        // silently keeps the previous target. Safe: the name is catalog-sourced, and findByName
        // still falls back to a fuzzy lookup if the exact form doesn't resolve
        // (catalogscomponent.cpp:334-338).
        ctx.send(Commands.ASTRO_GET_OBJECTS_RISESET, buildJsonObject {
            putJsonArray("names") { add(name); add("Moon") }
            put("exact", true)
        })
    }

    /**
     * Reference image for framing: the offline sky atlas if available, else an online cutout
     * (see [ReferenceImageClient]). FOV is 1.4× the camera's ([AppState.framingFovDeg]) so the
     * drawn FOV box doesn't sit on the edge, with a placeholder before CCD_INFO arrives. Skips
     * if this target is already cached.
     */
    fun ensureReferenceImage(targetId: String) {
        val s = ctx.state()
        if (s.referenceImageForTargetId == targetId) return
        val target = s.findTarget(targetId) ?: return
        val (raDeg, decDeg) = target.raDecDegrees ?: return
        val fovDeg = (s.framingFovDeg?.let { maxOf(it.first, it.second) } ?: 2.4) * 1.4
        ctx.update { it.copy(referenceImageForTargetId = targetId, referenceImageJpeg = null, referenceImageFovDeg = null, referenceImageOnlineSkipped = false) }
        ctx.scope.launch {
            // Offline atlas first (works with no internet, any FOV); CDS cutout as the fallback.
            val offline = skyAtlas?.render(raDeg, decDeg, fovDeg, ctx.state().skyAtlas.completeOrder)
            val online = offline == null && allowOptionalInternet()
            val jpeg = offline ?: if (online) referenceImageClient.fetchCutout(raDeg, decDeg, fovDeg) else null
            // Apply only if the framed target hasn't changed meanwhile, so a slow fetch doesn't
            // land on a since-selected target.
            if (ctx.state().referenceImageForTargetId == targetId) {
                ctx.update {
                    it.copy(referenceImageJpeg = jpeg, referenceImageFovDeg = fovDeg.takeIf { jpeg != null }, referenceImageOffline = offline != null, referenceImageOnlineSkipped = offline == null && !online)
                }
            }
        }
    }
}
