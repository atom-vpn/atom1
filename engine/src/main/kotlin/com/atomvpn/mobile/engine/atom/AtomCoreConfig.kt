package com.atomvpn.mobile.engine.atom

import android.content.Context
import org.json.JSONObject
import java.io.File

object AtomCoreConfig {
    const val SOCKS_PORT = 1819
    const val TUN_SOCKS_PORT = 1818
    const val CHAIN_SOCKS_PORT = 1820
    // Psiphon tunnel 2 ("two tunnels at once"); 1821/1822 belong to the ghost handover.
    const val SIDECAR_SOCKS_PORT = 1823
    const val OUTER_AUTO = "auto"
    const val DISCOVERY_CACHE = "cache"
    const val DISCOVERY_FRESH = "fresh"
    const val SCAN_TURBO = "turbo"
    const val SCAN_BALANCED = "balanced"
    const val SCAN_THOROUGH = "thorough"

    /** MASQUE first: MCI advanced filtering drops UDP (WireGuard and classic WoW) before the handshake. */
    val OUTER_LADDER = listOf("masque", "gool", "wireguard")
    val SCAN_MODES = listOf(SCAN_TURBO, SCAN_BALANCED, SCAN_THOROUGH)

    fun normalizeScanMode(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when (value) {
            "fast", SCAN_TURBO -> SCAN_TURBO
            "deep", SCAN_THOROUGH -> SCAN_THOROUGH
            SCAN_BALANCED -> SCAN_BALANCED
            else -> SCAN_BALANCED
        }
    }

    private fun migrateLegacyFile(context: Context, legacyName: String, currentName: String): File {
        val target = File(context.filesDir, currentName)
        val legacy = File(context.filesDir, legacyName)
        if (!target.exists() && legacy.isFile) runCatching { legacy.copyTo(target, overwrite = false) }
        return target
    }

    fun identityPath(context: Context): File =
        migrateLegacyFile(context, "uac-pow.toml", "atom-engine.toml")

    fun hevConfigPath(context: Context): File {
        val targetDir = File(context.filesDir, "atom-engine")
        val target = File(targetDir, "hev.yml")
        val legacy = File(File(context.filesDir, "uac-pow"), "hev.yml")
        if (!target.exists() && legacy.isFile) {
            runCatching {
                targetDir.mkdirs()
                legacy.copyTo(target, overwrite = false)
            }
        }
        return target
    }

    fun masqueCachePath(context: Context): File =
        migrateLegacyFile(context, "uac-pow-masque-cache.json", "atom-engine-masque-cache.json")

    fun lastconnPath(context: Context): File =
        migrateLegacyFile(context, "uac-pow-lastconn.toml", "atom-engine-lastconn.toml")

    fun goolLastconnPath(context: Context): File =
        migrateLegacyFile(context, "uac-pow-gool-lastconn.toml", "atom-engine-gool-lastconn.toml")

    /**
     * Clears obsolete last-connected peer hints during the one-time connect-order reset.
     * Remove both Atom copies and their UAC-era sources so migrateLegacyFile cannot
     * silently restore the stale hints on the next read. Identity and endpoint caches
     * are deliberately not included.
     */
    fun clearObsoleteLastConnMemory(context: Context): Int {
        val files = listOf(
            File(context.filesDir, "atom-engine-lastconn.toml"),
            File(context.filesDir, "uac-pow-lastconn.toml"),
            File(context.filesDir, "atom-engine-gool-lastconn.toml"),
            File(context.filesDir, "uac-pow-gool-lastconn.toml"),
        )
        return files.count { file ->
            runCatching { file.isFile && file.delete() }.getOrDefault(false)
        }
    }

    fun pathMemoryFiles(context: Context): List<File> = listOf(
        masqueCachePath(context),
        lastconnPath(context),
        goolLastconnPath(context),
        // Saved clean H2 peers are tried BEFORE any scan and each verify can
        // burn 6-8s. Leaving them after MASQUE/SIM death makes turbo retune
        // spend its whole budget on 400/timeout edges and never raise outer.
        cleanH2File(context),
    )

    fun expireStalePathMemory(context: Context, nowMs: Long = System.currentTimeMillis()): Int =
        pathMemoryFiles(context).count { AtomQualityPolicy.expireFileIfStale(it, nowMs) }

    fun forgetPathMemory(context: Context): Int =
        pathMemoryFiles(context).count { file -> file.isFile && file.delete() }

    fun cleanH2File(context: Context): File =
        migrateLegacyFile(context, "uac-pow-clean-h2.json", "atom-engine-clean-h2.json")

