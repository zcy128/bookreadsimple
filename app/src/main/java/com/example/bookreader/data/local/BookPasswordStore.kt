package com.example.bookreader.data.local

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One password shared by every protected book for the signed-in user.
 */
data class StoredPassword(
    val salt: String,
    val hash: String,
    val updatedAt: Long
)

class BookPasswordStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPassword(userId: String): Boolean {
        return read(userId) != null
    }

    fun read(userId: String): StoredPassword? {
        val hash = prefs.getString(hashKey(userId), null)?.takeIf { it.isNotBlank() } ?: return null
        val salt = prefs.getString(saltKey(userId), null)?.takeIf { it.isNotBlank() } ?: return null
        return StoredPassword(salt, hash, prefs.getLong(updatedKey(userId), 0L))
    }

    fun setPassword(userId: String, password: String) {
        val salt = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(salt)
        saveVerifier(
            userId,
            Base64.encodeToString(salt, Base64.NO_WRAP),
            hash(password, salt),
            System.currentTimeMillis()
        )
    }

    fun saveVerifier(userId: String, salt: String, hash: String, updatedAt: Long) {
        if (salt.isBlank() || hash.isBlank()) return
        prefs.edit()
            .putString(saltKey(userId), salt)
            .putString(hashKey(userId), hash)
            .putLong(updatedKey(userId), updatedAt)
            .apply()
    }

    fun verify(userId: String, password: String): Boolean {
        val saltText = prefs.getString(saltKey(userId), null) ?: return false
        val expected = prefs.getString(hashKey(userId), null) ?: return false
        val salt = Base64.decode(saltText, Base64.NO_WRAP)
        return hash(password, salt) == expected
    }

    private fun hash(password: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(password.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun saltKey(userId: String) = "salt_$userId"

    private fun hashKey(userId: String) = "hash_$userId"

    private fun updatedKey(userId: String) = "updated_$userId"

    companion object {
        private const val PREFS_NAME = "book_password_prefs"
        private const val SALT_SIZE = 16
    }
}
