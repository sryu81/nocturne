package com.nocturne.session

import com.nocturne.protocol.WireDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstalledDriverOptionsTest {

    private fun drv(label: String, family: String, maker: String = "") =
        WireDriver(name = label, label = label, family = family, manufacturer = maker)

    @Test
    fun `null until the catalog arrives`() {
        assertNull(AppState().installedDriverOptions("mount"))
    }

    @Test
    fun `filters by the role's family, one per label, sorted by maker then label, makerless last`() {
        val s = AppState(wireDriverCatalog = listOf(
            drv("ZWO CCD", "CCDs", "ZWO"),
            drv("LX200 OnStep", "Telescopes", "OnStep"),
            drv("Generic CCD", "CCDs"),
            drv("QHY CCD", "CCDs", "QHY"),
            drv("ASI CCD", "CCDs", "ZWO"),
            drv("QHY CCD", "CCDs", "QHY"),
        ))
        assertEquals(listOf("QHY CCD", "ASI CCD", "ZWO CCD", "Generic CCD"), s.installedDriverOptions("cam")!!.map { it.label })
        // the guide camera picks from the same CCDs family
        assertEquals(4, s.installedDriverOptions("guide")!!.size)
        assertEquals(listOf("LX200 OnStep"), s.installedDriverOptions("mount")!!.map { it.label })
        assertEquals(emptyList<WireDriver>(), s.installedDriverOptions("efw"))
    }
}
