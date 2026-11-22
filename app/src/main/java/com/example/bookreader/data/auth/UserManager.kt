package com.example.bookreader.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserProfile(
    val userId: String,
    val displayName: String,
    val email: String? = null,
    val photoUrl: String? = null,
    val isLoggedIn: Boolean = false
)

class UserManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    private val _currentUser = MutableStateFlow(getInitialUser())
    val currentUser: StateFlow<UserProfile> = _currentUser.asStateFlow()

    private fun getGuestProfile(): UserProfile {
        val savedGuestId = prefs.getString("guest_user_id", null) ?: run {
            val newId = "guest_${System.currentTimeMillis().toString().takeLast(6)}"
            prefs.edit().putString("guest_user_id", newId).apply()
            newId
        }
        return UserProfile(
            userId = savedGuestId,
            displayName = "本地访客",
            email = null,
            photoUrl = null,
            isLoggedIn = false
        )
    }

    private fun getInitialUser(): UserProfile {
        if (prefs.getBoolean(KEY_LOGGED_IN, false)) {
            val userId = prefs.getString(KEY_USER_ID, null)
            if (!userId.isNullOrBlank()) {
                return UserProfile(
                    userId = userId,
                    displayName = prefs.getString(KEY_USER_NAME, null) ?: "Google reader",
                    email = prefs.getString(KEY_USER_EMAIL, null),
                    photoUrl = prefs.getString(KEY_USER_PHOTO, null),
                    isLoggedIn = true
                )
            }
        }
        return getGuestProfile()
    }

    private fun saveUserSession(profile: UserProfile) {
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_USER_ID, profile.userId)
            .putString(KEY_USER_NAME, profile.displayName)
            .putString(KEY_USER_EMAIL, profile.email)
            .putString(KEY_USER_PHOTO, profile.photoUrl)
            .apply()
    }

    /**
     * 获取 Google 登录客户端 Intent
     */
    fun getGoogleSignInClient(activityContext: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(activityContext, gso)
    }

    fun trySilentSignIn(activityContext: Context) {
        if (_currentUser.value.isLoggedIn) return
        val lastAccount = GoogleSignIn.getLastSignedInAccount(activityContext) ?: return
        signInWithGoogle(lastAccount)
    }

    fun signInWithGoogle(account: GoogleSignInAccount): Result<UserProfile> {
        val accountId = account.id
        if (accountId.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Google account id is missing"))
        }
        val profile = UserProfile(
            userId = accountId,
            displayName = account.displayName?.takeIf { it.isNotBlank() }
                ?: account.email?.substringBefore('@')
                ?: "Google reader",
            email = account.email,
            photoUrl = account.photoUrl?.toString(),
            isLoggedIn = true
        )
        _currentUser.value = profile
        saveUserSession(profile)
        return Result.success(profile)
    }

    fun signOut(activityContext: Context) {
        try {
            getGoogleSignInClient(activityContext).signOut()
        } catch (e: Exception) {
            // Ignore client sign-out failures.
        }
        prefs.edit().clear().apply()
        _currentUser.value = UserProfile(
            userId = "",
            displayName = "Signed out",
            email = null,
            photoUrl = null,
            isLoggedIn = false
        )
    }

    companion object {
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_USER_ID = "current_user_id"
        private const val KEY_USER_NAME = "current_user_name"
        private const val KEY_USER_EMAIL = "current_user_email"
        private const val KEY_USER_PHOTO = "current_user_photo"
    }
}
