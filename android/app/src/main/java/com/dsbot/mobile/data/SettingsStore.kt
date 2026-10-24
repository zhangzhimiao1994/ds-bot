package com.dsbot.mobile.data

import android.content.Context
import com.dsbot.mobile.core.Endpoint
import java.security.MessageDigest
import java.util.Base64

/**
 * Small persisted app settings (owned by the Lead).
 * The endpoint URL is plain; the device token is only ever stored through
 * [TokenVault] (Android Keystore encryption).
 */
class SettingsStore(context: Context) {

    private companion object {
        const val PREFS = "dsbot.settings"
        const val KEY_ENDPOINT_URL = "endpoint.url"
        const val KEY_ENDPOINT_HISTORY = "endpoint.history"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val vault = TokenVault(context.applicationContext)

    /** Current paired endpoint (URL + decrypted token), if any. */
    fun currentEndpoint(): Endpoint? {
        val url = prefs.getString(KEY_ENDPOINT_URL, null) ?: return null
        val token = vault.load() ?: return null
        return Endpoint(url, token)
    }

    /** Endpoint URLs seen before (most recent first), for the connect screen. */
    fun knownEndpointUrls(): List<String> =
        prefs.getString(KEY_ENDPOINT_HISTORY, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(5)
            ?: emptyList()

    fun rememberEndpoint(endpoint: Endpoint) {
        vault.save(endpoint.token)
        val history = (listOf(endpoint.baseUrl) + knownEndpointUrls())
            .distinct()
            .take(5)
            .joinToString("\n")
        prefs.edit()
            .putString(KEY_ENDPOINT_URL, endpoint.baseUrl)
            .putString(KEY_ENDPOINT_HISTORY, history)
            .apply()
    }

    fun forgetEndpoint() {
        vault.clear()
        prefs.edit().remove(KEY_ENDPOINT_URL).apply()
    }

    fun forgetAll() {
        vault.clear()
        prefs.edit().clear().apply()
    }

    /** `sha256(token)` base64url, first 8 chars — shown on the security page. */
    fun tokenFingerprint(endpoint: Endpoint?): String? {
        val token = endpoint?.token?.takeIf { it.isNotBlank() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(8)
    }
}
