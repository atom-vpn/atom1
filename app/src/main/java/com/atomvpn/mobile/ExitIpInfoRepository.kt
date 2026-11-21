package com.atomvpn.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class ExitIpInfo(
    val ipAddress: String,
    val countryCode: String,
    val countryName: String,
    val provider: String,
)

data class ExitIpInfoState(
    val info: ExitIpInfo? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Resolves the public egress IP through the active Atom SOCKS listener.
 * The request must never fall back to the phone's direct network.
 */
object ExitIpInfoRepository {
    private val mutableState = MutableStateFlow(ExitIpInfoState())
    val state: StateFlow<ExitIpInfoState> = mutableState.asStateFlow()

    fun clear() {
        mutableState.value = ExitIpInfoState()
    }

    suspend fun refresh(socksPort: Int) {
        if (socksPort !in 1..65535) {
            mutableState.value = ExitIpInfoState(errorMessage = "Invalid SOCKS port")
            return
        }
        mutableState.value = ExitIpInfoState(isLoading = true)
        try {
            val info = withContext(Dispatchers.IO) {
                lookupThroughSocks(socksPort)
            }
            mutableState.value = ExitIpInfoState(info = info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message?.replace(Regex("\\s+"), " ")?.trim()?.take(100)
                .orEmpty().ifBlank { error.javaClass.simpleName }
            android.util.Log.e("AtomVPN-IP", "Public exit IP lookup failed on SOCKS port $socksPort: $message", error)
            mutableState.value = ExitIpInfoState(errorMessage = message)
        }
    }

    private fun lookupThroughSocks(port: Int): ExitIpInfo {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val primary = runCatching { parseIpWhoIs(fetchJson("https://ipwho.is/", proxy)) }
        if (primary.isSuccess) return primary.getOrThrow()
        return try {
            parseIpApi(fetchJson("https://ipapi.co/json/", proxy))
        } catch (fallback: Throwable) {
            primary.exceptionOrNull()?.let(fallback::addSuppressed)
            throw fallback
        }
    }

    private fun fetchJson(url: String, proxy: Proxy): JSONObject {
        val connection = URL(url).openConnection(proxy) as HttpsURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.buffered()?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            check(code in 200..299) { "HTTP $code" }
            check(payload.isNotBlank()) { "Empty IP lookup response" }
            JSONObject(payload)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseIpWhoIs(json: JSONObject): ExitIpInfo {
        check(json.optBoolean("success", false)) { json.optString("message", "IP lookup failed") }
        return ExitIpInfo(
            ipAddress = json.optString("ip").also { check(it.isNotBlank()) { "Missing public IP" } },
            countryCode = json.optString("country_code").uppercase(),
            countryName = json.optString("country"),
            provider = "ipwho.is",
        )
    }

    private fun parseIpApi(json: JSONObject): ExitIpInfo {
        check(!json.optBoolean("error", false)) { json.optString("reason", "IP lookup failed") }
        return ExitIpInfo(
            ipAddress = json.optString("ip").also { check(it.isNotBlank()) { "Missing public IP" } },
            countryCode = json.optString("country_code").uppercase(),
            countryName = json.optString("country_name"),
            provider = "ipapi.co",
        )
    }

    private const val TIMEOUT_MS = 7_000
}