    fun cleanH2Peers(context: Context): List<String> {
        val file = cleanH2File(context)
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrDefault("")
        val body = text.substringAfter("\"peers\"").substringAfter("[").substringBefore("]")
        return body.split(',')
            .map { it.trim().trim('"') }
            .filter { it.contains('.') || it.contains(':') }
    }

    fun chainOuterJson(
        context: Context,
        protocol: String,
        settings: AtomEngineSettings,
        discovery: String = DISCOVERY_CACHE,
        scanMode: String = AtomCoreConfig.SCAN_BALANCED,
        mci: Boolean = false,
    ): String = JSONObject().apply {
        put("config_path", identityPath(context).absolutePath)
        put("protocol", protocol)
        put("listen", "127.0.0.1:$CHAIN_SOCKS_PORT")
        put("scan_mode", scanMode)
        val plan = outerPlan(settings, mci)
        put("ip_scan", ipScanFor(settings, plan, protocol))
        put("ip_scan_strict", ipScanStrict(settings, protocol))
        put("endpoint_cache_path", masqueCachePath(context).absolutePath)
        put(
            "endpoint_discovery",
            if (settings.linkRecipe == RECIPE_MASQUE_H2_SAVED) "clean" else discovery,
        )
        put("masque_transport", if (protocol == "masque") plan.masqueTransport else "h3")
        put("obfuscation_profile", obfuscationFor(settings, protocol, plan))
        put("retry_obfuscation_profiles", true)
        put("tls_curve_preset", "chrome")
        put("wireguard_data_check", true)
        put("log_level", "info")
        put("perf_profile", "high")
        put("h2_fragmentation", h2FragmentEffective(context, settings, protocol, plan))
        put("quic_tuned", settings.quicTuned)
        put("h2_large_windows", settings.h2LargeWindows)
        put("h2_socket_tuning", settings.h2SocketTuning)
        put("h2_speed_pick", settings.h2SpeedPick)
        put("h2_fingerprint", settings.validated().h2Fingerprint)
        put("api_camo", settings.keyFetch)
        put("gateway", false)
    }.toString()

    /** MASQUE uses the user's IP setting when it is not "auto"; other protocols keep the recipe's. */
    fun ipScanFor(settings: AtomEngineSettings, plan: AtomOuterPlan, protocol: String): String =
        if (ipScanStrict(settings, protocol)) settings.validated().masqueIpVersion else plan.ipFor(protocol)

    fun ipScanStrict(settings: AtomEngineSettings, protocol: String): Boolean =
        protocol == "masque" && settings.validated().masqueIpVersion != AtomEngineSettings.MASQUE_IP_AUTO

    /** H2 fragment: On/Off are the user's; Auto = recipe default OR the adaptive DPI flag. MASQUE h2 only. */
    fun h2FragmentEffective(context: Context, settings: AtomEngineSettings, protocol: String, plan: AtomOuterPlan): Boolean {
        val h2 = protocol == "masque" && plan.masqueTransport == "h2"
        val adaptive = AtomAdaptiveObfuscation.adaptiveOn(context)
        val effective = h2 && when (settings.h2FragmentMode) {
            AtomEngineSettings.H2_FRAG_ON -> true
            AtomEngineSettings.H2_FRAG_OFF -> false
            else -> plan.fragment || adaptive
        }
        if (protocol == "masque") AtomLiveExit.updatePath { it.copy(h2Fragment = effective) }
        if (protocol == "masque") {
            com.atomvpn.mobile.engine.logging.AppLogRepository.info(
                com.atomvpn.mobile.engine.logging.LogSource.ATOM,
                "H2 fragment: mode=${settings.h2FragmentMode} effective=$effective" +
                    " (recipe=${plan.fragment} adaptive=$adaptive transport=${plan.masqueTransport})",
            )
        }
        return effective
    }

    /** MASQUE over HTTP/2 already hides inside TLS; the user may lighten the extra obfuscation. */
    fun obfuscationFor(settings: AtomEngineSettings, protocol: String, plan: AtomOuterPlan): String {
        val h2 = protocol == "masque" && plan.masqueTransport == "h2"
        val override = settings.h2Obfuscation.trim().lowercase()
        return if (h2 && override in AtomEngineSettings.H2_OBFS_CHOICES && override != AtomEngineSettings.H2_OBFS_UNCHANGED) {
            override
        } else {
            settings.obfuscationProfile
        }
    }

