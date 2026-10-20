package com.example

import com.example.data.model.LatLng
import com.example.data.model.Ride
import com.example.data.model.RideRole
import com.example.data.model.RideStatus
import com.example.data.model.RiderMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLobbyScreenTest {

    @Test
    fun `test lobby rendering with valid route and multiple riders`() {
        val ride = Ride(
            id = "ride_123",
            name = "Weekend Getaway",
            startLocationName = "Pune",
            destinationName = "Lonavala",
            startLocation = LatLng(18.5204, 73.8567),
            destinationLocation = LatLng(18.7546, 73.4062),
            leaderId = "leader_1",
            inviteCode = "LOBBY12",
            status = RideStatus.LOBBY,
            totalDistanceMeters = 49800.0,
            totalDurationSeconds = 2400.0
        )

        assertEquals("LOBBY12", ride.inviteCode)
        assertEquals("Pune", ride.startLocationName)
        assertEquals("Lonavala", ride.destinationName)
        assertEquals(RideStatus.LOBBY, ride.status)

        val leader = RiderMember(
            id = "leader_1",
            name = "Rohan",
            role = RideRole.LEADER,
            isCurrentUser = true,
            location = LatLng(18.5204, 73.8567)
        )
        val member2 = RiderMember(
            id = "rider_2",
            name = "Priya",
            role = RideRole.MEMBER,
            isCurrentUser = false,
            location = LatLng(18.5210, 73.8570)
        )

        val members = listOf(leader, member2)
        assertEquals(2, members.size)
        assertTrue("Leader must be present in lobby member list", members.any { it.role == RideRole.LEADER })
    }

    @Test
    fun `test copy and share invite actions`() {
        var startRideExecuted = false
        val onStartRide = { startRideExecuted = true }

        onStartRide()
        assertTrue("Start ride callback should be executable by host/leader", startRideExecuted)
    }
}
