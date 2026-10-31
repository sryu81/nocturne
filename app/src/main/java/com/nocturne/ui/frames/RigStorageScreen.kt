package com.nocturne.ui.frames

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nocturne.session.PendingOp
import com.nocturne.session.RigOpKind
import com.nocturne.session.deleteMessage
import com.nocturne.session.driveFor
import com.nocturne.session.driveLabel
import com.nocturne.session.opRunning
import com.nocturne.session.pendingBytes
import com.nocturne.session.progressLine
import com.nocturne.session.transferMessage
import com.nocturne.ui.components.WindowConfirmDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import com.nocturne.protocol.MediaFrame
import com.nocturne.protocol.WireDrive
import com.nocturne.protocol.WireFileEntry
import com.nocturne.session.AppState
import com.nocturne.session.RigStorageState
import com.nocturne.session.RigStorageStatus
import com.nocturne.session.SessionController
import com.nocturne.session.crumbs
import com.nocturne.session.headerSummary
import com.nocturne.session.formatByteSize
import com.nocturne.session.sortedEntries
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.MediaFramePreview
import com.nocturne.ui.components.NocturneButton
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
    val barVisible = rs.selection.isNotEmpty() || rs.pending != null

    Box(modifier) {
        TabPane(
            landscape = landscape,
            modifier = Modifier,
            items = buildList {
                add(TabItem(full = true) { StorageHeader(rs, ctrl) })
                if (rs.opRunning || rs.notice != null) add(TabItem(full = true) { OpPanel(rs, ctrl) })
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
                            RigStorageStatus.READY -> when {
                                rs.file != null -> FilePage(rs, ctrl, state.latestFilePreviewFrame)
                                rs.path == null -> DriveList(rs, ctrl)
                                else -> FolderList(rs, ctrl)
                            }
                        }
                    },
                )
                // Room for the action bar over the last rows.
                if (barVisible) add(TabItem(full = true) { Spacer(Modifier.height(120.dp)) })
            },
        )
        if (barVisible) ActionBar(rs, ctrl, Modifier.align(Alignment.BottomCenter))
    }
}

