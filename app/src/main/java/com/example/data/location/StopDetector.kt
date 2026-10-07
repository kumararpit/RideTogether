package com.example.data.location

import com.example.data.model.MemberStatus

class StopDetector(
    var stopSpeedThresholdKmh: Double = 5.0,
    var resumeSpeedThresholdKmh: Double = 8.0,
    var stopDurationThresholdSec: Long = 120L // 2 minutes per spec
) {
    private var lowSpeedStartTimestampMs: Long? = null
    private var currentStatus: MemberStatus = MemberStatus.RIDING

    /**
     * Updates state based on current speed and elapsed time.
     * Returns true if status transitioned.
     */
    fun processSpeed(speedKmh: Double, currentTimeMs: Long = System.currentTimeMillis()): Pair<MemberStatus, Long> {
        if (speedKmh < stopSpeedThresholdKmh) {
            if (lowSpeedStartTimestampMs == null) {
                lowSpeedStartTimestampMs = currentTimeMs
            }
            val elapsedSec = (currentTimeMs - (lowSpeedStartTimestampMs ?: currentTimeMs)) / 1000
            if (elapsedSec >= stopDurationThresholdSec) {
                currentStatus = MemberStatus.STOPPED
            }
            return Pair(currentStatus, elapsedSec)
        } else if (speedKmh >= resumeSpeedThresholdKmh) {
            lowSpeedStartTimestampMs = null
            currentStatus = MemberStatus.RIDING
            return Pair(MemberStatus.RIDING, 0L)
        } else {
            // In hysteresis zone between 5.0 and 8.0 km/h: maintain previous status
            val elapsedSec = lowSpeedStartTimestampMs?.let { (currentTimeMs - it) / 1000 } ?: 0L
            return Pair(currentStatus, elapsedSec)
        }
    }

    fun reset() {
        lowSpeedStartTimestampMs = null
        currentStatus = MemberStatus.RIDING
    }
}
