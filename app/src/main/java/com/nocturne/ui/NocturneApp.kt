package com.nocturne.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nocturne.session.FlipConfirm
import com.nocturne.session.RigRebootState
import com.nocturne.session.SheetType
import com.nocturne.session.AppState
import com.nocturne.session.contractJob
import com.nocturne.session.findTarget
import com.nocturne.transport.ConnectionState
import com.nocturne.transport.ConnectionStatus
import com.nocturne.ui.connect.ConnectScreen
import com.nocturne.ui.frames.FramesScreen
import com.nocturne.ui.home.HomeScreen
import com.nocturne.ui.controls.ControlsScreen
import com.nocturne.ui.gear.GearScreen
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.nav.NocturneTab
import com.nocturne.ui.plan.PlanScreen
import com.nocturne.ui.sequence.SequenceScreen
import com.nocturne.ui.session.ConnectionMode
import com.nocturne.ui.session.SessionViewModel
import com.nocturne.ui.session.SheetHost
import com.nocturne.ui.sequence.SubPreviewOverlay
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
                onCancel = vm::disconnect,
                notice = vm.connectNotice,
                power = vm.rigPower,
                onPower = vm::powerRig,
            )
            // SOCKET_OPEN: the socket is up but Ekos isn't running. Show the shell with a banner
            // so Gear's Start Ekos (profile_start) is reachable; only the dial phase keeps
            // ConnectScreen.
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
                    onCancel = vm::disconnect,
                    power = vm.rigPower,
                    onPower = vm::powerRig,
                )
            }
            is ConnectionMode.Connected -> NocturneShell(
                vm = vm,
                redMode = redMode,
                onToggleRed = { redMode = !redMode },
                // Once ONLINE, a later drop stays on the shell with a banner. SOCKET_OPEN means
                // only Ekos stopped (e.g. a Scheduler shutdown), not the socket, so only a
                // socket-level drop says "Reconnecting".
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
    val currentTab = NocturneTab.entries.firstOrNull { it.route == backStackEntry?.destination?.route } ?: NocturneTab.Home

    val state by vm.ctrl.state.collectAsState()
    val connectionMode by vm.connectionMode.collectAsState()
    val connectionStatus = when (val m = connectionMode) {
        is ConnectionMode.Connected -> m.status
        is ConnectionMode.Connecting -> m.status
        else -> null
    }
    val ctrl = vm.ctrl

    // Reboot or shutdown accepted by the Pi — leave the shell entirely (closes every sheet/dialog with it).
    LaunchedEffect(state.rigRebootState) {
        if (state.rigRebootState == RigRebootState.SENT) vm.disconnectAfterReboot()
    }
    LaunchedEffect(state.rigShutdownState) {
        if (state.rigShutdownState == RigRebootState.SENT) vm.disconnectAfterShutdown()
    }

    // Phone notifications: the foreground service keeps the connection alive and speaks up while
    // the phone is locked. It runs only while this shell is up (connected) and the user wants it.
    val context = androidx.compose.ui.platform.LocalContext.current
    var notificationsAllowed by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < 33 ||
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val askForNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { notificationsAllowed = it }
    LaunchedEffect(state.pushNotificationsEnabled) {
        if (state.pushNotificationsEnabled && !notificationsAllowed) askForNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    val monitoring = state.pushNotificationsEnabled && notificationsAllowed
    androidx.compose.runtime.DisposableEffect(monitoring) {
        val service = android.content.Intent(context, com.nocturne.monitor.RigMonitorService::class.java)
        if (monitoring) {
            com.nocturne.monitor.MonitorBridge.source = vm.monitorSource()
            androidx.core.content.ContextCompat.startForegroundService(context, service)
        }
        onDispose {
            if (monitoring) {
                com.nocturne.monitor.MonitorBridge.source = null
                context.stopService(service)
            }
        }
    }

    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun navigate(tab: NocturneTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val health = vm.rigHealth?.let { com.nocturne.ui.home.RigHealthSource(connectionStatus, vm.link, it) }
    androidx.compose.runtime.CompositionLocalProvider(com.nocturne.ui.home.LocalRigHealth provides health) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bg),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    // System-bar insets applied once for the whole stack (banner, header, rail, nav
                    // bar); the nav bar is at the bottom in portrait, at a side in landscape.
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // Banner and header are compact in landscape so all 6 tabs fit the vertical rail.
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
                // Shown even with a banner: last-known module status is still useful context.
                ModuleStatusRow(state, landscape, onOpen = ctrl::openSheet)
                // The header lives inside this Row, beside the rail, so the rail spans the full
                // height up to the status row.
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
                            redMode = redMode,
                            landscape = landscape,
                            onToggleRed = onToggleRed,
                            alertCount = state.wireNotifications.size,
                            onOpenAlerts = { ctrl.openSheet(SheetType.ALERTS) },
                        )

                        NavHost(
                            navController = navController,
                            startDestination = NocturneTab.Home.route,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            composable(NocturneTab.Home.route) {
                                HomeScreen(
                                    state, ctrl, landscape,
                                    onGoToSequence = { navigate(NocturneTab.Sequence) },
                                    onDisconnect = vm::disconnect,
                                    logRepo = vm.logRepo,
                                )
                            }
                            composable(NocturneTab.Plan.route) {
                                PlanScreen(state, ctrl, landscape, onGoToSequence = { navigate(NocturneTab.Sequence) })
                            }
                            composable(NocturneTab.Sequence.route) {
                                SequenceScreen(state, ctrl, landscape, onFixInGear = { navigate(NocturneTab.Gear) })
                            }
                            composable(NocturneTab.Frames.route) { FramesScreen(state, ctrl, landscape) }
                            composable(NocturneTab.Gear.route) { GearScreen(state, ctrl, landscape) }
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

            if (state.logOpen) {
                com.nocturne.ui.home.LogScreen(repo = vm.logRepo, onClose = ctrl::closeLog)
            }

            if (state.expandedFrameId != null) {
                com.nocturne.ui.frames.FrameExpandOverlay(state = state, ctrl = ctrl)
            }

            // Currently unreachable: FlipBanner's FLIP NOW/DEFER are disabled (no wire command), so
            // pendingFlipConfirm is never set. Kept for when a trigger exists.
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
}

// ── Module status row ─────────────────────────────────────────────────────

/**
 * Always-visible per-module status: one badge each for Scheduler, Mount, Camera, Focus, Guide
 * and Align, from state already decoded elsewhere. Coloring is a keyword heuristic (see
 * [statusColorFor]) because Ekos's state vocabularies (`ekos.h`) share enough wording that a
 * per-module table isn't needed. Scrolls horizontally since 6 badges don't always fit.
 */
@Composable
private fun ModuleStatusRow(state: AppState, landscape: Boolean, onOpen: (SheetType) -> Unit) {
    val c = NocturneTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(c.surface)
            .padding(horizontal = NocturneTheme.spacing.s4, vertical = if (landscape) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModuleStatusBadge("Sched", if (state.schedulerRunning) "Running" else "Idle")
        ModuleStatusBadge("Mount", state.wireMountStatus)
        ModuleStatusBadge("Cam", state.wireCaptureStatus)
        // Focus/Guide open their live charts (V-curve, drift) — the badge alone is too little.
        ModuleStatusBadge("Focus", state.wireFocusStatus, onClick = { onOpen(SheetType.FOCUS) })
        ModuleStatusBadge("Guide", state.wireGuideStatus, onClick = { onOpen(SheetType.GUIDE) })
        ModuleStatusBadge("Align", state.wireAlignStatus)
    }
}

/** One module's compact "Label: Status" badge. */
@Composable
private fun ModuleStatusBadge(label: String, status: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = NocturneTheme.colors
    Row(
        modifier
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(end = 14.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(statusColorFor(status, c), androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text("$label ${status ?: "—"}", style = NocturneTheme.type.MonoMicro, color = statusColorFor(status, c))
        if (onClick != null) Text(" ›", style = NocturneTheme.type.MonoMicro, color = c.textFaint)
    }
}

/** Fail/Abort/Error → danger, Idle/Complete/Successful/Connected → neutral, else busy. */
private fun statusColorFor(status: String?, c: com.nocturne.ui.theme.NocturneColorScheme): Color = when {
    status == null -> c.textFaint
    status.contains("fail", true) || status.contains("abort", true) || status.contains("error", true) -> c.danger
    status.equals("idle", true) || status.contains("complete", true) || status.contains("successful", true) ||
        status.contains("connected", true) -> c.textMuted
    else -> c.accent
}

// ── Header ────────────────────────────────────────────────────────────────

@Composable
private fun NocturneHeader(
    tab: NocturneTab,
    redMode: Boolean,
    landscape: Boolean,
    onToggleRed: () -> Unit,
    alertCount: Int,
    onOpenAlerts: () -> Unit,
) {
    val colors = NocturneTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .drawBehind { drawLine(colors.divider, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1.dp.toPx()) }
            // No status-bar inset here; NocturneShell applies it once above the banner.
            // Tighter in landscape so all 6 tabs fit the vertical rail.
            .padding(horizontal = NocturneTheme.spacing.s4, vertical = if (landscape) NocturneTheme.spacing.s1 else NocturneTheme.spacing.s3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tab.label,
                    style = NocturneTheme.type.HeaderTitle,
                    color = colors.text,
                )
            }

            // No orientation toggle: forcing requestedOrientation overrode the phone's own
            // auto-rotate lock (and stuck until toggled back). The app follows the phone.

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
                badge = alertCount.takeIf { it > 0 },
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
    // The badge overhangs the corner, so it can't live inside the clipped button box.
    Box(Modifier.size(34.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(NocturneTheme.radius.sm))
                .border(1.dp, colors.dividerStrong, RoundedCornerShape(NocturneTheme.radius.sm))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = colors.text, modifier = Modifier.size(17.dp))
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 5.dp, y = (-5).dp)
                    .defaultMinSize(minWidth = 16.dp)
                    .height(16.dp)
                    .background(colors.warn, RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp),
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
 * Portrait-only bottom tab strip; landscape uses [NavRail]. No nav-bar inset of its own,
 * [NocturneShell] applies it.
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
 * Landscape's vertical tab rail. Items are short so all 6 tabs fit without clipping.
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
