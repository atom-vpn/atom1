package com.uacspoofer.mobile.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Permission-free network snapshot used by the migrated PoW engine.
 * Android does not expose SIM operator identity to ordinary apps without
 * phone-state permissions. Unknown carrier is therefore intentional; users
 * can select MCI or Irancell manually in Atom settings.
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
        return PowNetworkFingerprint(
            transport = resolveTransport(connectivity),
            dataSubscriptionId = -1,
            carrierClass = "unknown",
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

        return when {
            "wifi" in candidates -> "wifi"
            "cellular" in candidates -> "cellular"
            candidates.size == 1 -> candidates.first()
            else -> "unknown"
        }
    }
}