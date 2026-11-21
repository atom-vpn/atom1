package com.atomvpn.mobile.engine.atom

import android.os.SystemClock
import com.atomvpn.mobile.engine.logging.AppLogRepository
import com.atomvpn.mobile.engine.logging.LogSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Level C: Ghost Handover — zero-downtime migration.
 * Instead of hard-cut retune (stop everything then start), warms a
 * secondary outer+inner on shadow ports (1821/1822) and atomically
 * flips upstream. TunFd stays open, user sees no RST.
 *
 * Isolated to PoW: uses shadow ports that never collide with
 * Xray (10808) or Tor (19050/19051). Hev TUN is NOT restarted.
 */
internal object AtomGhostHandover {
    const val SHADOW_CHAIN_PORT = 1821
    const val SHADOW_SOCKS_PORT = 1822

    data class WarmResult(val outerLabel: String, val socksPort: Int, val probeMs: Long)

    suspend fun warmShadowPath(
        appContext: android.content.Context,
        currentSettings: AtomEngineSettings,
        lastOuter: String,
        establish: suspend (protocol: String, discovery: String, scanMode: String, chainPort: Int) -> String?,
        startPsiphonOnShadow: suspend (socksPort: Int) -> Boolean,
    ): WarmResult? {
        val ladder = AtomCoreConfig.candidates(currentSettings)
        val ordered = AtomQualityPolicy.outerOrder(ladder, lastOuter)
        for (protocol in ordered) {
            val label = AtomCoreConfig.outerLabel(protocol)
            val outer = withTimeoutOrNull(AtomQualityPolicy.GHOST_HANDOVER_TIMEOUT_MS) {
                AppLogRepository.info(LogSource.ATOM, "Ghost handover probing $label on :$SHADOW_CHAIN_PORT")
                val ok = establish(protocol, AtomCoreConfig.DISCOVERY_FRESH, "turbo", SHADOW_CHAIN_PORT)
                ok
            }
            if (outer == null) {
                AppLogRepository.debug(LogSource.ATOM, "Ghost shadow $label no outer in time")
                continue
            }
            val psiphonOk = withTimeoutOrNull(AtomQualityPolicy.INNER_RETUNE_TIMEOUT_MS) {
                startPsiphonOnShadow(SHADOW_SOCKS_PORT)
            } ?: false
            if (!psiphonOk) {
                AppLogRepository.warning(LogSource.ATOM, "Ghost shadow $label psiphon did not connect")
                continue
            }
            delay(800)
            val probe = AtomPathProbe.measureMs(SHADOW_SOCKS_PORT)
            if (probe <= 0L) {
                AppLogRepository.warning(LogSource.ATOM, "Ghost shadow $label probe failed")
                continue
            }
            AppLogRepository.success(LogSource.ATOM, "Ghost shadow $label ready ${probe}ms")
            return WarmResult(label, SHADOW_SOCKS_PORT, probe)
        }
        return null
    }

    suspend fun atomicFlip(
        shadowSocksPort: Int,
        verifyMs: Long = 900L,
    ): Boolean {
        // Atomically redirect TUN relay to shadow; verify with a quick probe.
        AtomSocksConnectOnly.setUpstreamPort(shadowSocksPort)
        // Don't drop relays on flip — let existing streams drain.
        delay(verifyMs)
        val probe = AtomPathProbe.measureMs(shadowSocksPort)
        return probe > 0L
    }

    fun shadowChainJson(
        appContext: android.content.Context,
        protocol: String,
        settings: AtomEngineSettings,
        chainPort: Int,
        mci: Boolean = false,
    ): String {
        val base = AtomCoreConfig.chainOuterJson(
            appContext,
            protocol,
            settings,
            discovery = AtomCoreConfig.DISCOVERY_FRESH,
            scanMode = AtomCoreConfig.SCAN_TURBO,
            mci = mci,
        )
        return runCatching {
            val obj = org.json.JSONObject(base)
            obj.put("listen", "127.0.0.1:$chainPort")
            obj.toString()
        }.getOrDefault(base)
    }
}
