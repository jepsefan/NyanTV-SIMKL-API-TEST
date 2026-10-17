package com.nyantv.data

import android.os.SystemClock
import android.util.Base64
import com.nyantv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

data class SimklDeviceSession(
    val userCode: String,
    val deviceCode: String,
    val verificationUrl: String,
    val qrUrl: String,
    val expiresIn: Int,
    val interval: Int,
    internal val codeVerifier: String
)

data class SimklOAuthToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
    val scope: String
)

/** Simkl AUTH V2 Device Authorization Grant (with PKCE). */
internal object SimklDeviceAuth {
    private const val API = "https://api.simkl.com"
    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private data class HttpResult(val status: Int, val body: JsonObject)

    private suspend fun post(path: String, body: JsonObject): HttpResult = withContext(Dispatchers.IO) {
        val clientId = BuildConfig.SIMKL_CLIENT_ID
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing from this build" }
        val request = Request.Builder().url("$API$path")
            .header("simkl-api-key", clientId)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val payload = runCatching {
                json.parseToJsonElement(response.body.string()).jsonObject
            }.getOrDefault(buildJsonObject {})
            HttpResult(response.code, payload)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String, fallback: Int): Int =
        (get(key) as? JsonPrimitive)?.intOrNull ?: fallback

    private fun HttpResult.errorMessage(): String =
        body.string("error_description") ?: body.string("message")
        ?: body.string("error") ?: "HTTP $status"

    private fun newVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    suspend fun request(): SimklDeviceSession {
        val verifier = newVerifier()
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val result = post("/oauth2/device", buildJsonObject {
            put("client_id", BuildConfig.SIMKL_CLIENT_ID)
            put("scope", "media:read media:write")
            put("code_challenge", challenge)
            put("code_challenge_method", "S256")
        })
        if (result.status !in 200..299) error("Simkl device login: ${result.errorMessage()}")
        val code = result.body.string("user_code")?.takeIf { it.isNotBlank() }
            ?: error("Simkl did not return a user code")
        val deviceCode = result.body.string("device_code")?.takeIf { it.isNotBlank() }
            ?: error("Simkl did not return a device code")
        val url = result.body.string("verification_uri")
            ?: result.body.string("verification_url") ?: "https://simkl.com/pin"
        // Use the provider-supplied complete URL; never guess a query parameter.
        val complete = result.body.string("verification_uri_complete") ?: url
        require(url.startsWith("https://simkl.com/") && complete.startsWith("https://simkl.com/")) {
            "Unexpected verification URL returned by Simkl"
        }
        return SimklDeviceSession(
            userCode = code,
            deviceCode = deviceCode,
            verificationUrl = url,
            qrUrl = complete,
            expiresIn = result.body.number("expires_in", 900).coerceIn(60, 1800),
            interval = result.body.number("interval", 5).coerceAtLeast(5),
            codeVerifier = verifier
        )
    }

    private fun HttpResult.asToken(): SimklOAuthToken {
        if (status !in 200..299) error("Simkl authorization failed: ${errorMessage()}")
        val access = body.string("access_token")?.takeIf { it.isNotBlank() }
            ?: error("Simkl did not return an access token")
        return SimklOAuthToken(
            accessToken = access,
            refreshToken = body.string("refresh_token"),
            expiresIn = body.number("expires_in", 604800).toLong(),
            scope = body.string("scope") ?: ""
        )
    }

    suspend fun poll(session: SimklDeviceSession, onStatus: (String) -> Unit): SimklOAuthToken {
        val deadline = SystemClock.elapsedRealtime() + session.expiresIn * 1000L
        var intervalSeconds = session.interval
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(intervalSeconds * 1000L)
            coroutineContext.ensureActive()
            if (SystemClock.elapsedRealtime() >= deadline) break
            val result = post("/oauth2/token", buildJsonObject {
                put("grant_type", DEVICE_GRANT)
                put("device_code", session.deviceCode)
                put("client_id", BuildConfig.SIMKL_CLIENT_ID)
                put("code_verifier", session.codeVerifier)
            })
            if (result.status in 200..299) return result.asToken()
            when (result.body.string("error")) {
                "authorization_pending" -> onStatus("Waiting for approval on your phone…")
                "slow_down" -> {
                    intervalSeconds += 5
                    onStatus("Waiting for Simkl (polling slowed)…")
                }
                "access_denied" -> error("Simkl login was denied")
                "expired_token" -> error("Simkl code expired; request a new QR code")
                else -> error("Simkl login: ${result.errorMessage()}")
            }
        }
        error("Simkl code expired; request a new QR code")
    }

    suspend fun refresh(refreshToken: String): SimklOAuthToken {
        val result = post("/oauth2/token", buildJsonObject {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("client_id", BuildConfig.SIMKL_CLIENT_ID)
        })
        return result.asToken()
    }
}