    fun outerLabel(protocol: String): String = when (protocol) {
        "masque" -> "MASQUE"
        "wireguard" -> "WireGuard"
        "gool" -> "WoW"
        else -> protocol.uppercase()
    }

    fun outerBudgetMs(protocol: String, retune: Boolean = false, scanMode: String = SCAN_BALANCED): Long {
        if (retune) {
            return when (protocol) {
                "masque" -> 14_000L
                "wireguard" -> 11_000L
                else -> 16_000L
            }
        }
        val scan = normalizeScanMode(scanMode)
        val (masque, wireguard, gool) = when (scan) {
            SCAN_TURBO -> Triple(90_000L, 24_000L, 34_000L)
            SCAN_THOROUGH -> Triple(180_000L, 55_000L, 70_000L)
            else -> Triple(120_000L, 36_000L, 48_000L)
        }
        return when (protocol) {
            "masque" -> masque
            "wireguard" -> wireguard
            else -> gool
        }
    }

    fun candidates(settings: AtomEngineSettings, mci: Boolean = false): List<String> {
        val mode = settings.outerTransport
        val ladder = if (mci) MCI_LADDER else OUTER_LADDER
        return when {
            mode.isEmpty() || mode == OUTER_AUTO -> ladder
            ladder.contains(mode) || OUTER_LADDER.contains(mode) -> listOf(mode)
            else -> ladder
        }
    }

    private val MCI_LADDER = listOf("wireguard", "gool", "masque")

    const val CARRIER_AUTO = "auto"
    const val CARRIER_MANUAL = "manual"
    const val CARRIER_OFF = "off"
    val CARRIER_MODES = setOf(CARRIER_AUTO, CARRIER_MANUAL, CARRIER_OFF)

    const val FIREWALL_AUTO = "auto"
    const val FIREWALL_IRANCELL = "irancell"
    const val FIREWALL_MCI = "mci"
    val FIREWALLS = setOf(FIREWALL_AUTO, FIREWALL_IRANCELL, FIREWALL_MCI)

    const val RECIPE_AUTO = "auto"
    const val RECIPE_WARP_V6 = "warp-v6"
    const val RECIPE_WARP_ANY = "warp-any"
    const val RECIPE_WOW_V6 = "wow-v6"
    const val RECIPE_WOW_ANY = "wow-any"
    const val RECIPE_MASQUE_H2 = "masque-h2"
    const val RECIPE_MASQUE_H3 = "masque-h3"
    const val RECIPE_MASQUE_H2_SPLIT = "masque-h2-split"
    const val RECIPE_MASQUE_H2_SAVED = "masque-h2-saved"
    val RECIPES = setOf(
        RECIPE_AUTO,
        RECIPE_WARP_V6,
        RECIPE_WARP_ANY,
        RECIPE_WOW_V6,
        RECIPE_WOW_ANY,
        RECIPE_MASQUE_H2,
        RECIPE_MASQUE_H3,
        RECIPE_MASQUE_H2_SPLIT,
        RECIPE_MASQUE_H2_SAVED,
    )

    const val KEY_AUTO = "auto"
    const val KEY_ECH = "ech"
    const val KEY_FRAGMENT = "fragment"
    const val KEY_EXTERNAL = "external"
    /** OOB desync only (Android: nottl first; no relay / ECH / split). */
    const val KEY_OOB = "oob"
    val KEY_FETCHES = setOf(KEY_AUTO, KEY_OOB, KEY_ECH, KEY_FRAGMENT, KEY_EXTERNAL)

    fun keyProtocols(recipe: String): List<String> = when (recipe) {
        RECIPE_WOW_V6, RECIPE_WOW_ANY -> listOf("wireguard", "gool")
        RECIPE_MASQUE_H2, RECIPE_MASQUE_H3, RECIPE_MASQUE_H2_SPLIT, RECIPE_MASQUE_H2_SAVED -> listOf("masque")
        RECIPE_WARP_V6, RECIPE_WARP_ANY -> listOf("wireguard")
        else -> listOf("wireguard", "gool", "masque")
    }

    fun keyRequestJson(context: Context, settings: AtomEngineSettings, protocol: String): String {
        val json = JSONObject(
            chainOuterJson(
                context,
                protocol,
                settings,
                mci = usesMciFirewall(context, settings),
            ),
        )
        json.put("protocol", protocol)
        json.put("api_camo", settings.validated().keyFetch)
        return json.toString()
    }

    fun certRenewJson(context: Context, settings: AtomEngineSettings): String {
        val json = JSONObject(keyRequestJson(context, settings, "masque"))
        json.put("renew_masque", true)
        return json.toString()
    }

