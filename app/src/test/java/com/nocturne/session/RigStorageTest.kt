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
}
