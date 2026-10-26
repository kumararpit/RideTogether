package com.example

import com.example.data.offline.ble.BleAdvertisement
import com.example.data.offline.crypto.generate
import com.example.data.offline.protocol.PeerId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAdvertisementTest {

    private val peerId = PeerId.fromPublicKey("advertisement_test_key".toByteArray())

    @Test
    fun encodeDecodeRoundTrip() {
        val encoded = BleAdvertisement.encode(
            peerId = peerId,
            flags = BleAdvertisement.Flags.CONNECTABLE or BleAdvertisement.Flags.SUPPORTS_ENCRYPTED,
            deviceName = "RiderOne",
        )

        val decoded = BleAdvertisement.decode(encoded)!!
        assertEquals(peerId, decoded.peerId)
        assertTrue(BleAdvertisement.isConnectable(decoded.flags))
        assertTrue(BleAdvertisement.supportsEncrypted(decoded.flags))
        assertEquals("RiderOne", decoded.deviceName)
    }

    @Test
    fun encodeWithoutName() {
        val encoded = BleAdvertisement.encode(peerId = peerId, flags = 0)
        val decoded = BleAdvertisement.decode(encoded)!!
        assertEquals(peerId, decoded.peerId)
        assertEquals(0, decoded.flags)
        assertNull(decoded.deviceName)
    }

    @Test
    fun invalidMagicReturnsNull() {
        val bad = byteArrayOf(0x00, 0x00, 0x01, 0x02, 0x03)
        assertNull(BleAdvertisement.decode(bad))
    }

    @Test
    fun invalidVersionReturnsNull() {
        val encoded = BleAdvertisement.encode(peerId = peerId, flags = 0)
        val tampered = encoded.copyOfRange(0, encoded.size)
        tampered[2] = 99 // wrong version
        assertNull(BleAdvertisement.decode(tampered))
    }

    @Test
    fun truncatedAdvertisementReturnsNull() {
        val encoded = BleAdvertisement.encode(peerId = peerId, flags = 0, deviceName = "Test")
        assertNull(BleAdvertisement.decode(encoded.copyOfRange(0, 10)))
    }

    @Test
    fun peerIdRoundTripThroughAdvertisement() {
        val identity = generate()
        val encoded = BleAdvertisement.encode(
            peerId = identity.peerId,
            flags = BleAdvertisement.Flags.CONNECTABLE,
        )
        val decoded = BleAdvertisement.decode(encoded)!!
        assertEquals(identity.peerId, decoded.peerId)
    }

    @Test
    fun connectableAndEncryptedFlagsAreIndependent() {
        val connectableOnly = BleAdvertisement.encode(peerId, flags = BleAdvertisement.Flags.CONNECTABLE)
        val decoded1 = BleAdvertisement.decode(connectableOnly)!!
        assertTrue(BleAdvertisement.isConnectable(decoded1.flags))
        assertFalse(BleAdvertisement.supportsEncrypted(decoded1.flags))

        val encryptedOnly = BleAdvertisement.encode(peerId, flags = BleAdvertisement.Flags.SUPPORTS_ENCRYPTED)
        val decoded2 = BleAdvertisement.decode(encryptedOnly)!!
        assertFalse(BleAdvertisement.isConnectable(decoded2.flags))
        assertTrue(BleAdvertisement.supportsEncrypted(decoded2.flags))

        val both = BleAdvertisement.encode(peerId, flags = 0xFF)
        val decoded3 = BleAdvertisement.decode(both)!!
        assertTrue(BleAdvertisement.isConnectable(decoded3.flags))
        assertTrue(BleAdvertisement.supportsEncrypted(decoded3.flags))
    }

    @Test
    fun advertisementSizeIsCorrect() {
        val withName = BleAdvertisement.encode(peerId, flags = 0, deviceName = "Hello")
        assertEquals(12 + 5, withName.size)

        val withoutName = BleAdvertisement.encode(peerId, flags = 0)
        assertEquals(12, withoutName.size)
    }
}
