package com.nocturne.ui.sequence

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nocturne.protocol.SchedulerJobStatus
import com.nocturne.protocol.WireSchedulerJob
import com.nocturne.protocol.jobStatusLabel
import com.nocturne.session.BINNING_OPTIONS
import com.nocturne.session.Block
import com.nocturne.session.DITHER_OPTIONS
import com.nocturne.session.FILTER_CYCLE
import com.nocturne.session.SequenceJob
import com.nocturne.session.SessionController
import com.nocturne.session.SheetType
import com.nocturne.session.AppState
import com.nocturne.session.contractJob
import com.nocturne.session.displayName
import com.nocturne.session.findTarget
import com.nocturne.session.meta
import com.nocturne.session.missing
import com.nocturne.session.pct
import com.nocturne.session.plannedHM
import com.nocturne.session.realFilterNames
import com.nocturne.session.spec
import com.nocturne.session.ready
import com.nocturne.session.targetNameFor
import com.nocturne.session.unmanagedWireJobs
import com.nocturne.session.wireJobFor
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.SwitchRow
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

@Composable
fun SequenceScreen(
    state: AppState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    onFixInGear: () -> Unit = {},
) {
    val activeJob = state.jobs.firstOrNull { it.id == state.activeJobId }
    if (activeJob != null) {
        JobDetailScreen(state, ctrl, activeJob, landscape, modifier, onFixInGear)
    } else {
        JobListScreen(state, ctrl, landscape, modifier)
    }
}

@Composable
private fun JobListScreen(
    state: AppState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier,
) {
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = listOf(
            TabItem(full = true) { NightPlanBar(state, ctrl) },
            TabItem(full = true) { JobList(state, ctrl) },
        ),
    )
}

@Composable
private fun JobList(state: AppState, ctrl: SessionController) {
    val unmanaged = state.unmanagedWireJobs
    if (state.jobs.isEmpty() && unmanaged.isEmpty()) {
        EmptyJobListCard()
        return
    }
    Column(Modifier.fillMaxWidth()) {
        state.jobs.forEach { job ->
            JobCard(
                state = state,
                job = job,
                onOpen = { ctrl.openJob(job.id) },
                onRemove = { ctrl.removeJob(job.id) },
            )
            Spacer(Modifier.height(11.2.dp))
        }
        // Real jobs Ekos's Scheduler holds that no local, pushed job claims — added directly in
        // KStars, or left over from before this app ever touched them. The app never hides
        // these: it reads Ekos's real state and shows it as-is (2026-08-23 redesign).
        if (unmanaged.isNotEmpty()) {
            TextC("ON EKOS, NOT MANAGED HERE", style = NocturneTheme.type.Caption, color = NocturneTheme.colors.textFaint)
            Spacer(Modifier.height(6.dp))
            unmanaged.forEach { wireJob ->
                UnmanagedJobCard(state = state, wireJob = wireJob, onRemove = { ctrl.removeUnmanagedJob(wireJob.name) })
                Spacer(Modifier.height(11.2.dp))
            }
        }
    }
}

@Composable
private fun EmptyJobListCard() {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Card {
        TextC(
            "No targets queued — add one from the Plan tab.",
            style = t.Body13, color = c.textMuted,
        )
    }
}

