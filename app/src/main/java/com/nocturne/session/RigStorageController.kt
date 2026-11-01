package com.nocturne.session

import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Frames tab's Rig storage view (plan 3): drives, folders, file pages and the copy, move, delete,
 * rename, cancel and eject operations, all on the fork's `file_*` commands. Pulled out of
 * [EkosRemoteController], which delegates its Rig storage overrides here. State lives in
 * [AppState.rigStorage]; this class holds only the two timeout watches.
 */
internal class RigStorageController(private val ctx: ControllerContext) {

    private var rigStorageWatch: Job? = null
    private var filePageWatch: Job? = null

    fun requestRigStorageSummary() {
        if (ctx.linkUp()) ctx.send(Commands.FILE_LIST_DRIVES)
    }

    fun openRigStorage() {
        ctx.update { it.copy(rigStorage = it.rigStorage.copy(open = true).openDrives()) }
        requestRigStorageData()
    }

    fun closeRigStorage() {
        rigStorageWatch?.cancel()
        filePageWatch?.cancel()
        ctx.update { it.copy(rigStorage = it.rigStorage.copy(open = false).openDrives()) }
    }

    fun rigStorageOpenFolder(path: String) {
        ctx.update { it.copy(rigStorage = it.rigStorage.openFolder(path)) }
        requestRigStorageData()
    }

    fun rigStorageUp() {
        val rs = ctx.state().rigStorage
        val parent = rs.parentPath()
        if (rs.file != null) {
            filePageWatch?.cancel()
            ctx.update { it.copy(rigStorage = it.rigStorage.closeFile()) }
        } else if (rs.path == null) {
            closeRigStorage()
        } else if (parent == null) {
            ctx.update { it.copy(rigStorage = it.rigStorage.openDrives()) }
            requestRigStorageData()
        } else {
            rigStorageOpenFolder(parent)
        }
    }

    fun rigStorageOpenFile(path: String) {
        filePageWatch?.cancel()
        ctx.update { it.copy(rigStorage = it.rigStorage.openFile(path), latestFilePreviewFrame = null) }
        if (!ctx.linkUp()) return
        ctx.send(Commands.FILE_HEADER, buildJsonObject { put("path", path) })
        ctx.send(Commands.FILE_PREVIEW, buildJsonObject { put("path", path) })
        // A preview needs the whole file read and stretched on the Pi; the header is tiny.
        filePageWatch = ctx.scope.launch {
            delay(FILE_PAGE_TIMEOUT_MS)
            ctx.update { s ->
                val page = s.rigStorage.file
                if (page == null || page.path != path) return@update s
                val arrived = page.previewUuid != null && s.latestFilePreviewFrame?.header?.uuid == page.previewUuid
                s.copy(
                    rigStorage = s.rigStorage.copy(
                        file = page.copy(
                            headerError = page.headerError ?: if (page.header == null) "no_reply" else null,
                            previewTimedOut = !arrived && page.previewError == null,
                        ),
                    ),
                )
            }
        }
    }

    fun rigStorageToggleSelect(path: String) = ctx.update { it.copy(rigStorage = it.rigStorage.toggled(path)) }

    fun rigStorageClearSelection() = ctx.update { it.copy(rigStorage = it.rigStorage.clearedSelection()) }

    fun rigStorageStartOp(kind: RigOpKind, paths: List<String>) =
        ctx.update { it.copy(rigStorage = it.rigStorage.startPending(kind, paths)) }

    fun rigStorageChooseTarget(driveId: String) = ctx.update { it.copy(rigStorage = it.rigStorage.withTarget(driveId)) }

    fun rigStorageCancelPending() = ctx.update { it.copy(rigStorage = it.rigStorage.cancelPending()) }

    fun rigStorageConfirmOp() {
        val rs = ctx.state().rigStorage
        val pending = rs.pending ?: return
        if (!pending.confirming || rs.opRunning) return
        val command = when (pending.kind) {
            RigOpKind.DELETE -> Commands.FILE_DELETE
            RigOpKind.COPY -> Commands.FILE_COPY
            RigOpKind.MOVE -> Commands.FILE_MOVE
            RigOpKind.RENAME -> return
        }
        ctx.send(command, buildJsonObject {
            put("paths", kotlinx.serialization.json.JsonArray(pending.paths.map { JsonPrimitive(it) }))
            pending.toDrive?.let { put("toDrive", it) }
        })
        // A file page whose file is about to move or go is left; the folder shows what is left.
        ctx.update { it.copy(rigStorage = it.rigStorage.sent().closeFile()) }
    }

