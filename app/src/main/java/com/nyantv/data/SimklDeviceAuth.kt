package com.nyantv.data

import android.os.SystemClock
import com.nyantv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

data class SimklDeviceSession(
    val userCode: String,
    val deviceCode: String,
    val verificationUrl: String,
    val qrUrl: String,
    val expiresIn: Int,
    val interval: Int
)

data class SimklOAuthToken(
    val accessToken: String,
    val expiresIn: Long
)

/** Simkl's documented TV PIN flow. No client secret is required. */
internal object SimklDeviceAuth {
    private const val API = "https://api.simkl.com"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private data class HttpResult(val status: Int, val body: JsonObject)

    private suspend fun get(path: String): HttpResult = withContext(Dispatchers.IO) {
        val clientId = BuildConfig.SIMKL_CLIENT_ID
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing from this build" }
        val separator = if ("?" in path) "&" else "?"
        val url = "$API$path" + separator + "client_id=" + clientId
        val request = Request.Builder()
            .url(url)
            .header("simkl-api-key", clientId)
            .get()
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

    suspend fun request(): SimklDeviceSession {
        val result = get("/oauth/pin")
        if (result.status !in 200..299 || result.body.string("result") != "OK") {
            error("Simkl PIN request failed: HTTP ${result.status}")
        }
        val code = result.body.string("user_code")?.takeIf { it.isNotBlank() }
            ?: error("Simkl did not return a PIN")
        val url = result.body.string("verification_uri")
            ?: result.body.string("verification_url") ?: "https://simkl.com/pin"
        require(url.startsWith("https://simkl.com/")) { "Unexpected verification URL" }
        return SimklDeviceSession(
            userCode = code,
            deviceCode = result.body.string("device_code").orEmpty(),
            verificationUrl = url,
            qrUrl = url,
            expiresIn = result.body.number("expires_in", 900).coerceIn(60, 1800),
            interval = result.body.number("interval", 5).coerceAtLeast(5)
        )
    }

    suspend fun poll(session: SimklDeviceSession, onStatus: (String) -> Unit): SimklOAuthToken {
        val deadline = SystemClock.elapsedRealtime() + session.expiresIn * 1000L
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(session.interval * 1000L)
            coroutineContext.ensureActive()
            if (SystemClock.elapsedRealtime() >= deadline) break
            val result = get("/oauth/pin/${session.userCode}")
            val access = result.body.string("access_token")
            if (result.status in 200..299 &&
                result.body.string("result") == "OK" &&
                !access.isNullOrBlank()) {
                return SimklOAuthToken(
                    accessToken = access,
                    expiresIn = result.body.number("expires_in", 157680000).toLong()
                )
            }
            if (result.body.string("device_code") != null) {
                error("Simkl PIN is no longer valid. Request a new code.")
            }
            if (result.status !in 200..299) error("Simkl PIN polling failed: HTTP ${result.status}")
            if (result.body.string("result") != "KO") {
                error("Unexpected response from Simkl PIN endpoint")
            }
            onStatus(result.body.string("message") ?: "Waiting for approval on your phone…")
        }
        error("Simkl PIN expired. Request a new code.")
    }
}
