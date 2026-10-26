package com.example.data.offline.protocol

import com.example.data.offline.model.OfflineMessageType

/**
 * Clean-room binary packet format for RideTogether offline mesh messaging.
 *
 * Wire layout (all multi-byte integers big-endian):
 *
 *   Offset  Size  Field           Description
 *   0       1     MAGIC           0xA5 frame-sync byte
 *   1       1     version         Protocol version (1)
 *   2       1     msgType         [OfflineMessageType] ordinal
 *   3       1     flags           bit0=ackRequired, bit1=encrypted, bit2=broadcast
 *   4       4     ttl             Hops remaining (decremented at each relay)
 *   8       8     timestampMs     Epoch milliseconds (full 64 bits)
 *   16      8     senderId        Peer id (8 bytes) of the originator
 *   24      8     recipientId     Peer id (8 bytes); [PeerId.BROADCAST] = all peers
 *   32      2     payloadLen      Payload length n (u16), max 65535
 *   34      n     payload         Opaque message payload bytes
 *   34+n    64    signature       Ed25519 signature over bytes [0 .. 34+n)
 *
 * The fixed header occupies 34 bytes (everything before the payload).
 *
 * This layout is an original RideTogether design. Concepts (version/type/TTL/
 * timestamp/flags + sender/recipient ids + payload + signature) are shared
 * with mesh protocols generally, but the byte ordering, field arrangement,
 * magic value, and header size are not copied from any third-party format.
 *
 * Encoding/decoding are pure functions with no Android dependencies so the
 * format can be unit-tested on the JVM.
 */
