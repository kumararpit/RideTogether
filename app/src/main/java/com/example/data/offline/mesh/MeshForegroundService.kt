package com.example.data.offline.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity

/**
 * Foreground service that keeps the offline mesh network alive when the app is
 * backgrounded. Maintains BLE advertising and scanning so nearby peers can still
 * discover and exchange SOS/rider-status packets while the rider's phone is in
 * a pocket or the screen is off.
 *
 * Started/stopped from [RideViewModel] when the ride moves between foreground
 * and background. The service creates and manages its own [MeshManager]
 * instance independent of the ViewModel's, avoiding cross-component state
 * sharing.
 *
 * Clean-room design: the foreground service lifecycle and notification pattern
 * mirror [RideLocationService] but are an original RideTogether design for
 * mesh connectivity.
 */
class MeshForegroundService : Service() {

    private var isServiceRunning = false
    private var meshManager: MeshManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP_MESH -> stopMeshService()
            else -> startMeshService(intent)
        }
        return START_STICKY
    }

    private fun startMeshService(intent: Intent?) {
        if (isServiceRunning) return
        isServiceRunning = true

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val flags = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, flags)
                } else {
                    startForeground(NOTIFICATION_ID, notification, flags)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting foreground mesh service: ${e.message}", e)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val riderId = intent?.getStringExtra(EXTRA_RIDER_ID) ?: "rider_unknown"
        val riderName = intent?.getStringExtra(EXTRA_RIDER_NAME) ?: "Rider"

        // Initialize mesh manager and start BLE advertising/scanning.
        meshManager = MeshManager(localRiderId = riderId, localRiderName = riderName)
        meshManager?.start()

        Log.d(TAG, "MeshForegroundService started: riderId=$riderId riderName=$riderName")
    }

    private fun stopMeshService() {
        if (!isServiceRunning) return
        isServiceRunning = false
        meshManager?.stop()
        meshManager = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "MeshForegroundService stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RideTogether Offline Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BLE advertising and scanning for offline group communication"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RideTogether Offline Mesh")
            .setContentText("BLE advertising and scanning for offline group communication")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopMeshService()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "mesh_channel"
        const val NOTIFICATION_ID = 2002
        const val ACTION_START_MESH = "com.example.action.START_MESH"
        const val ACTION_STOP_MESH = "com.example.action.STOP_MESH"
        const val EXTRA_RIDER_ID = "com.example.extra.RIDER_ID"
        const val EXTRA_RIDER_NAME = "com.example.extra.RIDER_NAME"
        private const val TAG = "MeshForegroundService"

        fun startService(context: Context, riderId: String, riderName: String) {
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_START_MESH
                putExtra(EXTRA_RIDER_ID, riderId)
                putExtra(EXTRA_RIDER_NAME, riderName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not startForegroundService: ${e.message}, falling back to startService", e)
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_STOP_MESH
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MeshForegroundService: ${e.message}")
            }
        }
    }
}
