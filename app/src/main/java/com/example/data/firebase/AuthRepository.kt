package com.example.data.firebase

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() }
) {
    val auth: FirebaseAuth?
        get() = try { authProvider() } catch (e: Exception) { null }

    val currentUser: FirebaseUser?
        get() = auth?.currentUser

    fun authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
        val fbAuth = auth
        if (fbAuth == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        val listener = FirebaseAuth.AuthStateListener { fb ->
            trySend(fb.currentUser)
        }
        fbAuth.addAuthStateListener(listener)
        awaitClose { fbAuth.removeAuthStateListener(listener) }
    }

    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    suspend fun signInWithGoogle(context: Context): Result<FirebaseUser> {
        val activityContext = context.findActivity() ?: context
        val credentialManager = CredentialManager.create(activityContext)
        val serverClientId = resolveWebClientId(context)

        if (serverClientId.isBlank()) {
            return Result.failure(
                IllegalStateException("Firebase Web Client ID is not configured. Please ensure google-services.json or BuildConfig.FIREBASE_WEB_CLIENT_ID is provided.")
            )
        }

        return try {
            // Priority 1: Interactive GetSignInWithGoogleOption (Standard button dialog)
            val googleIdOption = GetSignInWithGoogleOption.Builder(serverClientId)
                .build()

            val primaryRequest = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response = try {
                credentialManager.getCredential(context = activityContext, request = primaryRequest)
            } catch (e: GetCredentialException) {
                // If primary request encounters NoCredentialException or error 16, try GetGoogleIdOption without authorized-account filter
                Log.w("AuthRepository", "Primary Google sign-in encountered ${e.javaClass.simpleName}: ${e.message}, attempting GetGoogleIdOption fallback")
                val fallbackOption = com.google.android.libraries.identity.googleid.GetGoogleIdOption.Builder()
                    .setServerClientId(serverClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
                val fallbackRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(fallbackOption)
                    .build()
                credentialManager.getCredential(context = activityContext, request = fallbackRequest)
            }

            val credential = response.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val authCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                val fbAuth = auth ?: return Result.failure(IllegalStateException("Firebase Auth service unavailable"))
                val authResult = fbAuth.signInWithCredential(authCredential).await()
                val user = authResult.user ?: error("FirebaseUser is null after Google sign-in")
                Result.success(user)
            } else {
                Result.failure(IllegalStateException("Unexpected credential type: ${credential.type}"))
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("AuthRepository", "Google sign-in was cancelled by user: ${e.message}")
            Result.failure(e)
        } catch (e: androidx.credentials.exceptions.NoCredentialException) {
            Log.e("AuthRepository", "NoCredentialException during Google sign-in", e)
            val friendlyMsg = "No Google Account found or authorized. Please ensure a Google Account is added to this device in Settings > Accounts, then tap Sign In again."
            Result.failure(Exception(friendlyMsg, e))
        } catch (e: GetCredentialException) {
            Log.e("AuthRepository", "CredentialManager exception during Google sign-in", e)
            val msg = e.message.orEmpty()
            val friendlyMsg = when {
                msg.contains("16") || msg.contains("Account reauth failed") ->
                    "Google Play account re-authentication required. Please check that a Google Account is active on your device and tap Sign In again."
                msg.contains("No credentials available", ignoreCase = true) ->
                    "No Google Account found on this device. Please sign in to a Google account in Android Settings > Accounts, then retry."
                else -> e.localizedMessage ?: "Google sign-in failed. Please try again."
            }
            Result.failure(Exception(friendlyMsg, e))
        } catch (e: Exception) {
            Log.e("AuthRepository", "Authentication failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut(context: Context) {
        try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w("AuthRepository", "Error clearing credential state: ${e.message}")
        }
        try {
            auth?.signOut()
        } catch (e: Exception) {
            Log.w("AuthRepository", "Error during FirebaseAuth signOut: ${e.message}")
        }
    }

    /**
     * Resolves the Web Client ID for Google Sign-In:
     * 1. Dynamic string resource `default_web_client_id` (generated by google-services plugin from google-services.json)
     * 2. BuildConfig.FIREBASE_WEB_CLIENT_ID (optional build-time override)
     */
    fun resolveWebClientId(context: Context): String {
        // Priority 1: Resource generated by google-services plugin from google-services.json
        try {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) {
                val generatedId = context.getString(resId)
                if (generatedId.isNotBlank()) {
                    return generatedId
                }
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Could not locate default_web_client_id resource", e)
        }

        // Priority 2: BuildConfig field override if configured
        if (BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank()) {
            return BuildConfig.FIREBASE_WEB_CLIENT_ID
        }

        return ""
    }
}
