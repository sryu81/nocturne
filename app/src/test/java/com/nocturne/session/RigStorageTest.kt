package com.nocturne.session

import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.WireDrive
import com.nocturne.protocol.WireFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RigStorageTest {
    private val sd = WireDrive(id = "sd", kind = "sd", label = "SD card", fs = "ext4", path = "/home/soo/Nocturne", total = 30e9, free = 9.6e9, hasRoot = true)
    private val usb = WireDrive(id = "/media/STICK", kind = "usb", label = "STICK", fs = "ntfs3", path = "/media/STICK/Nocturne", total = 256e9, free = 158e9, hasRoot = true)
    private val state = RigStorageState(open = true, status = RigStorageStatus.READY, drives = listOf(sd, usb))

    @Test
    fun theDriveHoldingAFolderIsFoundAndTheParentWalksUp() {
        val deep = state.openFolder("/media/STICK/Nocturne/M_31/2026-10-30")
        assertEquals(usb, deep.driveFor(deep.path))
        assertEquals("/media/STICK/Nocturne/M_31", deep.parentPath())
        assertEquals("/media/STICK/Nocturne", state.openFolder("/media/STICK/Nocturne/M_31").parentPath())
        assertNull(state.openFolder("/media/STICK/Nocturne").parentPath())
        assertNull(state.parentPath())
    }

    @Test
    fun aSimilarNamedDriveIsNotMistakenForAnother() {
        val other = usb.copy(id = "/media/STICK2", path = "/media/STICK2/Nocturne")
        val s = state.copy(drives = listOf(usb, other))
        assertEquals(other, s.driveFor("/media/STICK2/Nocturne/M_31"))
        assertEquals(usb, s.driveFor("/media/STICK/Nocturne"))
        assertNull(s.driveFor("/media/STICK3/Nocturne"))
    }

    @Test
    fun theBreadcrumbIsTheDriveThenEachFolder() {
        val s = state.openFolder("/home/soo/Nocturne/M_31/2026-10-30")
        assertEquals(listOf("SD", "M_31", "2026-10-30"), s.crumbs().map { it.label })
        assertEquals(
            listOf("/home/soo/Nocturne", "/home/soo/Nocturne/M_31", "/home/soo/Nocturne/M_31/2026-10-30"),
            s.crumbs().map { it.path },
        )
        assertEquals(listOf("STICK"), state.openFolder("/media/STICK/Nocturne").crumbs().map { it.label })
        assertEquals(emptyList<Crumb>(), state.crumbs())
    }

    @Test
    fun aListingForAnotherFolderIsIgnored() {
        val s = state.openFolder("/home/soo/Nocturne/M_31")
        val late = EkosEvent.FileListing(path = "/home/soo/Nocturne/NGC_7193")
        assertEquals(s, s.withListing(late))
        val mine = EkosEvent.FileListing(path = "/home/soo/Nocturne/M_31")
        val done = s.withListing(mine)
        assertEquals(mine, done.listing)
        assertEquals(RigStorageStatus.READY, done.status)
    }

    @Test
    fun aDriveReplyEndsAWaitAtTheRootButNotInAFolder() {
        val waiting = RigStorageState(open = true, status = RigStorageStatus.OLD_FORK)
        assertEquals(RigStorageStatus.READY, waiting.withDrives(listOf(sd)).status)
        val inFolder = RigStorageState(open = true, status = RigStorageStatus.LOADING, path = "/home/soo/Nocturne/M_31")
        assertEquals(RigStorageStatus.LOADING, inFolder.withDrives(listOf(sd)).status)
    }

    @Test
    fun openingTheDrivesUsesWhatIsKnown() {
        assertEquals(RigStorageStatus.LOADING, RigStorageState().openDrives().status)
        assertEquals(RigStorageStatus.READY, state.openFolder("/home/soo/Nocturne").openDrives().status)
    }

    @Test
    fun theSummaryNamesTheSdSpaceAndTheUsbCount() {
        assertEquals("SD 8.9 GB free · USB 1", rigStorageSummary(listOf(sd, usb)))
        assertEquals("SD 8.9 GB free · no USB", rigStorageSummary(listOf(sd)))
        assertNull(rigStorageSummary(emptyList()))
    }

    private fun dir(name: String, modified: Double = 0.0) = WireFileEntry(name = name, isDir = true, modified = modified)

    @Test
    fun nightFoldersSortNewestFirstByNameWhateverTheirModifiedTime() {
        val sorted = sortedEntries(listOf(dir("2026-10-26", 9.0), dir("2026-10-30", 1.0), dir("2026-10-28", 5.0)))
        assertEquals(listOf("2026-10-30", "2026-10-28", "2026-10-26"), sorted.map { it.name })
    }

    @Test
    fun otherFoldersKeepTheRigOrderAndFoldersComeBeforeFiles() {
        val file = WireFileEntry(name = "a.fits", size = 5.0)
        val sorted = sortedEntries(listOf(file, dir("NGC_7193"), dir("M_31")))
        assertEquals(listOf("NGC_7193", "M_31", "a.fits"), sorted.map { it.name })
    }

    @Test
    fun sizesAreReadableAtEveryScale() {
        assertEquals("512 B", formatByteSize(512))
        assertEquals("12 KB", formatByteSize(12 * 1024L))
        assertEquals("248 MB", formatByteSize(248L * 1024 * 1024))
        assertEquals("3.4 GB", formatByteSize((3.4 * 1024 * 1024 * 1024).toLong()))
        assertEquals("148 GB", formatByteSize(148L * 1024 * 1024 * 1024))
        assertEquals("1.2 TB", formatByteSize((1.2 * 1024 * 1024 * 1024 * 1024).toLong()))
    }

    private fun rec(key: String, value: String) = com.nocturne.protocol.WireHeaderRecord(key, value)

    @Test
    fun theFilePageIsTheLastCrumbAndClosesBackToTheFolder() {
        val inFolder = state.openFolder("/home/soo/Nocturne/M_31/2026-10-30/Light/H")
        val open = inFolder.openFile("/home/soo/Nocturne/M_31/2026-10-30/Light/H/a.fits")
        assertEquals("a.fits", open.file?.name)
        assertEquals(listOf("SD", "M_31", "2026-10-30", "Light", "H", "a.fits"), open.crumbs().map { it.label })
        assertNull(open.crumbs().last().path)
        assertEquals(inFolder.path, open.closeFile().path)
        assertNull(open.closeFile().file)
        assertNull(open.openDrives().file)
    }

    @Test
    fun aHeaderOrPreviewReplyCountsOnlyForTheOpenFile() {
        val open = state.openFolder("/p").openFile("/p/a.fits")
        val header = EkosEvent.FileHeader(path = "/p/a.fits", records = listOf(rec("FILTER", "H")))
        assertEquals(listOf("FILTER"), open.withHeader(header).file?.header?.map { it.key })
        assertEquals(open, open.withHeader(header.copy(path = "/p/other.fits")))
        assertEquals("not_fits", open.withHeader(EkosEvent.FileHeader(path = "/p/a.fits", error = "not_fits")).file?.headerError)

        assertEquals("fp_3", open.withPreviewReply(EkosEvent.FilePreviewReply("/p/a.fits", uuid = "fp_3")).file?.previewUuid)
        assertEquals(open, open.withPreviewReply(EkosEvent.FilePreviewReply("/p/other.fits", uuid = "fp_4")))
        assertNull(state.withHeader(header).file)   // no file open: ignored
    }

    @Test
    fun theHeaderSummaryNamesWhatMattersAndSkipsWhatIsMissing() {
        val rows = headerSummary(
            listOf(
                rec("SIMPLE", "T"), rec("OBJECT", "M 31"), rec("IMAGETYP", "Light Frame"), rec("FILTER", "H"),
                rec("EXPTIME", "300.0"), rec("GAIN", "100"), rec("XBINNING", "1"), rec("YBINNING", "1"),
                rec("CCD-TEMP", "-9.80"), rec("DATE-OBS", "2026-10-30T21:25:03"), rec("FOCALLEN", "250.0"),
            ),
        ).toMap()
        assertEquals("M 31", rows["Object"])
        assertEquals("Light Frame", rows["Frame"])
        assertEquals("300 s", rows["Exposure"])
        assertEquals("100", rows["Gain"])
        assertEquals("1x1", rows["Binning"])
        assertEquals("-9.8 °C", rows["Sensor"])
        assertEquals("2026-10-30 21:25:03", rows["Taken"])
        assertEquals("250 mm", rows["Focal length"])
        assertEquals(null, rows["Offset"])
        assertEquals(emptyList<Pair<String, String>>(), headerSummary(listOf(rec("SIMPLE", "T"))))
    }

    @Test
    fun aFrameKeyBeatsImagetypAndANonNumberIsKeptAsItIs() {
        val rows = headerSummary(listOf(rec("FRAME", "Dark"), rec("IMAGETYP", "Light Frame"), rec("EXPTIME", "unknown"))).toMap()
        assertEquals("Dark", rows["Frame"])
        assertEquals("unknown s", rows["Exposure"])
    }

    private fun progress(state: String, op: String = "copy", done: Int = 2, total: Int = 2, bytes: Double = 5e6, failed: List<String> = emptyList(), error: String? = null) =
        EkosEvent.FileProgress(id = 1, op = op, state = state, filesDone = done, filesTotal = total, bytesDone = bytes, bytesTotal = bytes, failed = failed, error = error)

    @Test
    fun aSelectionTicksAndUnticksAndClears() {
        val s = state.toggled("/a").toggled("/b")
        assertEquals(setOf("/a", "/b"), s.selection)
        assertEquals(setOf("/b"), s.toggled("/a").selection)
        assertEquals(emptySet<String>(), s.clearedSelection().selection)
    }

    @Test
    fun deleteAsksAtOnceWhileCopyAndMoveAskWhereToFirst() {
        assertEquals(true, state.startPending(RigOpKind.DELETE, listOf("/a")).pending?.confirming)
        val copy = state.startPending(RigOpKind.COPY, listOf("/a"))
        assertEquals(false, copy.pending?.confirming)
        assertNull(copy.pending?.toDrive)
        val target = copy.withTarget("/media/STICK")
        assertEquals("/media/STICK", target.pending?.toDrive)
        assertEquals(true, target.pending?.confirming)
        assertNull(target.cancelPending().pending)
    }

    @Test
    fun sendingSpendsTheSelectionAndMarksTheStart() {
        val s = state.toggled("/a").startPending(RigOpKind.DELETE, listOf("/a")).sent()
        assertEquals(true, s.starting)
        assertEquals(true, s.opRunning)
        assertNull(s.pending)
        assertEquals(emptySet<String>(), s.selection)
    }

    @Test
    fun aRefusalOrAProgressPushEndsTheStart() {
        val sent = state.sent()
        val refused = sent.withOpReply(EkosEvent.FileOpReply(error = "in_use"))
        assertEquals(false, refused.opRunning)
        assertEquals("A capture is writing there, so it is locked", refused.notice)

        val accepted = sent.withOpReply(EkosEvent.FileOpReply(id = 4, op = "move"))
        assertEquals(true, accepted.opRunning)
        assertEquals(4, accepted.op?.id)

        val running = accepted.withProgress(progress("running", done = 1))
        assertEquals(true, running.opRunning)
        assertNull(running.notice)
        val done = running.withProgress(progress("done"))
        assertEquals(false, done.opRunning)
        assertEquals("Copied 2 files · 5 MB", done.notice)
    }

    @Test
    fun theNoticesSayWhatHappened() {
        assertEquals("Moved 2 files · 5 MB", progressNotice(progress("done", op = "move")))
        assertEquals("Deleted 1 file · 5 MB", progressNotice(progress("done", op = "delete", total = 1)))
        assertEquals("Cancelled after 1 of 2 files", progressNotice(progress("cancelled", done = 1)))
        assertEquals(
            "1 file not done: a.fits: exists",
            progressNotice(progress("failed", failed = listOf("a.fits: exists"))),
        )
        assertEquals(
            "Not enough room on the drive: it needs 3.7 GB and has 954 MB free",
            progressNotice(
                EkosEvent.FileProgress(op = "copy", state = "failed", error = "no_space", needed = 4e9, free = 1e9),
            ),
        )
        assertEquals("8/24 files · 1 MB / 3 MB", progressLine(EkosEvent.FileProgress(filesDone = 8, filesTotal = 24, bytesDone = 1.1e6, bytesTotal = 3.2e6)))
    }

    @Test
    fun anEjectOrRenameResultBecomesANotice() {
        assertEquals("Ejected. It is safe to pull the drive.", state.copy(ejecting = "/media/X").withEjectReply(EkosEvent.FileEjectReply("/media/X", ok = true)).notice)
        val failed = state.withEjectReply(EkosEvent.FileEjectReply("/media/X", error = "failed", message = "/media/X is busy"))
        assertEquals("Could not eject: /media/X is busy", failed.notice)
        assertNull(failed.ejecting)
        val open = state.openFolder("/p").openFile("/p/a.fits")
        assertNull(open.withRenameReply(EkosEvent.FileRenameReply(ok = true, path = "/p/b.fits")).file)
        assertEquals("Something with that name is already there", state.withRenameReply(EkosEvent.FileRenameReply(error = "exists")).notice)
    }

    @Test
    fun theConfirmationNamesWhatGoesAndWhereItLands() {
        assertEquals(
            "2026-10-30 (3.1 GB) goes, with everything inside it. This cannot be undone.",
            deleteMessage(listOf("2026-10-30"), 3_328_599_654L),
        )
        assertEquals("3 items goes, with everything inside it. This cannot be undone.", deleteMessage(listOf("a", "b", "c"), null))
        assertEquals("2026-10-30 (3.1 GB) is copied to the same place on STICK. The original stays.", transferMessage(RigOpKind.COPY, listOf("2026-10-30"), 3_328_599_654L, "STICK"))
        assertEquals(
            "2026-10-30 is copied to the same place on STICK, checked byte for byte, and only then removed from here.",
            transferMessage(RigOpKind.MOVE, listOf("2026-10-30"), null, "STICK"),
        )
    }

    @Test
    fun thePendingSizeComesFromTheFolderBeingShown() {
        val listing = EkosEvent.FileListing(
            path = "/p",
            entries = listOf(WireFileEntry(name = "a", isDir = true, bytes = 100.0), WireFileEntry(name = "b", size = 50.0)),
        )
        val s = state.openFolder("/p").withListing(listing).startPending(RigOpKind.DELETE, listOf("/p/a", "/p/b"))
        assertEquals(150L, s.pendingBytes())
        assertNull(s.startPending(RigOpKind.DELETE, listOf("/p/a", "/p/other")).pendingBytes())   // one is not in the listing
    }

    @Test
    fun errorTextsAreReadable() {
        assertEquals("The rig is already working on something", opErrorText("busy"))
        assertEquals("It is already on that drive", opErrorText("same_drive"))
        assertEquals("Not done (weird)", opErrorText("weird"))
        assertEquals("The drive is in use by a copy or move", ejectErrorText(EkosEvent.FileEjectReply(error = "busy")))
    }
}