@Composable
private fun JobCard(state: AppState, job: SequenceJob, onOpen: () -> Unit, onRemove: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val target = state.findTarget(job.targetId)
    val doneTotal = job.blocks.sumOf { it.doneCount }
    val subTotal = job.blocks.sumOf { it.subCount }
    val real = state.wireJobFor(job)
    val (chipText, chipColor) = when {
        !job.synced -> "not pushed" to c.textMuted
        real == null -> "pushed — awaiting Ekos" to c.textMuted
        real.state == SchedulerJobStatus.BUSY -> real.jobStatusLabel to c.accent400
        real.state == SchedulerJobStatus.ERROR || real.state == SchedulerJobStatus.ABORTED -> real.jobStatusLabel to c.danger
        else -> real.jobStatusLabel to c.textMuted
    }
    val chipBg = if (real?.state == SchedulerJobStatus.BUSY) c.accent.copy(alpha = 0.16f) else c.divider.copy(alpha = 0.4f)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextC(target?.displayName ?: job.targetId, style = t.Body135, color = c.text)
                TextC(
                    "${job.blocks.size} ${if (job.blocks.size == 1) "block" else "blocks"} · ${job.blocks.joinToString(", ") { it.filter }}",
                    style = t.MonoMicro, color = c.textFaint,
                )
            }
            Box(
                Modifier
                    .background(chipBg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                TextC(chipText, style = t.MonoMicro, color = chipColor)
            }
            Spacer(Modifier.width(8.dp))
            IconBtn(icon = Phosphor.X, onClick = onRemove, size = 40, iconSize = 20.dp, tint = c.danger)
            Spacer(Modifier.width(8.dp))
            IconBtn(icon = Phosphor.CaretRight, onClick = onOpen, size = 40, iconSize = 20.dp)
        }
        Spacer(Modifier.height(6.dp))
        TextC("$doneTotal / $subTotal subs done", style = t.MonoMicro, color = c.textFaint)
    }
}

/**
 * A real Scheduler job with no local counterpart — see [AppState.unmanagedWireJobs]. No
 * drill-down: there's no local block data for a job this app never wrote the `.esq` for. Same
 * check-status-first, no-confirm-dialog remove shape as `PushOrRemoveRow` — see its own doc.
 */
@Composable
private fun UnmanagedJobCard(state: AppState, wireJob: WireSchedulerJob, onRemove: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextC(wireJob.name, style = t.Body135, color = c.text)
                TextC("${wireJob.completedCount}/${wireJob.sequenceCount} subs", style = t.MonoMicro, color = c.textFaint)
            }
            Box(
                Modifier
                    .background(c.divider.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                TextC(wireJob.jobStatusLabel, style = t.MonoMicro, color = c.textMuted)
            }
            Spacer(Modifier.width(8.dp))
            IconBtn(icon = Phosphor.X, onClick = onRemove, size = 40, iconSize = 20.dp, tint = c.danger)
        }
        if (state.jobRemoveRefused == wireJob.name) {
            Spacer(Modifier.height(6.dp))
            TextC(
                "Can't remove while the Scheduler is running — stop it first (see the button above the queue).",
                style = t.MonoMicro, color = c.warn,
            )
        }
    }
}

@Composable
private fun JobDetailScreen(
    state: AppState,
    ctrl: SessionController,
    job: SequenceJob,
    landscape: Boolean,
    modifier: Modifier,
    onFixInGear: () -> Unit,
) {
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = listOf(
            TabItem(full = true) { JobDetailHeader(state, job, onBack = ctrl::closeJob) },
            TabItem(full = true) { BlocksList(state, ctrl, job) },
            TabItem(full = true) {
                if (job.synced) {
                    SyncedToEkosBanner()
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .border(1.dp, Color(0xFFE9E9ED).copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                            .clickable { ctrl.addBlock(job.id) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Phosphor.Icon(Phosphor.Plus, size = 15.dp, tint = NocturneTheme.colors.textMuted)
                            Spacer(Modifier.width(8.dp))
                            TextC("Add block", style = NocturneTheme.type.Button12, color = NocturneTheme.colors.textMuted)
                        }
                    }
                }
            },
            TabItem(full = true) { PushOrRemoveRow(state, ctrl, job) },
            TabItem(full = true) {
                if (!state.ready) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clickable(onClick = onFixInGear)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Phosphor.Icon(Phosphor.Plugs, size = 15.dp, tint = NocturneTheme.colors.warn)
                            Spacer(Modifier.width(7.dp))
                            TextC("Fix in Gear — a session needs mount + camera", style = NocturneTheme.type.Button12, color = NocturneTheme.colors.warn)
                        }
                    }
                }
            },
        ),
    )
}

@Composable
private fun JobDetailHeader(state: AppState, job: SequenceJob, onBack: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val target = state.findTarget(job.targetId)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Phosphor.Icon(Phosphor.CaretLeft, size = 22.dp, tint = c.accent400)
            Spacer(Modifier.width(6.dp))
            TextC("Back to list", style = t.Body13, color = c.accent400)
        }
        Spacer(Modifier.height(4.dp))
        TextC(target?.displayName ?: job.targetId, style = t.CardTitle, color = c.text)
    }
}

