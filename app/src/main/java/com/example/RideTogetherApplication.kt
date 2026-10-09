package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class RideTogetherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initFirebaseDefensively()
    }

    private fun initFirebaseDefensively() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId("1:13707892827:android:e982c3179342c585f0877e")
                    .setApiKey("AIzaSyBwTrOliLZV_Z2UkyCVjOKq2JbxoxOj0Yg")
                    .setProjectId("gen-lang-client-0349632077")
                    .build()
                FirebaseApp.initializeApp(this, options)
                Log.i("RideTogetherApp", "FirebaseApp initialized with fallback options")
            } else {
                Log.i("RideTogetherApp", "FirebaseApp already initialized by ContentProvider")
            }
        } catch (e: Exception) {
            Log.e("RideTogetherApp", "Defensive FirebaseApp initialization error: ${e.message}", e)
        }
    }
}
