package com.example.data.remote

import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

object BackendConfig {
    private const val PLACEHOLDER_URL = "https://your-dsk-backend.example.com"
    private const val PLACEHOLDER_PHONE = "OPTIONAL_OWNER_PHONE"

    val apiBaseUrl: String
        get() = readField("BACKEND_API_URL")
            .takeIf { it.isNotBlank() && it != PLACEHOLDER_URL && it.startsWith("http") }
            ?.trimEnd('/')
            .orEmpty()

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
