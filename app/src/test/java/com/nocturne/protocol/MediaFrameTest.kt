package com.nocturne.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MediaFrame.parse] round-trip against literal 512-byte header buffers, one
 * per real shape (docs/M4-plan.md "Media channel"). Fixtures are hand-built
 * (not a live capture, unlike [EkosEventCodecTest]'s precedent) — binary
 * frames aren't practical to paste inline; field values match the confirmed
 * real shapes byte-for-byte, just not pulled from an actual socket capture.
 */
class MediaFrameTest {

    /** Null-pads [json] out to 512 bytes, then appends [jpeg] — the real on-wire layout. */
    private fun frameBytes(json: String, jpeg: ByteArray = byteArrayOf(0x11, 0x22, 0x33)): ByteArray {
        val header = ByteArray(512)
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        jsonBytes.copyInto(header)
        return header + jpeg
    }

    @Test
    fun `parses full header shape`() {
        val json = """{"resolution":"1280x960","size":123456,"channels":1,"mean":1500.5,"median":1490.0,
            "stddev":45.2,"min":100.0,"max":60000.0,"bin":"1x1","bpp":16,"uuid":"","exposure":"30",
            "focal_length":"550","aperture":"80","gain":"100","pixel_size":"3.7600","shadows":0.1,
            "midtones":0.5,"highlights":0.95,"hasWCS":false,"hfr":2.34,"view":"normal","ext":"jpg"}"""
        val frame = MediaFrame.parse(frameBytes(json))!!
        assertEquals("1280x960", frame.header.resolution)
        assertEquals(2.34, frame.header.hfr)
        assertEquals("550", frame.header.focalLength)
        assertEquals("3.7600", frame.header.pixelSize)
        assertEquals(MediaFrameType.CAPTURE, frame.header.frameType)
        assertEquals(3, frame.jpeg.size)
        assertEquals(0x11.toByte(), frame.jpeg[0])
    }

    @Test
    fun `parses reduced header shape (align fast-preview)`() {
        val json = """{"resolution":"640x480","size":50000,"channels":1,"mean":1200.0,"median":1190.0,
            "stddev":30.0,"bin":"2x2","bpp":16,"uuid":"+A","exposure":"5","focal_length":"550",
            "aperture":"80","gain":"100","pixel_size":"3.7600"}"""
        val frame = MediaFrame.parse(frameBytes(json))!!
        assertEquals(MediaFrameType.ALIGN, frame.header.frameType)
        assertNull(frame.header.hfr)
        assertNull(frame.header.hasWCS)
    }

    @Test
    fun `parses minimal header shape (live video, no uuid at all)`() {
        val json = """{"resolution":"320x240","ext":"jpg"}"""
        val frame = MediaFrame.parse(frameBytes(json))!!
        assertEquals("320x240", frame.header.resolution)
        assertNull(frame.header.uuid)
        assertEquals(MediaFrameType.LIVE_VIDEO, frame.header.frameType)
    }

    @Test
    fun `focus and guide uuid tags map to their own frame types`() {
        assertEquals(MediaFrameType.FOCUS, MediaFrame.parse(frameBytes("""{"resolution":"1x1","uuid":"+F"}"""))!!.header.frameType)
        assertEquals(MediaFrameType.GUIDE, MediaFrame.parse(frameBytes("""{"resolution":"1x1","uuid":"+G"}"""))!!.header.frameType)
        assertEquals(MediaFrameType.DARK, MediaFrame.parse(frameBytes("""{"resolution":"1x1","uuid":"+D"}"""))!!.header.frameType)
        assertEquals(MediaFrameType.OTHER, MediaFrame.parse(frameBytes("""{"resolution":"1x1","uuid":"hips_abc123"}"""))!!.header.frameType)
    }

    @Test
    fun `returns null for a buffer shorter than the 512-byte metadata packet`() {
        assertNull(MediaFrame.parse(ByteArray(100)))
    }

    @Test
    fun `returns null when the header text isn't valid JSON`() {
        assertNull(MediaFrame.parse(frameBytes("not json at all")))
    }
}
