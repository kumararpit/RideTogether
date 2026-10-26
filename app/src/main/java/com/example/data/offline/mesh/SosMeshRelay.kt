package com.example.data.offline.mesh

import com.example.data.offline.crypto.PeerIdentity
import com.example.data.offline.model.OfflineMessageType
import com.example.data.offline.protocol.OfflinePacket
import com.example.data.offline.protocol.PeerId
import com.example.data.model.SosEvent
import java.nio.charset.StandardCharsets

/**
 * Relay for SOS/breakdown alerts over the offline mesh network.
 *
 * SOS events are broadcast (not unicast) with elevated TTL so they reach
 * all nearby peers, including those without internet connectivity.
 * Acknowledgment and resolution messages also propagate over mesh so
 * every peer in the group has a consistent view of the emergency state.
 *
 * Payload format: semicolon-delimited key=value pairs, e.g.
 *   a=sos;id=sos_123;r=user_bob;n=Bob;la=18.5204;lo=73.8567;t=1700000000000;acc=10.0;src=FRESH_LOCATION_FIX
 *
 * Clean-room design: the SOS mesh payload format, broadcast relay strategy,
 * and ack/resolve propagation are original RideTogether designs.
 */
class SosMeshRelay(
    private val meshRouter: MeshRouter,
    private val localPeerId: PeerId,
    private val localRiderName: String,
) {

    /** TTL for SOS broadcast packets — high to maximize reach through the mesh. */
    /** TTL for SOS broadcast packets — high to maximize reach through the mesh. */
    private val SOS_TTL: Int = 7

    /** Action codes embedded in CUSTOM-type payloads for ack/resolve. */
    private val ACTION_SOS = "sos"
    private val ACTION_ACK = "ack"
    private val ACTION_RESOLVE = "resolve"

    /** Field keys used in the payload. */
    private val FIELD_ACTION = "a"
    private val FIELD_ID = "id"
    private val FIELD_RIDER_ID = "r"
    private val FIELD_RIDER_NAME = "n"
    private val FIELD_LAT = "la"
    private val FIELD_LON = "lo"
    private val FIELD_TIMESTAMP = "t"
    private val FIELD_ACCURACY = "acc"
    private val FIELD_SOURCE = "src"
    private val FIELD_ACKER = "acker"

    /** Sends an SOS alert over the mesh. Called when a rider triggers SOS. */
    fun sendSos(event: SosEvent) {
        val payload = serializeSos(event)
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.SOS,
            ttl = SOS_TTL,
            timestampMs = event.timestampMs,
            senderId = localPeerId,
            recipientId = PeerId.BROADCAST,
            payload = payload,
        )
        meshRouter.sendPacket(packet)
    }

    /** Sends an SOS acknowledgment over the mesh. */
    fun sendSosAck(sosId: String, ackerId: String) {
        val payload = buildString {
            append("$FIELD_ACTION=$ACTION_ACK;")
            append("$FIELD_ID=$sosId;")
            append("$FIELD_ACKER=$ackerId")
        }.toByteArray(StandardCharsets.UTF_8)
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.CUSTOM,
            ttl = SOS_TTL,
            timestampMs = System.currentTimeMillis(),
            senderId = localPeerId,
            recipientId = PeerId.BROADCAST,
            payload = payload,
        )
        meshRouter.sendPacket(packet)
    }

    /** Sends an SOS resolution over the mesh. */
    fun sendSosResolve(sosId: String) {
        val payload = buildString {
            append("$FIELD_ACTION=$ACTION_RESOLVE;")
            append("$FIELD_ID=$sosId")
        }.toByteArray(StandardCharsets.UTF_8)
        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.CUSTOM,
            ttl = SOS_TTL,
            timestampMs = System.currentTimeMillis(),
            senderId = localPeerId,
            recipientId = PeerId.BROADCAST,
            payload = payload,
        )
        meshRouter.sendPacket(packet)
    }

    /**
     * Parses an incoming OfflinePacket for SOS-related content.
     * Returns a [SosEvent] for SOS alert packets, or null if the packet is
     * not an SOS-related message or is malformed.
     *
     * Ack and resolve actions are parsed but returned as null here — the
     * caller handles them via the returned [ParsedAction] if needed.
     */
    fun parseIncomingPacket(packet: OfflinePacket, from: PeerIdentity): ParsedSosMessage? {
        return when (packet.msgType) {
            OfflineMessageType.SOS -> parseSosAlert(packet, from)
            OfflineMessageType.CUSTOM -> parseCustomAction(packet, from)
            else -> null
        }
    }

    /** Result of parsing an incoming SOS-related mesh packet. */
    sealed class ParsedSosMessage {
        /** An SOS alert received from a remote peer. */
        data class SosAlert(val event: SosEvent) : ParsedSosMessage()
        /** An SOS acknowledgment received from a remote peer. */
        data class SosAck(val sosId: String, val ackerId: String) : ParsedSosMessage()
        /** An SOS resolution received from a remote peer. */
        data class SosResolve(val sosId: String) : ParsedSosMessage()
    }

    private fun parseSosAlert(packet: OfflinePacket, from: PeerIdentity): ParsedSosMessage? {
        val fields = parseFields(packet.payload) ?: return null
        return try {
            ParsedSosMessage.SosAlert(
                event = SosEvent(
                    id = fields[FIELD_ID] ?: return null,
                    riderId = fields[FIELD_RIDER_ID] ?: from.peerId.toHex(),
                    riderName = fields[FIELD_RIDER_NAME] ?: "Rider",
                    location = com.example.data.model.LatLng(
                        fields[FIELD_LAT]?.toDoubleOrNull() ?: 0.0,
                        fields[FIELD_LON]?.toDoubleOrNull() ?: 0.0,
                    ),
                    timestampMs = fields[FIELD_TIMESTAMP]?.toLongOrNull() ?: packet.timestampMs,
                    accuracyMeters = fields[FIELD_ACCURACY]?.toFloatOrNull() ?: 10f,
                    locationSource = fields[FIELD_SOURCE] ?: "MESH_RELAY",
                ),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseCustomAction(packet: OfflinePacket, from: PeerIdentity): ParsedSosMessage? {
        val fields = parseFields(packet.payload) ?: return null
        val action = fields[FIELD_ACTION] ?: return null
        val sosId = fields[FIELD_ID] ?: return null

        return when (action) {
            ACTION_ACK -> {
                val ackerId = fields[FIELD_ACKER] ?: from.peerId.toHex()
                ParsedSosMessage.SosAck(sosId, ackerId)
            }
            ACTION_RESOLVE -> ParsedSosMessage.SosResolve(sosId)
            else -> null
        }
    }

    private fun parseFields(payload: ByteArray): Map<String, String>? {
        val str = String(payload, StandardCharsets.UTF_8)
        val result = mutableMapOf<String, String>()
        for (pair in str.split(";")) {
            val idx = pair.indexOf('=')
            if (idx >= 0 && idx < pair.length - 1) {
                result[pair.substring(0, idx)] = pair.substring(idx + 1)
            }
        }
        return if (result.isEmpty()) null else result
    }

    private fun serializeSos(event: SosEvent): ByteArray {
        val parts = mutableListOf<String>()
        parts.add("$FIELD_ACTION=$ACTION_SOS")
        parts.add("$FIELD_ID=${event.id}")
        parts.add("$FIELD_RIDER_ID=${event.riderId}")
        parts.add("$FIELD_RIDER_NAME=${event.riderName}")
        parts.add("$FIELD_LAT=${event.location.latitude}")
        parts.add("$FIELD_LON=${event.location.longitude}")
        parts.add("$FIELD_TIMESTAMP=${event.timestampMs}")
        parts.add("$FIELD_ACCURACY=${event.accuracyMeters}")
        parts.add("$FIELD_SOURCE=${event.locationSource}")
        return parts.joinToString(";").toByteArray(StandardCharsets.UTF_8)
    }
}
