package com.example.data.firebase

import com.example.data.db.OfflineMessageEntity
import com.example.data.db.RideDatabase
import com.example.data.model.QuickMessage
import com.example.data.offline.mesh.MeshRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bidirectional bridge between the offline mesh network and Firestore.
 *
 * When both mesh and Firestore are available, messages flow in both directions:
 *   - Mesh → Firestore: mesh-delivered QuickMessages are also written to
 *     /rides/{rideId}/messages/ so riders with internet but outside mesh range
 *     can receive them.
 *   - Firestore → Mesh: Firestore messages not yet seen via mesh are forwarded
 *     over BLE to offline-only peers.
 *   - Store-and-forward flush: the Room offline_messages queue is drained to
 *     Firestore when connectivity returns.
 *
 * Deduplication: every message ID seen via either channel is recorded so the
 * same logical message is never delivered twice.
 *
 * Clean-room design: the reconciliation strategy (dual-channel delivery,
 * ID-based dedup, queue-to-Firestore flush) is an original RideTogether design.
 */
class MeshFirestoreBridge(
    private val gateway: FirestoreMessageGateway,
    private val meshRouter: MeshRouter,
    private val currentUserId: String,
    private val currentRideId: String?,
) {

    /** Message IDs seen via either mesh or Firestore, for deduplication. */
    private val seenMessageIds = MutableStateFlow(emptySet<String>())
    val seenMessageIdsFlow: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        seenMessageIds.asStateFlow()

    /** Whether a reconciler flush cycle is currently in progress. */
    private val _isFlushing = MutableStateFlow(false)
    val isFlushing: kotlinx.coroutines.flow.StateFlow<Boolean> = _isFlushing.asStateFlow()

    /**
     * Register a message ID as seen via mesh. Returns true if this is a new
     * (previously unseen) message, false if it was already delivered via
     * Firestore and should be suppressed.
     */
    fun registerMeshMessageId(messageId: String): Boolean {
        val current = seenMessageIds.value
        if (messageId in current) return false
        seenMessageIds.value = current + messageId
        return true
    }

    /**
     * Register a message ID as seen via Firestore. Returns true if new, false
     * if already delivered via mesh.
     */
    fun registerFirestoreMessageId(messageId: String): Boolean {
        val current = seenMessageIds.value
        if (messageId in current) return false
        seenMessageIds.value = current + messageId
        return true
    }

    /**
     * Returns true if [messageId] was already seen via either channel.
     */
    fun isMessageKnown(messageId: String): Boolean = messageId in seenMessageIds.value

    /**
     * Flush the Room store-and-forward queue to Firestore. Undelivered
     * [OfflineMessageEntity] rows are written as QuickMessage documents and
     * marked delivered in Room.
     */
    suspend fun flushQueueToFirestore() {
        val rideId = currentRideId ?: return
        _isFlushing.value = true
        try {
            val undelivered = gateway.getUndeliveredMessages()
            for (msg in undelivered) {
                val quickMsg = entityToQuickMessage(msg, rideId)
                // Skip sending if already seen via the other channel, but still
                // mark delivered in Room so we don't keep retrying it.
                val isNew = registerFirestoreMessageId(quickMsg.id)
                if (isNew) {
                    gateway.sendMessage(rideId, quickMsg)
                }
                gateway.markDelivered(msg.id)
            }
        } finally {
            _isFlushing.value = false
        }
    }

    /**
     * Fetch messages from Firestore, register their IDs for dedup, and forward
     * any not yet sent over mesh to offline-only peers.
     */
    suspend fun syncFirestoreToMesh() {
        val rideId = currentRideId ?: return
        val messages = gateway.getMessages(rideId)
        for (msg in messages) {
            if (!registerFirestoreMessageId(msg.id)) continue
            meshRouter.sendPacket(quickMessageToPacket(msg))
        }
    }

    /**
     * Called when a QuickMessage is received via mesh. Registers the ID for
     * dedup and returns whether it should be delivered to the UI
     * (i.e. not already seen via Firestore).
     */
    fun onMeshMessageReceived(messageId: String): Boolean = registerMeshMessageId(messageId)

    /**
     * Called when a QuickMessage is received via Firestore. Registers the ID
     * for dedup and returns whether it should be delivered to the UI
     * (i.e. not already seen via mesh).
     */
    fun onFirestoreMessageReceived(messageId: String): Boolean = registerFirestoreMessageId(messageId)

    // ---- Conversion between OfflineMessageEntity and QuickMessage ----

    private fun entityToQuickMessage(entity: OfflineMessageEntity, rideId: String): QuickMessage {
        return QuickMessage(
            id = entity.id,
            senderId = bytesToHex(entity.senderId),
            senderName = "Peer-${bytesToHex(entity.senderId).take(4)}",
            type = com.example.data.model.QuickMessageType.values()
                .firstOrNull { it.ordinal == entity.msgType }
                ?: com.example.data.model.QuickMessageType.IM_OKAY,
            text = entity.payload.decodeToString(),
            timestampMs = entity.timestampMs,
        )
    }

    private fun quickMessageToPacket(msg: QuickMessage): com.example.data.offline.protocol.OfflinePacket {
        return com.example.data.offline.protocol.OfflinePacket(
            version = 1,
            msgType = com.example.data.offline.model.OfflineMessageType.QUICK_MESSAGE,
            ttl = 5,
            timestampMs = msg.timestampMs,
            senderId = com.example.data.offline.protocol.PeerId.BROADCAST,
            recipientId = com.example.data.offline.protocol.PeerId.BROADCAST,
            payload = msg.text.toByteArray(),
        )
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Abstracts Firestore + Room message access so [MeshFirestoreBridge] can be
 * unit-tested on the JVM without Android dependencies. Production
 * implementations wrap FirebaseFirestore and RideDatabase.
 */
interface FirestoreMessageGateway {

    /** All undelivered store-and-forward queue entries from Room. */
    suspend fun getUndeliveredMessages(): List<OfflineMessageEntity>

    /** Mark a queued message as delivered in Room. */
    suspend fun markDelivered(messageId: String)

    /** All QuickMessage documents from /rides/{rideId}/messages/. */
    suspend fun getMessages(rideId: String): List<QuickMessage>

    /** Write a QuickMessage to /rides/{rideId}/messages/. */
    suspend fun sendMessage(rideId: String, msg: QuickMessage)
}
