package com.example.data.offline.crypto

import com.example.data.offline.protocol.PeerId
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * The device's own offline-mesh identity: a static X25519 key pair for key
 * agreement and an Ed25519 key pair for packet signing.
 *
 * The peer id is derived from the Ed25519 public key via [PeerId.fromPublicKey].
 *
 * Keys are held in memory only here; persistence (Room/AndroidKeyStore) is
 * handled by later phases. This class has no Android dependencies so it can be
 * unit-tested on the JVM.
 *
 * Clean-room implementation: uses Bouncy Castle primitives directly, not
 * derived from any third-party key-management code.
 */
data class LocalIdentity(
    /** X25519 static public key (32 bytes). */
    val x25519PublicKey: ByteArray,
    /** X25519 static private key (32 bytes). */
    val x25519PrivateKey: ByteArray,
    /** Ed25519 signing public key (32 bytes). */
    val ed25519PublicKey: ByteArray,
    /** Ed25519 signing private key (32 bytes). */
    val ed25519PrivateKey: ByteArray,
    /** Epoch milliseconds when this identity was created. */
    val createdAtMs: Long,
) {
    /** The peer id derived from the Ed25519 public key. */
    val peerId: PeerId get() = PeerId.fromPublicKey(ed25519PublicKey)
}

/** Generates a fresh random [LocalIdentity]. */
fun generate(rng: SecureRandom = SecureRandom()): LocalIdentity {
    // X25519 static key pair
    val x25519Gen = X25519KeyPairGenerator()
    x25519Gen.init(X25519KeyGenerationParameters(rng))
    val x25519Pair = x25519Gen.generateKeyPair()
    val xPriv = x25519Pair.private as X25519PrivateKeyParameters
    val xPub = x25519Pair.public as X25519PublicKeyParameters

    // Ed25519 signing key pair
    val ed25519Gen = Ed25519KeyPairGenerator()
    ed25519Gen.init(Ed25519KeyGenerationParameters(rng))
    val ed25519Pair = ed25519Gen.generateKeyPair()
    val edPriv = ed25519Pair.private as Ed25519PrivateKeyParameters
    val edPub = ed25519Pair.public as Ed25519PublicKeyParameters

    return LocalIdentity(
        x25519PublicKey = xPub.getEncoded(),
        x25519PrivateKey = xPriv.getEncoded(),
        ed25519PublicKey = edPub.getEncoded(),
        ed25519PrivateKey = edPriv.getEncoded(),
        createdAtMs = System.currentTimeMillis(),
    )
}
