package com.nocturne.ui.frames

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nocturne.protocol.WireDrive
import com.nocturne.protocol.WireFileEntry
import com.nocturne.session.AppState
import com.nocturne.session.RigStorageState
import com.nocturne.session.RigStorageStatus
import com.nocturne.session.SessionController
import com.nocturne.session.crumbs
import com.nocturne.session.formatByteSize
import com.nocturne.session.sortedEntries
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturnePalette
import com.nocturne.ui.theme.NocturneTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ENTRY_DATE_FMT = SimpleDateFormat("MMM d, HH:mm", Locale.US)

/**
 * Plan 3, R2: the Pi's drives and their `Nocturne` folders, read-only. Drives, then folders, then
 * files, one level per tap, with a breadcrumb; Back goes up a level and out of the screen from the
 * drives. File rows do nothing yet (the file page is R4, the actions R6).
 */
@Composable
fun RigStorageScreen(state: AppState, ctrl: SessionController, landscape: Boolean, modifier: Modifier = Modifier) {
    val rs = state.rigStorage
    BackHandler(onBack = ctrl::rigStorageUp)

    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = buildList {
            add(TabItem(full = true) { StorageHeader(rs, ctrl) })
            add(
                TabItem(full = true) {
                    when (rs.status) {
                        RigStorageStatus.OFFLINE -> Notice("Needs KStars running", "Connect to the rig, then refresh.")
                        RigStorageStatus.OLD_FORK -> Notice(
                            "Needs the updated KStars",
                            "The KStars on the Pi is older than the file manager. Install the current build, then refresh.",
                        )
                        RigStorageStatus.NO_REPLY -> Notice("No answer from the rig", "The drive may be busy or gone. Refresh to try again.")
                        RigStorageStatus.LOADING -> Notice("Reading…", null)
                        RigStorageStatus.READY -> if (rs.path == null) DriveList(rs.drives, ctrl) else FolderList(rs, ctrl)
                    }
                },
            )
        },
    )
}

/** Back arrow, then `Rig storage › SD › M_31 › 2026-10-30`; each step opens that level. */
@Composable
private fun StorageHeader(rs: RigStorageState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable(onClick = ctrl::rigStorageUp), verticalAlignment = Alignment.CenterVertically) {
            Phosphor.Icon(Phosphor.CaretLeft, size = 18.dp, tint = c.textMuted)
            Spacer(Modifier.width(6.dp))
        }
        // Scrolled to the end whenever the path grows, so the current folder is always in view.
        val scroll = rememberScrollState()
        LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
        Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            val last = rs.crumbs().lastIndex
            TextC(
                "Rig storage", style = t.Body135, color = if (rs.path == null) c.text else c.textMuted,
                modifier = Modifier.clickable(enabled = rs.path != null) { ctrl.openRigStorage() },
            )
            rs.crumbs().forEachIndexed { i, crumb ->
                TextC("  ›  ", style = t.Body13, color = c.textFaint)
                TextC(
                    crumb.label, style = t.Body135, color = if (i == last) c.text else c.textMuted,
                    modifier = Modifier.clickable(enabled = i != last) { crumb.path?.let(ctrl::rigStorageOpenFolder) },
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.clickable(onClick = ctrl::rigStorageRefresh).padding(4.dp)) {
            Phosphor.Icon(Phosphor.ArrowsClockwise, size = 20.dp, tint = c.textMuted)
        }
    }
}

@Composable
private fun Notice(title: String, body: String?) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Card(Modifier.fillMaxWidth()) {
        TextC(title, style = t.Body135, color = c.text)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            TextC(body, style = t.Body13, color = c.textMuted)
        }
    }
}

@Composable
private fun DriveList(drives: List<WireDrive>, ctrl: SessionController) {
    if (drives.isEmpty()) {
        Notice("No drives reported", "The rig answered but listed no storage.")
        return
    }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(11.2.dp)) {
        drives.forEach { DriveCard(it, onOpen = { ctrl.rigStorageOpenFolder(it.path) }) }
    }
}

