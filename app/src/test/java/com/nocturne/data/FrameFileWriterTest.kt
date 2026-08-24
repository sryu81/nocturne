package com.nocturne.data

import com.nocturne.protocol.MediaFrame
import com.nocturne.protocol.MediaHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FrameFileWriter]'s real folder templates (user-specified 2026-08-23, docs/M4.5-plan.md):
 * `Preview/<session-datetime>/Prev_00000.jpg` for test captures,
 * `Plan/<date>/<target>/<target>_<date>_<filter>_<exposure>sec_<temp>C_<seq>.jpg` for scheduler
 * ones. Uses a real [TemporaryFolder] as the base dir — [FrameFileWriter] takes a plain [java.io.File],
 * not a Context, specifically so this doesn't need Robolectric/instrumentation to test.
 */
class FrameFileWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun frame(exposure: String? = "300", hfr: Double = 2.3) =
        MediaFrame(MediaHeader(exposure = exposure, hfr = hfr, uuid = "abc123"), byteArrayOf(1, 2, 3))

    @Test
    fun `test captures land under Preview with a zero-padded 5-digit counter`() {
        val writer = FrameFileWriter(tmp.root)
        val f1 = writer.write(frame(), timestampMs = 0L, source = FrameSource.Test)
        val f2 = writer.write(frame(), timestampMs = 1_000L, source = FrameSource.Test)

        assertEquals("Prev_00000.jpg", f1.name)
        assertEquals("Prev_00001.jpg", f2.name)
        // Same session dir for both, even though timestamps differ — one dated folder per writer
        // instance (session), not per frame.
        assertEquals(f1.parentFile, f2.parentFile)
        assertTrue(f1.parentFile!!.path.contains("Preview"))
        assertTrue(f1.exists() && f2.exists())
    }

    @Test
    fun `plan captures use the real target-date-filter-exposure-temp-sequence template`() {
        val writer = FrameFileWriter(tmp.root)
        val source = FrameSource.Plan(target = "NGC 7000", filter = "Ha", temperatureC = -10.0)
        val f = writer.write(frame(exposure = "300"), timestampMs = 1_700_000_000_000L, source = source)

        // Spaces sanitized out of the target name for the real filesystem path.
        assertTrue(f.path.contains("Plan"))
        assertTrue(f.path.contains("NGC_7000"))
        assertTrue(f.name.startsWith("NGC_7000_"))
        assertTrue(f.name.contains("_Ha_"))
        assertTrue(f.name.contains("300sec"))
        assertTrue(f.name.contains("-10C"))
        assertTrue(f.name.endsWith("_1.jpg"))
    }

    @Test
    fun `plan sequence counter increments per target+filter+exposure, independently per combo`() {
        val writer = FrameFileWriter(tmp.root)
        val ha = FrameSource.Plan(target = "M31", filter = "Ha", temperatureC = -5.0)
        val oiii = FrameSource.Plan(target = "M31", filter = "OIII", temperatureC = -5.0)

        val ha1 = writer.write(frame(), 0L, ha)
        val ha2 = writer.write(frame(), 1L, ha)
        val o1 = writer.write(frame(), 2L, oiii)

        assertTrue(ha1.name.endsWith("_1.jpg"))
        assertTrue(ha2.name.endsWith("_2.jpg"))
        // A different filter is a different combo — starts back at 1, doesn't share Ha's counter.
        assertTrue(o1.name.endsWith("_1.jpg"))
    }

    @Test
    fun `missing filter or temperature falls back to an honest placeholder, not a fabricated value`() {
        val writer = FrameFileWriter(tmp.root)
        val source = FrameSource.Plan(target = "M42", filter = null, temperatureC = null)
        val f = writer.write(frame(), 0L, source)

        assertTrue(f.name.contains("NoFilter"))
        assertTrue(f.name.contains("unknownC"))
    }
}