    fun rigStorageRename(newName: String) {
        val rs = ctx.state().rigStorage
        val pending = rs.pending?.takeIf { it.kind == RigOpKind.RENAME } ?: return
        val path = pending.paths.singleOrNull() ?: return
        ctx.send(Commands.FILE_RENAME, buildJsonObject {
            put("path", path)
            put("name", newName.trim())
        })
        ctx.update { it.copy(rigStorage = it.rigStorage.cancelPending().clearedSelection()) }
    }

    fun rigStorageCancelOp() {
        val id = ctx.state().rigStorage.op?.takeIf { it.state == "running" }?.id ?: return
        ctx.send(Commands.FILE_CANCEL, buildJsonObject { put("id", id) })
    }

    fun rigStorageEject(driveId: String) {
        if (ctx.state().rigStorage.opRunning) return
        ctx.update { it.copy(rigStorage = it.rigStorage.copy(ejecting = driveId, notice = null)) }
        ctx.send(Commands.FILE_EJECT, buildJsonObject { put("id", driveId) })
    }

    fun rigStorageDismissNotice() = ctx.update { it.copy(rigStorage = it.rigStorage.dismissedNotice()) }

    /** Reads the drives and the open folder again, without showing a loading state. */
    private fun refreshRigStorage() {
        if (!ctx.linkUp()) return
        ctx.send(Commands.FILE_LIST_DRIVES)
        ctx.state().rigStorage.path?.let { ctx.send(Commands.FILE_LIST, buildJsonObject { put("path", it) }) }
    }

    fun rigStorageRefresh() {
        ctx.update { it.copy(rigStorage = it.rigStorage.copy(listing = null, status = RigStorageStatus.LOADING)) }
        requestRigStorageData()
    }

    /**
     * Asks for what the screen shows: the drives, or the listing of the open folder. A fork from
     * before R1 does not know the commands and never replies, so the wait ends in OLD_FORK (drives)
     * or NO_REPLY (a folder) after [RIG_STORAGE_TIMEOUT_MS]; a late reply still wins.
     */
    private fun requestRigStorageData() {
        rigStorageWatch?.cancel()
        val path = ctx.state().rigStorage.path
        if (!ctx.linkUp()) {
            ctx.update { it.copy(rigStorage = it.rigStorage.copy(status = RigStorageStatus.OFFLINE)) }
            return
        }
        if (path == null) {
            ctx.send(Commands.FILE_LIST_DRIVES)
        } else {
            ctx.send(Commands.FILE_LIST, buildJsonObject { put("path", path) })
        }
        rigStorageWatch = ctx.scope.launch {
            delay(RIG_STORAGE_TIMEOUT_MS)
            ctx.update { s ->
                val rs = s.rigStorage
                if (rs.open && rs.path == path && rs.status == RigStorageStatus.LOADING) {
                    s.copy(rigStorage = rs.copy(status = if (path == null) RigStorageStatus.OLD_FORK else RigStorageStatus.NO_REPLY))
                } else s
            }
        }
    }

    /** What an operation changed (free space, folder contents) is read again when it ends. */
    fun onEvent(event: EkosEvent) {
        when (event) {
            is EkosEvent.FileProgress -> if (event.state != "running") refreshRigStorage()
            is EkosEvent.FileRenameReply -> if (event.ok) refreshRigStorage()
            is EkosEvent.FileEjectReply -> {
                refreshRigStorage()
                // The ejected drive's folders are gone: back to the drives.
                val rs = ctx.state().rigStorage
                if (event.ok && rs.driveFor(rs.path)?.id == event.id) ctx.update { it.copy(rigStorage = it.rigStorage.openDrives()) }
            }
            else -> Unit
        }
    }
}

private const val RIG_STORAGE_TIMEOUT_MS = 5_000L
private const val FILE_PAGE_TIMEOUT_MS = 20_000L
