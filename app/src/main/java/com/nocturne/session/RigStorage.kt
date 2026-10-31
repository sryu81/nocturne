package com.nocturne.session

import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.WireDrive
import com.nocturne.protocol.WireFileEntry
import com.nocturne.protocol.WireHeaderRecord

/** What the Rig storage screen can show; see [RigStorageState.status]. */
enum class RigStorageStatus {
    /** A request is out. */
    LOADING,
    READY,

    /** No connection to KStars: "Needs KStars running". */
    OFFLINE,

    /** Connected, but the fork never answered `file_list_drives`: "Needs the updated KStars". */
    OLD_FORK,

    /** A folder request got no reply. */
    NO_REPLY,
}

/**
 * Frames tab's Rig storage view (plan 3, R2): the Pi's drives and their `Nocturne` folders, read-only.
 * Local navigation plus the last replies. [path] null shows the drives; otherwise the folder whose
 * [listing] is shown (null until its reply arrives).
 */
data class RigStorageState(
    /** Paths ticked in the folder view (R6). Long-press starts a selection; the action bar appears while it is not empty. */
    val selection: Set<String> = emptySet(),
    /** An operation being set up: a target to choose, or a confirmation to give. */
    val pending: PendingOp? = null,
    /** The newest operation the rig reported, running or finished; null before any. */
    val op: EkosEvent.FileProgress? = null,
    /** A request is sent and neither its refusal nor its first progress has arrived. */
    val starting: Boolean = false,
    /** What the last operation, rename or eject came to, until dismissed. */
    val notice: String? = null,
    /** The drive being ejected. */
    val ejecting: String? = null,
    /** The file whose page is open (R4); null shows the folder. */
    val file: FilePageState? = null,
    val open: Boolean = false,
    val status: RigStorageStatus = RigStorageStatus.LOADING,
    /** The last `file_list_drives` reply, kept after the screen closes for the picker's one-liner. */
    val drives: List<WireDrive> = emptyList(),
    val path: String? = null,
    val listing: EkosEvent.FileListing? = null,
)

enum class RigOpKind(val verb: String) { COPY("Copy"), MOVE("Move"), DELETE("Delete"), RENAME("Rename") }

/** [toDrive] is the drive id a copy or move goes to; [confirming] once everything needed is chosen. */
data class PendingOp(
    val kind: RigOpKind,
    val paths: List<String>,
    val toDrive: String? = null,
    val confirming: Boolean = false,
)

/** True while the rig works on a request (or the app has sent one). */
val RigStorageState.opRunning: Boolean get() = starting || op?.state == "running"

fun RigStorageState.toggled(path: String): RigStorageState =
    copy(selection = if (path in selection) selection - path else selection + path, notice = null)

fun RigStorageState.clearedSelection(): RigStorageState = copy(selection = emptySet(), pending = null)

/** A delete asks at once; copy and move first ask where to; rename asks for the name. */
fun RigStorageState.startPending(kind: RigOpKind, paths: List<String>): RigStorageState =
    copy(pending = PendingOp(kind, paths, confirming = kind == RigOpKind.DELETE), notice = null)

fun RigStorageState.withTarget(driveId: String): RigStorageState =
    copy(pending = pending?.copy(toDrive = driveId, confirming = true))

fun RigStorageState.cancelPending(): RigStorageState = copy(pending = null)

/** The request is on its way: the selection and the setup are spent. */
fun RigStorageState.sent(): RigStorageState = copy(pending = null, selection = emptySet(), starting = true, op = null, notice = null)

fun RigStorageState.withOpReply(event: EkosEvent.FileOpReply): RigStorageState =
    if (event.error != null) copy(starting = false, notice = opErrorText(event.error))
    else copy(starting = false, op = EkosEvent.FileProgress(id = event.id ?: 0, op = event.op.orEmpty()))

fun RigStorageState.withProgress(event: EkosEvent.FileProgress): RigStorageState =
    copy(starting = false, op = event, notice = if (event.state == "running") notice else progressNotice(event))

fun RigStorageState.withRenameReply(event: EkosEvent.FileRenameReply): RigStorageState =
    if (event.ok) copy(notice = "Renamed to ${event.path.substringAfterLast('/')}", file = null)
    else copy(notice = opErrorText(event.error))

fun RigStorageState.withEjectReply(event: EkosEvent.FileEjectReply): RigStorageState = copy(
    ejecting = null,
    notice = if (event.ok) "Ejected. It is safe to pull the drive." else ejectErrorText(event),
)

fun RigStorageState.dismissedNotice(): RigStorageState = copy(notice = null, op = op?.takeIf { it.state == "running" })

/** Bytes the pending paths hold, from the folder being shown; null when the rig has not said. */
fun RigStorageState.pendingBytes(): Long? {
    val entries = listing?.entries ?: return null
    val names = pending?.paths.orEmpty().map { it.substringAfterLast('/') }.toSet()
    val hit = entries.filter { it.name in names }
    return if (hit.size == names.size) hit.sumOf { it.sizeBytes } else null
}

