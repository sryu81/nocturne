package com.nocturne.data

import com.nocturne.protocol.MediaFrame
import com.nocturne.protocol.MediaHeader
import org.junit.Assert.assertEquals
import org.junit.Test

/** [FrameEntity.from] maps a real [MediaFrame]'s header fields verbatim — pure mapping, no Room instance needed. */
class FrameEntityTest {

    @Test
    fun `maps a full-shape capture frame's real header fields`() {
        val header = MediaHeader(
            resolution = "1280x960", hfr = 2.34, mean = 1500.5, median = 1490.0, stddev = 45.2,
            exposure = "30", gain = "100", bin = "1x1", uuid = "",
        )
        val frame = MediaFrame(header, byteArrayOf(1, 2, 3))
        val entity = FrameEntity.from(frame, timestampMs = 1_000L)

        assertEquals(0L, entity.id) // Room assigns this on insert — default until then.
        assertEquals(1_000L, entity.timestampMs)
        assertEquals(2.34, entity.hfr)
        assertEquals(1500.5, entity.mean)
        assertEquals(1490.0, entity.median)
        assertEquals(45.2, entity.stddev)
        assertEquals("30", entity.exposure)
        assertEquals("100", entity.gain)
        assertEquals("1x1", entity.bin)
        assertEquals("1280x960", entity.resolution)
        assertEquals(true, entity.keep)
        assertEquals(3, entity.jpeg.size)
    }

    @Test
    fun `missing header fields map to null, not fabricated defaults`() {
        val header = MediaHeader(resolution = "640x480", uuid = "")
        val entity = FrameEntity.from(MediaFrame(header, byteArrayOf()), timestampMs = 0L)

        assertEquals(null, entity.hfr)
        assertEquals(null, entity.mean)
        assertEquals(null, entity.exposure)
        assertEquals(null, entity.gain)
    }
}