/**
 * Same always-visible warning-banner style as ConnectScreen's "No authentication" notice
 * (2026-08-23, user feedback: the old plain gray caption text here was too easy to miss).
 * Factored out (2026-08-25) so [NightPlanBar] can show the same reminder at the top of the
 * Sequence tab, not just once a job's own detail screen is opened — a job stays locked the
 * moment it's pushed, so the warning shouldn't need drilling in to see.
 */
@Composable
private fun SyncedToEkosBanner() {
    val c = NocturneTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.warn.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .border(1.dp, c.warn.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.Warning, size = 16.dp, tint = c.warn)
        Spacer(Modifier.width(10.dp))
        TextC(
            "Synced to Ekos Scheduler — remove it from Ekos to edit blocks again.",
            style = NocturneTheme.type.Caption, color = c.warn,
        )
    }
}

/**
 * Header shows the real contract-job target name + total real session length (`plannedHM`,
 * 2026-08-25 — replaced the dusk→dawn night window, which said nothing about how long the actual
 * plan runs) and, once any job is pushed, [SyncedToEkosBanner].
 *
 * **Segments/labels wired to real per-block data (2026-08-23, user report)** — the bar used to
 * show a fixed fixture split (`cal`/`Ha`/`flip`/`OIII`/`SII`) regardless of what was actually
 * queued (confirmed live: a real 1-block, 30s×1, `L`-filter job still showed the old `Ha`/`OIII`/
 * `SII` labels, no relation to it at all). Now [state.contractJob]'s own blocks drive both the
 * segment widths (proportional to each block's real `subCount × exposureSec`) and labels (each
 * block's real, possibly-real-wheel-sourced filter name — see `AppState.realFilterNames` — plus
 * its real shot count, added 2026-08-25). `cal` stays a small fixed fixture bookend, and `flip`
 * is dropped entirely rather than guessed a placement — neither has real per-block data to derive
 * from (no calibration-block concept, and flip timing depends on real mount position, not block
 * data); both explicitly deferred to a later pass, per the user's own call, rather than solved or
 * faked here. Falls back to the full original fixture bar if there's no contract job or its
 * blocks carry no real planned time yet (e.g. exposureSec/subCount still zero).
 *
 * **Real bug found live (2026-08-22, user report)**: with zero jobs queued (the "No targets
 * queued" card right below this one), the fixture bar+filter-labels rendered anyway —
 * illustrative fixture content with zero relation to anything real, right above an honest
 * "nothing queued" message, reading as a real plan that doesn't exist. Fixed: the bar+labels are
 * now only shown when there's actually a job to illustrate, same honesty norm as
 * `NightArcCard`/`FlipBanner` omitting fixture elements rather than fabricating them.
 */
