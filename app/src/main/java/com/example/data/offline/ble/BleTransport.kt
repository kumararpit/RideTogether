package com.example.data.offline.ble

import com.example.data.offline.crypto.LocalIdentity
import com.example.data.offline.crypto.PeerIdentity
import com.example.data.offline.protocol.OfflinePacket

/**
 * Transport abstraction for sending and receiving [OfflinePacket] instances over
 * Bluetooth Low Energy.
 *
 * Implementations handle the platform-specific BLE stack (advertising, scanning,
 * GATT connection management, MTU fragmentation/reassembly). This interface has
 * no Android dependencies so it can be tested and mocked on the JVM.
 *
 * Clean-room design: the advertisement payload format, GATT service/characteristic
 * UUIDs, and connection state machine are original RideTogether designs, not
 * derived from any third-party BLE transport code.
 */
interface BleTransport {

    /** Starts BLE advertising so nearby peers can discover this device. */
    fun startAdvertising(identity: LocalIdentity, callback: BleAdvertisingCallback)

    /** Stops BLE advertising. */
    fun stopAdvertising()

    /** Starts BLE scanning for nearby peers. */
    fun startScanning(callback: BleScanCallback)

    /** Stops BLE scanning. */
    fun stopScanning()

    /**
     * Connects to a discovered peer and returns a [BleConnection] for reliable
     * packet exchange. Returns null if the peer cannot be reached.
     */
    fun connect(peer: PeerIdentity): BleConnection?

    /** Shuts down the transport, releasing all BLE resources. */
    fun shutdown()
}

/** Callback for BLE advertising lifecycle events. */
interface BleAdvertisingCallback {
    /** Advertising started successfully. */
    fun onStarted()

    /** Advertising failed to start. */
    fun onStartFailed(error: String)

    /** A scan request was received from a remote peer (connectable advertising). */
    fun onScanRequestReceived(peerId: ByteArray)
}

/** Callback for BLE scan results. */
interface BleScanCallback {
    /** A peer was discovered via BLE advertising. */
    fun onPeerDiscovered(peer: PeerIdentity, rssi: Int)

    /** A previously discovered peer's advertisement was updated. */
    fun onPeerUpdated(peer: PeerIdentity, rssi: Int)

    /** Scanning stopped. */
    fun onScanStopped()
}

/**
 * A connection to a remote peer over BLE GATT, used for reliable [OfflinePacket]
 * delivery. Packets larger than the negotiated MTU are fragmented automatically.
 */
interface BleConnection {

    /** The connected peer. */
    val peer: PeerIdentity

    /** True if the connection is currently open. */
    val isOpen: Boolean

    /** Sends a packet to the peer. Delivery is best-effort over this connection. */
    fun sendPacket(packet: OfflinePacket)

    /** Callback invoked when a packet is received from the remote peer. */
    var onPacketReceived: ((OfflinePacket) -> Unit)?

    /** Closes the connection and releases GATT resources. */
    fun close()
}

/** Factory for creating [BleTransport] instances. Platform-specific implementations
 * provide their own factory (e.g. AndroidBleTransportFactory on Android) and register
 * it once at app startup. */
object BleTransportFactory {
    private var factory: (() -> BleTransport)? = null

    /** Registers a platform-specific factory. Call once at app startup. */
    fun register(factory: () -> BleTransport) {
        this.factory = factory
    }

    /** Creates a [BleTransport] using the registered factory. */
    fun create(): BleTransport = factory?.invoke()
        ?: throw UnsupportedOperationException(
            "No platform BLE transport factory registered. " +
            "Install an AndroidBleTransportFactory on Android."
        )
}
