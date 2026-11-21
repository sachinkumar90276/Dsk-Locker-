package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class FetchedLinkMetadata(
    val title: String?,
    val description: String?,
    val thumbnailUrl: String?,
    val pageContent: String? = null
)

class SqlBackendClient {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMedia by lazy { "application/json; charset=utf-8".toMediaType() }

    suspend fun registerOrVerifyOwner(
        firebaseIdToken: String,
        phoneE164: String,
        ownerName: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BackendConfig.isSqlBackendConfigured || firebaseIdToken.isBlank()) {
            return@withContext Result.success(Unit)
        }
        runCatching {
            val payload = JSONObject().put("phoneNumber", phoneE164)
            if (!ownerName.isNullOrBlank()) payload.put("ownerName", ownerName)

            val req = Request.Builder()
                .url("${BackendConfig.apiBaseUrl}/api/auth/register-owner")
                .addHeader("Authorization", "Bearer $firebaseIdToken")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseError(resp.body?.string().orEmpty(), "Owner registration denied (${resp.code})."))
                }
            }
        }
    }

    suspend fun syncMpinSecurityHash(
        firebaseIdToken: String,
        mpinHash: String,
        mpinSalt: String,
        biometricEnabled: Boolean? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BackendConfig.isSqlBackendConfigured || firebaseIdToken.isBlank()) {
            return@withContext Result.success(Unit)
        }
        runCatching {
            val payload = JSONObject()
                .put("mpinHash", mpinHash)
                .put("mpinSalt", mpinSalt)
            if (biometricEnabled != null) payload.put("biometricEnabled", biometricEnabled)

            val req = Request.Builder()
                .url("${BackendConfig.apiBaseUrl}/api/security/mpin")
                .addHeader("Authorization", "Bearer $firebaseIdToken")
                .addHeader("Content-Type", "application/json")
                .put(payload.toString().toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseError(resp.body?.string().orEmpty(), "Security hash sync failed (${resp.code})."))
                }
            }
        }
    }

    suspend fun upsertRow(
        table: String,
        firebaseIdToken: String,
        rowJson: JSONObject
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BackendConfig.isSqlBackendConfigured || firebaseIdToken.isBlank()) {
            return@withContext Result.success(Unit)
        }
        runCatching {
            val req = Request.Builder()
                .url("${BackendConfig.apiBaseUrl}/api/sync/$table")
                .addHeader("Authorization", "Bearer $firebaseIdToken")
                .addHeader("Content-Type", "application/json")
                .post(rowJson.toString().toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseError(resp.body?.string().orEmpty(), "SQL sync $table failed (${resp.code})."))
                }
            }
        }
    }

    suspend fun deleteRow(
        table: String,
        firebaseIdToken: String,
        recordId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BackendConfig.isSqlBackendConfigured || firebaseIdToken.isBlank()) {
            return@withContext Result.success(Unit)
        }
        runCatching {
            val req = Request.Builder()
                .url("${BackendConfig.apiBaseUrl}/api/sync/$table/${URLEncoder.encode(recordId, "UTF-8")}")
                .addHeader("Authorization", "Bearer $firebaseIdToken")
                .delete()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseError(resp.body?.string().orEmpty(), "SQL delete $table failed (${resp.code})."))
                }
            }
        }
    }

    suspend fun fetchRemoteFiles(firebaseIdToken: String): Result<JSONArray> = withContext(Dispatchers.IO) {
        if (!BackendConfig.isSqlBackendConfigured || firebaseIdToken.isBlank()) {
            return@withContext Result.success(JSONArray())
        }
        runCatching {
            val req = Request.Builder()
                .url("${BackendConfig.apiBaseUrl}/api/files")
                .addHeader("Authorization", "Bearer $firebaseIdToken")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseError(str, "Fetch SQL files failed (${resp.code})."))
                }
                JSONArray(str.ifBlank { "[]" })
            }
        }
    }

    suspend fun fetchLinkMetadata(rawUrl: String): FetchedLinkMetadata = withContext(Dispatchers.IO) {
        val normalized = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl"
        }

        if (normalized.contains("youtube.com", ignoreCase = true) ||
            normalized.contains("youtu.be", ignoreCase = true)
        ) {
            val ytResult = runCatching {
                val encoded = URLEncoder.encode(normalized, "UTF-8")
                val req = Request.Builder()
                    .url("https://www.youtube.com/oembed?url=$encoded&format=json")
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        FetchedLinkMetadata(
                            title = json.optString("title").takeIf { it.isNotBlank() },
                            description = json.optString("author_name").takeIf { it.isNotBlank() }?.let { "Channel: $it" },
                            thumbnailUrl = json.optString("thumbnail_url").takeIf { it.isNotBlank() }
                        )
                    } else null
                }
            }.getOrNull()
            if (ytResult != null) return@withContext ytResult
        }

        runCatching {
            val req = Request.Builder()
                .url(normalized)
                .addHeader("User-Agent", "Mozilla/5.0 (Android) DSKLocker/1.0")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching FetchedLinkMetadata(null, null, null)
                val html = resp.body?.string()?.take(65_536).orEmpty()
                val ogTitle = extractMetaContent(html, "og:title")
                    ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(html)?.groupValues?.getOrNull(1)?.trim()
                val ogDesc = extractMetaContent(html, "og:description")
                    ?: extractMetaContent(html, "description")
                val ogImage = extractMetaContent(html, "og:image")
                val visibleText = extractVisibleHtmlText(html)
                FetchedLinkMetadata(
                    title = ogTitle?.replace(Regex("\\s+"), " ")?.take(140),
                    description = ogDesc?.replace(Regex("\\s+"), " ")?.take(300),
                    thumbnailUrl = ogImage,
                    pageContent = visibleText
                )
            }
        }.getOrDefault(FetchedLinkMetadata(null, null, null, null))
    }

    private fun extractVisibleHtmlText(html: String): String? {
        if (html.isBlank()) return null
        val cleaned = html
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.isNotBlank() }?.take(12_000)
    }

    private fun extractMetaContent(html: String, key: String): String? {
        val pattern = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(key)}["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val reversePattern = Regex(
            """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["']${Regex.escape(key)}["']""",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(html)?.groupValues?.getOrNull(1)
            ?: reversePattern.find(html)?.groupValues?.getOrNull(1)
    }

    private fun parseError(body: String, fallback: String): String {
        return runCatching {
            val json = JSONObject(body)
            json.optString("error").takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: fallback
        }.getOrDefault(fallback)
    }
}