data class OfflinePacket(
    /** Protocol version. Must be 1. */
    val version: Int = 1,
    /** Type of message carried in the payload. */
    val msgType: OfflineMessageType,
    /** When set, the recipient should return an acknowledgement. */
    val ackRequired: Boolean = false,
    /** When set, the payload is encrypted (content opaque to relays). */
    val encrypted: Boolean = false,
    /** When set, the packet is addressed to all peers (recipientId is broadcast). */
    val broadcast: Boolean = false,
    /** Hops remaining; decremented by each relay. 0 means do not relay further. */
    val ttl: Int,
    /** Epoch milliseconds when the packet was originated. */
    val timestampMs: Long,
    /** Peer id of the packet originator. */
    val senderId: PeerId,
    /** Peer id of the intended recipient, or [PeerId.BROADCAST]. */
    val recipientId: PeerId,
    /** Opaque message payload. */
    val payload: ByteArray = ByteArray(0),
    /** Ed25519 signature over the signed portion; empty until signed. */
    val signature: ByteArray = ByteArray(0),
) {
    init {
        require(version == 1) { "Unsupported protocol version: $version" }
        require(senderId.bytes.size == PeerId.LENGTH) { "senderId must be ${PeerId.LENGTH} bytes" }
        require(recipientId.bytes.size == PeerId.LENGTH) { "recipientId must be ${PeerId.LENGTH} bytes" }
        require(payload.size <= 0xFFFF) { "Payload too large: ${payload.size} bytes" }
        require(ttl >= 0 && ttl <= 0xFFFF_FFFF) { "TTL out of range: $ttl" }
        require(signature.size == 0 || signature.size == 64) {
            "Signature must be empty or exactly 64 bytes, was ${signature.size}"
        }
    }

    companion object {
        /** Frame-sync magic byte. */
        const val MAGIC: Int = 0xA5

        /** Fixed header size in bytes (before payload). */
        const val HEADER_SIZE: Int = 34

        /** Ed25519 signature size in bytes. */
        const val SIGNATURE_SIZE: Int = 64

        /** Maximum payload size (u16 length field). */
        const val MAX_PAYLOAD_SIZE: Int = 0xFFFF

        /** Bit masks for the flags byte. */
        @Suppress("MemberVisibilityCanBePrivate")
        object Flags {
            const val ACK_REQUIRED: Int = 0x01
            const val ENCRYPTED: Int = 0x02
            const val BROADCAST: Int = 0x04
        }

        private fun ByteArray.writeLong(offset: Int, value: Long) {
            set(offset, (value shr 56).toByte())
            set(offset + 1, (value shr 48).toByte())
            set(offset + 2, (value shr 40).toByte())
            set(offset + 3, (value shr 32).toByte())
            set(offset + 4, (value shr 24).toByte())
            set(offset + 5, (value shr 16).toByte())
            set(offset + 6, (value shr 8).toByte())
            set(offset + 7, value.toByte())
        }

        private fun ByteArray.writeInt(offset: Int, value: Int) {
            set(offset, (value shr 24).toByte())
            set(offset + 1, (value shr 16).toByte())
            set(offset + 2, (value shr 8).toByte())
            set(offset + 3, value.toByte())
        }

        private fun readInt(data: ByteArray, offset: Int): Int {
            return (data[offset].toInt() and 0xFF) shl 24 or
                (data[offset + 1].toInt() and 0xFF) shl 16 or
                (data[offset + 2].toInt() and 0xFF) shl 8 or
                (data[offset + 3].toInt() and 0xFF)
        }

        private fun readLong(data: ByteArray, offset: Int): Long {
            var v = 0L
            for (i in 0 until 8) {
                v = v shl 8 or (data[offset + i].toInt() and 0xFF).toLong()
            }
            return v
        }

        /**
         * Decodes a packet from its full wire representation (header + payload +
         * signature). Returns null if the bytes are malformed or the magic byte
         * does not match.
         */
        @JvmStatic
        fun decode(data: ByteArray): OfflinePacket? {
            if (data.size < HEADER_SIZE) return null
            if (data[0].toInt() and 0xFF != MAGIC) return null

            var o = 1
            val version = data[o++].toInt() and 0xFF
            val msgTypeOrdinal = data[o++].toInt() and 0xFF
            val flags = data[o++].toInt() and 0xFF
            val ttl = readInt(data, o); o += 4
            val timestampMs = readLong(data, o); o += 8
            val senderIdBytes = data.copyOfRange(o, o + PeerId.LENGTH); o += PeerId.LENGTH
            val recipientIdBytes = data.copyOfRange(o, o + PeerId.LENGTH); o += PeerId.LENGTH
            val payloadLen = (data[o++].toInt() and 0xFF) shl 8 or (data[o++].toInt() and 0xFF)

            if (data.size < o + payloadLen + SIGNATURE_SIZE) return null

            val payload = data.copyOfRange(o, o + payloadLen); o += payloadLen
            val signatureBytes = data.copyOfRange(o, o + SIGNATURE_SIZE)
            // An all-zero signature field means the packet is unsigned.
            val signature = if (signatureBytes.all { it == 0.toByte() }) ByteArray(0) else signatureBytes

            val msgType = OfflineMessageType.values().firstOrNull { it.ordinal == msgTypeOrdinal }
                ?: return null

            return OfflinePacket(
                version = version,
                msgType = msgType,
                ackRequired = (flags and Flags.ACK_REQUIRED) != 0,
                encrypted = (flags and Flags.ENCRYPTED) != 0,
                broadcast = (flags and Flags.BROADCAST) != 0,
                ttl = ttl,
                timestampMs = timestampMs,
                senderId = PeerId(senderIdBytes),
                recipientId = PeerId(recipientIdBytes),
                payload = payload,
                signature = signature,
            )
        }
    }

    /** Builds the flags byte from [ackRequired], [encrypted], [broadcast]. */
    private fun buildFlags(): Int {
        var f = 0
        if (ackRequired) f = f or Flags.ACK_REQUIRED
        if (encrypted) f = f or Flags.ENCRYPTED
        if (broadcast) f = f or Flags.BROADCAST
        return f
    }

    /**
     * Encodes the fixed header (34 bytes) without the payload or signature.
     */
    fun encodeHeader(): ByteArray {
        val flags = buildFlags()
        return ByteArray(HEADER_SIZE).apply {
            var o = 0
            set(o++, MAGIC.toByte())
            set(o++, version.toByte())
            set(o++, msgType.ordinal.toByte())
            set(o++, flags.toByte())
            writeInt(o, ttl); o += 4
            writeLong(o, timestampMs); o += 8
            senderId.bytes.forEachIndexed { i, b -> set(o + i, b) }; o += 8
            recipientId.bytes.forEachIndexed { i, b -> set(o + i, b) }; o += 8
            set(o++, (payload.size shr 8).toByte())
            set(o++, payload.size.toByte())
        }
    }

    /**
     * Returns the byte range covered by the signature: header + payload,
     * i.e. everything except the signature itself.
     */
    fun signedPayload(): ByteArray {
        return encodeHeader() + payload
    }

    /**
     * Encodes the complete packet: header + payload + signature.
     * The signature field is always 64 bytes on the wire (zero-padded when unsigned).
     */
    fun encode(): ByteArray {
        val sig = if (signature.isEmpty()) ByteArray(SIGNATURE_SIZE) else signature
        return encodeHeader() + payload + sig
    }

    /** Returns true if this packet is addressed to all peers. */
    fun isBroadcast(): Boolean = broadcast || recipientId == PeerId.BROADCAST

    /** Returns true if this packet carries a non-empty signature. */
    val isSigned: Boolean = signature.size == SIGNATURE_SIZE

    /** Human-readable hex summary for logging. */
    fun toSummary(): String =
        "OfflinePacket(type=${msgType}, ttl=$ttl, sender=${senderId.toHex()}, " +
        "recipient=${recipientId.toHex()}, payload=${payload.size}B, signed=$isSigned)"
}

/** Writes a big-endian 32-bit integer into [buffer] at [offset]. */
private fun ByteArray.writeInt(offset: Int, value: Int) {
    set(offset, (value shr 24).toByte())
    set(offset + 1, (value shr 16).toByte())
    set(offset + 2, (value shr 8).toByte())
    set(offset + 3, value.toByte())
}

/** Reads a big-endian 32-bit integer from [data] at [offset]. */
private fun readInt(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() and 0xFF) shl 24 or
        (data[offset + 1].toInt() and 0xFF) shl 16 or
        (data[offset + 2].toInt() and 0xFF) shl 8 or
        (data[offset + 3].toInt() and 0xFF)
}
