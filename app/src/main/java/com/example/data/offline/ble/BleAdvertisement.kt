package com.example.data.offline.ble

import com.example.data.offline.protocol.PeerId
import java.nio.charset.StandardCharsets

/**
 * BLE advertisement payload format used for peer discovery.
 *
 * When a device advertises, it places this payload in the manufacturer data
 * field of its BLE advertisement so nearby peers can discover it and learn
 * its peer id without connecting.
 *
 * Wire layout (all multi-byte integers big-endian):
 *
 *   Offset  Size  Field           Description
 *   0       2     MAGIC           0xBEEF frame-sync for advertisements
 *   2       1     version         Advertisement format version (1)
 *   3       8     peerId          Peer id of the advertising device
 *   11      1     flags           bit0=connectable, bit1=supports-encrypted
 *   12      n     deviceName      Optional UTF-8 device name (length = remaining bytes)
 *
 * Clean-room design: this advertisement format is an original RideTogether design.
 */
object BleAdvertisement {

    /** Frame-sync magic for advertisement payloads. */
    const val MAGIC: Int = 0xBEEF

    /** Advertisement format version. */
    const val VERSION: Int = 1

    /** Fixed prefix size before the optional device name. */
    const val HEADER_SIZE: Int = 12

    /** Bit masks for the flags byte. */
    object Flags {
        const val CONNECTABLE: Int = 0x01
        const val SUPPORTS_ENCRYPTED: Int = 0x02
    }

    /**
     * Encodes an advertisement payload for a peer.
     */
    fun encode(peerId: PeerId, flags: Int = 0, deviceName: String? = null): ByteArray {
        val nameBytes = deviceName?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        return ByteArray(HEADER_SIZE + nameBytes.size).apply {
            set(0, (MAGIC shr 8).toByte())
            set(1, MAGIC.toByte())
            set(2, VERSION.toByte())
            peerId.bytes.forEachIndexed { i, b -> set(3 + i, b) }
            set(11, flags.toByte())
            copyBytes(12, nameBytes)
        }
    }

    /**
     * Decoded advertisement payload.
     */
    data class Decoded(
        /** Peer id of the advertising device. */
        val peerId: PeerId,
        /** Advertisement flags. */
        val flags: Int,
        /** Optional device name. */
        val deviceName: String?,
    )

    fun decode(data: ByteArray): Decoded? {
        if (data.size < HEADER_SIZE) return null
        if ((data[0].toInt() and 0xFF) != ((MAGIC shr 8) and 0xFF)) return null
        if ((data[1].toInt() and 0xFF) != (MAGIC and 0xFF)) return null
        if ((data[2].toInt() and 0xFF) != VERSION) return null

        val peerIdBytes = data.copyOfRange(3, 3 + PeerId.LENGTH)
        val flags = data[11].toInt() and 0xFF
        val nameBytes = data.copyOfRange(HEADER_SIZE, data.size)
        val deviceName = if (nameBytes.isEmpty()) null else String(nameBytes, StandardCharsets.UTF_8)

        return Decoded(
            peerId = PeerId(peerIdBytes),
            flags = flags,
            deviceName = deviceName,
        )
    }

    /** Whether the advertisement indicates the device is connectable. */
    fun isConnectable(flags: Int): Boolean = (flags and Flags.CONNECTABLE) != 0

    /** Whether the advertisement indicates the device supports encrypted sessions. */
    fun supportsEncrypted(flags: Int): Boolean = (flags and Flags.SUPPORTS_ENCRYPTED) != 0

    private fun ByteArray.copyBytes(offset: Int, src: ByteArray) {
        for (i in src.indices) {
            this[offset + i] = src[i]
        }
    }
}
