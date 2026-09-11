package com.zillit.zillitapp.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.preferences.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the refresh token lives between launches.
 *
 * The refresh token is the session — 90 days of it — so it is the one credential in the app
 * that must not sit in plain preferences. It is sealed with an AES key held in the **Android
 * Keystore**, which means the key material never enters app memory and cannot be read off a
 * rooted device's backup: an attacker who copies the DataStore file gets ciphertext.
 *
 * The ciphertext itself goes in the same DataStore as everything else — encrypting the value
 * rather than adopting a second storage engine keeps one place to clear on logout.
 */
@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun readRefreshToken(): String? {
        val stored = context.dataStore.data.first()[KEY_REFRESH] ?: return null
        return decrypt(stored)
    }

    suspend fun writeRefreshToken(token: String) {
        val sealed = encrypt(token) ?: return
        context.dataStore.edit { it[KEY_REFRESH] = sealed }
    }

    /** Called on logout and whenever the server tells us the session is over. */
    suspend fun clear() {
        context.dataStore.edit { it.remove(KEY_REFRESH) }
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray())
        // IV first, then ciphertext — GCM generates a fresh IV per encryption and needs it
        // back verbatim to decrypt.
        val combined = cipher.iv + encrypted
        Base64.encodeToString(combined, Base64.NO_WRAP)
    }.onFailure { ZillitLog.w(TAG, "Could not seal the refresh token: ${it.message}") }.getOrNull()

    private fun decrypt(sealed: String): String? = runCatching {
        val combined = Base64.decode(sealed, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val body = combined.copyOfRange(IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(body).decodeToString()
    }.onFailure {
        // A key lost to a reinstall or a device restore is not an error worth shouting
        // about — it simply means this session cannot be resumed and the user logs in.
        ZillitLog.w(TAG, "Stored refresh token could not be read: ${it.message}")
    }.getOrNull()

    /** The Keystore entry, created once on first use. */
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not user-authentication-bound: the app has to resume its
                // session in the background — a push waking it for a call, for one — where
                // there is nobody present to unlock anything.
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SecureTokenStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zillit_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128

        val KEY_REFRESH = stringPreferencesKey("session_refresh_token")
    }
}
