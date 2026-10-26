package com.example

import com.example.data.offline.crypto.generate
import com.example.data.offline.mesh.MeshRouter
import com.example.data.offline.mesh.RiderStatusMeshRelay
import com.example.data.offline.model.OfflineMessageType
import com.example.data.offline.protocol.OfflinePacket
import com.example.data.offline.protocol.PeerId
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RiderStatusMeshRelayTest {

    private lateinit var meshRouter: FakeMeshRouter
    private lateinit var relay: RiderStatusMeshRelay
    private val localIdentity = generate()

    @Before
    fun setUp() {
        meshRouter = FakeMeshRouter(localIdentity)
        relay = RiderStatusMeshRelay(
            meshRouter,
            localIdentity.peerId,
            "user_bob",
            "Bob",
        )
    }

    @Test
    fun sendRiderStatusCreatesBroadcastPacketWithModerateTtl() {
        relay.sendRiderStatusUpdate(
            status = MemberStatus.RIDING,
            location = LatLng(18.5204, 73.8567),
            speedKmh = 45.0,
            headingDeg = 290f,
            accuracyMeters = 5.0f,
            timestampMs = 1_700_000_000_000L,
            sequenceNumber = 7,
        )

        assertEquals(1, meshRouter.sentPackets.size)
        val packet = meshRouter.sentPackets[0]
        assertEquals(OfflineMessageType.LOCATION_UPDATE, packet.msgType)
        assertEquals(3, packet.ttl)
        assertTrue(packet.isBroadcast())
        assertEquals(localIdentity.peerId, packet.senderId)
        assertEquals(PeerId.BROADCAST, packet.recipientId)
        assertEquals(1_700_000_000_000L, packet.timestampMs)
    }

    @Test
    fun sendRiderStatusPayloadContainsAllFields() {
        relay.sendRiderStatusUpdate(
            status = MemberStatus.STOPPED,
            location = LatLng(18.5204, 73.8567),
            speedKmh = 0.0,
            headingDeg = null,
            accuracyMeters = 8.0f,
            timestampMs = 1_700_000_000_000L,
            sequenceNumber = 3,
        )

        val payload = String(meshRouter.sentPackets[0].payload, java.nio.charset.StandardCharsets.UTF_8)
        assertTrue(payload.contains("a=status"))
        assertTrue(payload.contains("r=user_bob"))
        assertTrue(payload.contains("n=Bob"))
        assertTrue(payload.contains("s=STOPPED"))
        assertTrue(payload.contains("la=18.5204"))
        assertTrue(payload.contains("lo=73.8567"))
        assertTrue(payload.contains("sp=0.0"))
        assertTrue(payload.contains("hd=-1.0"))
        assertTrue(payload.contains("acc=8.0"))
        assertTrue(payload.contains("t=1700000000000"))
        assertTrue(payload.contains("seq=3"))
    }

    @Test
    fun sendRiderStatusWithHeadingIncludesHeadingField() {
        relay.sendRiderStatusUpdate(
            status = MemberStatus.RIDING,
            location = LatLng(0.0, 0.0),
            speedKmh = 60.0,
            headingDeg = 180.0f,
            accuracyMeters = 3.0f,
            timestampMs = 1_700_000_000_000L,
            sequenceNumber = 1,
        )

        val payload = String(meshRouter.sentPackets[0].payload, java.nio.charset.StandardCharsets.UTF_8)
        assertTrue(payload.contains("hd=180.0"))
    }

    @Test
    fun parseIncomingRiderStatusReturnsParsedStatus() {
        relay.sendRiderStatusUpdate(
            status = MemberStatus.RIDING,
            location = LatLng(18.5204, 73.8567),
            speedKmh = 45.0,
            headingDeg = 290f,
            accuracyMeters = 5.0f,
            timestampMs = 1_700_000_000_000L,
            sequenceNumber = 7,
        )

        val sentPacket = meshRouter.sentPackets[0]
        val fromPeer = generatePeer("user_bob")
        val parsed = relay.parseIncomingPacket(sentPacket, fromPeer)

        assertTrue(parsed is RiderStatusMeshRelay.ParsedRiderStatus)
        val status = parsed as RiderStatusMeshRelay.ParsedRiderStatus
        assertEquals("user_bob", status.riderId)
        assertEquals("Bob", status.riderName)
        assertEquals(MemberStatus.RIDING, status.status)
        assertEquals(18.5204, status.location.latitude, 0.0001)
        assertEquals(73.8567, status.location.longitude, 0.0001)
        assertEquals(45.0, status.speedKmh, 0.001)
        assertEquals(290f, status.headingDeg!!, 0.001f)
        assertEquals(5.0f, status.accuracyMeters, 0.001f)
        assertEquals(1_700_000_000_000L, status.timestampMs)
        assertEquals(7L, status.sequenceNumber)
    }

    @Test
    fun parseIncomingRiderStatusWithoutHeadingHasNullHeading() {
        val payload = "a=status;r=user_alice;n=Alice;s=STOPPED;la=10.0;lo=20.0;sp=0.0;hd=-1.0;acc=10.0;t=1700000000000;seq=1".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.LOCATION_UPDATE,
            ttl = 3,
            timestampMs = 1_700_000_000_000L,
            senderId = PeerId.BROADCAST,
            recipientId = PeerId.BROADCAST,
            payload = payload,
        )
        val fromPeer = generatePeer("user_alice")

        val parsed = relay.parseIncomingPacket(packet, fromPeer)

        assertTrue(parsed is RiderStatusMeshRelay.ParsedRiderStatus)
        val status = parsed as RiderStatusMeshRelay.ParsedRiderStatus
        assertNull(status.headingDeg)
    }

    @Test
    fun parseIncomingNonLocationUpdateReturnsNull() {
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.SOS,
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
    fun parseIncomingMalformedPayloadReturnsNull() {
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.LOCATION_UPDATE,
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
    fun parseIncomingRiderStatusMissingRiderIdReturnsNull() {
        val payload = "a=status;n=Bob;s=RIDING;la=18.5204;lo=73.8567".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.LOCATION_UPDATE,
            ttl = 5,
            timestampMs = 1_700_000_000_000L,
            senderId = PeerId.BROADCAST,
            recipientId = PeerId.BROADCAST,
            payload = payload,
        )
        val fromPeer = generatePeer("user_alice")

        assertNull(relay.parseIncomingPacket(packet, fromPeer))
    }

    @Test
    fun parseIncomingRiderStatusMissingStatusReturnsNull() {
        val payload = "a=status;r=user_bob;n=Bob;la=18.5204;lo=73.8567".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.LOCATION_UPDATE,
            ttl = 5,
            timestampMs = 1_700_000_000_000L,
            senderId = PeerId.BROADCAST,
            recipientId = PeerId.BROADCAST,
            payload = payload,
        )
        val fromPeer = generatePeer("user_alice")

        assertNull(relay.parseIncomingPacket(packet, fromPeer))
    }

    @Test
    fun parseIncomingRiderStatusMissingCoordinatesReturnsNull() {
        val payload = "a=status;r=user_bob;n=Bob;s=RIDING".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.LOCATION_UPDATE,
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
