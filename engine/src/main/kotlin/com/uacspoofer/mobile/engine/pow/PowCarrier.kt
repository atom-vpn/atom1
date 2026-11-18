package com.uacspoofer.mobile.engine.pow

import android.content.Context
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver

/**
 * Permission-free radio identity. Carrier-specific policy is selected manually
 * because Android SIM operator identifiers require phone-state permissions.
 */
internal data class PowRadioIdentity(
    val transport: String,
    val dataSubscriptionId: Int,
    val operatorCode: String,
    val carrierClass: String,
) {
    fun summary(): String = "$transport|sub=$dataSubscriptionId|op=$operatorCode|class=$carrierClass"

    fun isSimOrOperatorChange(previous: PowRadioIdentity): Boolean {
        if (previous == this) return false
        if (transport == "unknown" || previous.transport == "unknown") return false
        val wasCell = previous.transport == "cellular"
        val isCell = transport == "cellular"
        return wasCell != isCell
    }

    fun isConfirmedCarrierFlip(previous: PowRadioIdentity): Boolean = false
}

internal object PowCarrier {
    fun carrierClass(context: Context): String = "unknown"

    fun isMci(context: Context): Boolean = false

    fun radioIdentity(context: Context): PowRadioIdentity = runCatching {
        val fp = NetworkFingerprintResolver(context).capture()
        PowRadioIdentity(
            transport = fp.transport,
            dataSubscriptionId = -1,
            operatorCode = "",
            carrierClass = "unknown",
        )
    }.getOrDefault(
        PowRadioIdentity(
            transport = "unknown",
            dataSubscriptionId = -1,
            operatorCode = "",
            carrierClass = "unknown",
        ),
    )
}