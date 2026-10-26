package com.example.data.offline.mesh

import com.example.data.offline.ble.BleConnection
import com.example.data.offline.ble.BleTransport
import com.example.data.offline.crypto.LocalIdentity
import com.example.data.offline.crypto.PeerIdentity
import com.example.data.offline.protocol.OfflinePacket
import com.example.data.offline.protocol.PeerId

/** Callback for packets delivered locally to this device. */
typealias PacketHandler = (OfflinePacket, PeerIdentity) -> Unit

/**
 * Mesh routing layer: handles packet reception, duplicate detection,
 * TTL-decrement relay, and store-and-forward queuing.
 *
 * The router sits between the upper layers (RideViewModel, etc.) and the
 * [BleTransport]. Upper layers call [sendPacket] to transmit; the router
 * delivers locally or forwards over connected BLE links. The transport
 * invokes [handleIncomingPacket] via each [BleConnection.onPacketReceived]
 * callback when data arrives from a remote peer.
 *
 * Clean-room design: the relay algorithm, duplicate detection window,
 * and store-and-forward queue are original RideTogether designs.
 */
open class MeshRouter(
    private val localIdentity: LocalIdentity,
    private val transport: BleTransport,
    private val packetHandler: PacketHandler,
) {

    private val localPeerId: PeerId = localIdentity.peerId

    private val localPeer: PeerIdentity = PeerIdentity(
        peerId = localIdentity.peerId,
        x25519PublicKey = localIdentity.x25519PublicKey,
        ed25519PublicKey = localIdentity.ed25519PublicKey,
        firstSeenMs = localIdentity.createdAtMs,
    )

    /** Connected peers keyed by peer id. */
    private val connections = mutableMapOf<PeerId, BleConnection>()

    /** Peer identities of connected peers. */
    private val peerIdentities = mutableMapOf<PeerId, PeerIdentity>()

    /** Sliding window of recently seen message IDs for duplicate detection. */
    private val messageIdCache = mutableListOf<Long>()

    private val maxCacheSize: Int = 1000

    /** Store-and-forward queue for undelivered packets. */
    private val outboundQueue = mutableListOf<QueuedPacket>()

    /** A queued packet awaiting delivery to a peer that comes online. */
    data class QueuedPacket(
        val packet: OfflinePacket,
        val enqueueTimeMs: Long,
    )

    /** Called by the upper layer (or transport factory) when a peer connects. */
    fun onPeerConnected(peer: PeerIdentity, connection: BleConnection) {
        connections[peer.peerId] = connection
        peerIdentities[peer.peerId] = peer
        connection.onPacketReceived = { packet ->
            handleIncomingPacket(packet, peer)
        }
        flushQueueForPeer(peer.peerId)
    }

    /** Called when a peer disconnects. */
    fun onPeerDisconnected(peerId: PeerId) {
        connections.remove(peerId)
        peerIdentities.remove(peerId)
    }

    /** Called by the upper layer to send a packet. */
    open fun sendPacket(packet: OfflinePacket) {
        val isForLocalDelivery = packet.recipientId == localPeerId || packet.isBroadcast()

        if (isForLocalDelivery) {
            deliverLocally(packet, localPeer)
        }

        if (packet.isBroadcast()) {
            // Send broadcast to all connected peers (with original TTL — originator does not decrement).
            for ((_, connection) in connections) {
                if (connection.isOpen) {
                    connection.sendPacket(packet)
                }
            }
            return
        }

        if (packet.recipientId == localPeerId) {
            // Addressed to us only — no further forwarding needed.
            return
        }

        val connection = connections[packet.recipientId]
        if (connection != null && connection.isOpen) {
            connection.sendPacket(packet)
        } else {
            // Queue for store-and-forward delivery when the peer comes online.
            outboundQueue.add(QueuedPacket(packet, System.currentTimeMillis()))
        }
    }

    /** Called by [BleConnection.onPacketReceived] when a packet arrives from a remote peer. */
    fun handleIncomingPacket(packet: OfflinePacket, from: PeerIdentity) {
        // 1. Duplicate detection — drop if we've already processed this message.
        if (isDuplicate(packet)) return
        recordMessageId(packet)

        // 2. Local delivery if addressed to us or broadcast.
        if (packet.recipientId == localPeerId || packet.isBroadcast()) {
            deliverLocally(packet, from)
        }

        // 3. Relay if TTL allows — decrement and forward to all other peers.
        if (packet.ttl > 0) {
            val relayPacket = packet.copy(ttl = packet.ttl - 1)
            relayPacket(relayPacket, excludePeer = from.peerId)
        }
    }

    /** Call periodically to expire stale queued messages. */
    fun tick(maxQueueAgeMs: Long = 300_000) {
        val now = System.currentTimeMillis()
        outboundQueue.removeAll { queued ->
            now - queued.enqueueTimeMs >= maxQueueAgeMs
        }
    }

    /** Returns the number of packets currently in the store-and-forward queue. */
    fun queueSize(): Int = outboundQueue.size

    /** Returns the set of currently connected peer ids. */
    fun connectedPeers(): Set<PeerId> = connections.keys.toSet()

    private fun deliverLocally(packet: OfflinePacket, from: PeerIdentity) {
        packetHandler(packet, from)
    }

    private fun relayPacket(packet: OfflinePacket, excludePeer: PeerId) {
        for ((peerId, connection) in connections) {
            if (peerId == excludePeer) continue
            if (connection.isOpen) {
                connection.sendPacket(packet)
            }
        }
    }

    private fun flushQueueForPeer(peerId: PeerId) {
        val toSend = outboundQueue.filter { it.packet.recipientId == peerId }
        outboundQueue.removeAll { it.packet.recipientId == peerId }
        val connection = connections[peerId]
        if (connection != null && connection.isOpen) {
            for (queued in toSend) {
                connection.sendPacket(queued.packet)
            }
        }
    }

    private fun isDuplicate(packet: OfflinePacket): Boolean {
        val msgId = messageIdHash(packet)
        return messageIdCache.contains(msgId)
    }

    private fun recordMessageId(packet: OfflinePacket) {
        val msgId = messageIdHash(packet)
        messageIdCache.add(msgId)
        if (messageIdCache.size > maxCacheSize) {
            messageIdCache.removeAt(0)
        }
    }

    /** Hashes senderId + timestampMs + msgType to identify a unique logical message. */
    private fun messageIdHash(packet: OfflinePacket): Long {
        var hash = 0L
        for (b in packet.senderId.bytes) {
            hash = hash * 31 + (b.toInt() and 0xFF)
        }
        hash = hash * 31 + packet.timestampMs
        hash = hash * 31 + packet.msgType.ordinal.toLong()
        return hash
    }
}
