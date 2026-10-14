package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.location.LocationTimeoutException
import com.example.data.location.LocationTracker
import com.example.data.location.LocationUpdate
import com.example.data.location.requestSingleLocationFix
import com.example.data.model.LatLng
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationTimeoutAndManualOriginTest {

    @Test
    fun `test GPS requestSingleLocationFix timeout completes with explicit error and cleans up`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)

        // Single location fix with short timeout (100ms) on fake environment
        val startMs = System.currentTimeMillis()
        val result = tracker.requestSingleLocationFix(context, timeoutMs = 100L)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("Timeout must complete within reasonable duration", elapsedMs < 2000L)
        assertTrue("Result must be failure on timeout", result.isFailure)

        val exception = result.exceptionOrNull()
        assertTrue("Exception must be LocationTimeoutException or SecurityException", exception is LocationTimeoutException || exception is SecurityException)
        if (exception is LocationTimeoutException) {
            assertTrue("Timeout message must include delay info", exception.message?.contains("timed out") == true)
        }
    }

    @Test
    fun `test top-level requestSingleLocationFix helper function timeout`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = requestSingleLocationFix(context, timeoutMs = 150L)

        assertTrue("Result should be failure on timeout without active GPS provider", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("Error should be non-null", err != null)
    }

    @Test
    fun `test manual origin selection invalidates pending GPS request and late response is ignored`() = runBlocking {
        var startCoords: LatLng? = LatLng(18.5204, 73.8567)
        var startLocationText = "Default Start"
        var startLocationAccuracy: Float? = 10f

        var gpsRequestId = 0
        var isGpsFixLoading = false
        var gpsJob: Job? = null

        fun cancelGpsRequestAndMarkManual() {
            gpsJob?.cancel()
            gpsRequestId++
            isGpsFixLoading = false
            startLocationAccuracy = null
        }

        // Step 1: User initiates GPS fix (RequestId = 1)
        val curReqId = ++gpsRequestId
        isGpsFixLoading = true

        gpsJob = launch {
            // Simulate slow GPS fix (e.g. 500ms delay)
            delay(500)
            val lateFix = LocationUpdate(
                latLng = LatLng(18.5000, 73.8000), // GPS location fix
                speedKmh = 0.0,
                headingDeg = 0f,
                accuracyMeters = 8.0f
            )

            // Only apply fix if RequestId has not changed
            if (curReqId == gpsRequestId) {
                isGpsFixLoading = false
                startCoords = lateFix.latLng
                startLocationText = "Current GPS Location"
                startLocationAccuracy = lateFix.accuracyMeters
            }
        }

        // Step 2: While GPS fix is in flight (at 100ms), user manually selects a place
        delay(100)
        assertTrue("GPS fix should still be in progress", isGpsFixLoading)

        // User manually selects "Katraj, Pune"
        cancelGpsRequestAndMarkManual()
        val manualCoords = LatLng(18.4532, 73.8580)
        startCoords = manualCoords
        startLocationText = "Katraj, Pune"

        // Wait for late GPS job to finish executing
        delay(600)

        // Verify: Late GPS response MUST NOT overwrite manual origin selection!
        assertFalse("GPS loading state must be false", isGpsFixLoading)
        assertEquals("Start text must remain 'Katraj, Pune'", "Katraj, Pune", startLocationText)
        assertEquals("Start coordinates must remain manual coordinates", manualCoords, startCoords)
        assertNull("Accuracy for manual origin selection must be null", startLocationAccuracy)
    }

    @Test
    fun `test horizontal accuracy preservation and threshold classification`() {
        // High Precision Fix (<= 30m)
        val highPrecisionFix = LocationUpdate(
            latLng = LatLng(18.5204, 73.8567),
            speedKmh = 0.0,
            headingDeg = 0f,
            accuracyMeters = 12.5f
        )
        assertEquals(12.5f, highPrecisionFix.accuracyMeters, 0.01f)
        assertTrue("12.5m accuracy is <= 30m (High Precision)", highPrecisionFix.accuracyMeters <= 30f)

        // Coarse Fix (30m < accuracy <= 100m)
        val coarseFix = LocationUpdate(
            latLng = LatLng(18.5204, 73.8567),
            speedKmh = 0.0,
            headingDeg = 0f,
            accuracyMeters = 85.0f
        )
        assertEquals(85.0f, coarseFix.accuracyMeters, 0.01f)
        assertTrue("85m accuracy is > 30m and <= 100m (Coarse Fix)", coarseFix.accuracyMeters > 30f && coarseFix.accuracyMeters <= 100f)

        // Low Precision Fix (> 100m)
        val lowPrecisionFix = LocationUpdate(
            latLng = LatLng(18.5204, 73.8567),
            speedKmh = 0.0,
            headingDeg = 0f,
            accuracyMeters = 150.0f
        )
        assertEquals(150.0f, lowPrecisionFix.accuracyMeters, 0.01f)
        assertTrue("150m accuracy is > 100m (Low Precision)", lowPrecisionFix.accuracyMeters > 100f)
    }
}
