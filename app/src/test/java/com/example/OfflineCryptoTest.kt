package com.example

import com.example.data.offline.crypto.PacketSigner
import com.example.data.offline.crypto.fromKeys
import com.example.data.offline.crypto.generate
import com.example.data.offline.crypto.X25519Agreement
import com.example.data.offline.model.OfflineMessageType
import com.example.data.offline.protocol.OfflinePacket
import com.example.data.offline.protocol.PeerId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCryptoTest {

    @Test
    fun localIdentityGeneration() {
        val identity = generate()
        assertEquals(32, identity.x25519PublicKey.size)
        assertEquals(32, identity.x25519PrivateKey.size)
        assertEquals(32, identity.ed25519PublicKey.size)
        assertEquals(32, identity.ed25519PrivateKey.size)
        assertNotNull(identity.peerId)
        assertEquals(8, identity.peerId.bytes.size)
    }

    @Test
    fun localIdentityIsUniquePerGeneration() {
        val id1 = generate()
        val id2 = generate()
        assertFalse(id1.peerId.bytes.contentEquals(id2.peerId.bytes))
        assertFalse(id1.ed25519PublicKey.contentEquals(id2.ed25519PublicKey))
    }

    @Test
    fun peerIdentityFromKeys() {
        val identity = generate()
        val peer = fromKeys(identity.x25519PublicKey, identity.ed25519PublicKey)
        assertEquals(identity.peerId, peer.peerId)
        assertArrayEquals(identity.x25519PublicKey, peer.x25519PublicKey)
        assertArrayEquals(identity.ed25519PublicKey, peer.ed25519PublicKey)
    }

    @Test
    fun packetSignAndVerifyRoundTrip() {
        val identity = generate()
        val packet = OfflinePacket(
            msgType = OfflineMessageType.SOS,
            ttl = 3,
            timestampMs = 1700000000000L,
            senderId = identity.peerId,
            recipientId = PeerId.BROADCAST,
            payload = "emergency".toByteArray(),
        )

        val signed = PacketSigner.signAndAttach(packet, identity.ed25519PrivateKey)
        assertTrue(signed.isSigned)
        assertEquals(64, signed.signature.size)

        // Verify with correct public key
        assertTrue(PacketSigner.verify(signed, identity.ed25519PublicKey))

        // Verify with wrong public key should fail
        val other = generate()
        assertFalse(PacketSigner.verify(signed, other.ed25519PublicKey))
    }

    @Test
    fun unsignedPacketVerifyReturnsFalse() {
        val identity = generate()
        val packet = OfflinePacket(
            msgType = OfflineMessageType.SOS,
            ttl = 1,
            timestampMs = 1000L,
            senderId = identity.peerId,
            recipientId = PeerId.BROADCAST,
            payload = byteArrayOf(1),
            signature = ByteArray(0),
        )
        assertFalse(PacketSigner.verify(packet, identity.ed25519PublicKey))
    }

    @Test
    fun tamperedPayloadFailsVerification() {
        val identity = generate()
        val packet = OfflinePacket(
            msgType = OfflineMessageType.SOS,
            ttl = 1,
            timestampMs = 1000L,
            senderId = identity.peerId,
            recipientId = PeerId.BROADCAST,
            payload = "original".toByteArray(),
        )
        val signed = PacketSigner.signAndAttach(packet, identity.ed25519PrivateKey)

        // Tamper with the payload
        val tampered = signed.copy(payload = "tampered!".toByteArray())
        assertFalse(PacketSigner.verify(tampered, identity.ed25519PublicKey))
    }

    @Test
    fun x25519AgreementProducesMatchingSharedSecret() {
        val alice = generate()
        val bob = generate()

        val aliceSecret = X25519Agreement.agree(alice.x25519PrivateKey, bob.x25519PublicKey)
        val bobSecret = X25519Agreement.agree(bob.x25519PrivateKey, alice.x25519PublicKey)

        assertEquals(32, aliceSecret.size)
        assertEquals(32, bobSecret.size)
        assertArrayEquals(aliceSecret, bobSecret)
    }

    @Test
    fun x25519AgreementDifferentPartiesDifferentSecrets() {
        val alice = generate()
        val bob = generate()
        val carol = generate()

        val aliceBob = X25519Agreement.agree(alice.x25519PrivateKey, bob.x25519PublicKey)
        val aliceCarol = X25519Agreement.agree(alice.x25519PrivateKey, carol.x25519PublicKey)

        assertFalse(aliceBob.contentEquals(aliceCarol))
    }
}
