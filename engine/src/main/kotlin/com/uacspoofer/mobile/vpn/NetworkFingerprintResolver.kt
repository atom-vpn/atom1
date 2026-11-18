package com.uacspoofer.mobile.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * Minimal Android network identity snapshot required by the extracted PoW carrier policy.
 *
 * The upstream source tree imports NetworkFingerprintResolver but does not contain its
 * implementation. This implementation is intentionally limited to the three fields
 * consumed by PowCarrier; it does not claim to reproduce any wider app fingerprint API.
 */
data class PowNetworkFingerprint(
    val transport: String,
    val dataSubscriptionId: Int,
    val carrierClass: String,
)

class NetworkFingerprintResolver(context: Context) {
    private val appContext = context.applicationContext

    fun capture(): PowNetworkFingerprint {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val transport = resolveTransport(connectivity)
        val subscriptionId = resolveDataSubscriptionId()
        val operator = resolveOperator(subscriptionId)
        return PowNetworkFingerprint(
            transport = transport,
            dataSubscriptionId = subscriptionId,
            carrierClass = classifyCarrier(operator),
        )
    }

    private fun resolveTransport(connectivity: ConnectivityManager?): String {
        if (connectivity == null) return "unknown"

        fun classify(caps: NetworkCapabilities?): String? {
            if (caps == null) return null
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> null
            }
        }

        val active = runCatching { connectivity.activeNetwork }.getOrNull()
        val activeCaps = active?.let { runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull() }
        classify(activeCaps)?.let { return it }

        // During VPN operation activeNetwork is the VPN itself. Inspect available
        // non-VPN internet networks to identify the underlying Wi-Fi/cellular bearer.
        val candidates = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
            .mapNotNull { network ->
                val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
                    ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) return@mapNotNull null
                classify(caps)
            }

        // If both bearers are exposed, Wi-Fi is preferred for the effective default path.
        return when {
            "wifi" in candidates -> "wifi"
            "cellular" in candidates -> "cellular"
            candidates.size == 1 -> candidates.first()
            else -> "unknown"
        }
    }

    private fun resolveDataSubscriptionId(): Int = runCatching {
        SubscriptionManager.getDefaultDataSubscriptionId()
    }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    private fun resolveOperator(subscriptionId: Int): String {
        val manager = runCatching {
            appContext.getSystemService(TelephonyManager::class.java)
        }.getOrNull() ?: return ""
        val scoped = if (subscriptionId >= 0) {
            runCatching { manager.createForSubscriptionId(subscriptionId) }.getOrNull() ?: manager
        } else {
            manager
        }
        return runCatching { scoped.networkOperator.orEmpty() }.getOrDefault("")
    }

    private fun classifyCarrier(operator: String): String {
        val code = operator.trim()
        if (code.length < 5) return "unknown"
        return when {
            code.startsWith("43211") -> "mci"
            code.startsWith("43235") -> "irancell"
            code.startsWith("43220") -> "rightel"
            else -> "other"
        }
    }
}
