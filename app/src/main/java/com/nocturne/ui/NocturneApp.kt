package com.nocturne.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nocturne.session.ALERTS
import com.nocturne.session.FlipConfirm
import com.nocturne.session.SheetType
import com.nocturne.session.SimState
import com.nocturne.session.contractJob
import com.nocturne.session.currentBlockIndex
import com.nocturne.session.findTarget
import com.nocturne.transport.ConnectionState
import com.nocturne.transport.ConnectionStatus
import com.nocturne.ui.connect.ConnectScreen
import com.nocturne.ui.frames.FramesScreen
import com.nocturne.ui.controls.ControlsScreen
import com.nocturne.ui.gear.GearScreen
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.nav.NocturneTab
import com.nocturne.ui.plan.PlanScreen
import com.nocturne.ui.sequence.SequenceScreen
import com.nocturne.ui.session.ConnectionMode
import com.nocturne.ui.session.SessionScreen
import com.nocturne.ui.session.SessionViewModel
import com.nocturne.ui.session.SheetHost
import com.nocturne.ui.session.SubPreviewOverlay
import com.nocturne.ui.theme.NocturneTheme

/** App root. Red mode is hoisted above the theme so toggling re-themes everything. */
@Composable
fun NocturneApp() {
    var redMode by rememberSaveable { mutableStateOf(false) }
    NocturneTheme(redMode = redMode) {
        val vm: SessionViewModel = viewModel()
        val connectionMode by vm.connectionMode.collectAsState()

        when (val mode = connectionMode) {
            is ConnectionMode.NeedsConnect -> ConnectScreen(
                status = ConnectionStatus(ConnectionState.DISCONNECTED, vm.savedHost),
                savedHost = vm.savedHost,
                savedPort = vm.savedPort,
                onConnect = vm::connect,
            )
            // SOCKET_OPEN means the WebSocket handshake succeeded and get_profiles/get_devices
            // are already flowing — there's a real, useful app to show. Only the pre-socket
            // dial phase (CONNECTING) still needs ConnectScreen's own status text; waiting on
            // Ekos itself to start is the shell's problem to show a banner for, not a reason to
            // trap the user behind the connect form — Gear tab's Start Ekos is how you'd ever
            // get past this in the first place (profile_start), so it must be reachable now.
            is ConnectionMode.Connecting -> if (mode.status.state == ConnectionState.SOCKET_OPEN) {
                NocturneShell(
                    vm = vm,
                    redMode = redMode,
                    onToggleRed = { redMode = !redMode },
                    banner = "Connected — waiting for Ekos to start…",
                )
            } else {
                ConnectScreen(
                    status = mode.status,
                    savedHost = vm.savedHost,
                    savedPort = vm.savedPort,
                    onConnect = vm::connect,
                )
            }
            is ConnectionMode.Connected -> NocturneShell(
                vm = vm,
                redMode = redMode,
                onToggleRed = { redMode = !redMode },
                // Session tab reached ONLINE at least once — a subsequent drop stays on this
                // shell with a banner, never a forced ConnectScreen.
                //
                // Real bug found live (2026-08-23, user report): this used to say
                // "Reconnecting to rig…" for *any* non-ONLINE state, including SOCKET_OPEN —
                // but SOCKET_OPEN means the WebSocket itself is fine, only Ekos stopped (the
                // real `online` field of new_connection_state tracks Ekos's own run state, not
                // the socket — see ConnectionState's own doc). Confirmed live: after a real
                // Scheduler-driven auto-shutdown (mount parked, Ekos stopped, socket never
                // dropped), the banner claimed "Reconnecting" for 20+ minutes while the app was
                // rendering perfectly live data the whole time — actively misleading, reads as
                // a network problem when there wasn't one. Now matches the identical, already-
                // correct SOCKET_OPEN message the pre-ONLINE Connecting branch above uses — an
                // actual socket-level drop (CONNECTING/DISCONNECTED) is the only case that still
                // says "Reconnecting".
                banner = when (mode.status.state) {
                    ConnectionState.ONLINE -> null
                    ConnectionState.SOCKET_OPEN -> "Connected — Ekos isn't running"
                    else -> "Reconnecting to rig…"
                },
            )
        }
    }
}

