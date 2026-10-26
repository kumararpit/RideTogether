package com.example.data.offline.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement as BcX25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * X25519 key agreement (Elliptic Curve Diffie-Hellman over Curve25519).
 *
 * Computes a shared secret from a local private key and a remote public key.
 * The shared secret is used by later phases to derive symmetric session keys
 * for encrypting mesh traffic between two peers.
 *
 * Clean-room implementation: uses Bouncy Castle's X25519Agreement directly, not
 * derived from any third-party key-agreement code.
 */
object X25519Agreement {
    /**
     * Computes the X25519 shared secret between [localPrivateKey] and
     * [remotePublicKey]. Returns 32 bytes.
     */
    fun agree(localPrivateKey: ByteArray, remotePublicKey: ByteArray): ByteArray {
        val agreement = BcX25519Agreement()
        agreement.init(X25519PrivateKeyParameters(localPrivateKey, 0))
        val output = ByteArray(agreement.getAgreementSize())
        agreement.calculateAgreement(
            X25519PublicKeyParameters(remotePublicKey, 0),
            output,
            0,
        )
        return output
    }
}