/** The running operation with its progress and Cancel, or what the last one came to. */
@Composable
private fun OpPanel(rs: RigStorageState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val p = rs.op
    Card(Modifier.fillMaxWidth()) {
        if (rs.opRunning) {
            val verb = when (p?.op) {
                "copy" -> "Copying"
                "move" -> "Moving"
                "delete" -> "Deleting"
                else -> "Working"
            }
            TextC(verb + "…", style = t.Body135, color = c.text)
            if (p != null && p.filesTotal > 0) {
                Spacer(Modifier.height(4.dp))
                TextC(progressLine(p), style = t.MonoSmall, color = c.textMuted)
                if (p.current.isNotBlank()) TextC(p.current, style = t.MonoMicro, color = c.textFaint, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                val done = if (p.bytesTotal > 0) (p.bytesDone / p.bytesTotal).toFloat() else p.filesDone.toFloat() / p.filesTotal
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceDeep)) {
                    Box(Modifier.fillMaxWidth(done.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
                }
            }
            Spacer(Modifier.height(10.dp))
            NocturneButton("Cancel", onClick = ctrl::rigStorageCancelOp, style = BtnStyle.OUTLINE, enabled = p?.state == "running")
        } else {
            val bad = p?.state == "failed" || p?.state == "cancelled" || rs.notice?.startsWith("Not enough") == true
            TextC(rs.notice.orEmpty(), style = t.Body13, color = if (bad) NocturnePalette.Warn else c.text)
            Spacer(Modifier.height(8.dp))
            NocturneButton("Dismiss", onClick = ctrl::rigStorageDismissNotice, style = BtnStyle.SUBTLE)
        }
    }
}

/**
 * Pinned at the bottom while something is ticked or being set up: the actions, then (for copy and move)
 * the drive to send it to, or the new name, with the plain confirmation as a dialog.
 */
@Composable
private fun ActionBar(rs: RigStorageState, ctrl: SessionController, modifier: Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val pending = rs.pending
    val paths = pending?.paths ?: rs.selection.toList()
    val names = paths.map { it.substringAfterLast('/') }

    Column(
        modifier.fillMaxWidth().background(c.surfaceRaised).padding(horizontal = 16.8.dp, vertical = 12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        when {
            pending == null -> {
                val bytes = rs.listing?.entries?.filter { it.name in names }?.sumOf { it.sizeBytes }
                TextC(
                    "${paths.size} selected" + (bytes?.let { " · ${formatByteSize(it)}" } ?: ""),
                    style = t.MonoSmall, color = c.text,
                )
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    val idle = !rs.opRunning
                    NocturneButton("Copy to…", { ctrl.rigStorageStartOp(RigOpKind.COPY, paths) }, Modifier.weight(1f), BtnStyle.OUTLINE, idle)
                    NocturneButton("Move to…", { ctrl.rigStorageStartOp(RigOpKind.MOVE, paths) }, Modifier.weight(1f), BtnStyle.OUTLINE, idle)
                    if (paths.size == 1) {
                        NocturneButton("Rename", { ctrl.rigStorageStartOp(RigOpKind.RENAME, paths) }, Modifier.weight(1f), BtnStyle.OUTLINE, idle)
                    }
                    NocturneButton("Delete", { ctrl.rigStorageStartOp(RigOpKind.DELETE, paths) }, Modifier.weight(1f), BtnStyle.DANGER, idle)
                }
                NocturneButton("Clear selection", ctrl::rigStorageClearSelection, style = BtnStyle.SUBTLE)
            }
            pending.kind == RigOpKind.RENAME -> {
                var name by remember(pending.paths) { mutableStateOf(names.single()) }
                TextC("Rename", style = t.MonoSmall, color = c.textMuted)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = t.Body13.copy(color = c.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().height(44.dp).background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
                )
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    NocturneButton("Cancel", ctrl::rigStorageCancelPending, Modifier.weight(1f), BtnStyle.OUTLINE)
                    NocturneButton(
                        "Rename", { ctrl.rigStorageRename(name) }, Modifier.weight(1f),
                        enabled = name.isNotBlank() && name != names.single(),
                    )
                }
            }
            pending.toDrive == null -> {
                val from = rs.driveFor(paths.first())?.id
                TextC("${pending.kind.verb} to which drive?", style = t.MonoSmall, color = c.text)
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    rs.drives.filter { it.id != from && !it.readOnly }.forEach { drive ->
                        NocturneButton(driveLabel(drive), { ctrl.rigStorageChooseTarget(drive.id) }, Modifier.weight(1f), BtnStyle.OUTLINE)
                    }
                    NocturneButton("Cancel", ctrl::rigStorageCancelPending, Modifier.weight(1f), BtnStyle.SUBTLE)
                }
                if (rs.drives.none { it.id != from && !it.readOnly }) {
                    TextC("No other drive is attached.", style = t.Body13, color = c.textMuted)
                }
            }
        }
    }

    if (pending != null && pending.confirming) {
        val bytes = rs.pendingBytes()
        val target = rs.drives.firstOrNull { it.id == pending.toDrive }?.let(::driveLabel).orEmpty()
        val isDelete = pending.kind == RigOpKind.DELETE
        WindowConfirmDialog(
            title = when (pending.kind) {
                RigOpKind.DELETE -> if (names.size == 1) "Delete ${names.single()}?" else "Delete ${names.size} items?"
                else -> "${pending.kind.verb} to $target?"
            },
            message = if (isDelete) deleteMessage(names, bytes) else transferMessage(pending.kind, names, bytes, target),
            confirmText = pending.kind.verb,
            confirmStyle = if (isDelete) BtnStyle.DANGER else BtnStyle.SOLID,
            onConfirm = ctrl::rigStorageConfirmOp,
            onDismiss = ctrl::rigStorageCancelPending,
        )
    }
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
private fun DriveList(rs: RigStorageState, ctrl: SessionController) {
    val drives = rs.drives
    if (drives.isEmpty()) {
        Notice("No drives reported", "The rig answered but listed no storage.")
        return
    }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(11.2.dp)) {
        drives.forEach { drive ->
            DriveCard(
                drive,
                onOpen = { ctrl.rigStorageOpenFolder(drive.path) },
                ejecting = rs.ejecting == drive.id,
                canEject = drive.kind == "usb" && !rs.opRunning && rs.ejecting == null,
                onEject = { ctrl.rigStorageEject(drive.id) },
            )
        }
    }
}

@Composable
private fun DriveCard(drive: WireDrive, onOpen: () -> Unit, ejecting: Boolean, canEject: Boolean, onEject: () -> Unit) {
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
        if (drive.kind == "usb") {
            Spacer(Modifier.height(10.dp))
            NocturneButton(
                if (ejecting) "Ejecting…" else "Eject", onClick = onEject, style = BtnStyle.OUTLINE,
                enabled = canEject,
            )
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
                val full = rs.path.orEmpty() + "/" + entry.name
                EntryRow(
                    entry,
                    withIconColumn = entries.any { it.isDir },
                    selecting = rs.selection.isNotEmpty(),
                    selected = full in rs.selection,
                    onToggle = { ctrl.rigStorageToggleSelect(full) },
                    onOpen = { if (entry.isDir) ctrl.rigStorageOpenFolder(full) else ctrl.rigStorageOpenFile(full) },
                )
            }
        }
        if (listing.truncated) {
            Spacer(Modifier.height(8.dp))
            TextC("Only the newest ${listing.entries.size} entries are shown.", style = t.MonoSmall, color = c.textMuted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: WireFileEntry,
    withIconColumn: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val opens = !entry.inUse && (entry.isDir || isFitsName(entry.name))
    // A long press ticks the row; once something is ticked a tap ticks too. A locked row cannot be ticked.
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                enabled = !entry.inUse && (opens || selecting),
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = onToggle,
            )
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting && !entry.inUse) {
            if (selected) Phosphor.Icon(Phosphor.CheckCircle, size = 22.dp, tint = c.accent)
            else Box(Modifier.size(22.dp).border(2.dp, c.textFaint, CircleShape))
            Spacer(Modifier.width(10.dp))
        }
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
        if (opens && !selecting) {
            Spacer(Modifier.width(6.dp))
            Phosphor.Icon(Phosphor.CaretRight, size = 16.dp, tint = c.textFaint)
        }
    }
}

