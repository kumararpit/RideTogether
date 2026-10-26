package com.example.data.offline.ble

import com.example.data.offline.protocol.OfflinePacket

/**
 * Fragments and reassembles [OfflinePacket] encodings for BLE MTU constraints.
 *
 * BLE ATT_MTU defaults to 23 bytes and can be negotiated up to 512. Each
 * GATT notification/write can carry at most (MTU - 3) bytes (ATT header overhead).
 * Packets larger than that are split into numbered fragments and reassembled
 * by the receiver.
 *
 * Fragment wire layout (all multi-byte integers big-endian):
 *
 *   Offset  Size  Field           Description
 *   0       2     fragmentIndex   Zero-based index of this fragment
 *   2       2     totalFragments  Total number of fragments in this packet
 *   4       n     fragmentData    Slice of the encoded packet
 *
 * Clean-room design: this fragment format is an original RideTogether design.
 */
object BlePacketFragmenter {

    /** ATT protocol overhead per GATT write/notification (3 bytes). */
    private const val ATT_OVERHEAD: Int = 3

    /** Fragment header size: 2 bytes index + 2 bytes totalFragments. */
    private const val FRAGMENT_HEADER_SIZE: Int = 4

    /** Maximum payload bytes per fragment given an MTU. */
    fun maxFragmentPayload(mtu: Int): Int = (mtu - ATT_OVERHEAD - FRAGMENT_HEADER_SIZE).coerceAtLeast(1)

    /**
     * Splits an encoded packet into fragments, each fitting within [mtu].
     * Returns a single fragment (no splitting) if the packet fits within the MTU.
     */
    fun fragment(encodedPacket: ByteArray, mtu: Int): List<ByteArray> {
        val payloadSize = maxFragmentPayload(mtu).coerceAtLeast(1)
        val totalFragments = (encodedPacket.size + payloadSize - 1) / payloadSize
        return (0 until totalFragments).map { index ->
            val start = index * payloadSize
            val end = (start + payloadSize).coerceAtMost(encodedPacket.size)
            val data = encodedPacket.copyOfRange(start, end)
            encodeFragment(index, totalFragments, data)
        }
    }

    /**
     * Reassembles fragments into the original encoded packet.
     * Returns null if fragments are incomplete, out of order, or inconsistent.
     */
    fun reassemble(fragments: List<ByteArray>): ByteArray? {
        if (fragments.isEmpty()) return null

        val decoded = fragments.mapNotNull { decodeFragment(it) }
        if (decoded.size != fragments.size) return null

        val totalFragments = decoded[0].totalFragments
        if (decoded.any { it.totalFragments != totalFragments }) return null

        val indices = decoded.map { it.index }.sorted()
        if (indices != (0 until totalFragments).toList()) return null

        val totalSize = decoded.sumOf { it.data.size }
        val result = ByteArray(totalSize)
        var pos = 0
        for (frag in decoded) {
            for (b in frag.data) {
                result[pos] = b
                pos++
            }
        }
        return result
    }

    private fun encodeFragment(index: Int, totalFragments: Int, data: ByteArray): ByteArray {
        return ByteArray(4 + data.size).apply {
            set(0, (index shr 8).toByte())
            set(1, index.toByte())
            set(2, (totalFragments shr 8).toByte())
            set(3, totalFragments.toByte())
            set(4, data)  // ByteArray.set with ByteArray range
        }
    }

    private fun decodeFragment(data: ByteArray): DecodedFragment? {
        if (data.size < 4) return null
        val index = (data[0].toInt() and 0xFF) shl 8 or (data[1].toInt() and 0xFF)
        val totalFragments = (data[2].toInt() and 0xFF) shl 8 or (data[3].toInt() and 0xFF)
        val payload = data.copyOfRange(4, data.size)
        return DecodedFragment(index, totalFragments, payload)
    }

    /** ByteArray.set(Int, ByteArray) — Kotlin stdlib doesn't have this, so inline below. */
    private fun ByteArray.set(offset: Int, src: ByteArray) {
        for (i in src.indices) {
            this[offset + i] = src[i]
        }
    }

    private data class DecodedFragment(
        val index: Int,
        val totalFragments: Int,
        val data: ByteArray,
    )
}
