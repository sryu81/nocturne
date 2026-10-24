package com.nocturne.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RigNetworkTest {

    private fun ip(s: String) = InetAddress.getByName(s).address

    @Test
    fun `phone on the rig's subnet is detected, other networks are not`() {
        assertTrue(RigNetwork.samePrefix(ip("192.168.8.49"), ip("192.168.8.40"), 24))
        assertFalse(RigNetwork.samePrefix(ip("10.0.0.180"), ip("192.168.8.40"), 24))
        assertFalse(RigNetwork.samePrefix(ip("192.168.9.49"), ip("192.168.8.40"), 24))
        assertTrue(RigNetwork.samePrefix(ip("192.168.9.49"), ip("192.168.8.40"), 22))
    }
}
