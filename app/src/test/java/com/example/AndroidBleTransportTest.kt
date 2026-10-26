package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.offline.ble.AndroidBleTransport
import com.example.data.offline.ble.BleAdvertisingCallback
import com.example.data.offline.ble.BleScanCallback
import com.example.data.offline.crypto.PeerIdentity
import com.example.data.offline.crypto.generate
import com.example.data.offline.protocol.PeerId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for [AndroidBleTransport] (Phase 12 stub).
 *
 * The stub exercises the transport lifecycle contract (advertising/scanning
 * state flags, callback invocation, connect returning null, shutdown) without
 * invoking real platform BLE APIs. Real BLE behavior is verified on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBleTransportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun startAdvertisingSetsIsAdvertisingTrueAndInvokesCallback() {
        val transport = AndroidBleTransport(context)
        var started = false
        var failed = false
        transport.startAdvertising(generate(), object : BleAdvertisingCallback {
            override fun onStarted() { started = true }
            override fun onStartFailed(error: String) { failed = true }
            override fun onScanRequestReceived(peerId: ByteArray) {}
        })
        assertTrue(transport.isAdvertising.value)
        assertTrue(started)
        assertFalse(failed)
        transport.shutdown()
    }

    @Test
    fun stopAdvertisingSetsIsAdvertisingFalse() {
        val transport = AndroidBleTransport(context)
        transport.startAdvertising(generate(), object : BleAdvertisingCallback {
            override fun onStarted() {}
            override fun onStartFailed(error: String) {}
            override fun onScanRequestReceived(peerId: ByteArray) {}
        })
        assertTrue(transport.isAdvertising.value)

        transport.stopAdvertising()
        assertFalse(transport.isAdvertising.value)
        transport.shutdown()
    }

    @Test
    fun startScanningSetsIsScanningTrue() {
        val transport = AndroidBleTransport(context)
        transport.startScanning(object : BleScanCallback {
            override fun onPeerDiscovered(peer: PeerIdentity, rssi: Int) {}
            override fun onPeerUpdated(peer: PeerIdentity, rssi: Int) {}
            override fun onScanStopped() {}
        })
        assertTrue(transport.isScanning.value)
        transport.shutdown()
    }

    @Test
    fun stopScanningSetsIsScanningFalse() {
        val transport = AndroidBleTransport(context)
        transport.startScanning(object : BleScanCallback {
            override fun onPeerDiscovered(peer: PeerIdentity, rssi: Int) {}
            override fun onPeerUpdated(peer: PeerIdentity, rssi: Int) {}
            override fun onScanStopped() {}
        })
        assertTrue(transport.isScanning.value)

        transport.stopScanning()
        assertFalse(transport.isScanning.value)
        transport.shutdown()
    }

    @Test
    fun connectReturnsNull() {
        val transport = AndroidBleTransport(context)
        val peer = PeerIdentity(
            peerId = PeerId.BROADCAST,
            x25519PublicKey = ByteArray(32),
            ed25519PublicKey = ByteArray(32),
            firstSeenMs = System.currentTimeMillis(),
        )
        val connection = transport.connect(peer)
        assertNull(connection)
        transport.shutdown()
    }

    @Test
    fun shutdownClearsState() {
        val transport = AndroidBleTransport(context)
        transport.startAdvertising(generate(), object : BleAdvertisingCallback {
            override fun onStarted() {}
            override fun onStartFailed(error: String) {}
            override fun onScanRequestReceived(peerId: ByteArray) {}
        })
        transport.startScanning(object : BleScanCallback {
            override fun onPeerDiscovered(peer: PeerIdentity, rssi: Int) {}
            override fun onPeerUpdated(peer: PeerIdentity, rssi: Int) {}
            override fun onScanStopped() {}
        })
        assertTrue(transport.isAdvertising.value)
        assertTrue(transport.isScanning.value)

        transport.shutdown()
        assertFalse(transport.isAdvertising.value)
        assertFalse(transport.isScanning.value)
    }

    @Test
    fun startAdvertisingWhileAlreadyActiveDoesNotRestart() {
        val transport = AndroidBleTransport(context)
        var startCount = 0
        transport.startAdvertising(generate(), object : BleAdvertisingCallback {
            override fun onStarted() { startCount++ }
            override fun onStartFailed(error: String) {}
            override fun onScanRequestReceived(peerId: ByteArray) {}
        })
        transport.startAdvertising(generate(), object : BleAdvertisingCallback {
            override fun onStarted() { startCount++ }
            override fun onStartFailed(error: String) {}
            override fun onScanRequestReceived(peerId: ByteArray) {}
        })
        assertTrue(transport.isAdvertising.value)
        assertEquals(1, startCount)
        transport.shutdown()
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
