package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.location.LocationTracker
import com.example.data.location.StopDetector
import com.example.data.map.TileMath
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.QuickMessageType
import com.example.data.repository.RideRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("RideTogether", appName)
    }

    @Test
    fun `test haversine distance calculation`() {
        val pune = LatLng(18.5204, 73.8567)
        val lonavala = LatLng(18.7546, 73.4062)
        val distanceKm = pune.distanceTo(lonavala)
        // Distance between Pune and Lonavala coordinates is ~54-55 km as the crow flies
        assertTrue("Distance should be approximately 54 km", distanceKm in 50.0..60.0)
    }

    @Test
    fun `test stop detector hysteresis`() {
        val detector = StopDetector(
            stopSpeedThresholdKmh = 5.0,
            resumeSpeedThresholdKmh = 8.0,
            stopDurationThresholdSec = 10L
        )

        // Speed 0 for 5 seconds -> still RIDING until threshold
        val (status1, _) = detector.processSpeed(0.0, 1000L)
        assertEquals(MemberStatus.RIDING, status1)

        // Speed 0 for 12 seconds -> becomes STOPPED
        val (status2, _) = detector.processSpeed(0.0, 15000L)
        assertEquals(MemberStatus.STOPPED, status2)

        // Speed 6.0 km/h (in hysteresis buffer between 5 and 8) -> stays STOPPED
        val (status3, _) = detector.processSpeed(6.0, 16000L)
        assertEquals(MemberStatus.STOPPED, status3)

        // Speed 9.0 km/h (above resume threshold) -> becomes RIDING
        val (status4, _) = detector.processSpeed(9.0, 17000L)
        assertEquals(MemberStatus.RIDING, status4)
    }

    @Test
    fun `test tile math projection`() {
        val lon = 73.8567
        val lat = 18.5204
        val zoom = 13
        val tileX = TileMath.lonToTileX(lon, zoom)
        val tileY = TileMath.latToTileY(lat, zoom)

        assertTrue(tileX > 0)
        assertTrue(tileY > 0)

        val tileUrl = TileMath.getCartoDarkTileUrl(zoom, tileX.toInt(), tileY.toInt())
        assertTrue(tileUrl.contains("cartodb-basemaps"))
        assertTrue(tileUrl.endsWith(".png"))
    }

    @Test
    fun `test ride repository flow`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val repo = RideRepository(tracker)
        repo.initCurrentUser("user_arpit", "Arpit")

        val ride = repo.createRide(
            rideName = "Pune → Lonavala",
            startName = "Pune",
            destName = "Lonavala",
            startLoc = LatLng(18.5204, 73.8567),
            destLoc = LatLng(18.7546, 73.4062)
        )

        assertEquals("Pune → Lonavala", ride.name)
        assertEquals(6, ride.inviteCode.length)
        assertEquals(1, repo.members.value.size)

        // Add pack member
        repo.addPackMember("Rahul", 0.005, 0.004)
        assertEquals(2, repo.members.value.size)

        // Test Quick Message
        repo.sendQuickMessage(QuickMessageType.WAIT_FOR_ME)
        assertEquals(QuickMessageType.WAIT_FOR_ME, repo.recentMessage.value?.type)
        assertEquals("Wait for me", repo.recentMessage.value?.text)

        // Test SOS trigger
        repo.triggerSos()
        assertNotNull(repo.activeSos.value)
        assertEquals("Arpit", repo.activeSos.value?.riderName)

        // Test SOS resolve
        repo.resolveSos()
        assertEquals(null, repo.activeSos.value)
    }
}
