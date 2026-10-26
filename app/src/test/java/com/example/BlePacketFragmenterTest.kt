package com.example

import com.example.data.offline.ble.BlePacketFragmenter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePacketFragmenterTest {

    private val smallPacket = byteArrayOf(1, 2, 3, 4, 5)
    private val largePacket = ByteArray(200).apply { for (i in indices) this[i] = (i % 256).toByte() }

    @Test
    fun packetSmallerThanMtuIsSingleFragment() {
        val fragments = BlePacketFragmenter.fragment(smallPacket, 100)
        assertEquals(1, fragments.size)
        // Fragment = [index(2B)][total(2B)][payload...]
        val payload = fragments[0].copyOfRange(4, fragments[0].size)
        assertArrayEquals(smallPacket, payload)
    }

    @Test
    fun largePacketIsFragmented() {
        val mtu = 30 // payload size = 27 after ATT overhead
        val fragments = BlePacketFragmenter.fragment(largePacket, mtu)
        val maxPayload = mtu - 3

        assertTrue(fragments.size > 1)
        assertTrue(fragments.all { it.size <= mtu })

        // Reassemble
        val reassembled = BlePacketFragmenter.reassemble(fragments)
        assertArrayEquals(largePacket, reassembled!!)
    }

    @Test
    fun fragmentContainsIndexAndTotal() {
        val fragments = BlePacketFragmenter.fragment(largePacket, 30)
        assertTrue(fragments.size > 1)

        val first = fragments[0]
        val index = (first[0].toInt() and 0xFF) shl 8 or (first[1].toInt() and 0xFF)
        val total = (first[2].toInt() and 0xFF) shl 8 or (first[3].toInt() and 0xFF)
        assertEquals(0, index)
        assertEquals(fragments.size, total)

        val last = fragments.last()
        val lastIndex = (last[0].toInt() and 0xFF) shl 8 or (last[1].toInt() and 0xFF)
        assertEquals(fragments.size - 1, lastIndex)
    }

    @Test
    fun reassembleEmptyReturnsNull() {
        assertNull(BlePacketFragmenter.reassemble(emptyList()))
    }

    @Test
    fun reassembleInconsistentTotalsReturnsNull() {
        val fragments = BlePacketFragmenter.fragment(largePacket, 30)
        val inconsistent = fragments.first().copyOfRange(0, fragments.first().size)
        // Corrupt the totalFragments field (bytes 2-3)
        inconsistent[2] = 0xFF.toByte()
        inconsistent[3] = 0xFF.toByte()
        assertNull(BlePacketFragmenter.reassemble(listOf(inconsistent, fragments.last())))
    }

    @Test
    fun reassembleMissingFragmentReturnsNull() {
        val fragments = BlePacketFragmenter.fragment(largePacket, 30)
        assertNull(BlePacketFragmenter.reassemble(fragments.drop(1))) // missing fragment 0
    }

    @Test
    fun exactMtuFit() {
        val mtu = 30
        val payloadSize = mtu - 7 // ATT_OVERHEAD(3) + FRAGMENT_HEADER_SIZE(4)
        val packet = ByteArray(payloadSize * 2) // exactly 2 fragments
        val fragments = BlePacketFragmenter.fragment(packet, mtu)
        assertEquals(2, fragments.size)
        assertArrayEquals(packet, BlePacketFragmenter.reassemble(fragments)!!)
    }

    // Helper: encodeFragment is private, so we test via fragment() instead.
    // This test validates that fragment() produces well-formed fragments.
    @Test
    fun fragmentPayloadDoesNotExceedMtu() {
        val mtu = 50
        val fragments = BlePacketFragmenter.fragment(largePacket, mtu)
        assertTrue(fragments.all { it.size <= mtu })
    }
}
