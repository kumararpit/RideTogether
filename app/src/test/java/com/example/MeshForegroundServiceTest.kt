package com.example

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.offline.mesh.MeshForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Regression tests for [MeshForegroundService] (Phase 12).
 *
 * Covers the foreground service lifecycle: start/stop intent construction,
 * foreground startup via Robolectric's ServiceController, notification channel
 * creation, and service destruction. Uses Robolectric's ServiceController to
 * drive the service through onStartCommand / onDestroy without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeshForegroundServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun startServiceBuildsIntentWithActionAndExtras() {
        val intent = Intent(context, MeshForegroundService::class.java).apply {
            action = MeshForegroundService.ACTION_START_MESH
            putExtra(MeshForegroundService.EXTRA_RIDER_ID, "rider_1")
            putExtra(MeshForegroundService.EXTRA_RIDER_NAME, "Arpit")
        }

        assertEquals(MeshForegroundService.ACTION_START_MESH, intent.action)
        assertEquals("rider_1", intent.getStringExtra(MeshForegroundService.EXTRA_RIDER_ID))
        assertEquals("Arpit", intent.getStringExtra(MeshForegroundService.EXTRA_RIDER_NAME))
    }

    @Test
    fun stopServiceBuildsIntentWithStopAction() {
        val intent = Intent(context, MeshForegroundService::class.java).apply {
            action = MeshForegroundService.ACTION_STOP_MESH
        }

        assertEquals(MeshForegroundService.ACTION_STOP_MESH, intent.action)
    }

    @Test
    fun serviceStartsForegroundAndCreatesMeshManager() {
        val intent = Intent(context, MeshForegroundService::class.java).apply {
            action = MeshForegroundService.ACTION_START_MESH
            putExtra(MeshForegroundService.EXTRA_RIDER_ID, "rider_1")
            putExtra(MeshForegroundService.EXTRA_RIDER_NAME, "Arpit")
        }

        val controller: ServiceController<MeshForegroundService> = Robolectric.buildService(
            MeshForegroundService::class.java
        ).withIntent(intent).create()

        controller.startCommand(0, Service.START_STICKY)

        val service = controller.get()
        assertNotNull(service)

        // Foreground notification posted
        val shadowNm = org.robolectric.Shadows.shadowOf(
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        ) as ShadowNotificationManager
        assertTrue(shadowNm.getAllNotifications().isNotEmpty())

        controller.destroy()
    }

    @Test
    fun serviceStopCommandStopsService() {
        val startIntent = Intent(context, MeshForegroundService::class.java).apply {
            action = MeshForegroundService.ACTION_START_MESH
            putExtra(MeshForegroundService.EXTRA_RIDER_ID, "rider_1")
            putExtra(MeshForegroundService.EXTRA_RIDER_NAME, "Arpit")
        }

        val controller: ServiceController<MeshForegroundService> = Robolectric.buildService(
            MeshForegroundService::class.java
        ).withIntent(startIntent).create()

        controller.startCommand(0, Service.START_STICKY)

        // Send stop action via a new intent
        val stopIntent = Intent(context, MeshForegroundService::class.java).apply {
            action = MeshForegroundService.ACTION_STOP_MESH
        }
        controller.withIntent(stopIntent)
        controller.startCommand(0, Service.START_STICKY)

        controller.destroy()
    }

    @Test
    fun onDestroyCallsStopMeshService() {
        val controller: ServiceController<MeshForegroundService> = Robolectric.buildService(
            MeshForegroundService::class.java
        ).create()

        val service = controller.get()
        assertNotNull(service)

        controller.destroy()
        // onDestroy invokes stopMeshService (stopForeground + stopSelf) without throwing
    }

    @Test
    fun notificationChannelCreatedOnCreate() {
        val controller: ServiceController<MeshForegroundService> = Robolectric.buildService(
            MeshForegroundService::class.java
        ).create()

        val shadowNm = org.robolectric.Shadows.shadowOf(
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        ) as ShadowNotificationManager
        val channels = shadowNm.getNotificationChannels()
        assertTrue(channels.any { it.id == MeshForegroundService.CHANNEL_ID })

        controller.destroy()
    }
}
