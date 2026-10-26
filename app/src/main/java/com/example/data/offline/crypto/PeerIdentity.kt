package com.example.data.offline.crypto

import com.example.data.offline.protocol.PeerId

/**
 * A remote peer's offline-mesh identity: their public keys and the peer id
 * derived from their Ed25519 public key.
 *
 * [trusted] indicates whether the user has explicitly trusted this peer
 * (out-of-band verification). It starts false and is set by later phases.
 */
data class PeerIdentity(
    /** Peer id derived from ed25519PublicKey. */
    val peerId: PeerId,
    /** X25519 static public key (32 bytes). */
    val x25519PublicKey: ByteArray,
    /** Ed25519 signing public key (32 bytes). */
    val ed25519PublicKey: ByteArray,
    /** Epoch milliseconds when this peer was first observed. */
    val firstSeenMs: Long,
    /** Whether this peer has been explicitly trusted by the user. */
    val trusted: Boolean = false,
)

/** Builds a [PeerIdentity] from the two public keys, deriving the peer id. */
fun fromKeys(
    x25519PublicKey: ByteArray,
    ed25519PublicKey: ByteArray,
    firstSeenMs: Long = System.currentTimeMillis(),
): PeerIdentity {
    return PeerIdentity(
        peerId = PeerId.fromPublicKey(ed25519PublicKey),
        x25519PublicKey = x25519PublicKey,
        ed25519PublicKey = ed25519PublicKey,
        firstSeenMs = firstSeenMs,
    )
}