@Composable
private fun NocturneShell(
    vm: SessionViewModel,
    redMode: Boolean,
    onToggleRed: () -> Unit,
    /** Non-null shows a dismiss-free warning strip above the header — null hides it entirely. */
    banner: String?,
) {
    val colors = NocturneTheme.colors
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = NocturneTab.entries.firstOrNull { it.route == backStackEntry?.destination?.route } ?: NocturneTab.Session

    val state by vm.ctrl.state.collectAsState()
    val ctrl = vm.ctrl

    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current

    fun navigate(tab: NocturneTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // Applied once here, at the very top of the stack — covers the banner (which
                // used to render with no status-bar padding of its own, overlapping the status
                // bar icons, confirmed live) and the header (which used to apply its OWN
                // statusBarsPadding *again* below the banner, creating a real, visible gap
                // between "connection status" and "session status" rows — also confirmed live,
                // per the user's own report). navigationBarsPadding here insets whichever side is
                // actually the system nav bar (bottom in portrait, left/right in landscape) for
                // every row in this Column uniformly, replacing the narrower fixes that used to
                // live on the content Row / BottomNavBar individually.
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Landscape's "connection status" (banner) and "session status" (header) rows are
            // squeezed as compact as they'll go — landscape keeps a *vertical* tab rail (user's
            // explicit call, a horizontal bar was tried and rejected), which needs every bit of
            // vertical room it can get for all 6 tabs to fit without clipping.
            if (banner != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.warn.copy(alpha = 0.14f))
                        .padding(horizontal = NocturneTheme.spacing.s4, vertical = if (landscape) 3.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Phosphor.Icon(Phosphor.Warning, size = 13.dp, tint = colors.warn)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        banner,
                        style = NocturneTheme.type.Caption,
                        color = colors.warn,
                    )
                }
            }
            // NocturneHeader used to sit here, above this whole Row — meaning the vertical
            // NavRail's own top started below it, leaving a visible gap between the connection
            // status banner and the rail (confirmed live, user-reported: "the session message
            // bar should be shrunk so vertical bar should reach to the connection status bar").
            // Moved inside the Row instead, alongside NavRail rather than above it, so the rail
            // spans this Row's *full* height — flush with the banner above, independent of the
            // header's own height — while the header still only affects the content pane's width.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (landscape) {
                    NavRail(
                        selected = currentTab,
                        onSelect = ::navigate,
                    )
                }

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    NocturneHeader(
                        tab = currentTab,
                        state = state,
                        redMode = redMode,
                        landscape = landscape,
                        onToggleRed = onToggleRed,
                        onToggleOrientation = {
                            val activity = context as Activity
                            activity.requestedOrientation =
                                if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        },
                        alertCount = ALERTS.size,
                        onOpenAlerts = { ctrl.openSheet(SheetType.ALERTS) },
                    )

                    NavHost(
                        navController = navController,
                        startDestination = NocturneTab.Session.route,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        composable(NocturneTab.Session.route) { SessionScreen(state, ctrl, landscape) }
                        composable(NocturneTab.Plan.route) {
                            PlanScreen(state, ctrl, landscape, onGoToSequence = { navigate(NocturneTab.Sequence) })
                        }
                        composable(NocturneTab.Sequence.route) {
                            SequenceScreen(state, ctrl, landscape, onFixInGear = { navigate(NocturneTab.Gear) })
                        }
                        composable(NocturneTab.Frames.route) { FramesScreen(state, ctrl, landscape) }
                        composable(NocturneTab.Gear.route) { GearScreen(state, ctrl, landscape, onDisconnect = vm::disconnect) }
                        composable(NocturneTab.Controls.route) { ControlsScreen(state, ctrl, landscape) }
                    }
                }
            }

            if (!landscape) {
                TabBar(selected = currentTab, onSelect = ::navigate)
            }
        }

        SheetHost(state = state, ctrl = ctrl, landscape = landscape)

        if (state.subPreviewExpanded) {
            SubPreviewOverlay(state = state, onDismiss = ctrl::closeSubPreview)
        }

        if (state.expandedFrameId != null) {
            com.nocturne.ui.frames.FrameExpandOverlay(state = state, ctrl = ctrl)
        }

        // Unreachable in practice — FlipBanner's FLIP NOW/DEFER are always disabled (no wire
        // command exists for either, see FlipBanner's own doc), so pendingFlipConfirm can never
        // be set. Left in place rather than torn out, in case a real trigger is ever wired.
        state.pendingFlipConfirm?.let { pending ->
            val isNow = pending == FlipConfirm.NOW
            com.nocturne.ui.components.ConfirmDialog(
                title = if (isNow) "Flip now?" else "Defer flip by 10 min?",
                message = if (isNow) {
                    "Triggers the meridian flip immediately."
                } else {
                    "Pushes the flip deadline back 10 minutes."
                },
                confirmText = if (isNow) "Flip now" else "Defer",
                confirmStyle = if (isNow) com.nocturne.ui.components.BtnStyle.DANGER else com.nocturne.ui.components.BtnStyle.SOLID,
                onConfirm = ctrl::confirmFlipAction,
                onDismiss = ctrl::cancelFlipConfirm,
            )
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────

@Composable
private fun NocturneHeader(
    tab: NocturneTab,
    state: SimState,
    redMode: Boolean,
    landscape: Boolean,
    onToggleRed: () -> Unit,
    onToggleOrientation: () -> Unit,
    alertCount: Int,
    onOpenAlerts: () -> Unit,
) {
    val colors = NocturneTheme.colors
    val contractJob = state.contractJob
    val contractTarget = contractJob?.let { j -> state.findTarget(j.targetId) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            // statusBarsPadding used to live here, but that meant it applied a *second* time
            // whenever a banner rendered above this header (which had none of its own) — the
            // banner overlapped the status bar icons, and the header then left an unwanted gap
            // below the banner from its own redundant top inset. Now applied once, in
            // NocturneShell's own outer Column, ahead of both the banner and this header.
            // Vertical padding shrinks in landscape — the vertical tab rail needs every bit of
            // height it can get for all 6 tabs to fit without clipping (user's explicit call to
            // keep the rail vertical rather than switch to a horizontal bar).
            .padding(horizontal = NocturneTheme.spacing.s4, vertical = if (landscape) NocturneTheme.spacing.s1 else NocturneTheme.spacing.s3)
            .border(
                width = 1.dp,
                color = colors.divider,
                shape = RoundedCornerShape(bottomStart = NocturneTheme.radius.sm, bottomEnd = NocturneTheme.radius.sm),
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (tab == NocturneTab.Session && contractJob != null) {
                    val statusColor = if (contractJob.running) colors.ok else colors.warn
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(statusColor, RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(NocturneTheme.spacing.s2))
                        Text(
                            if (contractJob.running) "Imaging" else "Paused",
                            style = NocturneTheme.type.StatusLabel, color = statusColor,
                        )
                        val blockIndex = contractJob.currentBlockIndex
                        if (blockIndex != null) {
                            Spacer(Modifier.width(NocturneTheme.spacing.s2))
                            Text(
                                "· blk ${blockIndex + 1}/${contractJob.blocks.size}",
                                style = NocturneTheme.type.TelemetryTiny,
                                color = colors.neutral600,
                            )
                        }
                    }
                    Spacer(Modifier.height(1.dp))
                }
                Text(
                    if (tab == NocturneTab.Session) {
                        contractTarget?.let { if (it.custom) it.common else it.id } ?: "No target queued"
                    } else tab.label,
                    style = NocturneTheme.type.HeaderTitle,
                    color = colors.text,
                )
            }

            HeaderIconButton(
                icon = if (landscape) Phosphor.ArrowsInLineHorizontal else Phosphor.DeviceRotate,
                contentDescription = if (landscape) "Portrait" else "Landscape",
                onClick = onToggleOrientation,
            )
            Spacer(Modifier.width(NocturneTheme.spacing.s2))

            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(NocturneTheme.radius.sm))
                    .border(1.dp, colors.accent, RoundedCornerShape(NocturneTheme.radius.sm))
                    .clickable(onClick = onToggleRed)
                    .padding(horizontal = NocturneTheme.spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Phosphor.MoonStars,
                    contentDescription = "Toggle red mode",
                    tint = colors.accent,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (redMode) "RED ON" else "RED",
                    color = colors.accent,
                    style = NocturneTheme.type.ButtonSmall,
                )
            }
            Spacer(Modifier.width(NocturneTheme.spacing.s2))

            HeaderIconButton(
                icon = Phosphor.Bell,
                contentDescription = "Alerts",
                badge = alertCount,
                onClick = onOpenAlerts,
            )
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    badge: Int? = null,
) {
    val colors = NocturneTheme.colors
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(NocturneTheme.radius.sm))
            .border(1.dp, colors.dividerStrong, RoundedCornerShape(NocturneTheme.radius.sm))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = colors.text, modifier = Modifier.size(17.dp))
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 5.dp, y = (-5).dp)
                    .background(colors.warn, RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp)
                    .height(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$badge",
                    color = colors.bg,
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                    ),
                )
            }
        }
    }
}

