package com.example.data.offline.protocol

import java.security.MessageDigest

/**
 * A peer identifier: the first 8 bytes of SHA-256(static public key), big-endian.
 *
 * Rendered as 16 hex characters when displayed. [BROADCAST] (all-zero 8 bytes)
 * means the packet is addressed to every peer rather than a single recipient.
 *
 * Equality is content-based: two peer ids with the same 8 bytes are equal.
 *
 * This is an original RideTogether design; it is not derived from any
 * third-party identifier scheme.
 */
class PeerId internal constructor(val bytes: ByteArray) {
    init {
        require(bytes.size == LENGTH) { "PeerId must be exactly $LENGTH bytes, was ${bytes.size}" }
    }

    companion object {
        /** The expected length of a peer id, in bytes. */
        const val LENGTH: Int = 8

        /** All-zero peer id used for broadcast packets. */
        val BROADCAST: PeerId = PeerId(ByteArray(LENGTH))

        /**
         * Derives a [PeerId] from an Ed25519/X25519 static public key: the first
         * [LENGTH] bytes of SHA-256(publicKey), big-endian.
         */
        fun fromPublicKey(publicKey: ByteArray): PeerId {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
            return PeerId(digest.copyOfRange(0, LENGTH))
        }

        fun fromHex(hex: String): PeerId {
            require(hex.length == LENGTH * 2) { "PeerId hex must be ${LENGTH * 2} chars, was ${hex.length}" }
            return PeerId(
                (0 until LENGTH).map { i ->
                    Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte()
                }.toByteArray()
            )
        }
    }

    fun toHex(): String = bytes.joinToString("") { "%02x".format(it) }

    fun isBroadcast(): Boolean = this == PeerId.BROADCAST

    override fun equals(other: Any?): Boolean = other is PeerId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "PeerId(toHex=${toHex()})"
}