    fun usesMciFirewall(context: Context, settings: AtomEngineSettings): Boolean = when (settings.carrierMode) {
        CARRIER_AUTO -> AtomCarrier.carrierClass(context) == "mci"
        CARRIER_OFF -> false
        else -> when (settings.firewallProfile) {
            FIREWALL_MCI -> true
            FIREWALL_IRANCELL -> false
            else -> AtomCarrier.carrierClass(context) == "mci"
        }
    }

    fun outerPlan(
        settings: AtomEngineSettings,
        mci: Boolean,
        carrierClass: String = if (mci) "mci" else "unknown",
    ): AtomOuterPlan {
        val recipe = settings.validated().linkRecipe
        val recipePlan = when {
            settings.carrierMode == CARRIER_AUTO -> when (carrierClass) {
                "mci" -> AtomOuterPlan(listOf("masque"), "both", "h2", false, true, "Hamrah Aval · MASQUE HTTP/2")
                "irancell" -> AtomOuterPlan(OUTER_LADDER, "v4", "h3", false, false, "Irancell · automatic ladder")
                else -> AtomOuterPlan(OUTER_LADDER, "v4", "h3", false, false, "Generic · automatic ladder")
            }
            settings.carrierMode == CARRIER_OFF ->
                AtomOuterPlan(OUTER_LADDER, "v4", "h3", false, false, "Generic automatic ladder")
            settings.carrierMode == CARRIER_MANUAL && settings.firewallProfile == FIREWALL_MCI ->
                AtomOuterPlan(listOf("masque"), "both", "h2", false, true, "Hamrah Aval · manual")
            settings.carrierMode == CARRIER_MANUAL ->
                AtomOuterPlan(OUTER_LADDER, "v4", "h3", false, false, "Irancell · manual ladder")
            else -> when (recipe) {
            RECIPE_WARP_V6 -> AtomOuterPlan(listOf("wireguard"), "v6", "h3", false, true, "WARP IPv6")
            RECIPE_WARP_ANY -> AtomOuterPlan(listOf("wireguard"), "both", "h3", false, true, "WARP any IP")
            RECIPE_WOW_V6 -> AtomOuterPlan(listOf("gool"), "v6", "h3", false, true, "WARP-in-WARP IPv6")
            RECIPE_WOW_ANY -> AtomOuterPlan(listOf("gool"), "both", "h3", false, true, "WARP-in-WARP any IP")
            RECIPE_MASQUE_H2 -> AtomOuterPlan(listOf("masque"), "both", "h2", false, true, "MASQUE HTTP/2")
            RECIPE_MASQUE_H3 -> AtomOuterPlan(listOf("masque"), "v4", "h3", false, true, "MASQUE HTTP/3")
            RECIPE_MASQUE_H2_SPLIT -> AtomOuterPlan(listOf("masque"), "v4", "h2", true, true, "MASQUE HTTP/2 split hello")
            RECIPE_MASQUE_H2_SAVED -> AtomOuterPlan(listOf("masque"), "both", "h2", false, true, "MASQUE HTTP/2 saved IPs")
            else -> if (mci) {
                AtomOuterPlan(listOf("masque"), "both", "h2", false, true, "MASQUE HTTP/2")
            } else {
                AtomOuterPlan(OUTER_LADDER, "v4", "h3", false, false, "Automatic ladder")
            }
        }
        }

        // An explicit transport selection must override the recipe's protocol
        // list. Automatic keeps the recipe/carrier-aware protocol order.
        val selectedTransport = settings.outerTransport.trim().lowercase()
        return if (selectedTransport in OUTER_LADDER) {
            AtomOuterPlan(
                protocols = listOf(selectedTransport),
                ipMode = recipePlan.ipMode,
                masqueTransport = recipePlan.masqueTransport,
                fragment = recipePlan.fragment,
                fixedOrder = true,
                summary = "${recipePlan.summary} · ${outerLabel(selectedTransport)}",
            )
        } else {
            recipePlan
        }
    }
}

class AtomOuterPlan(
    val protocols: List<String>,
    val ipMode: String,
    val masqueTransport: String,
    val fragment: Boolean,
    val fixedOrder: Boolean,
    val summary: String,
) {
    fun ipFor(protocol: String): String = when (ipMode) {
        "v6-warp" -> if (protocol == "wireguard" || protocol == "gool") "v6" else "v4"
        "v6", "v4", "both" -> ipMode
        else -> "v4"
    }
}
