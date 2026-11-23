package com.example.data.remote

import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.net.URI

object BackendConfig {
    const val LIVE_RENDER_API_URL = "https://dsk-locker-k5jn.onrender.com"
    private const val PLACEHOLDER_URL = "https://your-dsk-backend.example.com"
    private const val PLACEHOLDER_RENDER_URL = "https://your-dsk-locker-backend.onrender.com"
    private const val PLACEHOLDER_PHONE = "OPTIONAL_OWNER_PHONE"

    val rawConfiguredApiUrl: String
        get() = readField("BACKEND_API_URL").trimEnd('/')

    val isSupabaseUrlBlocked: Boolean
        get() = isSupabaseUrl(rawConfiguredApiUrl)

    val isLocalhostUrlBlocked: Boolean
        get() = isLocalhostUrl(rawConfiguredApiUrl)

    val apiBaseUrl: String
        get() {
            val configured = rawConfiguredApiUrl
            if (isSupabaseUrl(configured) || isLocalhostUrl(configured)) {
                return ""
            }
            if (configured.isBlank() || configured == PLACEHOLDER_URL || configured == PLACEHOLDER_RENDER_URL) {
                return LIVE_RENDER_API_URL
            }
            return if (configured.startsWith("https://") || configured.startsWith("http://")) {
                configured
            } else {
                ""
            }
        }

    val configurationIssue: String?
        get() = when {
            isSupabaseUrlBlocked ->
                "BACKEND_API_URL points to Supabase, which is disabled. Use $LIVE_RENDER_API_URL."
            isLocalhostUrlBlocked ->
                "Localhost backend URL is not allowed in production builds. Use $LIVE_RENDER_API_URL."
            apiBaseUrl.isBlank() ->
                "Invalid BACKEND_API_URL configuration. Use $LIVE_RENDER_API_URL."
            else -> null
        }

    fun isSupabaseUrl(url: String): Boolean =
        url.contains("supabase.", ignoreCase = true)

    fun isLocalhostUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
        return host == "localhost" ||
            host == "127.0.0.1" ||
            host == "0.0.0.0" ||
            host == "10.0.2.2" ||
            url.contains("://localhost", ignoreCase = true) ||
            url.contains("://127.0.0.1", ignoreCase = true) ||
            url.contains("://10.0.2.2", ignoreCase = true)
    }

    val restrictedOwnerPhone: String
        get() = readField("AUTHORIZED_OWNER_PHONE")
            .takeIf { it.isNotBlank() && it != PLACEHOLDER_PHONE && it.startsWith("+") }
            .orEmpty()

    val isSqlBackendConfigured: Boolean
        get() = apiBaseUrl.isNotBlank()

    fun currentFirebaseUid(): String? = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()

    fun currentFirebasePhone(): String? = runCatching { Firebase.auth.currentUser?.phoneNumber }.getOrNull()

    private fun readField(name: String): String {
        return runCatching {
            val field = BuildConfig::class.java.getField(name)
            (field.get(null) as? String)?.trim().orEmpty()
        }.getOrDefault("")
    }
}
