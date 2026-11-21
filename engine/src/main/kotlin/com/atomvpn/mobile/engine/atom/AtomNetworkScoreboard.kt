package com.atomvpn.mobile.engine.atom

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Level B: Per-network scoreboard isolated to Atom.
 * Key = networkType + (operator/carrier) — stored inside AtomEngineStore prefs,
 * never touches XRAY or Tor stores. Keeps outerProtocol + strategy winner per
 * physical network so reconnect on the same WiFi/cell is instant.
 */
internal object AtomNetworkScoreboard {
    private const val KEY_PREFIX_OUTER = "atom_net_outer_"
    private const val KEY_PREFIX_STRATEGY = "atom_net_strat_"
    private const val KEY_PREFIX_STRAT_SHAPE = "atom_net_strat_shape_"
    private const val KEY_PREFIX_RTT = "atom_net_rtt_"
    private const val KEY_PREFIX_REGION = "atom_net_region_"
    private const val KEY_PREFIX_STRAT_RTT = "atom_net_srtt_"
    private const val KEY_PREFIX_REGION_STATS = "atom_net_region_stats_"

    /**
     * Copies the previous scoreboard namespace once. Legacy keys are retained
     * so a rollback to an older build does not lose the learned network profile.
     */
    fun migrateLegacyKeys(prefs: android.content.SharedPreferences) {
        val migrationKey = "atom_net_key_migration_v1"
        if (prefs.getBoolean(migrationKey, false)) return
        val prefixes = listOf(
            "pow_net_strat_shape_" to KEY_PREFIX_STRAT_SHAPE,
            "pow_net_region_stats_" to KEY_PREFIX_REGION_STATS,
            "pow_net_outer_" to KEY_PREFIX_OUTER,
            "pow_net_strat_" to KEY_PREFIX_STRATEGY,
            "pow_net_rtt_" to KEY_PREFIX_RTT,
            "pow_net_region_" to KEY_PREFIX_REGION,
            "pow_net_srtt_" to KEY_PREFIX_STRAT_RTT,
        )
        val values = prefs.all
        val editor = prefs.edit()
        for ((oldKey, value) in values) {
            val mapping = prefixes.firstOrNull { (oldPrefix, _) -> oldKey.startsWith(oldPrefix) } ?: continue
            val newKey = mapping.second + oldKey.removePrefix(mapping.first)
            if (prefs.contains(newKey)) continue
            when (value) {
                is String -> editor.putString(newKey, value)
                is Boolean -> editor.putBoolean(newKey, value)
                is Int -> editor.putInt(newKey, value)
                is Long -> editor.putLong(newKey, value)
                is Float -> editor.putFloat(newKey, value)
                is Set<*> -> editor.putStringSet(newKey, value.filterIsInstance<String>().toMutableSet())
            }
        }
        editor.putBoolean(migrationKey, true).commit()
    }