@Composable
private fun NightPlanBar(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val contractJob = state.contractJob
    val contractTarget = contractJob?.let { state.findTarget(it.targetId)?.displayName ?: it.targetId }
    val showFixturePlan = state.jobs.isNotEmpty()
    // subCount carried alongside for the segment labels below (2026-08-25, user request: show
    // shot count, not just filter name, per segment).
    val realBlocks = contractJob?.blocks
        ?.map { Triple(it.filter, it.subCount.toLong() * it.exposureSec.toLong(), it.subCount) }
        ?.filter { it.second > 0 }
        ?: emptyList()
    val totalPlannedSec = realBlocks.sumOf { it.second }
    val planSegPalette = listOf(c.accent, c.accentMuted, c.accent800, c.warn.copy(alpha = 0.55f), Color(0xFF5D5294))
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Bumped from t.Caption/c.textFaint (2026-08-23, user feedback: too small and too
            // low-contrast next to the big "Start Scheduler" button right below it) — this is the
            // Sequence tab's own title, not a mere section divider like a sheet's internal
            // "STARTUP CONDITION"-style labels, so it should read with more weight than those.
            TextC("NIGHT PLAN", style = t.Body135, color = c.text, modifier = Modifier.weight(1f))
            // Changed 2026-08-25 (user request, live scheduler testing): total real session
            // length (sum of exposureSec × subCount across the contract job's own blocks — same
            // `plannedHM` the Session tab's "OF 3:20 INTEGRATED" already uses) instead of the
            // dusk→dawn night window, which told you nothing about how long the actual plan runs.
            TextC(
                contractJob?.let { "${it.plannedHM} planned" } ?: "—",
                style = t.Mono13, color = c.textMuted,
            )
        }
        if (contractTarget != null) {
            Spacer(Modifier.height(2.dp))
            TextC(contractTarget, style = t.Mono13, color = c.textFaint)
        }
        Spacer(Modifier.height(9.dp))
        // Reminder surfaced here too (2026-08-25, user request), not just once a synced job's own
        // detail screen is opened — the lock takes effect the moment any job is pushed.
        if (state.jobs.any { it.synced }) {
            SyncedToEkosBanner()
            Spacer(Modifier.height(9.dp))
        }
        // Own full-width button, not an inline icon+label next to the title (2026-08-23, user
        // feedback: still not visible enough even labeled) — same treatment as
        // SchedulerToggleButton right below it, so both real Scheduler entry points read with
        // equal weight instead of one being a small aside next to the header.
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(1.dp, c.divider, RoundedCornerShape(10.dp))
                .clickable { ctrl.openSheet(SheetType.SCHEDULER_SETTINGS) },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Phosphor.Icon(Phosphor.SlidersHorizontal, size = 15.dp, tint = c.textMuted)
                Spacer(Modifier.width(8.dp))
                TextC("Scheduler settings", style = t.Button14, color = c.textMuted)
            }
        }
        Spacer(Modifier.height(9.dp))
        SchedulerToggleButton(state, ctrl)
        if (showFixturePlan) {
            Spacer(Modifier.height(12.6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(39.dp)
                    .background(Color.Transparent, RoundedCornerShape(4.dp)),
            ) {
                if (totalPlannedSec > 0) {
                    PlanSeg(0.10f, Color(0xFF5D5294)) // "cal" — still a fixed fixture bookend, deferred
                    realBlocks.forEachIndexed { i, (_, sec) ->
                        PlanSeg(0.90f * (sec.toFloat() / totalPlannedSec), planSegPalette[i % planSegPalette.size])
                    }
                } else {
                    PlanSeg(0.12f, Color(0xFF5D5294))
                    PlanSeg(0.31f, c.accent)
                    PlanSeg(0.06f, c.warn.copy(alpha = 0.55f))
                    PlanSeg(0.33f, c.accentMuted)
                    PlanSeg(0.18f, c.accent800)
                }
            }
            Spacer(Modifier.height(12.6.dp))
            Row(Modifier.fillMaxWidth()) {
                if (totalPlannedSec > 0) {
                    TextC("cal", style = t.Mono13, color = c.textFaint, modifier = Modifier.weight(1f))
                    // Shot count added alongside the filter name (2026-08-25, user request) — target
                    // name isn't repeated per segment since every block here belongs to the same
                    // contract job (shown once, above the bar); this stays correct if the bar ever
                    // grows to span multiple queued jobs, since each segment's own filter+count is
                    // already per-block, not assumed uniform.
                    realBlocks.forEach { (filter, _, subCount) ->
                        TextC("$filter ×$subCount", style = t.Mono13, color = c.text, modifier = Modifier.weight(1f))
                    }
                } else {
                    TextC("cal", style = t.Mono13, color = c.textFaint, modifier = Modifier.weight(1f))
                    TextC("Ha · now", style = t.Mono13, color = c.text, modifier = Modifier.weight(1f))
                    TextC("flip", style = t.Mono13, color = c.warn, modifier = Modifier.weight(1f))
                    TextC("OIII", style = t.Mono13, color = c.textFaint, modifier = Modifier.weight(1f))
                    TextC("SII", style = t.Mono13, color = c.textFaint, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Global Scheduler start/stop (2026-08-23 push/start/stop split) — not tied to any one job, since
 * real Ekos only has a whole-queue start/stop, not a per-job one. A user report found the earlier
 * bare-icon version of this undiscoverable ("where is the start job button?") — a labeled
 * full-width button, matching this app's other primary-action buttons, replaces it. Label/icon
 * follow `state.schedulerRunning`, which is only ever set from the real `new_scheduler_state`
 * push (see that field's own doc) — no optimistic flip on tap, so the label only flips once the
 * real transition actually lands.
 */
@Composable
private fun SchedulerToggleButton(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val running = state.schedulerRunning
    val color = if (running) c.warn else c.accent
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .border(1.dp, color, RoundedCornerShape(10.dp))
            .clickable(onClick = ctrl::toggleScheduler),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Phosphor.Icon(if (running) Phosphor.Pause else Phosphor.Play, size = 15.dp, tint = color)
            Spacer(Modifier.width(8.dp))
            TextC(if (running) "Stop Scheduler" else "Start Scheduler", style = t.Button14, color = color)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PlanSeg(width: Float, color: Color) {
    Box(
        Modifier
            .weight(width)
            .height(26.dp)
            .background(color),
    )
}

/**
 * Drag-to-reorder list. No lazy-list here (the tab body itself already
 * scrolls), so reordering is done by hand: track the dragged block's id and
 * a running pixel offset, and swap it with a neighbor once the offset passes
 * that neighbor's measured height/2 — same feel as a lazy-list reorder
 * without pulling in a dependency for it.
 */
@Composable
private fun BlocksList(state: AppState, ctrl: SessionController, job: SequenceJob) {
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    val locked = job.synced
    val filterNames = state.realFilterNames ?: FILTER_CYCLE
    Column(Modifier.fillMaxWidth()) {
        job.blocks.forEach { b ->
            val isDragging = b.id == draggingId
            BlockCard(
                block = b,
                open = state.openBlockId == b.id,
                canRemove = job.blocks.size > 1,
                locked = locked,
                onToggle = { ctrl.toggleBlock(job.id, b.id) },
                onRemove = { ctrl.removeBlock(job.id, b.id) },
                ctrl = ctrl,
                jobId = job.id,
                filterNames = filterNames,
                modifier = Modifier
                    .onGloballyPositioned { heights[b.id] = it.size.height }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f },
                dragModifier = if (locked) Modifier else Modifier.pointerInput(b.id) {
                    detectDragGestures(
                        onDragStart = { draggingId = b.id; dragOffset = 0f },
                        onDragEnd = { draggingId = null; dragOffset = 0f },
                        onDragCancel = { draggingId = null; dragOffset = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                        val curIndex = job.blocks.indexOfFirst { it.id == b.id }
                        if (dragOffset > 0) {
                            val belowId = job.blocks.getOrNull(curIndex + 1)?.id
                            val belowH = belowId?.let { heights[it] } ?: return@detectDragGestures
                            if (dragOffset > belowH / 2f) {
                                ctrl.moveBlock(job.id, b.id, curIndex + 1)
                                dragOffset -= belowH
                            }
                        } else if (dragOffset < 0) {
                            val aboveId = job.blocks.getOrNull(curIndex - 1)?.id
                            val aboveH = aboveId?.let { heights[it] } ?: return@detectDragGestures
                            if (-dragOffset > aboveH / 2f) {
                                ctrl.moveBlock(job.id, b.id, curIndex - 1)
                                dragOffset += aboveH
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(11.2.dp))
        }
    }
}

@Composable
private fun BlockCard(
    block: Block,
    open: Boolean,
    canRemove: Boolean,
    locked: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    ctrl: SessionController,
    jobId: String,
    filterNames: List<String>,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val first = block.filter == "Ha"
    Card(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(24.dp)
                    .height(30.dp)
                    .then(dragModifier),
                contentAlignment = Alignment.Center,
            ) {
                Phosphor.Icon(Phosphor.DotsSixVertical, size = 17.dp, tint = if (locked) c.neutral700.copy(alpha = 0.3f) else c.neutral700)
            }
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier
                    .height(30.dp)
                    .defaultMinSize(minWidth = 44.dp)
                    .background(
                        if (first) c.accent.copy(alpha = 0.2f) else c.surfaceRaised,
                        RoundedCornerShape(6.dp),
                    )
                    .border(1.dp, if (first) c.accent.copy(alpha = 0.6f) else c.divider, RoundedCornerShape(6.dp))
                    .then(if (locked) Modifier else Modifier.clickable { ctrl.cycleBlockFilter(jobId, block.id, filterNames) })
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextC(block.filter, style = t.Mono14, color = if (first) c.accent400 else c.text)
            }
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                TextC(block.spec, style = t.Mono14, color = c.text)
                TextC(block.meta, style = t.MonoMicro, color = c.textFaint)
            }
            if (canRemove && !locked) {
                Box(
                    Modifier
                        .width(30.dp)
                        .height(30.dp)
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Phosphor.Icon(Phosphor.X, size = 15.dp, tint = c.textMuted)
                }
            }
            Box(
                Modifier
                    .width(30.dp)
                    .height(30.dp)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Phosphor.Icon(if (open) Phosphor.CaretUp else Phosphor.CaretDown, size = 15.dp, tint = c.textMuted)
            }
        }
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(c.divider.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(block.pct)
                    .height(4.dp)
                    .background(c.accent, RoundedCornerShape(2.dp)),
            )
        }
        if (open) {
            Spacer(Modifier.height(9.dp))
            HDivider()
            Spacer(Modifier.height(9.dp))
            BlockDetails(block, ctrl, jobId, locked)
        }
    }
}

/**
 * `locked` (M3, `job.synced`) — once a job is synced to the real Scheduler
 * there's no live-edit-a-running-job wire primitive, so every mutating
 * control here becomes a no-op and dims; remove the job from Ekos to unlock
 * it again (`EkosRemoteController.removeJob` clears `synced`).
 */
@Composable
private fun BlockDetails(block: Block, ctrl: SessionController, jobId: String, locked: Boolean = false) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(Modifier.then(if (locked) Modifier.alpha(0.45f) else Modifier)) {
    Row(Modifier.fillMaxWidth()) {
        NumberField("EXPOSURE", block.exposureSec, "s", Modifier.weight(1f)) { if (!locked) ctrl.setBlockExposure(jobId, block.id, it) }
        Spacer(Modifier.width(8.4.dp))
        NumberField("SUBS", block.subCount, "×", Modifier.weight(1f)) { if (!locked) ctrl.setBlockSubCount(jobId, block.id, it) }
    }
    Spacer(Modifier.height(8.4.dp))
    Row(Modifier.fillMaxWidth()) {
        NumberField("GAIN", block.gain, "", Modifier.weight(1f)) { if (!locked) ctrl.setBlockGain(jobId, block.id, it) }
        Spacer(Modifier.width(8.4.dp))
        NumberField("OFFSET", block.offset, "", Modifier.weight(1f)) { if (!locked) ctrl.setBlockOffset(jobId, block.id, it) }
    }
    Spacer(Modifier.height(8.4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextC("Binning", style = t.Body135, color = c.text, modifier = Modifier.weight(1f))
        SegmentedRow(
            options = BINNING_OPTIONS,
            selected = block.binning,
            labelOf = { "${it}×$it" },
            onSelect = { if (!locked) ctrl.setBlockBinning(jobId, block.id, it) },
        )
    }
    Spacer(Modifier.height(8.4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextC("Dither every", style = t.Body135, color = c.text, modifier = Modifier.weight(1f))
        SegmentedRow(
            options = DITHER_OPTIONS,
            selected = block.ditherEvery,
            labelOf = { it?.toString() ?: "Off" },
            onSelect = { if (!locked) ctrl.setBlockDither(jobId, block.id, it) },
        )
    }
    }
}

/**
 * Small boxed digits-only field — label above, value + suffix inline.
 *
 * Local `text` state, not a `value`-derived one — same clear-and-retype fix as `Sheets.kt`'s
 * `DegreeField`/`IntField`/`MmField`. The old version rendered `value.toString()` directly and
 * fell back to `0` on an unparseable (e.g. empty) string, so clearing the field to type a fresh
 * number instantly forced it to display "0" first, fighting the retype. Local text tracks
 * whatever's actually typed (including empty, mid-edit) independently of [value]; [onChange]
 * only fires once a full number parses.
 */
@Composable
private fun NumberField(label: String, value: Int, suffix: String, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember { mutableStateOf(value.toString()) }
    Column(modifier) {
        TextC(label, style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(3.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }.take(4)
                    text = filtered
                    filtered.toIntOrNull()?.let(onChange)
                },
                singleLine = true,
                textStyle = t.Mono14.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f),
            )
            if (suffix.isNotEmpty()) TextC(suffix, style = t.MonoSmall, color = c.neutral500)
        }
    }
}

/** Shared segmented-button row for binning/dither pickers. */
@Composable
private fun <T> SegmentedRow(options: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(Modifier.border(1.dp, c.divider, RoundedCornerShape(10.dp))) {
        options.forEach { opt ->
            val sel = opt == selected
            Box(
                Modifier
                    .background(if (sel) c.accent.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                TextC(labelOf(opt), style = t.MonoSmall, color = if (sel) c.accent400 else c.textMuted)
            }
        }
    }
}

/**
 * Replaces the old combined `StartButton` (2026-08-23 push/start/stop split) — that button used
 * to say "Pause after this sub" while running, but the real stop path had no distinct pause
 * primitive at all: it stopped the real Scheduler *and removed the job entirely*, and separately
 * conflated "push to Ekos" with "start the whole Scheduler" in one tap (a raw toggle, not
 * idempotent — starting a second job while a first was already running silently stopped it,
 * confirmed live). Now split into the two real, independent actions: **Push to Ekos**
 * (non-destructive, plain tap, [SessionController.pushJob]) while not yet synced, and **Remove
 * from Ekos** once it is — starting/stopping the Scheduler itself is a separate global control
 * (see `NightPlanBar`'s `SchedulerToggleButton`).
 *
 * **No confirm dialog on remove (2026-08-23, user feedback)** — first cut gated every remove tap
 * behind an "are you sure?" dialog regardless of whether removal was actually risky. Real Ekos
 * itself already refuses to remove an active job; [SessionController.removeJob] now checks that
 * client-side first and refuses instantly with [AppState.jobRemoveRefused] if so — a plain tap
 * removes immediately when it's actually safe, no dialog either way.
 */
@Composable
private fun PushOrRemoveRow(state: AppState, ctrl: SessionController, job: SequenceJob) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val ready = state.ready
    if (!ready) {
        TextC("Blocked — connect ${state.missing}", style = t.Button14, color = c.neutral700)
        return
    }
    val real = state.wireJobFor(job)
    val targetName = state.targetNameFor(job)
    if (!job.synced) {
        val refused = state.jobPushRefused == targetName
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .border(1.dp, c.accent, RoundedCornerShape(10.dp))
                    .clickable { ctrl.pushJob(job.id) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Phosphor.Icon(Phosphor.Plus, size = 17.dp, tint = c.accent)
                    Spacer(Modifier.width(8.dp))
                    TextC("Push to Ekos", style = t.Button14, color = c.accent)
                }
            }
            if (refused) {
                Spacer(Modifier.height(6.dp))
                TextC(
                    "Can't push — a job named \"${state.jobPushRefused}\" is already on Ekos's Scheduler.",
                    style = t.MonoMicro, color = c.warn,
                )
            }
        }
        return
    }
    val refused = state.jobRemoveRefused == targetName
    Column {
        if (real != null) {
            TextC(real.jobStatusLabel, style = t.MonoMicro, color = c.textFaint)
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .border(1.dp, c.danger, RoundedCornerShape(10.dp))
                .clickable { ctrl.removeJob(job.id) },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Phosphor.Icon(Phosphor.X, size = 17.dp, tint = c.danger)
                Spacer(Modifier.width(8.dp))
                TextC("Remove from Ekos", style = t.Button14, color = c.danger)
            }
        }
        if (refused) {
            Spacer(Modifier.height(6.dp))
            TextC(
                "Can't remove while the Scheduler is running — stop it first.",
                style = t.MonoMicro, color = c.warn,
            )
        }
    }
}
