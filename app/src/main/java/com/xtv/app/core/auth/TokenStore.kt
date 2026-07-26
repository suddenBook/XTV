package com.xtv.app.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.tokenDataStore by preferencesDataStore("xtv_session")

/**
 * Encrypted at-rest storage for the OAuth tokens.
 *
 * Jetpack Security (`EncryptedSharedPreferences`) was deprecated in 2025 in favour of using the
 * platform Keystore directly, so that is what this does: an AES-256-GCM key lives in the
 * AndroidKeyStore and never leaves it; only `iv || ciphertext` is written to DataStore.
 *
 * Deliberately **not** `setUserAuthenticationRequired(true)`: a TV has no biometric and no
 * guaranteed secure lock screen, so requiring user auth to unwrap the key would lock the app out of
 * its own credentials with no recovery path.
 *
 * Threat model, stated honestly: this protects the token against someone reading the app's data
 * directory offline. It does not protect against a rooted box or one with ADB-over-network enabled,
 * which describes a lot of TV hardware. For a single-user personal app that is the proportionate
 * amount of effort — see also the media disk cache, which holds the actually-sensitive content and
 * is not encrypted.
 */
class TokenStore(private val context: Context) {

    suspend fun save(tokens: Tokens) {
        context.tokenDataStore.edit { prefs ->
            prefs[ACCESS] = encrypt(tokens.accessToken)
            tokens.refreshToken?.let { prefs[REFRESH] = encrypt(it) }
            prefs[EXPIRES_AT] = tokens.expiresAtMs.toString()
            prefs[ACCOUNT_ID] = tokens.accountId.orEmpty()
        }
    }

    suspend fun load(): Tokens? {
        val prefs = context.tokenDataStore.data.first()
        val access = prefs[ACCESS]?.let(::decrypt) ?: return null
        return Tokens(
            accessToken = access,
            refreshToken = prefs[REFRESH]?.let(::decrypt),
            expiresAtMs = prefs[EXPIRES_AT]?.toLongOrNull() ?: 0L,
            accountId = prefs[ACCOUNT_ID]?.takeIf { it.isNotBlank() },
        )
    }

    /** Sign-out must destroy the credential, not just forget the pointer to it. */
    suspend fun clear() {
        context.tokenDataStore.edit { it.clear() }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    // --- crypto ---------------------------------------------------------------------------------

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val body = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val body = raw.copyOfRange(GCM_IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        String(cipher.doFinal(body))
    }.getOrNull() // A rotated/destroyed key means "no session", not a crash.

    private companion object {
        const val KEY_ALIAS = "xtv_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        val ACCESS = stringPreferencesKey("access")
        val REFRESH = stringPreferencesKey("refresh")
        val EXPIRES_AT = stringPreferencesKey("expires_at")
        val ACCOUNT_ID = stringPreferencesKey("account_id")
    }
}

data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val accountId: String? = null,
) {
    /** Refresh a minute early so a request never races the expiry. */
    fun isExpired(nowMs: Long = System.currentTimeMillis()) = nowMs >= expiresAtMs - 60_000
}
