package com.atomvpn.mobile.engine.atom

import android.content.Context
import com.atomvpn.mobile.engine.vpn.NetworkFingerprintResolver

/**
 * Permission-free radio identity. Carrier-specific policy is selected manually
 * because Android SIM operator identifiers require phone-state permissions.
 */
internal data class AtomRadioIdentity(
    val transport: String,
    val dataSubscriptionId: Int,
    val operatorCode: String,
    val carrierClass: String,
) {
    fun summary(): String = "$transport|sub=$dataSubscriptionId|op=$operatorCode|class=$carrierClass"

    fun isSimOrOperatorChange(previous: AtomRadioIdentity): Boolean {
        if (previous == this) return false
        if (transport == "unknown" || previous.transport == "unknown") return false
        val wasCell = previous.transport == "cellular"
        val isCell = transport == "cellular"
        return wasCell != isCell
    }

    fun isConfirmedCarrierFlip(previous: AtomRadioIdentity): Boolean = false
}

internal object AtomCarrier {
    fun carrierClass(context: Context): String = "unknown"

    fun isMci(context: Context): Boolean = false

    fun radioIdentity(context: Context): AtomRadioIdentity = runCatching {
        val fp = NetworkFingerprintResolver(context).capture()
        AtomRadioIdentity(
            transport = fp.transport,
            dataSubscriptionId = -1,
            operatorCode = "",
            carrierClass = "unknown",
        )
    }.getOrDefault(
        AtomRadioIdentity(
            transport = "unknown",
            dataSubscriptionId = -1,
            operatorCode = "",
            carrierClass = "unknown",
        ),
    )
}