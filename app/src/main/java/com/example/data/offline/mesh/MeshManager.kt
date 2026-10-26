package com.example.data.offline.mesh

import com.example.data.offline.ble.BleAdvertisingCallback
import com.example.data.offline.ble.BleScanCallback
import com.example.data.offline.ble.BleTransport
import com.example.data.offline.ble.BleTransportFactory
import com.example.data.offline.crypto.LocalIdentity
import com.example.data.offline.crypto.PeerIdentity
import com.example.data.offline.crypto.generate
import com.example.data.offline.protocol.OfflinePacket
import com.example.data.firebase.RideRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the offline mesh network lifecycle: device identity, BLE transport,
 * mesh router, and SOS/rider status relays.
 *
 * Bridges incoming mesh packets to [RideRepository] so SOS alerts and rider
 * status updates from offline peers appear in the UI. Outgoing SOS/status
 * updates flow through the relays injected into [RideRepository].
 *
 * If no platform [BleTransport] factory is registered (e.g. on non-Android or
 * before Phase 12 foreground service), the mesh degrades gracefully: relays
 * are null, isMeshActive is false, and all mesh operations are no-ops.
 */
class MeshManager(
    private val localRiderId: String,
    private val localRiderName: String,
) {

    /** The device's offline-mesh identity (X25519 + Ed25519 key pairs). */
    val identity: LocalIdentity = generate()

    /** BLE transport, or null if no platform factory is registered. */
    private val transport: BleTransport? = try {
        BleTransportFactory.create()
    } catch (_: Exception) {
        null
    }

    /** Mutable holder for the packet handler target, set in [bindRepository]. */
    private var packetHandlerTarget: ((OfflinePacket, PeerIdentity) -> Unit)? = null

    /** Mesh router, or null if no transport is available. */
    val meshRouter: MeshRouter? = transport?.let {
        MeshRouter(
            localIdentity = identity,
            transport = it,
            packetHandler = { packet, from ->
                packetHandlerTarget?.invoke(packet, from)
            },
        )
    }

    /** SOS relay over mesh, or null if mesh is unavailable. */
    val sosRelay: SosMeshRelay? = meshRouter?.let {
        SosMeshRelay(it, identity.peerId, localRiderName)
    }

    /** Rider status relay over mesh, or null if mesh is unavailable. */
    val riderStatusRelay: RiderStatusMeshRelay? = meshRouter?.let {
        RiderStatusMeshRelay(it, identity.peerId, localRiderId, localRiderName)
    }

    /** Whether BLE mesh is currently active (advertising + scanning). */
    private val _isMeshActive = MutableStateFlow(transport != null)
    val isMeshActive: StateFlow<Boolean> = _isMeshActive.asStateFlow()

    /** Number of currently connected mesh peers. */
    private val _connectedPeerCount = MutableStateFlow(0)
    val connectedPeerCount: StateFlow<Int> = _connectedPeerCount.asStateFlow()

    /** Callback for rider status updates received from mesh peers. */
    var onRiderStatusReceived: ((RiderStatusMeshRelay.ParsedRiderStatus) -> Unit)? = null

    /**
     * Binds [repository] to this manager so incoming mesh packets are routed
     * through [RideRepository.onMeshSosReceived] and rider-status updates.
     * Must be called after [RideRepository] is constructed with the relays
     * from [sosRelay] and [riderStatusRelay].
     */
    fun bindRepository(repository: RideRepository) {
        packetHandlerTarget = { packet, from ->
            handleIncomingPacket(packet, from, repository)
        }
        _connectedPeerCount.value = meshRouter?.connectedPeers()?.size ?: 0
    }

    private fun handleIncomingPacket(
        packet: OfflinePacket,
        from: PeerIdentity,
        repository: RideRepository,
    ) {
        when (val parsed = sosRelay?.parseIncomingPacket(packet, from)) {
            is SosMeshRelay.ParsedSosMessage.SosAlert -> {
                repository.onMeshSosReceived(parsed.event)
            }
            else -> {
                riderStatusRelay?.parseIncomingPacket(packet, from)?.let { status ->
                    onRiderStatusReceived?.invoke(status)
                }
            }
        }
        _connectedPeerCount.value = meshRouter?.connectedPeers()?.size ?: 0
    }

    /** Starts BLE advertising and scanning. No-op if no transport is available. */
    fun start() {
        transport?.startAdvertising(identity, object : BleAdvertisingCallback {
            override fun onStarted() {}
            override fun onStartFailed(error: String) {}
            override fun onScanRequestReceived(peerId: ByteArray) {}
        })
        transport?.startScanning(object : BleScanCallback {
            override fun onPeerDiscovered(peer: PeerIdentity, rssi: Int) {}
            override fun onPeerUpdated(peer: PeerIdentity, rssi: Int) {}
            override fun onScanStopped() {}
        })
        _isMeshActive.value = transport != null
    }

    /** Stops BLE advertising and scanning. No-op if no transport is available. */
    fun stop() {
        transport?.stopAdvertising()
        transport?.stopScanning()
        _isMeshActive.value = false
    }
}
