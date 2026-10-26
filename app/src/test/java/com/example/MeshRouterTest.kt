package com.example

import com.example.data.offline.ble.BleAdvertisingCallback
import com.example.data.offline.ble.BleConnection
import com.example.data.offline.ble.BleScanCallback
import com.example.data.offline.ble.BleTransport
import com.example.data.offline.crypto.LocalIdentity
import com.example.data.offline.crypto.PeerIdentity
import com.example.data.offline.crypto.generate
import com.example.data.offline.mesh.MeshRouter
import com.example.data.offline.model.OfflineMessageType
import com.example.data.offline.protocol.OfflinePacket
import com.example.data.offline.protocol.PeerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshRouterTest {

    private lateinit var localIdentity: LocalIdentity
    private lateinit var router: MeshRouter
    private lateinit var transport: MockBleTransport
    private val receivedPackets = mutableListOf<Pair<OfflinePacket, PeerIdentity>>()

    private fun makePeer(): PeerIdentity {
        val id = generate()
        return PeerIdentity(
            peerId = id.peerId,
            x25519PublicKey = id.x25519PublicKey,
            ed25519PublicKey = id.ed25519PublicKey,
            firstSeenMs = 1000,
        )
    }

    private fun makePacket(
        recipient: PeerId = PeerId.BROADCAST,
        ttl: Int = 5,
        sender: PeerId = PeerId.BROADCAST,
        msgType: OfflineMessageType = OfflineMessageType.QUICK_MESSAGE,
    ): OfflinePacket {
        return OfflinePacket(
            version = 1,
            msgType = msgType,
            ttl = ttl,
            timestampMs = 1_700_000_000_000L,
            senderId = sender,
            recipientId = recipient,
            payload = byteArrayOf(1, 2, 3),
        )
    }

    @Before
    fun setUp() {
        localIdentity = generate()
        transport = MockBleTransport()
        receivedPackets.clear()
        router = MeshRouter(
            localIdentity = localIdentity,
            transport = transport,
            packetHandler = { packet, from -> receivedPackets.add(packet to from) },
        )
    }

    @Test
    fun sendPacketToConnectedPeerIsDelivered() {
        val peer = makePeer()
        val connection = MockBleConnection(peer)
        transport.connections[peer.peerId] = connection
        router.onPeerConnected(peer, connection)

        val packet = makePacket(recipient = peer.peerId, sender = localIdentity.peerId)
        router.sendPacket(packet)

        assertEquals(1, connection.sentPackets.size)
        assertEquals(packet, connection.sentPackets[0])
        assertEquals(0, receivedPackets.size)
    }

    @Test
    fun sendPacketToUnconnectedPeerIsQueued() {
        val unknownPeer = PeerId.fromHex("deadbeefdeadbeef")
        val packet = makePacket(recipient = unknownPeer, sender = localIdentity.peerId)
        router.sendPacket(packet)

        assertEquals(1, router.queueSize())
        // No connections exist, so nothing was sent.
        assertTrue(transport.connections.values.all { it.sentPackets.isEmpty() })
    }

    @Test
    fun peerConnectFlushesQueuedPackets() {
        val peer = makePeer()
        val connection = MockBleConnection(peer)
        transport.connections[peer.peerId] = connection

        val packet = makePacket(recipient = peer.peerId, sender = localIdentity.peerId)
        router.sendPacket(packet) // queued — peer not yet connected
        assertEquals(1, router.queueSize())

        router.onPeerConnected(peer, connection) // flushes queue
        assertEquals(0, router.queueSize())
        assertEquals(1, connection.sentPackets.size)
        assertEquals(packet, connection.sentPackets[0])
    }

    @Test
    fun packetToLocalPeerIsDeliveredToHandler() {
        val packet = makePacket(recipient = localIdentity.peerId, sender = localIdentity.peerId)
        router.sendPacket(packet)

        assertEquals(1, receivedPackets.size)
        assertEquals(packet, receivedPackets[0].first)
        assertEquals(localPeer(), receivedPackets[0].second)
    }

    @Test
    fun broadcastPacketFromRemoteIsDeliveredAndRelayed() {
        val peerA = makePeer()
        val peerB = makePeer()
        val connA = MockBleConnection(peerA)
        val connB = MockBleConnection(peerB)
        transport.connections[peerA.peerId] = connA
        transport.connections[peerB.peerId] = connB
        router.onPeerConnected(peerA, connA)
        router.onPeerConnected(peerB, connB)

        val packet = makePacket(recipient = PeerId.BROADCAST, sender = peerA.peerId, ttl = 3)
        router.handleIncomingPacket(packet, peerA)

        // Delivered locally
        assertEquals(1, receivedPackets.size)
        assertSame(packet, receivedPackets[0].first)

        // Relayed to peerB only (not back to peerA), with decremented TTL
        assertEquals(1, connB.sentPackets.size)
        assertEquals(2, connB.sentPackets[0].ttl)
        assertTrue(connA.sentPackets.isEmpty())
    }

    @Test
    fun broadcastPacketFromUpperLayerIsDeliveredAndSentToAll() {
        val peerA = makePeer()
        val peerB = makePeer()
        val connA = MockBleConnection(peerA)
        val connB = MockBleConnection(peerB)
        transport.connections[peerA.peerId] = connA
        transport.connections[peerB.peerId] = connB
        router.onPeerConnected(peerA, connA)
        router.onPeerConnected(peerB, connB)

        val packet = makePacket(recipient = PeerId.BROADCAST, sender = localIdentity.peerId)
        router.sendPacket(packet)

        // Delivered locally
        assertEquals(1, receivedPackets.size)
        assertEquals(localPeer(), receivedPackets[0].second)

        // Sent to both connected peers with original TTL (no decrement for originator's broadcast)
        assertEquals(1, connA.sentPackets.size)
        assertEquals(1, connB.sentPackets.size)
        assertEquals(5, connA.sentPackets[0].ttl)
        assertEquals(5, connB.sentPackets[0].ttl)
    }

    @Test
    fun duplicatePacketIsDropped() {
        val peer = makePeer()
        val connection = MockBleConnection(peer)
        transport.connections[peer.peerId] = connection
        router.onPeerConnected(peer, connection)

        val packet = makePacket(recipient = localIdentity.peerId, sender = peer.peerId)
        router.handleIncomingPacket(packet, peer)
        assertEquals(1, receivedPackets.size)

        // Same packet again — should be dropped
        router.handleIncomingPacket(packet, peer)
        assertEquals(1, receivedPackets.size)
    }

    @Test
    fun packetWithTtlZeroIsNotRelayed() {
        val peerA = makePeer()
        val peerB = makePeer()
        val connA = MockBleConnection(peerA)
        val connB = MockBleConnection(peerB)
        transport.connections[peerA.peerId] = connA
        transport.connections[peerB.peerId] = connB
        router.onPeerConnected(peerA, connA)
        router.onPeerConnected(peerB, connB)

        val packet = makePacket(recipient = PeerId.BROADCAST, sender = peerA.peerId, ttl = 1)
        router.handleIncomingPacket(packet, peerA)

        // TTL becomes 0 after relay — should not be forwarded further.
        // But we still relay once (from TTL 1 to TTL 0) to peerB.
        assertEquals(1, connB.sentPackets.size)
        assertEquals(0, connB.sentPackets[0].ttl)
    }

    @Test
    fun relayExcludesSenderPeer() {
        val peerA = makePeer()
        val peerB = makePeer()
        val connA = MockBleConnection(peerA)
        val connB = MockBleConnection(peerB)
        transport.connections[peerA.peerId] = connA
        transport.connections[peerB.peerId] = connB
        router.onPeerConnected(peerA, connA)
        router.onPeerConnected(peerB, connB)

        val packet = makePacket(recipient = PeerId.BROADCAST, sender = peerA.peerId, ttl = 5)
        router.handleIncomingPacket(packet, peerA)

        assertTrue(connA.sentPackets.isEmpty())
        assertEquals(1, connB.sentPackets.size)
    }

    @Test
    fun tickExpiresStaleQueuedPackets() {
        val unknownPeer = PeerId.fromHex("cafebabecafebabe")
        val oldPacket = makePacket(recipient = unknownPeer, sender = localIdentity.peerId)
        // Manually enqueue with an old timestamp
        router.sendPacket(oldPacket)
        assertEquals(1, router.queueSize())

        // Can't easily backdate enqueueTimeMs, but tick with 0 max age should clear everything
        router.tick(maxQueueAgeMs = 0)
        assertEquals(0, router.queueSize())
    }

    @Test
    fun connectedPeersReturnsConnectedPeerIds() {
        assertTrue(router.connectedPeers().isEmpty())

        val peer = makePeer()
        val connection = MockBleConnection(peer)
        transport.connections[peer.peerId] = connection
        router.onPeerConnected(peer, connection)

        assertEquals(setOf(peer.peerId), router.connectedPeers())

        router.onPeerDisconnected(peer.peerId)
        assertTrue(router.connectedPeers().isEmpty())
    }

    // ---- test doubles ----

    private fun localPeer(): PeerIdentity = PeerIdentity(
        peerId = localIdentity.peerId,
        x25519PublicKey = localIdentity.x25519PublicKey,
        ed25519PublicKey = localIdentity.ed25519PublicKey,
        firstSeenMs = localIdentity.createdAtMs,
    )

    private class MockBleConnection(
        override val peer: PeerIdentity,
    ) : BleConnection {
        override var isOpen: Boolean = true
        val sentPackets = mutableListOf<OfflinePacket>()
        override var onPacketReceived: ((OfflinePacket) -> Unit)? = null

        override fun sendPacket(packet: OfflinePacket) {
            sentPackets.add(packet)
        }

        fun simulateReceive(packet: OfflinePacket) {
            onPacketReceived?.invoke(packet)
        }

        override fun close() {
            isOpen = false
        }
    }

    private class MockBleTransport : BleTransport {
        val connections = mutableMapOf<PeerId, MockBleConnection>()

        override fun startAdvertising(
            identity: LocalIdentity,
            callback: BleAdvertisingCallback,
        ) {}

        override fun stopAdvertising() {}

        override fun startScanning(callback: BleScanCallback) {}

        override fun stopScanning() {}

        override fun connect(peer: PeerIdentity): BleConnection? {
            return connections[peer.peerId]
        }

        override fun shutdown() {}
    }
}
