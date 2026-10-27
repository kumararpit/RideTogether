package com.example.data.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Production [FirebaseTokenProvider] backed by Firebase Auth.
 *
 * Wraps [FirebaseUser.getIdToken](com.google.firebase.auth.FirebaseUser.getIdToken)
 * with forceRefresh = true to obtain a fresh ID token. On 401, forces a token
 * refresh so the next call returns a valid token.
 */
class FirebaseAuthTokenProvider : FirebaseTokenProvider {

    override suspend fun getFreshIdToken(): String? {
        return FirebaseAuth.getInstance().currentUser
            ?.getIdToken(true)   // forceRefresh = true
            ?.await()
            ?.token
    }

    override fun onUnauthorized() {
        FirebaseAuth.getInstance().currentUser?.getIdToken(true)  // fire refresh
    }
}
