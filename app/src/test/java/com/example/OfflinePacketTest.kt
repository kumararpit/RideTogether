package com.example

import com.example.data.offline.model.OfflineMessageType
import com.example.data.offline.protocol.OfflinePacket
import com.example.data.offline.protocol.PeerId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePacketTest {

    private val senderId = PeerId.fromPublicKey("sender_public_key_dummy_for_testing".toByteArray())
    private val recipientId = PeerId.fromPublicKey("recipient_public_key_dummy".toByteArray())

    @Test
    fun encodeDecodeRoundTrip() {
        val packet = OfflinePacket(
            msgType = OfflineMessageType.SOS,
            ackRequired = true,
            encrypted = false,
            broadcast = false,
            ttl = 3,
            timestampMs = 1700000000000L,
            senderId = senderId,
            recipientId = recipientId,
            payload = "hello mesh".toByteArray(),
            signature = ByteArray(0),
        )

        val encoded = packet.encode()
        val decoded = OfflinePacket.decode(encoded)

        assertNotNull(decoded)
        assertEquals(packet.version, decoded!!.version)
        assertEquals(packet.msgType, decoded.msgType)
        assertEquals(packet.ackRequired, decoded.ackRequired)
        assertEquals(packet.encrypted, decoded.encrypted)
        assertEquals(packet.broadcast, decoded.broadcast)
        assertEquals(packet.ttl, decoded.ttl)
        assertEquals(packet.timestampMs, decoded.timestampMs)
        assertArrayEquals(packet.senderId.bytes, decoded.senderId.bytes)
        assertArrayEquals(packet.recipientId.bytes, decoded.recipientId.bytes)
        assertArrayEquals(packet.payload, decoded.payload)
        assertArrayEquals(packet.signature, decoded.signature)
    }

    @Test
    fun broadcastPacketRoundTrip() {
        val packet = OfflinePacket(
            msgType = OfflineMessageType.LOCATION_UPDATE,
            broadcast = true,
            ttl = 5,
            timestampMs = 1700000001000L,
            senderId = senderId,
            recipientId = PeerId.BROADCAST,
            payload = byteArrayOf(1, 2, 3, 4),
        )

        val decoded = OfflinePacket.decode(packet.encode())!!
        assertTrue(decoded.isBroadcast())
        assertEquals(OfflineMessageType.LOCATION_UPDATE, decoded.msgType)
        assertEquals(5, decoded.ttl)
    }

    @Test
    fun invalidMagicReturnsNull() {
        val bad = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertNull(OfflinePacket.decode(bad))
    }

    @Test
    fun truncatedDataReturnsNull() {
        val packet = OfflinePacket(
            msgType = OfflineMessageType.QUICK_MESSAGE,
            ttl = 1,
            timestampMs = 12345L,
            senderId = senderId,
            recipientId = recipientId,
            payload = byteArrayOf(9),
        )
        val encoded = packet.encode()
        assertNull(OfflinePacket.decode(encoded.copyOfRange(0, OfflinePacket.HEADER_SIZE)))
    }

    @Test
    fun peerIdDerivationIsDeterministic() {
        val key = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32)
        val id1 = PeerId.fromPublicKey(key)
        val id2 = PeerId.fromPublicKey(key)
        assertArrayEquals(id1.bytes, id2.bytes)
        assertEquals(8, id1.bytes.size)
        assertEquals(16, id1.toHex().length)
    }

    @Test
    fun peerIdHexRoundTrip() {
        val id = PeerId.fromPublicKey("test_key_bytes".toByteArray())
        val roundTrip = PeerId.fromHex(id.toHex())
        assertArrayEquals(id.bytes, roundTrip.bytes)
    }

    @Test
    fun signedPayloadExcludesSignature() {
        val packet = OfflinePacket(
            msgType = OfflineMessageType.CUSTOM,
            ttl = 2,
            timestampMs = 99999L,
            senderId = senderId,
            recipientId = recipientId,
            payload = byteArrayOf(42),
        )
        val signed = packet.signedPayload()
        val encoded = packet.encode()
        assertEquals(OfflinePacket.HEADER_SIZE + 1, signed.size)
        assertEquals(signed.size + OfflinePacket.SIGNATURE_SIZE, encoded.size)
    }

    @Test
    fun maxPayloadSize() {
        val maxPayload = ByteArray(OfflinePacket.MAX_PAYLOAD_SIZE)
        val packet = OfflinePacket(
            msgType = OfflineMessageType.CUSTOM,
            ttl = 1,
            timestampMs = 1L,
            senderId = senderId,
            recipientId = recipientId,
            payload = maxPayload,
        )
        val decoded = OfflinePacket.decode(packet.encode())!!
        assertArrayEquals(maxPayload, decoded.payload)
    }

    @Test
    fun emptyPayloadRoundTrip() {
        val packet = OfflinePacket(
            msgType = OfflineMessageType.SOS,
            ttl = 1,
            timestampMs = 0L,
            senderId = senderId,
            recipientId = recipientId,
            payload = ByteArray(0),
        )
        val decoded = OfflinePacket.decode(packet.encode())!!
        assertEquals(0, decoded.payload.size)
        assertEquals(OfflineMessageType.SOS, decoded.msgType)
    }
}
