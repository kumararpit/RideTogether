package com.example.data.auth

/**
 * Obtains a fresh Firebase ID token and handles expiration / 401.
 *
 * Production implementation wraps [com.google.firebase.auth.FirebaseUser.getIdToken]
 * with forceRefresh = true. The token is passed to the trusted backend for
 * verification before any sensitive Supabase write.
 */
interface FirebaseTokenProvider {

    /**
     * Returns a fresh ID token, refreshing if expired or null.
     *
     * @return the Firebase ID token string, or null if the user is signed out.
     */
    suspend fun getFreshIdToken(): String?

    /**
     * Call on HTTP 401 to force a token refresh before retry.
     */
    fun onUnauthorized()
}