    fun networkKey(context: Context): String {
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "wifi:unknown"
            val net = cm.activeNetwork ?: return "other:unknown"
            val caps = cm.getNetworkCapabilities(net)
            val transport = when {
                caps == null -> "other"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                else -> "other"
            }
            val id = when (transport) {
                "wifi" -> runCatching {
                    val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                    wifi?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "unknown"
                }.getOrDefault("unknown")
                "cell" -> runCatching {
                    val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
                    tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm?.simOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"
                }.getOrDefault("unknown")
                else -> "unknown"
            }
            val safe = id.lowercase().replace(Regex("[^a-z0-9_\\-]"), "_").take(32).ifBlank { "unknown" }
            "$transport:$safe"
        }.getOrDefault("other:unknown")
    }

    fun networkKind(context: Context): String {
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "other:unknown"
            val net = cm.activeNetwork ?: return "other:unknown"
            val caps = cm.getNetworkCapabilities(net)
            when {
                caps == null -> "other:unknown"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi:unknown"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell:unknown"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth:unknown"
                else -> "other:unknown"
            }
        }.getOrDefault("other:unknown")
    }

    fun matchesCurrent(rowKey: String, currentKey: String): Boolean {
        val rowKind = rowKey.substringBefore(':')
        val currentKind = currentKey.substringBefore(':')
        return rowKind.isNotBlank() && rowKind == currentKind
    }

    fun saveOuter(prefs: android.content.SharedPreferences, netKey: String, protocol: String) {
        val clean = protocol.trim().lowercase().takeIf { it in AtomCoreConfig.OUTER_LADDER } ?: return
        prefs.edit().putString(KEY_PREFIX_OUTER + netKey, clean).apply()
    }

    fun loadOuter(prefs: android.content.SharedPreferences, netKey: String): String? =
        prefs.getString(KEY_PREFIX_OUTER + netKey, null)?.takeIf { it in AtomCoreConfig.OUTER_LADDER }

    fun saveStrategy(prefs: android.content.SharedPreferences, netKey: String, index: Int, shape: String, rttMs: Long = 0L) {
        prefs.edit()
            .putInt(KEY_PREFIX_STRATEGY + netKey, index)
            .putString(KEY_PREFIX_STRAT_SHAPE + netKey, shape)
            .apply()
        if (rttMs > 0) prefs.edit().putLong(KEY_PREFIX_RTT + netKey, rttMs).apply()
    }

    fun loadStrategy(prefs: android.content.SharedPreferences, netKey: String, shape: String, ladderSize: Int): Int? {
        if (prefs.getString(KEY_PREFIX_STRAT_SHAPE + netKey, null) != shape) return null
        if (!prefs.contains(KEY_PREFIX_STRATEGY + netKey)) return null
        return prefs.getInt(KEY_PREFIX_STRATEGY + netKey, 0).coerceIn(0, (ladderSize - 1).coerceAtLeast(0))
    }

    /** Path RTT measured with a given Psiphon strategy on this network. */
    fun saveStrategyRtt(prefs: android.content.SharedPreferences, netKey: String, strategy: String, rttMs: Long) {
        if (rttMs <= 0L || strategy.isBlank()) return
        prefs.edit().putLong(KEY_PREFIX_STRAT_RTT + strategy + "_" + netKey, rttMs).apply()
    }

    fun loadStrategyRtts(
        prefs: android.content.SharedPreferences,
        netKey: String,
        strategies: List<String>,
    ): Map<String, Long> = strategies.mapNotNull { name ->
        val rtt = runCatching { prefs.getLong(KEY_PREFIX_STRAT_RTT + name + "_" + netKey, 0L) }.getOrDefault(0L)
        if (rtt > 0L) name to rtt else null
    }.toMap()

    /** Psiphon exit country that connected on this network (Automatic only). */
    fun saveRegion(prefs: android.content.SharedPreferences, netKey: String, region: String) {
        val clean = region.trim().uppercase()
        if (clean.length != 2) return
        prefs.edit().putString(KEY_PREFIX_REGION + netKey, clean).apply()
    }

    fun loadRegion(prefs: android.content.SharedPreferences, netKey: String): String? =
        prefs.getString(KEY_PREFIX_REGION + netKey, null)?.takeIf { it.length == 2 }

    /** Record one automatic nearby-country attempt on the current physical network. */
    fun recordRegionResult(
        prefs: android.content.SharedPreferences,
        netKey: String,
        region: String,
        success: Boolean,
        connectMs: Long,
    ) {
        val code = region.trim().uppercase()
        if (code !in AtomRegions.NEARBY) return
        val prefix = KEY_PREFIX_REGION_STATS + netKey + "_" + code + "_"
        synchronized(prefs) {
            val attempts = prefs.getInt(prefix + "attempts", 0) + 1
            val successes = prefs.getInt(prefix + "successes", 0) + if (success) 1 else 0
            val totalMs = prefs.getLong(prefix + "total_ms", 0L) +
                if (success) connectMs.coerceAtLeast(1L) else 0L
            prefs.edit()
                .putInt(prefix + "attempts", attempts)
                .putInt(prefix + "successes", successes)
                .putLong(prefix + "total_ms", totalMs)
                .putLong(prefix + "last_ms", if (success) connectMs.coerceAtLeast(1L) else 0L)
                .apply()
        }
    }

    fun regionStats(prefs: android.content.SharedPreferences, netKey: String): Map<String, AtomRegions.AtomRegionStats> =
        AtomRegions.NEARBY.mapNotNull { code ->
            val prefix = KEY_PREFIX_REGION_STATS + netKey + "_" + code + "_"
            val attempts = prefs.getInt(prefix + "attempts", 0)
            if (attempts <= 0) null else code to AtomRegions.AtomRegionStats(
                attempts = attempts,
                successes = prefs.getInt(prefix + "successes", 0),
                totalConnectMs = prefs.getLong(prefix + "total_ms", 0L),
            )
        }.toMap()

    fun loadRttMs(prefs: android.content.SharedPreferences, netKey: String): Long? =
        runCatching { prefs.getLong(KEY_PREFIX_RTT + netKey, 0L) }.getOrNull()?.takeIf { it > 0L }

    fun prefNetworkKey(prefKey: String): String? = when {
        prefKey.startsWith(KEY_PREFIX_STRAT_SHAPE) -> prefKey.removePrefix(KEY_PREFIX_STRAT_SHAPE)
        prefKey.startsWith(KEY_PREFIX_OUTER) -> prefKey.removePrefix(KEY_PREFIX_OUTER)
        prefKey.startsWith(KEY_PREFIX_STRATEGY) -> prefKey.removePrefix(KEY_PREFIX_STRATEGY)
        prefKey.startsWith(KEY_PREFIX_RTT) -> prefKey.removePrefix(KEY_PREFIX_RTT)
        else -> null
    }

    fun rows(prefs: android.content.SharedPreferences): List<AtomScoreboardRow> {
        val keys = runCatching { prefs.all.keys }.getOrDefault(emptySet())
            .mapNotNull(::prefNetworkKey)
            .distinct()
            .sorted()
        return keys.map { netKey ->
            AtomScoreboardRow(
                networkKey = netKey,
                outer = loadOuter(prefs, netKey),
                strategyIndex = runCatching {
                    if (prefs.contains(KEY_PREFIX_STRATEGY + netKey)) {
                        prefs.getInt(KEY_PREFIX_STRATEGY + netKey, 0)
                    } else {
                        null
                    }
                }.getOrNull(),
                rttMs = loadRttMs(prefs, netKey),
            )
        }
    }

    fun clearAll(prefs: android.content.SharedPreferences): Int {
        val keys = prefs.all.keys.filter { key ->
            key.startsWith(KEY_PREFIX_OUTER) ||
                key.startsWith(KEY_PREFIX_STRATEGY) ||
                key.startsWith(KEY_PREFIX_STRAT_SHAPE) ||
                key.startsWith(KEY_PREFIX_RTT) ||
                key.startsWith(KEY_PREFIX_REGION) ||
                key.startsWith(KEY_PREFIX_STRAT_RTT) ||
                key.startsWith(KEY_PREFIX_REGION_STATS)
        }
        if (keys.isEmpty()) return 0
        prefs.edit().also { editor -> keys.forEach(editor::remove) }.apply()
        return keys.size
    }
}

internal data class AtomScoreboardRow(
    val networkKey: String,
    val outer: String?,
    val strategyIndex: Int?,
    val rttMs: Long?,
)
