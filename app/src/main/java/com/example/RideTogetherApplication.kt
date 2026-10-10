package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class RideTogetherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initFirebase()
    }

    private fun initFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val explicitOptions = FirebaseOptions.Builder()
                    .setApplicationId("1:186359431230:android:0250056c46cfaa074b916f")
                    .setApiKey("AIzaSyCLdbh6aE85_P5DJ0scAC-Ye-FWjRmotxM")
                    .setProjectId("ridetogether-f558a")
                    .setGcmSenderId("186359431230")
                    .setStorageBucket("ridetogether-f558a.firebasestorage.app")
                    .build()

                try {
                    FirebaseApp.initializeApp(this, explicitOptions)
                    Log.i("RideTogetherApp", "FirebaseApp initialized with explicit options successfully")
                } catch (e: Exception) {
                    Log.w("RideTogetherApp", "Explicit FirebaseApp init threw, trying default: ${e.message}")
                    FirebaseApp.initializeApp(this)
                }
            } else {
                Log.i("RideTogetherApp", "FirebaseApp already initialized by ContentProvider")
            }
        } catch (e: Exception) {
            Log.e("RideTogetherApp", "Firebase initialization error: ${e.message}", e)
        }
    }
}
