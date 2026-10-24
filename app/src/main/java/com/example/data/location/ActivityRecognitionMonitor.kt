package com.example.data.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coarse movement classification derived from Play services activity updates. */
enum class MotionState { UNKNOWN, STILL, MOVING }

/**
 * Independent "is the rider moving?" signal.
 *
 * Evidence (Google Maps 26.42.01, `aizh.x`): the GNSS bearing gate is
 * `speed > 3.0 m/s || activityMoving`. The activity term exists so a walking or
 * bike-pushing rider still gets a heading below the driving threshold.
 *
 * RideTogether keeps the reference's *intent* but not its unconditional OR: the
 * gate additionally requires non-zero motion, because a stale "in vehicle"
 * reading at a red light must never keep a noisy bearing alive.
 *
 * The monitor is a process-wide singleton because the result arrives on a
 * [android.content.BroadcastReceiver], which Android instantiates itself.
 */
object ActivityRecognitionMonitor {

    /** The reference requests activity updates every 5 s. */
    const val DEFAULT_INTERVAL_MS = 5_000L

    private const val REQUEST_CODE = 0x52A1

    private val _state = MutableStateFlow(MotionState.UNKNOWN)

    /** Latest motion classification; [MotionState.UNKNOWN] until the first result. */
    val state: StateFlow<MotionState> = _state.asStateFlow()

    /** True only for a confident "moving" classification. */
    val isMoving: Boolean
        get() = _state.value == MotionState.MOVING

    @Volatile
    private var registered = false

    /**
     * `ACTIVITY_RECOGNITION` is only a runtime permission from Android 10 (API 29).
     * Below that it is granted at install time.
     */
    fun isPermitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts activity updates. Returns false when the permission is missing, the
     * device has no Play services, or registration throws — callers must treat
     * the signal as optional and degrade to speed-only gating.
     */
    fun start(context: Context, intervalMs: Long = DEFAULT_INTERVAL_MS): Boolean {
        if (registered) return true
        if (!isPermitted(context)) return false
        return try {
            ActivityRecognition.getClient(context.applicationContext)
                .requestActivityUpdates(intervalMs, pendingIntent(context))
            registered = true
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Stops activity updates and clears the classification. */
    fun stop(context: Context) {
        if (!registered) return
        registered = false
        try {
            ActivityRecognition.getClient(context.applicationContext)
                .removeActivityUpdates(pendingIntent(context))
        } catch (_: Exception) {
            // Best effort: the ride is ending anyway.
        }
        _state.value = MotionState.UNKNOWN
    }

    /** Called by [ActivityRecognitionReceiver] on the Play services callback. */
    fun onActivityResult(result: ActivityRecognitionResult?) {
        if (result == null) return
        _state.value = motionStateFor(result.mostProbableActivity.type)
    }

    /**
     * Maps a Play services detected-activity type to a motion classification.
     *
     * `TILTING` and `UNKNOWN` stay [MotionState.UNKNOWN] rather than being
     * optimistically counted as movement.
     */
    @Suppress("DEPRECATION")
    fun motionStateFor(activityType: Int): MotionState = when (activityType) {
        DetectedActivity.STILL -> MotionState.STILL

        DetectedActivity.IN_VEHICLE,
        DetectedActivity.ON_BICYCLE,
        DetectedActivity.ON_FOOT,
        DetectedActivity.WALKING,
        DetectedActivity.RUNNING -> MotionState.MOVING

        else -> MotionState.UNKNOWN
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, ActivityRecognitionReceiver::class.java)
        return PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