private fun isFitsName(name: String): Boolean = name.lowercase().let { it.endsWith(".fits") || it.endsWith(".fit") || it.endsWith(".fts") }

/** One FITS file: the preview the Pi made, the header values, and the actions (switched on in R6). */
@Composable
private fun FilePage(rs: RigStorageState, ctrl: SessionController, frame: MediaFrame?) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val page = rs.file ?: return
    val entry = rs.listing?.entries?.firstOrNull { it.name == page.name }
    val shown = frame?.takeIf { page.previewUuid != null && it.header.uuid == page.previewUuid }

    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(11.2.dp)) {
        Column {
            TextC(page.name, style = t.Body13, color = c.text)
            if (entry != null) {
                TextC(
                    formatByteSize(entry.sizeBytes) + " · " + ENTRY_DATE_FMT.format(Date(entry.modifiedSec * 1000)),
                    style = t.MonoMicro, color = c.textMuted,
                )
            }
        }

        val ratio = shown?.header?.resolution?.split("x")?.let { p ->
            val w = p.getOrNull(0)?.toFloatOrNull()
            val h = p.getOrNull(1)?.toFloatOrNull()
            if (w != null && h != null && h > 0f) w / h else null
        } ?: 1.5f
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(8.dp)).background(c.surfaceDeep),
            contentAlignment = Alignment.Center,
        ) {
            if (shown != null) {
                MediaFramePreview(shown, Modifier.fillMaxSize())
            } else {
                TextC(
                    when {
                        page.previewError != null -> previewErrorText(page.previewError)
                        page.previewTimedOut -> "No preview came back"
                        else -> "Reading the file on the Pi…"
                    },
                    style = t.Body13, color = c.textMuted,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            val rows = buildList {
                addAll(headerSummary(page.header.orEmpty()))
                shown?.header?.let { h ->
                    h.resolution?.let { add("Resolution" to it) }
                    h.hfr?.takeIf { it > 0.0 }?.let { add("HFR" to String.format(Locale.US, "%.2f", it)) }
                    h.hasWCS?.let { add("Plate solved" to if (it) "yes" else "no") }
                }
            }
            when {
                page.headerError != null -> TextC(headerErrorText(page.headerError), style = t.Body13, color = c.textMuted)
                page.header == null -> TextC("Reading the header…", style = t.Body13, color = c.textMuted)
                rows.isEmpty() -> TextC("No usual header values in this file", style = t.Body13, color = c.textMuted)
                else -> rows.forEachIndexed { i, (label, value) ->
                    if (i > 0) HDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextC(label, style = t.MonoSmall, color = c.textMuted, modifier = Modifier.weight(1f))
                        TextC(value, style = t.Body13, color = c.text)
                    }
                }
            }
        }

        val idle = !rs.opRunning && rs.pending == null
        val one = listOf(page.path)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            NocturneButton("Copy", { ctrl.rigStorageStartOp(RigOpKind.COPY, one) }, Modifier.weight(1f), BtnStyle.OUTLINE, idle)
            NocturneButton("Move", { ctrl.rigStorageStartOp(RigOpKind.MOVE, one) }, Modifier.weight(1f), BtnStyle.OUTLINE, idle)
            NocturneButton("Rename", { ctrl.rigStorageStartOp(RigOpKind.RENAME, one) }, Modifier.weight(1f), BtnStyle.OUTLINE, idle)
            NocturneButton("Delete", { ctrl.rigStorageStartOp(RigOpKind.DELETE, one) }, Modifier.weight(1f), BtnStyle.DANGER, idle)
        }
    }
}

private fun previewErrorText(code: String): String = when (code) {
    "not_fits" -> "Not a FITS file"
    "not_allowed" -> "Outside the Nocturne folder"
    "not_found" -> "The file is gone"
    else -> "No preview ($code)"
}

private fun headerErrorText(code: String): String = when (code) {
    "not_fits" -> "This is not a readable FITS file."
    "not_allowed" -> "Outside the Nocturne folder."
    "not_found" -> "The file is gone."
    "no_reply" -> "The rig did not send the header."
    else -> "Header not available ($code)."
}