/** `8/24 files · 1.2 / 3.4 GB`. */
fun progressLine(p: EkosEvent.FileProgress): String =
    "${p.filesDone}/${p.filesTotal} files · ${formatByteSize(p.bytesDone.toLong())} / ${formatByteSize(p.bytesTotal.toLong())}"

fun progressNotice(p: EkosEvent.FileProgress): String {
    val verb = when (p.op) {
        "copy" -> "Copied"
        "move" -> "Moved"
        "delete" -> "Deleted"
        else -> "Done"
    }
    return when {
        p.state == "cancelled" -> "Cancelled after ${p.filesDone} of ${p.filesTotal} files"
        p.error == "no_space" ->
            "Not enough room on the drive: it needs ${formatByteSize(p.needed.toLong())} and has ${formatByteSize(p.free.toLong())} free"
        p.state == "failed" -> "${p.failed.size} file${if (p.failed.size == 1) "" else "s"} not done: " +
            p.failed.take(3).joinToString("; ") + if (p.failed.size > 3) "; …" else ""
        else -> "$verb ${p.filesTotal} file${if (p.filesTotal == 1) "" else "s"} · ${formatByteSize(p.bytesTotal.toLong())}"
    }
}

fun opErrorText(code: String?): String = when (code) {
    "busy" -> "The rig is already working on something"
    "in_use" -> "A capture is writing there, so it is locked"
    "not_allowed" -> "That is outside the Nocturne folder"
    "not_found" -> "It is gone"
    "no_such_drive" -> "That drive is not there"
    "same_drive" -> "It is already on that drive"
    "bad_request" -> "The rig did not understand the request"
    "bad_name" -> "That name cannot be used"
    "exists" -> "Something with that name is already there"
    "failed" -> "It did not work"
    else -> "Not done${code?.let { " ($it)" }.orEmpty()}"
}

fun ejectErrorText(event: EkosEvent.FileEjectReply): String = when (event.error) {
    "busy" -> "The drive is in use by a copy or move"
    "no_such_drive" -> "That drive is not there"
    else -> "Could not eject" + (event.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
}

/** The delete dialog's plain words, naming what goes (plan 3, "Delete"). */
fun deleteMessage(names: List<String>, bytes: Long?): String {
    val what = if (names.size == 1) names.single() else "${names.size} items"
    val size = bytes?.let { " (${formatByteSize(it)})" }.orEmpty()
    return "$what$size goes, with everything inside it. This cannot be undone."
}

fun transferMessage(kind: RigOpKind, names: List<String>, bytes: Long?, target: String): String {
    val what = if (names.size == 1) names.single() else "${names.size} items"
    val size = bytes?.let { " (${formatByteSize(it)})" }.orEmpty()
    return if (kind == RigOpKind.COPY) "$what$size is copied to the same place on $target. The original stays."
    else "$what$size is copied to the same place on $target, checked byte for byte, and only then removed from here."
}

/**
 * One file's page: its FITS header and a preview JPEG made on the Pi. The JPEG arrives on the media
 * channel under [previewUuid]; [AppState.latestFilePreviewFrame] holds it once it has.
 */
data class FilePageState(
    val path: String,
    val header: List<WireHeaderRecord>? = null,
    val headerError: String? = null,
    val previewUuid: String? = null,
    val previewError: String? = null,
    /** No frame arrived in time. */
    val previewTimedOut: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
}

fun RigStorageState.openFile(path: String): RigStorageState = copy(file = FilePageState(path))

fun RigStorageState.closeFile(): RigStorageState = copy(file = null)

fun RigStorageState.withHeader(event: EkosEvent.FileHeader): RigStorageState {
    val page = file?.takeIf { it.path == event.path } ?: return this
    return copy(file = page.copy(header = event.records.takeIf { event.error == null }, headerError = event.error))
}

fun RigStorageState.withPreviewReply(event: EkosEvent.FilePreviewReply): RigStorageState {
    val page = file?.takeIf { it.path == event.path } ?: return this
    return copy(file = page.copy(previewUuid = event.uuid, previewError = event.error))
}

/** The labelled header values worth showing, in reading order; a card the file lacks is left out. */
fun headerSummary(records: List<WireHeaderRecord>): List<Pair<String, String>> {
    val byKey = records.associate { it.key to it.value }
    fun v(key: String) = byKey[key]?.takeIf { it.isNotBlank() }
    return buildList {
        v("OBJECT")?.let { add("Object" to it) }
        (v("FRAME") ?: v("IMAGETYP"))?.let { add("Frame" to it) }
        v("FILTER")?.let { add("Filter" to it) }
        v("EXPTIME")?.let { add("Exposure" to "${trimNumber(it)} s") }
        v("GAIN")?.let { add("Gain" to trimNumber(it)) }
        v("OFFSET")?.let { add("Offset" to trimNumber(it)) }
        val bx = v("XBINNING")
        val by = v("YBINNING")
        if (bx != null) add("Binning" to "${trimNumber(bx)}x${trimNumber(by ?: bx)}")
        v("CCD-TEMP")?.let { add("Sensor" to "${trimNumber(it)} °C") }
        v("DATE-OBS")?.let { add("Taken" to it.replace('T', ' ')) }
        v("INSTRUME")?.let { add("Camera" to it) }
        v("FOCALLEN")?.let { add("Focal length" to "${trimNumber(it)} mm") }
    }
}

/** `300.0` -> `300`, `-9.80` -> `-9.8`; anything that is not a number is returned as it is. */
private fun trimNumber(text: String): String {
    val d = text.toDoubleOrNull() ?: return text
    return if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')
}

/** A drive reply arrived: the fork is new, so any waiting state is over. */
fun RigStorageState.withDrives(drives: List<WireDrive>): RigStorageState = copy(
    drives = drives,
    status = if (path == null || listing != null) RigStorageStatus.READY else status,
)

/** A folder reply counts only for the folder being shown (a late reply for another is dropped). */
fun RigStorageState.withListing(listing: EkosEvent.FileListing): RigStorageState =
    if (listing.path != path) this else copy(listing = listing, status = RigStorageStatus.READY)

fun RigStorageState.openFolder(path: String): RigStorageState =
    copy(path = path, listing = null, file = null, status = RigStorageStatus.LOADING)

fun RigStorageState.openDrives(): RigStorageState =
    copy(path = null, listing = null, file = null, status = if (drives.isEmpty()) RigStorageStatus.LOADING else RigStorageStatus.READY)

/** The drive whose `Nocturne` folder holds [path]; the longest match wins. */
fun RigStorageState.driveFor(path: String?): WireDrive? =
    if (path == null) null
    else drives.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }

/** One step up: the parent folder, or null (the drives) from a drive's own folder. */
fun RigStorageState.parentPath(): String? {
    val here = path ?: return null
    val drive = driveFor(here) ?: return null
    return if (here == drive.path) null else here.substringBeforeLast('/')
}

/** A step of the breadcrumb: the label shown and the folder it opens (null = the drives). */
data class Crumb(val label: String, val path: String?)

/** `SD › M_31 › 2026-10-30`: the drive, then each folder below its `Nocturne` folder. */
fun RigStorageState.crumbs(): List<Crumb> {
    val folders = folderCrumbs()
    return if (file == null) folders else folders + Crumb(file.name, null)
}

private fun RigStorageState.folderCrumbs(): List<Crumb> {
    val here = path ?: return emptyList()
    val drive = driveFor(here) ?: return listOf(Crumb(here.substringAfterLast('/'), here))
    val crumbs = mutableListOf(Crumb(driveLabel(drive), drive.path))
    var walked = drive.path
    here.removePrefix(drive.path).split('/').filter { it.isNotEmpty() }.forEach { part ->
        walked = "$walked/$part"
        crumbs += Crumb(part, walked)
    }
    return crumbs
}

fun driveLabel(drive: WireDrive): String = if (drive.kind == "sd") "SD" else drive.label.ifBlank { "USB" }

/** `SD 9.6 GB free · USB 1` for the picker card; null until a drive reply has arrived. */
fun rigStorageSummary(drives: List<WireDrive>): String? {
    if (drives.isEmpty()) return null
    val sd = drives.firstOrNull { it.kind == "sd" }
    val usb = drives.count { it.kind == "usb" }
    return buildList {
        if (sd != null) add("SD ${formatByteSize(sd.freeBytes)} free")
        add(if (usb == 0) "no USB" else "USB $usb")
    }.joinToString(" · ")
}

/**
 * Night folders are named by date, so those sort newest first by name (a folder's modified time
 * changes whenever something is moved into it); everything else keeps the rig's newest-first order.
 * Folders come before files.
 */
fun sortedEntries(entries: List<WireFileEntry>): List<WireFileEntry> {
    val folders = entries.filter { it.isDir }
    val files = entries.filter { !it.isDir }
    val dated = folders.isNotEmpty() && folders.all { NIGHT_NAME.matches(it.name) }
    return (if (dated) folders.sortedByDescending { it.name } else folders) + files
}

private val NIGHT_NAME = Regex("""\d{4}-\d{2}-\d{2}""")

/** `412 GB`, `3.4 GB`, `248 MB`, `12 KB`; binary units, the way the rig's own tools report them. */
fun formatByteSize(bytes: Long): String {
    val kb = 1024.0
    val b = bytes.toDouble()
    return when {
        b >= kb * kb * kb * kb -> String.format(java.util.Locale.US, "%.1f TB", b / (kb * kb * kb * kb))
        b >= kb * kb * kb * 100 -> String.format(java.util.Locale.US, "%.0f GB", b / (kb * kb * kb))
        b >= kb * kb * kb -> String.format(java.util.Locale.US, "%.1f GB", b / (kb * kb * kb))
        b >= kb * kb -> String.format(java.util.Locale.US, "%.0f MB", b / (kb * kb))
        b >= kb -> String.format(java.util.Locale.US, "%.0f KB", b / kb)
        else -> "$bytes B"
    }
}