@Composable
private fun DriveCard(drive: WireDrive, onOpen: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val used = if (drive.totalBytes > 0) (1f - drive.freeBytes.toFloat() / drive.totalBytes).coerceIn(0f, 1f) else 0f
    Card(Modifier.fillMaxWidth().clickable(enabled = drive.hasRoot, onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Phosphor.Icon(Phosphor.HardDrive, size = 22.dp, tint = c.accent400)
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                TextC(if (drive.kind == "sd") "SD card" else drive.label.ifBlank { "USB drive" }, style = t.Body135, color = c.text)
                TextC(
                    listOfNotNull(drive.fs.takeIf { it.isNotBlank() }, "read-only".takeIf { drive.readOnly }).joinToString(" · "),
                    style = t.MonoMicro, color = c.textMuted,
                )
            }
            if (drive.hasRoot) Phosphor.Icon(Phosphor.CaretRight, size = 18.dp, tint = c.textFaint)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceDeep)) {
            Box(Modifier.fillMaxWidth(used).fillMaxHeight().background(c.accent))
        }
        Spacer(Modifier.height(6.dp))
        TextC("${formatByteSize(drive.freeBytes)} free of ${formatByteSize(drive.totalBytes)}", style = t.MonoSmall, color = c.textMuted)
        if (!drive.hasRoot) {
            Spacer(Modifier.height(4.dp))
            TextC("No Nocturne folder on this drive yet", style = t.Body13, color = c.textMuted)
        }
    }
}

@Composable
private fun FolderList(rs: RigStorageState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val listing = rs.listing ?: return Notice("Reading…", null)
    listing.error?.let { code ->
        Notice(
            "Cannot open this folder",
            when (code) {
                "not_allowed" -> "It is outside the Nocturne folder."
                "not_found" -> "It no longer exists. The drive may have been removed."
                "not_dir" -> "It is a file, not a folder."
                else -> code
            },
        )
        return
    }
    if (listing.entries.isEmpty()) {
        Notice("Empty folder", null)
        return
    }
    Column {
        if (listing.capturing) {
            TextC("A capture is running. Its folder is locked.", style = t.MonoSmall, color = NocturnePalette.Warn)
            Spacer(Modifier.height(8.dp))
        }
        Card {
            val entries = sortedEntries(listing.entries)
            entries.forEachIndexed { i, entry ->
                if (i > 0) HDivider()
                EntryRow(
                    entry,
                    withIconColumn = entries.any { it.isDir },
                    onOpen = { ctrl.rigStorageOpenFolder(rs.path.orEmpty() + "/" + entry.name) },
                )
            }
        }
        if (listing.truncated) {
            Spacer(Modifier.height(8.dp))
            TextC("Only the newest ${listing.entries.size} entries are shown.", style = t.MonoSmall, color = c.textMuted)
        }
    }
}

@Composable
private fun EntryRow(entry: WireFileEntry, withIconColumn: Boolean, onOpen: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(
        Modifier.fillMaxWidth().clickable(enabled = entry.isDir && !entry.inUse, onClick = onOpen).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            entry.inUse -> Phosphor.Icon(Phosphor.Lock, size = 20.dp, tint = NocturnePalette.Warn)
            entry.isDir -> Phosphor.Icon(Phosphor.Folder, size = 20.dp, tint = c.accent400)
            withIconColumn -> Spacer(Modifier.width(20.dp))
        }
        if (entry.isDir || withIconColumn) Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // A file name is long (target, job stamp, filter, number): smaller type keeps it on one line.
            TextC(entry.name, style = if (entry.isDir) t.Body135 else t.Body13, color = c.text)
            val size = formatByteSize(entry.sizeBytes) + if (entry.bytesPartial) "+" else ""
            val detail = (if (entry.isDir) "${entry.count} item${if (entry.count == 1) "" else "s"} · $size" else size) +
                " · " + ENTRY_DATE_FMT.format(Date(entry.modifiedSec * 1000))
            TextC(
                if (entry.inUse) "$detail · capture in progress" else detail,
                style = t.MonoMicro, color = if (entry.inUse) NocturnePalette.Warn else c.textMuted,
            )
        }
        if (entry.isDir && !entry.inUse) {
            Spacer(Modifier.width(6.dp))
            Phosphor.Icon(Phosphor.CaretRight, size = 16.dp, tint = c.textFaint)
        }
    }
}
