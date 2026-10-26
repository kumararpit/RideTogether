package com.example.data.offline.crypto

import com.example.data.offline.protocol.OfflinePacket
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Ed25519 signing and verification for [OfflinePacket].
 *
 * The signature covers the packet's signed portion (header + payload, everything
 * except the signature itself), as defined by [OfflinePacket.signedPayload].
 *
 * Clean-room implementation: uses Bouncy Castle's Ed25519Signer directly, not
 * derived from any third-party signing code.
 */
object PacketSigner {
    /** Signs [packet] with the given Ed25519 private key and returns the 64-byte signature. */
    fun sign(packet: OfflinePacket, ed25519PrivateKey: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(ed25519PrivateKey, 0))
        val payload = packet.signedPayload()
        signer.update(payload, 0, payload.size)
        return signer.generateSignature()
    }

    /** Verifies [packet]'s signature against the given Ed25519 public key. */
    fun verify(packet: OfflinePacket, ed25519PublicKey: ByteArray): Boolean {
        if (!packet.isSigned) return false
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(ed25519PublicKey, 0))
        val payload = packet.signedPayload()
        signer.update(payload, 0, payload.size)
        return signer.verifySignature(packet.signature)
    }

    /** Returns a copy of [packet] with an Ed25519 signature attached. */
    fun signAndAttach(packet: OfflinePacket, ed25519PrivateKey: ByteArray): OfflinePacket {
        return packet.copy(signature = sign(packet, ed25519PrivateKey))
    }
}
