package com.dsbot.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the device token encrypted with a non-exportable Android Keystore key
 * (AES-256/GCM). Security requirement: docs/SECURITY.md §2.3.
 *
 * If the Keystore is unavailable (rare, e.g. some emulators) the token is not
 * persisted at all — the user must pair again on next launch rather than having
 * it written in clear text.
 */
class TokenVault(context: Context) {

    private companion object {
        const val PREFS = "dsbot.vault"
        const val KEY_CIPHER = "token.cipher"
        const val KEY_ALIAS = "dsbot-device-token"
        const val GCM_TAG_BITS = 128
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(token: String): Boolean {
        val key = secretKey() ?: return false
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val cipherText = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
            prefs.edit().putString(KEY_CIPHER, packed).apply()
            true
        }.getOrDefault(false)
    }

    fun load(): String? {
        val packed = prefs.getString(KEY_CIPHER, null) ?: return null
        val key = secretKey() ?: return null
        return runCatching {
            val raw = Base64.decode(packed, Base64.NO_WRAP)
            if (raw.size <= 12) return null
            val iv = raw.copyOfRange(0, 12)
            val cipherText = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_CIPHER).apply()
    }

    private fun secretKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
    }.getOrNull()
}