// ── Navigation bar / rail ─────────────────────────────────────────────────

@Composable
private fun NavItem(
    tab: NocturneTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = NocturneTheme.colors
    val tint = if (selected) colors.accent else colors.neutral600
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NocturneTheme.radius.sm))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(3.dp))
        Text(tab.label, style = NocturneTheme.type.NavLabel, color = tint, textAlign = TextAlign.Center)
    }
}

/**
 * Portrait-only horizontal tab strip, bottom-anchored. (Landscape briefly reused this at the top
 * of the screen instead of a vertical rail — rejected, per the user's explicit call: landscape
 * keeps a vertical rail, full stop.) No navigationBarsPadding of its own: the shared outer Column
 * in [NocturneShell] already insets from the system nav bar once, for every row alike.
 */
@Composable
private fun TabBar(
    selected: NocturneTab,
    onSelect: (NocturneTab) -> Unit,
) {
    val colors = NocturneTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .border(1.dp, colors.divider, RoundedCornerShape(topStart = NocturneTheme.radius.sm, topEnd = NocturneTheme.radius.sm))
            .padding(vertical = NocturneTheme.spacing.s1),
    ) {
        NocturneTab.entries.forEach { tab ->
            NavItem(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f).height(52.dp),
            )
        }
    }
}

/**
 * Landscape's vertical tab rail — user's explicit call over a horizontal bar (tried, rejected).
 * Item height trimmed from the original 56.dp to help all 6 tabs fit without clipping, alongside
 * the header/banner above it also being squeezed as compact as they'll go in landscape — see
 * NocturneShell's own landscape-specific padding.
 */
@Composable
private fun NavRail(
    selected: NocturneTab,
    onSelect: (NocturneTab) -> Unit,
) {
    val colors = NocturneTheme.colors
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(64.dp)
            .background(colors.surface)
            .border(1.dp, colors.divider, RoundedCornerShape(topEnd = NocturneTheme.radius.sm, bottomEnd = NocturneTheme.radius.sm))
            .padding(vertical = NocturneTheme.spacing.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NocturneTab.entries.forEach { tab ->
            NavItem(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.width(64.dp).height(48.dp),
            )
        }
    }
}
