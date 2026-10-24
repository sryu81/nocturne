package com.nocturne.session

import com.nocturne.protocol.WireProperty
import com.nocturne.protocol.WireSwitchElement
import org.junit.Assert.assertEquals
import org.junit.Test

class PropertyMergeTest {

    @Test
    fun `a compact switch push keeps the option labels from the full fetch`() {
        val full = WireProperty.Switch(
            device = "LX200 OnStep", name = "TELESCOPE_SLEW_RATE", state = 1,
            switches = listOf(
                WireSwitchElement("5", 0, "Max/4"),
                WireSwitchElement("6", 1, "Half-Max"),
                WireSwitchElement("7", 0, "Max"),
            ),
            label = "Slew Rate",
        ).toIndiProperty(null)

        // live pushes carry no labels
        val compact = WireProperty.Switch(
            device = "LX200 OnStep", name = "TELESCOPE_SLEW_RATE", state = 1,
            switches = listOf(WireSwitchElement("5", 0), WireSwitchElement("6", 0), WireSwitchElement("7", 1)),
        ).toIndiProperty(full) as IndiProperty.SwitchProp

        assertEquals(listOf("Max/4", "Half-Max", "Max"), compact.options)
        assertEquals(2, compact.selected)
        assertEquals("Slew Rate", compact.label)
    }
}
