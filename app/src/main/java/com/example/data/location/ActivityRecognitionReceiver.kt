package com.example.data.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult

/**
 * Receives the Play services activity-update broadcast and forwards it to
 * [ActivityRecognitionMonitor]. Declared in the manifest (the system
 * instantiates it), so it holds no state of its own.
 */
class ActivityRecognitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        ActivityRecognitionMonitor.onActivityResult(
            ActivityRecognitionResult.extractResult(intent)
        )
    }
}
