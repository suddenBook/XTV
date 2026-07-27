package com.xtv.app.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import java.util.WeakHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val Context.privateStateDataStore by preferencesDataStore(PRIVATE_STORE_NAME)

/**
 * One AES-256-GCM ciphertext backed by one atomic DataStore preference.
 *
 * The schema and application id are authenticated as AAD. A copied envelope cannot be decrypted as
 * another schema or app, and a present ciphertext with a missing Keystore key is reported explicitly.
 */
class AndroidEncryptedEnvelopeBackend(context: Context) : EncryptedEnvelopeBackend {
    private val appContext = context.applicationContext

    override suspend fun read(): EnvelopeRead {
        val encoded = try {
            appContext.privateStateDataStore.data.first()[ENVELOPE]
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return EnvelopeRead.Failed(StorageFailure.Io(t.safeMessage()))
        } ?: return EnvelopeRead.Missing

        val raw = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (t: Throwable) {
            return EnvelopeRead.Failed(StorageFailure.CorruptEnvelope("invalid base64 envelope"))
        }
        if (raw.size <= GCM_IV_BYTES) {
            return EnvelopeRead.Failed(StorageFailure.CorruptEnvelope("envelope is too short"))
        }

        return try {
            val key = existingKey()
                ?: return EnvelopeRead.Failed(StorageFailure.KeyUnavailable)
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = raw.copyOfRange(GCM_IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(AAD)
            }
            EnvelopeRead.Present(cipher.doFinal(ciphertext))
        } catch (_: AEADBadTagException) {
            EnvelopeRead.Failed(StorageFailure.AuthenticationFailed)
        } catch (t: Throwable) {
            EnvelopeRead.Failed(StorageFailure.Io(t.safeMessage()))
        }
    }

    override suspend fun write(plaintext: ByteArray): StorageFailure? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, createOrLoadKey())
            updateAAD(AAD)
        }
        val encoded = Base64.encodeToString(
            cipher.iv + cipher.doFinal(plaintext),
            Base64.NO_WRAP,
        )
        appContext.privateStateDataStore.edit { it[ENVELOPE] = encoded }
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        StorageFailure.Io(t.safeMessage())
    }

    override suspend fun clear(): StorageFailure? {
        try {
            appContext.privateStateDataStore.edit { it.clear() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return StorageFailure.Io(t.safeMessage())
        }
        // The private data is gone once DataStore commits. A best-effort orphan-key cleanup must
        // not report the reset as failed (and invite a destructive retry) after that point.
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
        return null
    }

    private fun existingKey(): SecretKey? {
        val entry = keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey
    }

    private fun createOrLoadKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
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

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun Throwable.safeMessage(): String = message ?: javaClass.simpleName

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val KEY_ALIAS = "xtv_private_state_v2"
        val AAD = "com.xtv.app/private-state/v2".encodeToByteArray()
        val ENVELOPE = stringPreferencesKey("encrypted_envelope")
    }
}

private const val PRIVATE_STORE_NAME = "xtv_private_state_v2"

fun androidPrivateStateStore(
    context: Context,
    legacy: LegacyStateSource = EmptyLegacyStateSource,
): PrivateStateStore = AndroidPrivateStateStores.get(context, legacy)

/**
 * One repository instance per application process, so every adapter shares the same transaction
 * mutex. DataStore serializes file writes, but only this shared repository can make read-transform-
 * write atomic across credentials, purchase and playback adapters.
 */
private object AndroidPrivateStateStores {
    private val stores = WeakHashMap<Context, PrivateStateStore>()

    fun get(context: Context, legacy: LegacyStateSource): PrivateStateStore {
        val appContext = context.applicationContext
        return synchronized(stores) {
            stores.getOrPut(appContext) {
                PrivateStateStore(AndroidEncryptedEnvelopeBackend(appContext), legacy)
            }
        }
    }
}
