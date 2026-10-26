package com.example

import com.example.data.db.OfflineMessageEntity
import com.example.data.firebase.FirestoreMessageGateway
import com.example.data.firebase.MeshFirestoreBridge
import com.example.data.model.QuickMessage
import com.example.data.model.QuickMessageType
import com.example.data.offline.crypto.generate
import com.example.data.offline.mesh.MeshRouter
import com.example.data.offline.protocol.OfflinePacket
import com.example.data.offline.protocol.PeerId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshFirestoreBridgeTest {

    private lateinit var bridge: MeshFirestoreBridge
    private lateinit var gateway: FakeGateway
    private lateinit var meshRouter: FakeMeshRouter

    @Before
    fun setUp() {
        gateway = FakeGateway()
        meshRouter = FakeMeshRouter()
        bridge = MeshFirestoreBridge(
            gateway = gateway,
            meshRouter = meshRouter,
            currentUserId = "user_alice",
            currentRideId = "ride_1",
        )
    }

    @Test
    fun registerMeshMessageIdNewMessageReturnsTrue() {
        assertTrue(bridge.registerMeshMessageId("msg_1"))
        assertTrue(bridge.isMessageKnown("msg_1"))
    }

    @Test
    fun registerMeshMessageIdDuplicateReturnsFalse() {
        assertTrue(bridge.registerMeshMessageId("msg_1"))
        assertFalse(bridge.registerMeshMessageId("msg_1"))
    }

    @Test
    fun registerFirestoreMessageIdNewMessageReturnsTrue() {
        assertTrue(bridge.registerFirestoreMessageId("msg_1"))
        assertTrue(bridge.isMessageKnown("msg_1"))
    }

    @Test
    fun messageSeenViaMeshIsNotDeliveredAgainViaFirestore() {
        assertTrue(bridge.registerMeshMessageId("msg_1"))
        assertFalse(bridge.registerFirestoreMessageId("msg_1"))
    }

    @Test
    fun messageSeenViaFirestoreIsNotDeliveredAgainViaMesh() {
        assertTrue(bridge.registerFirestoreMessageId("msg_1"))
        assertFalse(bridge.registerMeshMessageId("msg_1"))
    }

    @Test
    fun flushQueueToFirestoreWritesUndeliveredMessages() = runBlocking {
        val peer = generate()
        val entity = OfflineMessageEntity(
            id = "msg_1",
            senderId = peer.peerId.bytes,
            recipientId = PeerId.BROADCAST.bytes,
            msgType = 0,
            ttl = 5,
            timestampMs = 1_700_000_000_000L,
            payload = "hello mesh".toByteArray(),
            signature = ByteArray(0),
            enqueuedAtMs = 1_700_000_000_000L,
        )
        gateway.undelivered = listOf(entity)

        bridge.flushQueueToFirestore()

        assertEquals(1, gateway.sentMessages.size)
        assertEquals("msg_1", gateway.sentMessages[0].id)
        assertEquals("hello mesh", gateway.sentMessages[0].text)
        assertEquals(1, gateway.deliveredIds.size)
        assertEquals("msg_1", gateway.deliveredIds[0])
    }

    @Test
    fun flushQueueSkipsAlreadyKnownMessages() = runBlocking {
        val peer = generate()
        val entity = OfflineMessageEntity(
            id = "msg_1",
            senderId = peer.peerId.bytes,
            recipientId = PeerId.BROADCAST.bytes,
            msgType = 0,
            ttl = 5,
            timestampMs = 1_700_000_000_000L,
            payload = "hello mesh".toByteArray(),
            signature = ByteArray(0),
            enqueuedAtMs = 1_700_000_000_000L,
        )
        gateway.undelivered = listOf(entity)

        // Pre-register the ID as seen via mesh
        bridge.registerMeshMessageId("msg_1")

        bridge.flushQueueToFirestore()

        assertEquals(0, gateway.sentMessages.size)
        assertEquals(1, gateway.deliveredIds.size) // Still marked delivered to avoid retry
    }

    @Test
    fun syncFirestoreToMeshForwardsUnknownMessages() = runBlocking {
        val msg = QuickMessage(
            id = "fm_1",
            senderId = "user_bob",
            senderName = "Bob",
            type = QuickMessageType.SLOW_DOWN,
            text = "slow down everyone",
            timestampMs = 1_700_000_000_000L,
        )
        gateway.firestoreMessages = listOf(msg)

        bridge.syncFirestoreToMesh()

        assertEquals(1, meshRouter.sentPackets.size)
        assertTrue(bridge.isMessageKnown("fm_1"))
    }

    @Test
    fun syncFirestoreToMeshSkipsAlreadyKnownMessages() = runBlocking {
        val msg = QuickMessage(
            id = "fm_1",
            senderId = "user_bob",
            senderName = "Bob",
            type = QuickMessageType.SLOW_DOWN,
            text = "slow down everyone",
            timestampMs = 1_700_000_000_000L,
        )
        gateway.firestoreMessages = listOf(msg)

        // Pre-register the ID as seen via mesh
        bridge.registerMeshMessageId("fm_1")

        bridge.syncFirestoreToMesh()

        assertEquals(0, meshRouter.sentPackets.size)
    }

    @Test
    fun isFlushingReturnsFalseWhenIdle() {
        assertFalse(bridge.isFlushing.value)
    }

    // ---- Test doubles ----

    private class FakeGateway : FirestoreMessageGateway {
        var undelivered: List<OfflineMessageEntity> = emptyList()
        var firestoreMessages: List<QuickMessage> = emptyList()
        val sentMessages = mutableListOf<QuickMessage>()
        val deliveredIds = mutableListOf<String>()

        override suspend fun getUndeliveredMessages(): List<OfflineMessageEntity> = undelivered
        override suspend fun markDelivered(messageId: String) { deliveredIds.add(messageId) }
        override suspend fun getMessages(rideId: String): List<QuickMessage> = firestoreMessages
        override suspend fun sendMessage(rideId: String, msg: QuickMessage) {
            sentMessages.add(msg)
        }
    }

    private class FakeMeshRouter : MeshRouter(
        localIdentity = generate(),
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
