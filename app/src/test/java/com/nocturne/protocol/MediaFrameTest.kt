package com.nocturne.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MediaFrame.parse] round-trip against literal 512-byte header buffers, one per real shape.
 * The full-shape fixture is a REAL live capture (`NocturneMedia` debug probe against the real
 * rig, 2026-08-23) — the docs' own field-type summary had two real mistakes ([bpp]/[size] typed
 * as numbers, `uuid` assumed `""` for a capture frame) that silently decode-failed every real
 * frame until caught this way; see [MediaHeader]'s own doc. The other shapes are hand-built
 * (reduced/minimal frames weren't captured live this pass) but corrected to match the same two
 * fixes.
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
    fun `parses a real live-captured full header shape`() {
        // live capture — real primary-camera preview frame, 10.0.0.43:9000 (compact, no inline
        // whitespace — the real wire JSON is single-line; a multi-line Kotlin literal here would
        // add newline/indent bytes that don't exist on the wire and blow past the 512-byte cap)
        val json = "{\"aperture\":\"51\",\"bin\":\"1x1\",\"bpp\":\"16\",\"channels\":1,\"exposure\":\"5\",\"ext\":\"jpg\"," +
            "\"focal_length\":\"250\",\"gain\":\"100\",\"hasWCS\":false,\"hfr\":-1,\"highlights\":1,\"max\":65535," +
            "\"mean\":53.827204703235324,\"median\":53,\"midtones\":0.0005698748864233494,\"min\":0," +
            "\"pixel_size\":\"3.7600\",\"resolution\":\"6224x4168\",\"shadows\":0.0006186853279359639," +
            "\"size\":\"49.5 MiB\",\"stddev\":32.54686451138557,\"uuid\":\"5d7fe6d1d36a4378a4238bad2d054535\"," +
            "\"view\":\"a993e512174149e9859e7c80a264cec6\"}"
        val frame = MediaFrame.parse(frameBytes(json))!!
        assertEquals("6224x4168", frame.header.resolution)
        assertEquals("49.5 MiB", frame.header.size)
        assertEquals("16", frame.header.bpp)
        assertEquals("250", frame.header.focalLength)
        assertEquals("3.7600", frame.header.pixelSize)
        assertEquals(-1.0, frame.header.hfr)
        // A real per-image identifier, not "" — MediaFrameType.CAPTURE is the default case, not
        // gated on an empty uuid (see MediaHeader.frameType's own doc).
        assertEquals(MediaFrameType.CAPTURE, frame.header.frameType)
        assertEquals(3, frame.jpeg.size)
        assertEquals(0x11.toByte(), frame.jpeg[0])
    }

    @Test
    fun `parses reduced header shape (align fast-preview)`() {
        val json = """{"resolution":"640x480","size":"50.0 KiB","channels":1,"mean":1200.0,"median":1190.0,
            "stddev":30.0,"bin":"2x2","bpp":"16","uuid":"+A","exposure":"5","focal_length":"550",
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
    fun `focus, guide and dark uuid tags map to their own frame types`() {
        assertEquals(MediaFrameType.FOCUS, MediaFrame.parse(frameBytes("""{"resolution":"1x1","uuid":"+F"}"""))!!.header.frameType)
        assertEquals(MediaFrameType.GUIDE, MediaFrame.parse(frameBytes("""{"resolution":"1x1","uuid":"+G"}"""))!!.header.frameType)
        assertEquals(MediaFrameType.DARK, MediaFrame.parse(frameBytes("""{"resolution":"1x1","uuid":"+D"}"""))!!.header.frameType)
    }

    @Test
    fun `sky-lookup uuid tags map to OTHER, not CAPTURE`() {
        assertEquals(MediaFrameType.OTHER, MediaFrame.parse(frameBytes("""{"resolution":"1x1","uuid":"skypoint_hips"}"""))!!.header.frameType)
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
