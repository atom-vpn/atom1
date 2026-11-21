package com.atomvpn.mobile.engine.atom

import android.content.Context

internal data class AtomLearnedPathSnapshot(
    val currentNetworkKey: String,
    val globalOuter: String?,
    val networks: List<AtomScoreboardRow>,
    val masqueLastPeer: String?,
    val wowLastPeer: String?,
    val masqueCachedGateways: List<String>,
) {
    val isEmpty: Boolean
        get() = globalOuter.isNullOrBlank() &&
            networks.isEmpty() &&
            masqueLastPeer.isNullOrBlank() &&
            wowLastPeer.isNullOrBlank() &&
            masqueCachedGateways.isEmpty()

    fun summary(): String {
        val outer = globalOuter?.let(AtomCoreConfig::outerLabel)
        val peer = masqueLastPeer ?: wowLastPeer
        return when {
            outer != null && peer != null -> "$outer · $peer"
            outer != null -> outer
            peer != null -> peer
            networks.isNotEmpty() -> networks.first().outer?.let(AtomCoreConfig::outerLabel) ?: "—"
            else -> ""
        }
    }

    companion object {
        val EMPTY = AtomLearnedPathSnapshot(
            currentNetworkKey = "other:unknown",
            globalOuter = null,
            networks = emptyList(),
            masqueLastPeer = null,
            wowLastPeer = null,
            masqueCachedGateways = emptyList(),
        )
    }
}

internal object AtomPathMemory {
    fun snapshot(context: Context, prefs: android.content.SharedPreferences): AtomLearnedPathSnapshot {
        return runCatching {
            val app = context.applicationContext
            AtomLearnedPathSnapshot(
                currentNetworkKey = AtomNetworkScoreboard.networkKind(app),
                globalOuter = prefs.getString("outer_protocol", null)
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it in AtomCoreConfig.OUTER_LADDER },
                networks = AtomNetworkScoreboard.rows(prefs),
                masqueLastPeer = parseTomlPeer(readText(AtomCoreConfig.lastconnPath(app))),
                wowLastPeer = parseTomlPeer(readText(AtomCoreConfig.goolLastconnPath(app))),
                masqueCachedGateways = parseMasqueGateways(readText(AtomCoreConfig.masqueCachePath(app))),
            )
        }.getOrElse { AtomLearnedPathSnapshot.EMPTY }
    }

    fun forget(context: Context, prefs: android.content.SharedPreferences): Int {
        val scoreboard = AtomNetworkScoreboard.clearAll(prefs)
        val editor = prefs.edit()
            .remove("outer_protocol")
            .remove("psiphon_winning_strategy_chained")
            .remove("psiphon_winning_strategy_chained_shape")
        editor.apply()
        val files = AtomCoreConfig.forgetPathMemory(context)
        return scoreboard + files
    }

    fun networkLabel(networkKey: String): String {
        val parts = networkKey.split(':', limit = 2)
        val kind = when (parts.getOrNull(0)) {
            "wifi" -> "Wi-Fi"
            "cell" -> "Cell"
            "eth" -> "Ethernet"
            else -> "Net"
        }
        val name = parts.getOrNull(1)?.replace('_', ' ')?.trim().orEmpty()
        return if (name.isBlank() || name == "unknown") kind else "$kind · $name"
    }

    fun parseTomlPeer(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val line = text.lineSequence().firstOrNull { it.trim().startsWith("peer") } ?: return null
        val value = line.substringAfter('=', missingDelimiterValue = "")
            .trim()
            .trim('"', '\'')
        return value.takeIf { it.contains(':') && it.length in 3..80 }
    }

    fun parseMasqueGateways(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return Regex("""\{[^{}]*\}""").findAll(text).mapNotNull { match ->
            val ip = Regex(""""ip"\s*:\s*"([^"]+)"""").find(match.value)?.groupValues?.getOrNull(1)?.trim()
            val port = Regex(""""port"\s*:\s*(\d+)""").find(match.value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (!ip.isNullOrBlank() && port != null && port in 1..65_535) "$ip:$port" else null
        }.toList()
    }

    private fun readText(file: java.io.File): String? =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
}
