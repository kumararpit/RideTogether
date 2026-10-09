package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.location.LocationTracker
import com.example.data.location.StopDetector
import com.example.data.map.MapProvider
import com.example.data.map.TileMath
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.QuickMessageType
import com.example.data.model.RideRole
import com.example.data.model.RiderMember
import com.example.data.firebase.RideRepository
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
    fun `test tile math projection and osm user agent`() {
        val lon = 73.8567
        val lat = 18.5204
        val zoom = 13
        val tileX = TileMath.lonToTileX(lon, zoom)
        val tileY = TileMath.latToTileY(lat, zoom)

        assertTrue(tileX > 0)
        assertTrue(tileY > 0)

        val topoUrl = TileMath.getTopoTileUrl(zoom, tileX.toInt(), tileY.toInt())
        assertTrue(topoUrl.contains("opentopomap.org"))
        assertTrue(topoUrl.endsWith(".png"))

        val osmUrl = TileMath.getOsmTileUrl(zoom, tileX.toInt(), tileY.toInt())
        assertTrue(osmUrl.contains("tile.openstreetmap.org/"))

        assertTrue(TileMath.OSM_USER_AGENT.contains("RideTogether"))
    }

    @Test
    fun `test map provider abstraction implementation`() {
        var showedMap = false
        var addedRiderId: String? = null
        var routeCount = 0

        val provider = object : MapProvider {
            override fun showMap() { showedMap = true }
            override fun addRiderMarker(rider: RiderMember) { addedRiderId = rider.id }
            override fun updateRiderMarker(rider: RiderMember) {}
            override fun removeRiderMarker(riderId: String) {}
            override fun drawRoute(points: List<LatLng>) { routeCount = points.size }
            override fun setCenter(latLng: LatLng, zoom: Float) {}
            override fun fitBounds(points: List<LatLng>) {}
            override suspend fun calculateAndDrawRoute(start: LatLng, destination: LatLng): com.example.data.model.RouteResult {
                val pts = listOf(start, destination)
                drawRoute(pts)
                return com.example.data.model.RouteResult(pts, 1000.0, 60.0, emptyList())
            }
        }

        provider.showMap()
        assertTrue(showedMap)

        val rider = RiderMember(
            id = "rider_1",
            name = "Arpit",
            avatarColorHex = 0xFFFF9800,
            role = RideRole.LEADER,
            status = MemberStatus.RIDING,
            location = LatLng(18.5204, 73.8567),
            speedKmh = 45.0,
            headingDeg = 180f,
            lastUpdatedMs = System.currentTimeMillis()
        )
        provider.addRiderMarker(rider)
        assertEquals("rider_1", addedRiderId)

        provider.drawRoute(listOf(LatLng(18.52, 73.85), LatLng(18.75, 73.40)))
        assertEquals(2, routeCount)

        // Test MapLibreMapProvider with OSRM integration
        val mapLibreProvider = com.example.data.map.MapLibreMapProvider()
        mapLibreProvider.showMap()
        assertTrue(mapLibreProvider.isMapVisible)
        mapLibreProvider.addRiderMarker(rider)
        assertEquals(1, mapLibreProvider.riderMarkers.value.size)
        mapLibreProvider.drawRoute(listOf(LatLng(18.52, 73.85), LatLng(18.75, 73.40)))
        assertEquals(2, mapLibreProvider.routePoints.value.size)
    }

    @Test
    fun `test ride repository flow`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            val options = com.google.firebase.FirebaseOptions.Builder()
                .setApplicationId("1:13707892827:android:e982c3179342c585f0877e")
                .setApiKey("AIzaSyBwTrOliLZV_Z2UkyCVjOKq2JbxoxOj0Yg")
                .setProjectId("gen-lang-client-0349632077")
                .build()
            com.google.firebase.FirebaseApp.initializeApp(context, options)
        }
        val tracker = LocationTracker(context)
        val repo = RideRepository(context, tracker)
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

        // Test Route Update
        val pts = listOf(LatLng(18.52, 73.85), LatLng(18.60, 73.65), LatLng(18.75, 73.40))
        repo.updateRoute(
            routePoints = pts,
            totalDistanceMeters = 64000.0,
            totalDurationSeconds = 4500.0,
            steps = listOf(
                com.example.data.model.NavigationStep("Depart onto highway", "NH48", 12000.0, 700.0)
            )
        )
        assertEquals(3, repo.currentRide.value?.routePoints?.size)
        assertEquals(64000.0, repo.currentRide.value?.totalDistanceMeters ?: 0.0, 0.1)
    }

    @Test
    fun `test route geometry helpers`() {
        val p1 = LatLng(18.5204, 73.8567)
        val p2 = LatLng(18.6000, 73.7000)
        val p3 = LatLng(18.7546, 73.4062)
        val route = listOf(p1, p2, p3)

        // Closest point index
        val nearP2 = LatLng(18.6010, 73.7010)
        val closestIdx = com.example.data.location.RouteGeometry.findClosestPointIndex(nearP2, route)
        assertEquals(1, closestIdx)

        // Remaining distance along route
        val remDist = com.example.data.location.RouteGeometry.remainingDistanceMeters(1, route)
        assertTrue("Remaining distance should be positive", remDist > 0)

        // Distance to polyline
        val distMeters = com.example.data.location.RouteGeometry.distanceToPolylineMeters(nearP2, route)
        assertTrue("Distance to polyline should be small (< 500m)", distMeters < 500.0)
    }
}
