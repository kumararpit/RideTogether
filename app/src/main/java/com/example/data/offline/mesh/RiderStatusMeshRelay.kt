package com.example.data.offline.mesh

import com.example.data.offline.crypto.PeerIdentity
import com.example.data.offline.model.OfflineMessageType
import com.example.data.offline.protocol.OfflinePacket
import com.example.data.offline.protocol.PeerId
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import java.nio.charset.StandardCharsets

/**
 * Relay for rider status/location updates over the offline mesh network.
 *
 * Rider status updates (position, speed, heading, MemberStatus) are broadcast
 * with moderate TTL so nearby peers stay in sync even without internet.
 * Incoming updates are parsed and returned as [ParsedRiderStatus] for the
 * caller to apply to local rider state.
 *
 * Payload format: semicolon-delimited key=value pairs, e.g.
 *   a=status;r=user_bob;n=Bob;s=RIDING;la=18.5204;lo=73.8567;sp=40.0;hd=290.0;acc=10.0;t=1700000000000;seq=5
 *
 * Clean-room design: the rider status mesh payload format and broadcast strategy
 * are original RideTogether designs.
 */
class RiderStatusMeshRelay(
    private val meshRouter: MeshRouter,
    private val localPeerId: PeerId,
    private val localRiderId: String,
    private val localRiderName: String,
) {

    /** TTL for rider status broadcast packets — moderate since updates are frequent. */
    private val STATUS_TTL: Int = 3

    private val FIELD_ACTION = "a"
    private val FIELD_RIDER_ID = "r"
    private val FIELD_RIDER_NAME = "n"
    private val FIELD_STATUS = "s"
    private val FIELD_LAT = "la"
    private val FIELD_LON = "lo"
    private val FIELD_SPEED = "sp"
    private val FIELD_HEADING = "hd"
    private val FIELD_ACCURACY = "acc"
    private val FIELD_TIMESTAMP = "t"
    private val FIELD_SEQUENCE = "seq"

    /** Broadcasts the local rider's current status/location over the mesh. */
    fun sendRiderStatusUpdate(
        status: MemberStatus,
        location: LatLng,
        speedKmh: Double,
        headingDeg: Float?,
        accuracyMeters: Float,
        timestampMs: Long,
        sequenceNumber: Long,
    ) {
        val payload = buildString {
            append("$FIELD_ACTION=status;")
            append("$FIELD_RIDER_ID=$localRiderId;")
            append("$FIELD_RIDER_NAME=$localRiderName;")
            append("$FIELD_STATUS=$status;")
            append("$FIELD_LAT=${location.latitude};")
            append("$FIELD_LON=${location.longitude};")
            append("$FIELD_SPEED=$speedKmh;")
            append("$FIELD_HEADING=${headingDeg ?: -1.0};")
            append("$FIELD_ACCURACY=$accuracyMeters;")
            append("$FIELD_TIMESTAMP=$timestampMs;")
            append("$FIELD_SEQUENCE=$sequenceNumber")
        }.toByteArray(StandardCharsets.UTF_8)

        val packet = OfflinePacket(
            version = 1,
            msgType = OfflineMessageType.LOCATION_UPDATE,
            ttl = STATUS_TTL,
            timestampMs = timestampMs,
            senderId = localPeerId,
            recipientId = PeerId.BROADCAST,
            payload = payload,
        )
        meshRouter.sendPacket(packet)
    }

    /**
     * Parses an incoming OfflinePacket for rider status content.
     * Returns [ParsedRiderStatus] for LOCATION_UPDATE packets, or null if the
     * packet is not a rider status update or is malformed.
     */
    fun parseIncomingPacket(packet: OfflinePacket, from: PeerIdentity): ParsedRiderStatus? {
        if (packet.msgType != OfflineMessageType.LOCATION_UPDATE) return null
        val fields = parseFields(packet.payload) ?: return null
        return try {
            val riderId = fields[FIELD_RIDER_ID] ?: return null
            val statusStr = fields[FIELD_STATUS] ?: return null
            val status = MemberStatus.valueOf(statusStr)
            val lat = fields[FIELD_LAT]?.toDoubleOrNull() ?: return null
            val lon = fields[FIELD_LON]?.toDoubleOrNull() ?: return null
            val speed = fields[FIELD_SPEED]?.toDoubleOrNull() ?: 0.0
            val heading = fields[FIELD_HEADING]?.toFloatOrNull()?.takeIf { it >= 0f }
            val accuracy = fields[FIELD_ACCURACY]?.toFloatOrNull() ?: 10f
            val timestamp = fields[FIELD_TIMESTAMP]?.toLongOrNull() ?: packet.timestampMs
            val sequence = fields[FIELD_SEQUENCE]?.toLongOrNull() ?: 0L
            val name = fields[FIELD_RIDER_NAME] ?: "Rider"

            ParsedRiderStatus(
                riderId = riderId,
                riderName = name,
                status = status,
                location = LatLng(lat, lon),
                speedKmh = speed,
                headingDeg = heading,
                accuracyMeters = accuracy,
                timestampMs = timestamp,
                sequenceNumber = sequence,
                senderPeerId = from.peerId.toHex(),
            )
        } catch (e: Exception) {
            null
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

    /** Result of parsing an incoming rider status mesh packet. */
    data class ParsedRiderStatus(
        val riderId: String,
        val riderName: String,
        val status: MemberStatus,
        val location: LatLng,
        val speedKmh: Double,
        val headingDeg: Float?,
        val accuracyMeters: Float,
        val timestampMs: Long,
        val sequenceNumber: Long,
        val senderPeerId: String,
    )
}
