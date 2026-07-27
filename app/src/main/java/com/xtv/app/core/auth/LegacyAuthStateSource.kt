package com.xtv.app.core.auth

import android.content.Context
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xtv.app.core.storage.AccountBinding
import com.xtv.app.core.storage.LegacyStateSource
import com.xtv.app.core.storage.PrivateStateUnavailableException
import com.xtv.app.core.storage.PrivateStateV2
import com.xtv.app.core.storage.ProvisionedCredentials
import com.xtv.app.core.storage.StorageFailure
import com.xtv.app.core.storage.StoredDiagnostic
import com.xtv.app.core.storage.StoredSession
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

private val Context.legacyCredentialsStore by preferencesDataStore("xtv_credentials")
private val Context.legacySessionStore by preferencesDataStore("xtv_session")

/**
 * Reads the two pre-v2 auth stores exactly once.
 *
 * [com.xtv.app.core.storage.PrivateStateStore] writes and verifies the new envelope before invoking
 * [clear], so a process death cannot consume the only readable copy. Reel and ledger migration are
 * composed by their adapters; this source deliberately owns only files from the auth package.
 */
class LegacyAuthStateSource(context: Context) : LegacyStateSource {
    private val appContext = context.applicationContext

    override suspend fun read(): PrivateStateV2? {
        val credentials = appContext.legacyCredentialsStore.data.first()
        val session = appContext.legacySessionStore.data.first()
        if (credentials.asMap().isEmpty() && session.asMap().isEmpty()) return null

        val clientId = credentials[CLIENT_ID].orEmpty()
        val bearer = credentials[BEARER].orEmpty()
        var authUnreadable = false
        fun decryptOrQuarantine(stored: String?): String? = stored?.let {
            try {
                decryptLegacy(it)
            } catch (_: Throwable) {
                authUnreadable = true
                null
            }
        }
        val access = decryptOrQuarantine(session[ACCESS])
        val refresh = decryptOrQuarantine(session[REFRESH])
        val accountId = session[ACCOUNT_ID]?.takeIf(String::isNotBlank)

        return PrivateStateV2(
            credentials = if (clientId.isBlank() && bearer.isBlank()) {
                null
            } else {
                ProvisionedCredentials(clientId, bearer)
            },
            session = if (!authUnreadable && (access != null || refresh != null)) {
                StoredSession(
                    accessToken = access.orEmpty(),
                    refreshToken = refresh,
                    expiresAtMs = if (access.isNullOrBlank()) {
                        0L
                    } else {
                        session[EXPIRES_AT]?.toLongOrNull() ?: 0L
                    },
                )
            } else {
                null
            },
            account = accountId?.let { AccountBinding(it, clientId) },
            diagnostics = if (authUnreadable) {
                listOf(
                    StoredDiagnostic(
                        recordedAtMs = System.currentTimeMillis(),
                        category = "migration",
                        message = "legacy session was unreadable; reprovisioning required",
                    ),
                )
            } else {
                emptyList()
            },
        )
    }

    override suspend fun clear() {
        appContext.legacyCredentialsStore.edit { it.clear() }
        appContext.legacySessionStore.edit { it.clear() }
        runCatching { keyStore().deleteEntry(LEGACY_KEY_ALIAS) }
    }

    private fun decryptLegacy(stored: String): String {
        val key = existingLegacyKey()
            ?: throw PrivateStateUnavailableException(StorageFailure.KeyUnavailable)
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            require(raw.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, GCM_IV_BYTES)),
                )
            }
            cipher.doFinal(raw.copyOfRange(GCM_IV_BYTES, raw.size)).decodeToString()
        } catch (failure: PrivateStateUnavailableException) {
            throw failure
        } catch (_: Throwable) {
            throw PrivateStateUnavailableException(StorageFailure.AuthenticationFailed)
        }
    }

    private fun existingLegacyKey(): SecretKey? =
        (keyStore().getEntry(LEGACY_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val LEGACY_KEY_ALIAS = "xtv_session"
        const val TRANSFORMATION =
            "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/" +
                KeyProperties.ENCRYPTION_PADDING_NONE
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        val CLIENT_ID = stringPreferencesKey("client_id")
        val BEARER = stringPreferencesKey("bearer")
        val ACCESS = stringPreferencesKey("access")
        val REFRESH = stringPreferencesKey("refresh")
        val EXPIRES_AT = stringPreferencesKey("expires_at")
        val ACCOUNT_ID = stringPreferencesKey("account_id")
    }
}
