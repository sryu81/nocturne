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
    /** The file whose page is open (R4); null shows the folder. */
    val file: FilePageState? = null,
    val open: Boolean = false,
    val status: RigStorageStatus = RigStorageStatus.LOADING,
    /** The last `file_list_drives` reply, kept after the screen closes for the picker's one-liner. */
    val drives: List<WireDrive> = emptyList(),
    val path: String? = null,
    val listing: EkosEvent.FileListing? = null,
)

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
