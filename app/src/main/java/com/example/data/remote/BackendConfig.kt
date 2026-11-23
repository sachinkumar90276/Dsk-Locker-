package com.example.data.remote

import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

object BackendConfig {
    private const val PLACEHOLDER_URL = "https://your-dsk-backend.example.com"
    private const val PLACEHOLDER_RENDER_URL = "https://your-dsk-locker-backend.onrender.com"
    private const val PLACEHOLDER_PHONE = "OPTIONAL_OWNER_PHONE"

    val rawConfiguredApiUrl: String
        get() = readField("BACKEND_API_URL").trimEnd('/')

    val isSupabaseUrlBlocked: Boolean
        get() = rawConfiguredApiUrl.contains("supabase.", ignoreCase = true)

    val apiBaseUrl: String
        get() = rawConfiguredApiUrl
            .takeIf {
                it.isNotBlank() &&
                    it != PLACEHOLDER_URL &&
                    it != PLACEHOLDER_RENDER_URL &&
                    it.startsWith("http") &&
                    !it.contains("supabase.", ignoreCase = true)
            }
            .orEmpty()

    val configurationIssue: String?
        get() = when {
            isSupabaseUrlBlocked ->
                "BACKEND_API_URL points to Supabase, which is disabled. Update BACKEND_API_URL in AI Studio Secrets to your Render backend URL."
            apiBaseUrl.isBlank() ->
                "Render backend URL missing: configure BACKEND_API_URL in AI Studio Secrets."
            else -> null
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
