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
    internal val verifier: String
)

data class SimklOAuthToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long
)

/** Simkl AUTH V2 device-code flow with PKCE (public client; no embedded secret). */
internal object SimklDeviceAuth {
    private const val API = "https://api.simkl.com"
    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private data class Reply(val code: Int, val data: JsonObject)
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String, fallback: Int) =
        (this[key] as? JsonPrimitive)?.intOrNull ?: fallback

    private suspend fun post(path: String, body: JsonObject): Reply = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API$path")
            .header("Accept", "application/json")
            .header("simkl-api-key", BuildConfig.SIMKL_CLIENT_ID)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val data = runCatching {
                json.parseToJsonElement(resp.body.string()).jsonObject
            }.getOrDefault(buildJsonObject {})
            Reply(resp.code, data)
        }
    }

    private fun Reply.reason(): String = data.str("error_description")
        ?: data.str("message") ?: data.str("error") ?: "HTTP $code"

    private fun codeVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    suspend fun request(): SimklDeviceSession {
        val id = BuildConfig.SIMKL_CLIENT_ID
        require(id.isNotBlank()) { "Missing SIMKL_CLIENT_ID" }
        val verifier = codeVerifier()
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val response = post("/oauth2/device", buildJsonObject {
            put("client_id", id)
            put("scope", "media:read media:write")
            put("code_challenge", challenge)
            put("code_challenge_method", "S256")
        })
        if (response.code !in 200..299) error("Simkl V2 device request HTTP ${response.code}: ${response.reason()}")
        val pin = response.data.str("user_code") ?: error("Missing Simkl user_code")
        val deviceCode = response.data.str("device_code") ?: error("Missing Simkl device_code")
        val url = response.data.str("verification_uri")
            ?: response.data.str("verification_url") ?: error("Missing Simkl verification URL")
        val complete = response.data.str("verification_uri_complete") ?: url
        require(url.startsWith("https://") && complete.startsWith("https://")) { "Invalid Simkl verification URL" }
        return SimklDeviceSession(
            userCode = pin,
            deviceCode = deviceCode,
            verificationUrl = url,
            qrUrl = complete,
            expiresIn = response.data.num("expires_in", 900).coerceIn(30, 1800),
            interval = response.data.num("interval", 5).coerceAtLeast(5),
            verifier = verifier
        )
    }

    private fun token(reply: Reply): SimklOAuthToken {
        if (reply.code !in 200..299) error("Simkl token HTTP ${reply.code}: ${reply.reason()}")
        return SimklOAuthToken(
            accessToken = reply.data.str("access_token") ?: error("Missing access token"),
            refreshToken = reply.data.str("refresh_token"),
            expiresIn = reply.data.num("expires_in", 3600).toLong()
        )
    }

    suspend fun poll(session: SimklDeviceSession, status: (String) -> Unit): SimklOAuthToken {
        val deadline = SystemClock.elapsedRealtime() + session.expiresIn * 1000L
        var interval = session.interval
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(interval * 1000L)
            coroutineContext.ensureActive()
            val result = post("/oauth2/token", buildJsonObject {
                put("grant_type", DEVICE_GRANT)
                put("device_code", session.deviceCode)
                put("client_id", BuildConfig.SIMKL_CLIENT_ID)
                put("code_verifier", session.verifier)
            })
            if (result.code in 200..299) return token(result)
            when (result.data.str("error")) {
                "authorization_pending" -> status("Waiting for approval on your phone…")
                "slow_down" -> { interval += 5; status("Waiting for Simkl…") }
                "expired_token" -> error("Login code expired")
                "access_denied" -> error("Login denied")
                else -> error("Simkl V2 token HTTP ${result.code}: ${result.reason()}")
            }
        }
        error("Login code expired")
    }

    suspend fun refresh(refreshToken: String): SimklOAuthToken = token(
        post("/oauth2/token", buildJsonObject {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("client_id", BuildConfig.SIMKL_CLIENT_ID)
        })
    )
}
