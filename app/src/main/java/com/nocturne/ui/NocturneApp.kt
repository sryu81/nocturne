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
import com.nocturne.session.SheetType
import com.nocturne.ui.frames.FramesScreen
import com.nocturne.ui.gear.GearScreen
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.nav.NocturneTab
import com.nocturne.ui.plan.PlanScreen
import com.nocturne.ui.sequence.SequenceScreen
import com.nocturne.ui.session.SessionScreen
import com.nocturne.ui.session.SessionViewModel
import com.nocturne.ui.session.SheetHost
import com.nocturne.ui.theme.NocturneTheme

/** App root. Red mode is hoisted above the theme so toggling re-themes everything. */
@Composable
fun NocturneApp() {
    var redMode by rememberSaveable { mutableStateOf(false) }
    NocturneTheme(redMode = redMode) {
        NocturneShell(
            redMode = redMode,
            onToggleRed = { redMode = !redMode },
        )
    }
}

@Composable
private fun NocturneShell(
    redMode: Boolean,
    onToggleRed: () -> Unit,
) {
    val colors = NocturneTheme.colors
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = NocturneTab.entries.firstOrNull { it.route == backStackEntry?.destination?.route } ?: NocturneTab.Session

    val vm: SessionViewModel = viewModel()
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
        Column(Modifier.fillMaxSize()) {
            NocturneHeader(
                tab = currentTab,
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

                NavHost(
                    navController = navController,
                    startDestination = NocturneTab.Session.route,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    composable(NocturneTab.Session.route) { SessionScreen(state, ctrl, landscape) }
                    composable(NocturneTab.Plan.route) { PlanScreen(state, ctrl, landscape) }
                    composable(NocturneTab.Sequence.route) {
                        SequenceScreen(state, ctrl, landscape, onFixInGear = { navigate(NocturneTab.Gear) })
                    }
                    composable(NocturneTab.Frames.route) { FramesScreen(state, ctrl, landscape) }
                    composable(NocturneTab.Gear.route) { GearScreen(state, ctrl, landscape) }
                }
            }

            if (!landscape) {
                BottomNavBar(
                    selected = currentTab,
                    onSelect = ::navigate,
                )
            }
        }

        SheetHost(state = state, ctrl = ctrl, landscape = landscape)
    }
}

// ── Header ────────────────────────────────────────────────────────────────

@Composable
private fun NocturneHeader(
    tab: NocturneTab,
    redMode: Boolean,
    landscape: Boolean,
    onToggleRed: () -> Unit,
    onToggleOrientation: () -> Unit,
    alertCount: Int,
    onOpenAlerts: () -> Unit,
) {
    val colors = NocturneTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .statusBarsPadding()
            .padding(horizontal = NocturneTheme.spacing.s4, vertical = NocturneTheme.spacing.s3)
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
                if (tab == NocturneTab.Session) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(colors.ok, RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(NocturneTheme.spacing.s2))
                        Text("Imaging", style = NocturneTheme.type.StatusLabel, color = colors.ok)
                        Spacer(Modifier.width(NocturneTheme.spacing.s2))
                        Text(
                            "· blk 2/5",
                            style = NocturneTheme.type.TelemetryTiny,
                            color = colors.neutral600,
                        )
                    }
                    Spacer(Modifier.height(1.dp))
                }
                Text(
                    if (tab == NocturneTab.Session) "NGC 7000" else tab.label,
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

@Composable
private fun BottomNavBar(
    selected: NocturneTab,
    onSelect: (NocturneTab) -> Unit,
) {
    val colors = NocturneTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .border(1.dp, colors.divider, RoundedCornerShape(topStart = NocturneTheme.radius.sm, topEnd = NocturneTheme.radius.sm))
            .navigationBarsPadding()
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
            .padding(vertical = NocturneTheme.spacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NocturneTab.entries.forEach { tab ->
            NavItem(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.width(64.dp).height(56.dp),
            )
        }
    }
}
