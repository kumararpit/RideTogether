package com.example

import com.example.data.offline.crypto.generate
import com.example.data.offline.mesh.MeshRouter
import com.example.data.offline.mesh.SosMeshRelay
import com.example.data.offline.mesh.SosMeshRelay.ParsedSosMessage
import com.example.data.offline.model.OfflineMessageType
import com.example.data.offline.protocol.OfflinePacket
import com.example.data.offline.protocol.PeerId
import com.example.data.model.LatLng
import com.example.data.model.SosEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SosMeshRelayTest {

    private lateinit var meshRouter: FakeMeshRouter
    private lateinit var relay: SosMeshRelay
    private val localIdentity = generate()

    @Before
    fun setUp() {
        meshRouter = FakeMeshRouter(localIdentity)
        relay = SosMeshRelay(meshRouter, localIdentity.peerId, "Alice")
    }

    @Test
    fun sendSosCreatesBroadcastPacketWithHighTtl() {
        val event = SosEvent(
            id = "sos_1",
            riderId = "user_bob",
            riderName = "Bob",
            location = LatLng(18.5204, 73.8567),
            timestampMs = 1_700_000_000_000L,
            accuracyMeters = 5.0f,
            locationSource = "FRESH_LOCATION_FIX",
        )
        relay.sendSos(event)

        assertEquals(1, meshRouter.sentPackets.size)
        val packet = meshRouter.sentPackets[0]
        assertEquals(OfflineMessageType.SOS, packet.msgType)
        assertEquals(7, packet.ttl)
        assertTrue(packet.isBroadcast())
        assertEquals(localIdentity.peerId, packet.senderId)
        assertEquals(PeerId.BROADCAST, packet.recipientId)
        assertEquals(1_700_000_000_000L, packet.timestampMs)
    }

    @Test
    fun sendSosPayloadContainsAllFields() {
        val event = SosEvent(
            id = "sos_1",
            riderId = "user_bob",
            riderName = "Bob",
            location = LatLng(18.5204, 73.8567),
            timestampMs = 1_700_000_000_000L,
            accuracyMeters = 5.0f,
            locationSource = "FRESH_LOCATION_FIX",
        )
        relay.sendSos(event)

        val payload = String(meshRouter.sentPackets[0].payload, java.nio.charset.StandardCharsets.UTF_8)
        assertTrue(payload.contains("a=sos"))
        assertTrue(payload.contains("id=sos_1"))
        assertTrue(payload.contains("r=user_bob"))
        assertTrue(payload.contains("n=Bob"))
        assertTrue(payload.contains("la=18.5204"))
        assertTrue(payload.contains("lo=73.8567"))
        assertTrue(payload.contains("t=1700000000000"))
        assertTrue(payload.contains("acc=5.0"))
        assertTrue(payload.contains("src=FRESH_LOCATION_FIX"))
    }

    @Test
    fun sendSosAckCreatesCustomPacket() {
        relay.sendSosAck("sos_1", "user_alice")

        assertEquals(1, meshRouter.sentPackets.size)
        val packet = meshRouter.sentPackets[0]
        assertEquals(OfflineMessageType.CUSTOM, packet.msgType)
        assertEquals(7, packet.ttl)
        assertTrue(packet.isBroadcast())

        val payload = String(packet.payload, java.nio.charset.StandardCharsets.UTF_8)
        assertTrue(payload.contains("a=ack"))
        assertTrue(payload.contains("id=sos_1"))
        assertTrue(payload.contains("acker=user_alice"))
    }

    @Test
    fun sendSosResolveCreatesCustomPacket() {
        relay.sendSosResolve("sos_1")

        assertEquals(1, meshRouter.sentPackets.size)
        val packet = meshRouter.sentPackets[0]
        assertEquals(OfflineMessageType.CUSTOM, packet.msgType)
        assertEquals(7, packet.ttl)
        assertTrue(packet.isBroadcast())

        val payload = String(packet.payload, java.nio.charset.StandardCharsets.UTF_8)
        assertTrue(payload.contains("a=resolve"))
        assertTrue(payload.contains("id=sos_1"))
    }

    @Test
    fun parseIncomingSosAlertReturnsParsedEvent() {
        val event = SosEvent(
            id = "sos_1",
            riderId = "user_bob",
            riderName = "Bob",
            location = LatLng(18.5204, 73.8567),
            timestampMs = 1_700_000_000_000L,
            accuracyMeters = 5.0f,
            locationSource = "FRESH_LOCATION_FIX",
        )
        relay.sendSos(event)

        val sentPacket = meshRouter.sentPackets[0]
        val fromPeer = generatePeer("user_bob")
        val parsed = relay.parseIncomingPacket(sentPacket, fromPeer)

        assertTrue(parsed is ParsedSosMessage.SosAlert)
        val alert = (parsed as ParsedSosMessage.SosAlert).event
        assertEquals("sos_1", alert.id)
        assertEquals("user_bob", alert.riderId)
        assertEquals("Bob", alert.riderName)
        assertEquals(18.5204, alert.location.latitude, 0.0001)
        assertEquals(73.8567, alert.location.longitude, 0.0001)
        assertEquals(1_700_000_000_000L, alert.timestampMs)
        assertEquals(5.0f, alert.accuracyMeters, 0.001f)
        assertEquals("FRESH_LOCATION_FIX", alert.locationSource)
    }

    @Test
    fun parseIncomingSosAlertFromUnknownSenderUsesPacketSenderId() {
        val event = SosEvent(
            id = "sos_1",
            riderId = "user_bob",
            riderName = "Bob",
            location = LatLng(0.0, 0.0),
            timestampMs = 1_700_000_000_000L,
        )
        relay.sendSos(event)

        val sentPacket = meshRouter.sentPackets[0]
        val fromPeer = generatePeer("user_alice") // different from packet sender
        val parsed = relay.parseIncomingPacket(sentPacket, fromPeer)

        assertTrue(parsed is ParsedSosMessage.SosAlert)
        // The riderId in the payload takes precedence over the "from" peer
        assertEquals("user_bob", (parsed as ParsedSosMessage.SosAlert).event.riderId)
    }

    @Test
    fun parseIncomingAckReturnsParsedAck() {
        val payload = "a=ack;id=sos_1;acker=user_alice".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.CUSTOM,
            ttl = 5,
            timestampMs = 1_700_000_000_000L,
            senderId = PeerId.BROADCAST,
            recipientId = PeerId.BROADCAST,
            payload = payload,
        )
        val fromPeer = generatePeer("user_alice")

        val parsed = relay.parseIncomingPacket(packet, fromPeer)

        assertTrue(parsed is ParsedSosMessage.SosAck)
        val ack = parsed as ParsedSosMessage.SosAck
        assertEquals("sos_1", ack.sosId)
        assertEquals("user_alice", ack.ackerId)
    }

    @Test
    fun parseIncomingResolveReturnsParsedResolve() {
        val payload = "a=resolve;id=sos_1".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.CUSTOM,
            ttl = 5,
            timestampMs = 1_700_000_000_000L,
            senderId = PeerId.BROADCAST,
            recipientId = PeerId.BROADCAST,
            payload = payload,
        )
        val fromPeer = generatePeer("user_alice")

        val parsed = relay.parseIncomingPacket(packet, fromPeer)

        assertTrue(parsed is ParsedSosMessage.SosResolve)
        val resolve = parsed as ParsedSosMessage.SosResolve
        assertEquals("sos_1", resolve.sosId)
    }

    @Test
    fun parseIncomingMalformedPayloadReturnsNull() {
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.SOS,
            ttl = 5,
            timestampMs = 1_700_000_000_000L,
            senderId = PeerId.BROADCAST,
            recipientId = PeerId.BROADCAST,
            payload = "not-valid-payload".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
        )
        val fromPeer = generatePeer("user_alice")

        assertNull(relay.parseIncomingPacket(packet, fromPeer))
    }

    @Test
    fun parseIncomingNonSosPacketReturnsNull() {
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.QUICK_MESSAGE,
            ttl = 5,
            timestampMs = 1_700_000_000_000L,
            senderId = PeerId.BROADCAST,
            recipientId = PeerId.BROADCAST,
            payload = byteArrayOf(1, 2, 3),
        )
        val fromPeer = generatePeer("user_alice")

        assertNull(relay.parseIncomingPacket(packet, fromPeer))
    }

    @Test
    fun parseIncomingSosMissingIdReturnsNull() {
        val payload = "a=sos;r=user_bob;n=Bob".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.SOS,
            ttl = 5,
            timestampMs = 1_700_000_000_000L,
            senderId = PeerId.BROADCAST,
            recipientId = PeerId.BROADCAST,
            payload = payload,
        )
        val fromPeer = generatePeer("user_alice")

        assertNull(relay.parseIncomingPacket(packet, fromPeer))
    }

    // ---- Test doubles ----

    private fun generatePeer(riderId: String): com.example.data.offline.crypto.PeerIdentity {
        val id = generate()
        return com.example.data.offline.crypto.PeerIdentity(
            peerId = id.peerId,
            x25519PublicKey = id.x25519PublicKey,
            ed25519PublicKey = id.ed25519PublicKey,
            firstSeenMs = 1000,
        )
    }

    private class FakeMeshRouter(localIdentity: com.example.data.offline.crypto.LocalIdentity) :
        MeshRouter(
            localIdentity = localIdentity,
            transport = object : com.example.data.offline.ble.BleTransport {
                override fun startAdvertising(
                    identity: com.example.data.offline.crypto.LocalIdentity,
                    callback: com.example.data.offline.ble.BleAdvertisingCallback,
                ) {}
                override fun stopAdvertising() {}
                override fun startScanning(callback: com.example.data.offline.ble.BleScanCallback) {}
                override fun stopScanning() {}
                override fun connect(peer: com.example.data.offline.crypto.PeerIdentity): com.example.data.offline.ble.BleConnection? = null
                override fun shutdown() {}
            },
            packetHandler = { _, _ -> }
        ) {
        val sentPackets = mutableListOf<OfflinePacket>()
        override fun sendPacket(packet: OfflinePacket) {
            sentPackets.add(packet)
        }
    }
}
