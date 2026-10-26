package com.example.data.offline.ble

import android.content.Context
import android.util.Log
import com.example.data.offline.crypto.LocalIdentity
import com.example.data.offline.crypto.PeerIdentity
import com.example.data.offline.protocol.OfflinePacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [BleTransport] using the platform BLE stack.
 *
 * Advertising: places the peer's advertisement payload (see [BleAdvertisement])
 * in the manufacturer data field of connectable BLE advertisements so nearby
 * peers can discover this device and learn its peer id without connecting.
 *
 * Scanning: filters for the same manufacturer data and decodes incoming
 * advertisements into [PeerIdentity] instances delivered via [BleScanCallback].
 *
 * Connections: opens a GATT connection to a discovered peer and exchanges
 * [OfflinePacket] instances over a GATT characteristic, fragmenting large
 * packets via [BlePacketFragmenter] when they exceed the negotiated MTU.
 *
 * All BLE calls run on a dedicated handler thread so the transport never blocks
 * the caller's thread. Advertising/scanning state is tracked in
 * [isAdvertising]/[isScanning].
 *
 * Clean-room design: the GATT service/characteristic UUIDs, manufacturer data
 * layout, and connection state machine are original RideTogether designs.
 *
 * Phase 12 status: BLE advertising/scanning/connection APIs are wired as a
 * compile-safe stub. Real platform BLE calls (BluetoothAdapter, BluetoothLeAdvertiser,
 * BluetoothLeScanner, GATT) are added once the transport can be exercised on a
 * physical device. Until then, startAdvertising/startScanning invoke their
 * callbacks to signal success, keeping the mesh layer structurally intact.
 */
class AndroidBleTransport(private val context: Context) : BleTransport {

    companion object {
        private const val TAG = "AndroidBleTransport"

        /** Manufacturer company id used in BLE advertisement manufacturer data. */
        const val MANUFACTURER_ID: Int = 0xFFFF

        /** GATT service UUID for the offline mesh profile. */
        const val SERVICE_UUID = "0000a100-0000-1000-8000-00805f9b34fb"

        /** GATT characteristic UUID for sending mesh packets to the peer. */
        const val CHARACTERISTIC_UUID_RX = "0000a101-0000-1000-8000-00805f9b34fb"

        /** GATT characteristic UUID for receiving mesh packets from the peer. */
        const val CHARACTERISTIC_UUID_TX = "0000a102-0000-1000-8000-00805f9b34fb"
    }

    /** Whether advertising is currently active. */
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    /** Whether scanning is currently active. */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    override fun startAdvertising(identity: LocalIdentity, callback: BleAdvertisingCallback) {
        if (_isAdvertising.value) {
            // Already advertising — no new start occurred, so do not re-signal.
            return
        }
        _isAdvertising.value = true
        callback.onStarted()
        Log.d(TAG, "Advertising started (stub): peerId=${identity.peerId.toHex()}")
    }

    override fun stopAdvertising() {
        _isAdvertising.value = false
    }

    override fun startScanning(callback: BleScanCallback) {
        if (_isScanning.value) {
            return
        }
        _isScanning.value = true
        Log.d(TAG, "Scanning started (stub)")
    }

    override fun stopScanning() {
        _isScanning.value = false
    }

    override fun connect(peer: PeerIdentity): com.example.data.offline.ble.BleConnection? {
        Log.w(TAG, "connect() not yet implemented: peer=${peer.peerId.toHex()}")
        return null
    }

    override fun shutdown() {
        _isAdvertising.value = false
        _isScanning.value = false
        Log.d(TAG, "Transport shut down (stub)")
    }
}
