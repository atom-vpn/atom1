package com.atomvpn.mobile.engine.atom

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.telephony.SubscriptionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import com.atomvpn.mobile.engine.core.ConnectionStateStore
import com.atomvpn.mobile.engine.logging.AppLogRepository
import com.atomvpn.mobile.engine.logging.LogSource
import com.atomvpn.mobile.engine.mci.DomesticDirect
import com.atomvpn.mobile.engine.settings.NetworkGuardStore
import com.atomvpn.mobile.engine.settings.AdvancedSettingsData
import com.atomvpn.mobile.engine.settings.CONNECTION_MODE_PROXY
import com.atomvpn.mobile.engine.vpn.TunStats
import com.atomvpn.mobile.engine.vpn.TunPacketFilter
import com.atomvpn.mobile.engine.vpn.VpnPauseStore
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class AtomConnectionCoordinator(
    private val service: VpnService,
) {
    private val appContext = service.applicationContext
    private val store = AtomEngineStore.get(appContext)
    private val lifecycle = Mutex()
    private val retuneMutex = Mutex()
    private val qualityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private val retuning = AtomicBoolean(false)
    private val innerCallbacksArmed = AtomicBoolean(true)
    private val qualityRetunes = AtomicInteger(0)
    private val crashRecovers = AtomicInteger(0)
    private val sessionGeneration = AtomicInteger(0)
    private val psiphonGeneration = AtomicInteger(0)
    private val txBytes = AtomicLong(0L)
    private val rxBytes = AtomicLong(0L)
    private val txPackets = AtomicLong(0L)
    private val rxPackets = AtomicLong(0L)
    private val ladderActive = AtomicBoolean(false)
    private val callbacks = PsiphonCallbacks()
    @Volatile private var psiphon: AtomPsiphonClient? = null
    @Volatile private var lastPsiphonConfig = ""
    private val sidecar by lazy {
        AtomPsiphonSidecar(service) { sent, received ->
            if (sent > 0) {
                txBytes.addAndGet(sent)
                txPackets.incrementAndGet()
            }
            if (received > 0) {
                rxBytes.addAndGet(received)
                rxPackets.incrementAndGet()
            }
        }
    }

    private val ghostInner by lazy {
        AtomGhostInner(service) { sent, received ->
            if (sent > 0) {
                txBytes.addAndGet(sent)
                txPackets.incrementAndGet()
            }
            if (received > 0) {
                rxBytes.addAndGet(received)
                rxPackets.incrementAndGet()
            }
        }
    }
    /** True while the relay points at the ghost Psiphon instead of tunnel 1. */
    @Volatile private var ghostServing = false
    private val ghostFinishing = AtomicBoolean(false)

    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var ownsTun = false
    @Volatile private var proxyMode = false
    @Volatile private var socksPort = AtomCoreConfig.SOCKS_PORT
    @Volatile private var outerThread: Thread? = null
    @Volatile private var outerCommitted = false
    @Volatile private var directPsiphon = false
    @Volatile private var warpOnly = false
    /** User picked 'Psiphon only': no Aether/WARP leg at all, for connect and every retune. */
    @Volatile private var psiphonOnly = false
    @Volatile private var innerReady = false
    @Volatile private var routing = false
    @Volatile private var activeTunnelProtocol = ""
    @Volatile private var regionPhase = false
    @Volatile private var regionPhaseTried = ""
    @Volatile private var regionPhaseBias = false
    @Volatile private var regionCandidates: List<String> = emptyList()
    @Volatile private var regionCandidateIndex = 0
    @Volatile private var regionAttemptStartedAtMs = 0L
    @Volatile private var regionAttemptCode = ""
    @Volatile private var connectedServerRegion = ""
    @Volatile private var ladderIndex = 0
    @Volatile private var ladderAttempts = 0
    @Volatile private var connectedSignal: CompletableDeferred<Unit>? = null
    @Volatile private var ladderJob: Job? = null
    @Volatile private var currentSettings = AtomEngineSettings.DEFAULT
    @Volatile private var innerStarted = false
    @Volatile private var sessionMtu = AtomTunRelayConfig.DEFAULT_MTU
    @Volatile private var qualityJob: Job? = null
    @Volatile private var watchdogJob: Job? = null
    @Volatile private var passiveJob: Job? = null
    @Volatile private var retuneSignal: CompletableDeferred<Unit>? = null
    @Volatile private var lastOuterProtocol = ""
    @Volatile private var wonTransport = ""
    @Volatile private var wonIp = ""
    @Volatile private var lastRetuneAt = 0L
    @Volatile private var baselineRttMs = 0L
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile var notificationSink: ((Boolean) -> Unit)? = null
    @Volatile private var recentSamples: MutableList<Long> = mutableListOf()
    @Volatile private var ghostInFlight = AtomicBoolean(false)
    @Volatile private var lastStableAt = SystemClock.elapsedRealtime()
    @Volatile private var restoreJob: Job? = null
    private val restoreWaiting = AtomicBoolean(false)
    private val restoreAttempts = AtomicInteger(0)
    /** Ignore VPN-up / link callbacks as path-loss until first settle after ready. */
    @Volatile private var connectGraceUntilMs = 0L
    @Volatile private var lastRadioIdentity: AtomRadioIdentity? = null
    @Volatile private var simSwitchJob: Job? = null
    private val simSwitchInFlight = AtomicBoolean(false)
    private var subscriptionsCallback: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var watchdogTx = -1L
    private var watchdogRx = -1L
    private var watchdogProcessMisses = 0
    private var watchdogStallStrikes = 0
    private val psiphonTunnelCount = AtomicInteger(0)
    @Volatile private var zeroTunnelJob: Job? = null

    val tunAddress: AtomTun2Socks.PrivateAddress
        get() = AtomTun2Socks.privateAddress

    fun isRetuning(): Boolean = retuning.get()

    fun isAwaitingNetworkRestore(): Boolean = restoreWaiting.get()

    fun isHealthyPath(): Boolean =
        !stopRequested.get() &&
            innerReady &&
            (warpOnly || psiphonTunnelCount.get() > 0) &&
            (proxyMode || AtomTun2Socks.isRunning) &&
            (directPsiphon || (AetherNative.isRunning() && AetherNative.isReady()))

    fun isRunning(): Boolean {
        if (retuning.get()) {
            return !stopRequested.get() && (proxyMode || AtomTun2Socks.isRunning)
        }
        return innerReady &&
            (proxyMode || AtomTun2Socks.isRunning) &&
            (directPsiphon || AetherNative.isRunning())
    }

    fun isOuterRunning(): Boolean = AetherNative.isRunning()

    fun isRelayRunning(): Boolean = proxyMode || AtomTun2Socks.isRunning

    fun socksPort(): Int = socksPort

    private fun tunByteTotal(): Long = runCatching {
        val stats = tunStats()
        stats.txBytes + stats.rxBytes
    }.getOrDefault(0L)

    fun tunStats(): TunStats {
        if (!proxyMode) {
            val tun = AtomTun2Socks.stats()
            if (tun != TunStats.ZERO) return tun
        }
        return TunStats(
            txPackets = txPackets.get(),
            txBytes = txBytes.get(),
            rxPackets = rxPackets.get(),
            rxBytes = rxBytes.get(),
        )
    }

    suspend fun connect(
        settings: AdvancedSettingsData,
        establishTun: (AtomTun2Socks.PrivateAddress, Int) -> ParcelFileDescriptor?,
    ) {
        lifecycle.withLock { stopLocked() }
        DomesticDirect.ensure(appContext)
        val iranOn = NetworkGuardStore.get(appContext).snapshot().iranDirect
        DomesticDirect.applyEnabled(iranOn)
        AtomSocksConnectOnly.setDomesticDirect(iranOn)
        stopRequested.set(false)
        sessionGeneration.incrementAndGet()
        txBytes.set(0L)
        rxBytes.set(0L)
        txPackets.set(0L)
        rxPackets.set(0L)
        innerReady = false
        innerStarted = false
        psiphonTunnelCount.set(0)
        cancelZeroTunnelTimeout()
        routing = false
        outerCommitted = false
        warpOnly = false
        directPsiphon = false
        psiphonOnly = false
        sessionMtu = AtomTunRelayConfig.DEFAULT_MTU
        activeTunnelProtocol = ""
        lastOuterProtocol = ""
        wonTransport = ""
        wonIp = ""
        baselineRttMs = 0L
        lastRetuneAt = 0L
        qualityRetunes.set(0)
        crashRecovers.set(0)
        currentSettings = store.snapshot()
        proxyMode = settings.connectionMode == CONNECTION_MODE_PROXY
        socksPort = if (proxyMode) settings.socksPort else AtomCoreConfig.SOCKS_PORT
        AtomLiveExit.reset()
        AtomStatusStore.reset()
        armRegionPhase()
        ladderIndex = rememberedLadderStart()
        ladderAttempts = 0
        if (!AetherNative.available) {
            error(AetherNative.unavailableReason)
        }
        try {
            coroutineScope {
                val expired = AtomCoreConfig.expireStalePathMemory(appContext)
                if (expired > 0) {
                    AppLogRepository.info(
                        LogSource.ATOM,
                        "Dropped $expired stale WARP endpoint cache(s) so the next hop can be measured again",
                    )
                }
                AtomStatusStore.update(AtomPhase.STARTING, 5, "Starting Atom")
                if (!proxyMode) {
                    AtomTun2Socks.selectPrivateAddress()
                    val atomMtu = store.snapshot().tunMtu
                    sessionMtu = if (atomMtu > 0) {
                        AtomTunRelayConfig.mtuForChain(atomMtu, underlyingLinkMtu(), AtomTunRelayConfig.MAX_CUSTOM_MTU)
                    } else {
                        AtomTunRelayConfig.mtuForChain(settings.tunMtu, underlyingLinkMtu())
                    }
                }
                psiphonOnly = currentSettings.exitMode == AtomEngineSettings.EXIT_PSIPHON
                val outer = if (psiphonOnly) {
                    AppLogRepository.info(LogSource.ATOM, "Exit: Psiphon only - WARP leg skipped")
                    null
                } else {
                    AetherNative.attach(service)
                    raiseOuterLeg(
                        scanMode = AtomCoreConfig.normalizeScanMode(currentSettings.scanMode),
                    )
                }
                val chain = currentSettings.chainPsiphon
                if (!chain && outer == null) {
                    error("WARP did not come up")
                }
                val outerName: String
                if (!chain) {
                    outerName = outer ?: "WARP"
                    if (!proxyMode) openDeviceTun(establishTun)
                    finishWithoutPsiphon()
                } else {
                directPsiphon = outer == null
                outerName = outer ?: "Psiphon"
                if (directPsiphon && !psiphonOnly) {
                    AppLogRepository.warning(
                        LogSource.ATOM,
                        "No WARP transport came up; Psiphon will connect on its own",
                    )
                }
                // directPsiphon is only known now: pick the ladder, start
                // strategy and region for this leg.
                armRegionPhase()
                ladderIndex = rememberedLadderStart()
                ladderAttempts = 0
                logPsiphonPlan()
                ensureActive()
                AtomStatusStore.update(
                    AtomPhase.INNER,
                    55,
                    if (directPsiphon) "Starting Psiphon directly" else "Starting Psiphon through $outerName",
                    outerLabel = outerName,
                )
                connectedSignal = CompletableDeferred()
                startPsiphonTunnel()
                ladderJob = launch {
                    watchLadder()
                }
                val connected = connectedSignal ?: error("Psiphon connect was not started")
                try {
                    withTimeout(INNER_CONNECT_TIMEOUT_MS) { connected.await() }
                } catch (timeout: TimeoutCancellationException) {
                    error("Psiphon did not connect in time")
                }
                ensureActive()
                if (!proxyMode) {
                    openDeviceTun(establishTun)
                    val fd = tunFd ?: error("VPN interface missing")
                    AtomStatusStore.update(AtomPhase.BRIDGING, 90, "Routing device traffic through Atom")
                    if (!AtomSocksConnectOnly.start(AtomCoreConfig.TUN_SOCKS_PORT, socksPort)) {
                        error("Could not start whole-device routing")
                    }
                    if (!AtomTun2Socks.start(
                            appContext,
                            fd,
                            AtomCoreConfig.TUN_SOCKS_PORT,
                            sessionMtu,
                            tunAddress,
                        )
                    ) {
                        AtomSocksConnectOnly.stop()
                        error("Could not start whole-device routing")
                    }
                    routing = true
                }
                }
                innerReady = true
                rememberConnectedMode()
                val ready = when {
                    proxyMode -> "Atom ready · Proxy SOCKS 127.0.0.1:$socksPort"
                    warpOnly -> "Atom ready · Tunnel VPN through WARP"
                    directPsiphon -> "Atom ready · Tunnel VPN through Psiphon"
                    else -> "Atom ready · Tunnel VPN through Psiphon over WARP"
                }
                AtomStatusStore.update(AtomPhase.CONNECTED, 100, ready, outerLabel = outerName)
                AppLogRepository.success(LogSource.ATOM, ready)
                // Arm before markConnected: VPN-up callbacks race the service's second
                // markConnected and must not treat TUN bring-up as path loss.
                connectGraceUntilMs = SystemClock.elapsedRealtime() + CONNECT_GRACE_MS
                ConnectionStateStore.markConnected()
            }
            startSessionWatch()
            AtomPageTurbo.kick(qualityScope, socksPort, keepWarm = true)
            kickDnsWarm(force = true)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stop() }
            throw cancelled
        } catch (error: Throwable) {
            AtomStatusStore.update(
                AtomPhase.FAILED,
                0,
                error.message.orEmpty().ifBlank { "Atom connect failed" },
            )
            withContext(NonCancellable) { stop() }
            throw error
        }
    }

    suspend fun stop() {
        signalStop()
        cancelNetworkRestore()
        cancelQualityWork()
        AtomPageTurbo.cancel()
        AtomDnsWarm.cancel()
        connectedSignal?.cancel()
        unregisterNetworkCallback()
        lifecycle.withLock { stopLocked() }
    }

    /**
     * Non-blocking stop signal. Safe from any thread and idempotent: marks the session as
     * stopping and asks the native core to abort an in-flight prepare (key race / cert
     * renewal) or outer tunnel right away, so loops and joins unwind instead of finishing
     * their current step first.
     */
    fun signalStop() {
        stopRequested.set(true)
        cancelZeroTunnelTimeout()
        psiphonTunnelCount.set(0)
        ladderActive.set(false)
        connectedSignal?.cancel()
        retuneSignal?.cancel()
        runCatching { AetherNative.abortPrepare() }
        runCatching { AetherNative.stop() }
    }

    /**
     * Last-resort teardown used when a normal stop did not finish within the service's hard
     * timeout. Never suspends and never takes [lifecycle]; the slow parts run on a daemon
     * thread with their own bounds. The regular [stop] may still complete later; every step
     * here is idempotent with it.
     */
    fun forceRelease() {
        signalStop()
        val forcedSession = sessionGeneration.incrementAndGet()
        cancelNetworkRestore()
        cancelQualityWork()
        AtomPageTurbo.cancel()
        AtomDnsWarm.cancel()
        unregisterNetworkCallback()
        ladderJob?.cancel()
        runCatching { sidecar.forceRelease() }
        ghostServing = false
        runCatching { ghostInner.forceRelease() }
        innerReady = false
        val client = psiphon
        val fd = if (ownsTun) tunFd else null
        val reaper = Thread({
            runCatching { AtomTun2Socks.stop() }
            runCatching { AtomTun2Socks.awaitNativeExit(FORCE_NATIVE_EXIT_MS) }
            runCatching { TunPacketFilter.stop() }
            runCatching { fd?.close() }
            // Anything after the wait must not touch a session the user started meanwhile.
            if (forcedSession != sessionGeneration.get()) return@Thread
            runCatching { AtomSocksConnectOnly.stop() }
            runCatching { AetherNative.stop() }
            // Release both the JNI global reference and OOB's service reference only
            // after the native core has stopped, and never detach a newer session.
            if (forcedSession != sessionGeneration.get()) return@Thread
            runCatching { AetherNative.detach() }
            if (client != null && forcedSession == sessionGeneration.get()) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(FORCE_NATIVE_EXIT_MS) { client.stop() }
                    }
                }
                runCatching { client.close() }
            }
        }, "atom-engine-force-release")
        reaper.isDaemon = true
        reaper.start()
        if (AtomStatusStore.status.value.phase != AtomPhase.FAILED) {
            AtomStatusStore.reset()
        }
        AppLogRepository.warning(LogSource.ATOM, "Atom force-released after stop timeout")
    }

    fun notifyPathLost(reason: String) {
        beginNetworkRestore(reason)
    }

    suspend fun recoverBehindTun(reason: String, force: Boolean = false): Boolean {
        if (stopRequested.get()) return false
        if (!proxyMode && !AtomTun2Socks.isRunning) return false
        return retuneMutex.withLock {
            if (stopRequested.get()) return@withLock false
            if (!force && isHealthyPath()) return@withLock true
            if (
                !restoreWaiting.get() &&
                crashRecovers.incrementAndGet() > AtomQualityPolicy.MAX_CRASH_RECOVERS
            ) {
                AppLogRepository.warning(LogSource.ATOM, "Atom path refresh limit reached")
                return@withLock false
            }
            // Outer must accept SOCKS and actually forward traffic — isRunning alone can be a zombie.
            val outerAlive = !psiphonOnly &&
                AetherNative.isRunning() &&
                AetherNative.isReady() &&
                outerProxyResponsive()
            val recovered = if (outerAlive) {
                AppLogRepository.info(LogSource.ATOM, "Outer still healthy — inner-only rebuild")
                retuneInnerOnly(reason)
            } else {
                AppLogRepository.info(LogSource.ATOM, "Outer down or SOCKS dead — full outer then Psiphon rebuild")
                retuneLocked(reason, quality = false)
            }
            if (recovered && currentSettings.optimizedMode) resetWatchdogBaseline()
            recovered
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (
                    !AtomNetworkRestore.shouldRetryOnAvailable(
                        waiting = restoreWaiting.get(),
                        stopRequested = stopRequested.get(),
                        pathReady = innerReady,
                    )
                ) {
                    return
                }
                AppLogRepository.info(LogSource.ATOM, "Network restored — retrying Atom now")
                restoreAttempts.set(0)
                scheduleRestoreAttempt("network restored", immediate = true)
            }

            override fun onLost(network: Network) {
                if (AtomNetworkRestore.shouldVerifyOnLost(
                        sessionLive = sessionSupposedUp(),
                        retuning = retuning.get(),
                        stopRequested = stopRequested.get(),
                        paused = VpnPauseStore.isPaused(),
                    )) {
                    qualityScope.launch { verifyPathAfterNetworkChange() }
                }
            }
        }
        runCatching { cm.registerNetworkCallback(req, cb) }
        networkCallback = cb
        registerSubscriptionListener()
    }

    private fun registerSubscriptionListener() {
        if (subscriptionsCallback != null) return
        // OnSubscriptionsChangedListener() constructs Handler(Looper.myLooper()).
        // connect()/startSessionWatch run on DefaultDispatcher — must build on main.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            registerSubscriptionListenerOnMain()
            return
        }
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                registerSubscriptionListenerOnMain()
            } catch (error: Throwable) {
                failure = error
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            AppLogRepository.debug(LogSource.ATOM, "Subscription listener register timed out on main")
            return
        }
        failure?.let { throw it }
    }

    private fun registerSubscriptionListenerOnMain() {
        if (subscriptionsCallback != null) return
        val sm = appContext.getSystemService(SubscriptionManager::class.java) ?: return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                if (stopRequested.get() || VpnPauseStore.isPaused() || retuning.get()) return
                val previous = lastRadioIdentity
                val current = AtomCarrier.radioIdentity(appContext)
                // VPN bring-up fires this with no baseline — only learn identity, never wipe.
                if (previous == null) {
                    if (sessionSupposedUp()) {
                        lastRadioIdentity = current
                        AppLogRepository.debug(
                            LogSource.ATOM,
                            "Subscription event — baseline radio ${current.summary()}",
                        )
                    }
                    return
                }
                if (!current.isSimOrOperatorChange(previous)) return
                if (sessionSupposedUp() && !retuning.get() && !stopRequested.get()
                    && !VpnPauseStore.isPaused() && !restoreWaiting.get()
                ) {
                    scheduleSimSwitchReset("subscriptions changed")
                }
            }
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                sm.addOnSubscriptionsChangedListener(appContext.mainExecutor, listener)
            } else {
                @Suppress("DEPRECATION")
                sm.addOnSubscriptionsChangedListener(listener)
            }
        }
        if (registered.isFailure) {
            AppLogRepository.debug(
                LogSource.ATOM,
                "Subscription listener unavailable: ${registered.exceptionOrNull()?.message}",
            )
            return
        }
        subscriptionsCallback = listener
    }

    private fun unregisterSubscriptionListener() {
        val listener = subscriptionsCallback ?: return
        subscriptionsCallback = null
        val sm = appContext.getSystemService(SubscriptionManager::class.java) ?: return
        runCatching { sm.removeOnSubscriptionsChangedListener(listener) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        cancelSimSwitchWork()
        unregisterSubscriptionListener()
        runCatching { appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private fun beginNetworkRestore(reason: String) {
        if (stopRequested.get()) return
        if (!restoreWaiting.compareAndSet(false, true)) return
        innerReady = false
        restoreAttempts.set(0)
        cancelQualityWork()
        publishReconnecting(reason)
        registerNetworkCallback()
        AppLogRepository.warning(LogSource.ATOM, "Atom waiting to reconnect: $reason")
        scheduleRestoreAttempt(reason, immediate = false)
    }

    private fun scheduleRestoreAttempt(reason: String, immediate: Boolean) {
        restoreJob?.cancel()
        val delayMs = if (immediate) 0L else AtomNetworkRestore.delayMs(restoreAttempts.getAndIncrement())
        val session = sessionGeneration.get()
        restoreJob = qualityScope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (stopRequested.get() || !restoreWaiting.get() || session != sessionGeneration.get()) return@launch
            val recovered = try {
                recoverBehindTun(reason, force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (stopRequested.get() || session != sessionGeneration.get()) return@launch
            if (recovered) {
                finishNetworkRestore()
            } else {
                AppLogRepository.warning(
                    LogSource.ATOM,
                    "Atom reconnect missed ($reason); waiting for the next attempt",
                )
                if (!stopRequested.get() && restoreWaiting.get()) {
                    scheduleRestoreAttempt(reason, immediate = false)
                }
            }
        }
    }

    private fun finishNetworkRestore() {
        restoreWaiting.set(false)
        restoreAttempts.set(0)
        crashRecovers.set(0)
        restoreJob = null
        // The network callback stays registered for the session — push-based, costs nothing.
        publishConnected()
        startSessionWatch()
    }

    private fun cancelNetworkRestore() {
        restoreWaiting.set(false)
        restoreAttempts.set(0)
        restoreJob?.cancel()
        restoreJob = null
        cancelSimSwitchWork()
        // The network callback stays registered for the session — push-based, costs nothing.
    }

    private fun publishReconnecting(reason: String) {
        if (stopRequested.get()) return
        ConnectionStateStore.markConnecting()
        AtomStatusStore.update(
            AtomPhase.STARTING,
            8,
            AtomNetworkRestore.statusDetail(reason),
        )
        notificationSink?.invoke(false)
    }

    private fun publishConnected() {
        if (stopRequested.get()) return
        lastRadioIdentity = AtomCarrier.radioIdentity(appContext)
        ConnectionStateStore.markConnecting()
        ConnectionStateStore.markConnected()
        val outer = AtomStatusStore.status.value.outerLabel.ifBlank {
            AtomCoreConfig.outerLabel(lastOuterProtocol.ifBlank { store.rememberedOuterProtocol().orEmpty() })
        }
        val ready = when {
            proxyMode -> "Atom ready · Proxy SOCKS 127.0.0.1:$socksPort"
            warpOnly -> "Atom ready · Tunnel VPN through WARP"
            directPsiphon -> "Atom ready · Tunnel VPN through Psiphon"
            else -> "Atom ready · Tunnel VPN through Psiphon over WARP"
        }
        AtomStatusStore.update(AtomPhase.CONNECTED, 100, ready, outerLabel = outer)
        notificationSink?.invoke(true)
    }

    private suspend fun retuneInnerOnly(reason: String): Boolean {
        if (stopRequested.get() || warpOnly) return !stopRequested.get() && warpOnly
        setRetuning(true)
        AtomSocksConnectOnly.setFailFast(true)
        AppLogRepository.info(LogSource.ATOM, "Inner-only rebuild ($reason)")
        dropGhost("inner rebuild")
        innerReady = false
        return try {
            innerCallbacksArmed.set(false)
            innerStarted = false
            withContext(Dispatchers.IO) { runCatching { innerClient().stop() } }
            if (stopRequested.get()) return false
            delay(400)
            armRegionPhase(retune = true); ladderIndex = rememberedLadderStart(); ladderAttempts = 0
            ladderActive.set(false); ladderJob?.cancel()
            val keepExit = AtomRegions.egressRegion(currentSettings.exitCountryCode) != null
            val connected = CompletableDeferred<Unit>()
            retuneSignal = connected
            innerCallbacksArmed.set(true)
            startPsiphonTunnel(keepExit = keepExit)
            ladderJob = qualityScope.launch { watchLadder() }
            withTimeout(AtomQualityPolicy.INNER_RETUNE_TIMEOUT_MS) { connected.await() }
            if (stopRequested.get()) return false
            lastRetuneAt = SystemClock.elapsedRealtime()
            val sample = AtomPathProbe.measureMsLite(socksPort)
            if (sample > 0L) {
                baselineRttMs = sample
                recordStrategyRtt(sample)
            }
            AppLogRepository.success(
                LogSource.ATOM,
                "Inner-only path rebuilt" + if (sample > 0L) " ${sample}ms" else "",
            )
            true
        } catch (_: TimeoutCancellationException) {
            AppLogRepository.warning(LogSource.ATOM, "Inner-only rebuild timeout — escalating to full handover")
            retuneLocked(reason, quality = false)
        } catch (e: Throwable) {
            AppLogRepository.warning(LogSource.ATOM, "Inner-only failed: ${e.message}")
            false
        } finally {
            retuneSignal = null
            innerCallbacksArmed.set(true)
            AtomSocksConnectOnly.setFailFast(false)
            setRetuning(false)
        }
    }

    private fun scanPulse(message: String): String {
        val scanning = message.startsWith("Scanning ")
        val body = if (scanning) {
            message.removePrefix("Scanning ").substringBefore(" via")
        } else {
            message.removePrefix("Rejected ").substringBefore(":")
        }
        val why = when {
            scanning -> "trying"
            message.contains("status 400") -> "400"
            message.contains("ACCESS_DENIED") -> "access-denied"
            message.contains("CERTIFICATE") -> "certificate"
            message.contains("timeout") -> "timeout"
            message.contains("reset") -> "reset"
            message.contains("unexpected EOF") -> "eof"
            else -> "other"
        }
        return "Scan $body $why"
    }

    private fun stageLine(message: String): String? = when {
        message.startsWith("Key:") -> message.removePrefix("Key:").trim().let { "Key $it" }
        message.startsWith("Clean HTTP/2 list:") -> message
        message.startsWith("Clean HTTP/2 list is empty. Starting") -> "Clean list empty. HTTP/2 scan is starting."
        message.startsWith("Trying saved clean address") -> message
        message.startsWith("Saved clean address accepted") -> message
        message.startsWith("Saved clean address rejected") -> message
        message.startsWith("Every saved address failed. Scan was not started") -> message
        message.startsWith("Every saved address failed. Starting") -> "Saved addresses failed. HTTP/2 scan is starting."
        message.startsWith("Scanning HTTP/2") -> message
        message.startsWith("Saved clean HTTP/2 address") -> message
        message.startsWith("Scanning ") -> scanPulse(message)
        message.startsWith("Rejected ") -> scanPulse(message)
        message.startsWith("HTTP/2 selected") -> "HTTP/2 started"
        message.startsWith("MASQUE accepted") -> message
        message.startsWith("MASQUE start") -> message
        message.startsWith("Tunnel did not stay up") -> message
        message.startsWith("MASQUE: dialing known gateways on UDP/1701") -> "Trying UDP/1701"
        message.startsWith("MASQUE: no known gateway accepted UDP/1701") -> "UDP/1701 did not answer"
        message.startsWith("MASQUE: UDP/1701 did not answer") -> "UDP/1701 did not answer, trying TCP"
        message.startsWith("No usable MASQUE") -> message
        message.startsWith("MASQUE identity refused") -> "Certificate refused. Requesting a new one."
        message.startsWith("Certificate ") -> message
        message.startsWith("Certificate refused during") -> message
        message.startsWith("This certificate was refused") -> message
        message.startsWith("New certificate saved") -> message
        message.startsWith("New MASQUE certificate issued") -> "New certificate issued. Retrying the gateways."
        message.startsWith("Could not refresh the MASQUE certificate") -> message
        else -> null
    }

    fun onNativeEvent(json: String) {
        val event = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (event.optString("type")) {
            "log" -> {
                val message = event.optString("message")
                if (message.isNotBlank()) {
                    if (message.startsWith("Key:") ||
                        message.startsWith("MASQUE H2: TLS fingerprint") || message.startsWith("MASQUE: IP")
                    ) {
                        AppLogRepository.info(LogSource.ATOM, message.take(500))
                    } else {
                        AppLogRepository.debug(LogSource.ATOM, message.take(240))
                    }
                    stageLine(message)?.let { line ->
                        if (line.startsWith("Scan ")) {
                            AtomStatusStore.scan(line, line.substringAfterLast(' '))
                        } else {
                            val failed = line.startsWith("Every saved") ||
                                line.startsWith("Tunnel did not") ||
                                line.startsWith("UDP/1701 did not") ||
                                line.startsWith("No usable MASQUE")
                            AtomStatusStore.note(line, failed)
                        }
                    }
                }
            }
        }
    }

    private suspend fun raiseOuterLeg(
        discovery: String = AtomCoreConfig.DISCOVERY_CACHE,
        scanMode: String = AtomCoreConfig.SCAN_BALANCED,
        silent: Boolean = false,
        retune: Boolean = false,
        chainPort: Int = AtomCoreConfig.CHAIN_SOCKS_PORT,
    ): String? {
        val mci = AtomCoreConfig.usesMciFirewall(appContext, currentSettings)
        val plan = AtomCoreConfig.outerPlan(currentSettings, mci, AtomCarrier.carrierClass(appContext))
        val ladder = plan.protocols
        val auto = ladder.size > 1 && !plan.fixedOrder
        val remembered = store.rememberedOuterProtocol()
        val ordered = when {
            retune -> AtomQualityPolicy.outerOrder(ladder, lastOuterProtocol.ifBlank { remembered.orEmpty() })
            plan.fixedOrder -> ladder
            auto -> {
                val first = ladder.firstOrNull()
                buildList {
                    if (first != null) add(first)
                    if (remembered != null && remembered in ladder && remembered != first) add(remembered)
                    addAll(ladder.filter { it != first && it != remembered })
                }
            }
            else -> ladder
        }
        if (!silent) {
            val firewall = when (currentSettings.firewallProfile) {
                AtomCoreConfig.FIREWALL_MCI -> "Hamrah Aval"
                AtomCoreConfig.FIREWALL_IRANCELL -> "Irancell"
                else -> when (AtomCarrier.carrierClass(appContext)) {
                    "mci" -> "Hamrah Aval from SIM"
                    "irancell" -> "Irancell from SIM"
                    else -> "unspecified network"
                }
            }
            AppLogRepository.info(
                LogSource.ATOM,
                "Firewall $firewall · ${plan.summary} · key ${currentSettings.keyFetch} · " +
                    when (currentSettings.exitMode) { AtomEngineSettings.EXIT_WARP -> "WARP only"; AtomEngineSettings.EXIT_PSIPHON -> "Psiphon only"; else -> "Psiphon chain" },
            )
        }
        for ((offset, protocol) in ordered.withIndex()) {
            coroutineContext.ensureActive()
            if (stopRequested.get()) return null
            val label = AtomCoreConfig.outerLabel(protocol)
            val attemptScan = if (!retune && offset == 0 && protocol == "masque") {
                AtomCoreConfig.SCAN_THOROUGH
            } else {
                scanMode
            }
            val budget = AtomCoreConfig.outerBudgetMs(
                protocol,
                retune = retune,
                scanMode = attemptScan,
            )
            if (AetherNative.isRunning()) {
                stopOuterLeg()
            }
            if (!silent) {
                AtomStatusStore.update(
                    AtomPhase.OUTER,
                    20 + (offset * 10),
                    "Connecting $label",
                    outerLabel = label,
                )
            }
            AppLogRepository.info(
                LogSource.ATOM,
                "Outer attempt ${offset + 1}/${ordered.size}: $label (${budget / 1000}s, $attemptScan)" +
                    if (retune) " fresh" else "",
            )
            val config = if (chainPort == AtomCoreConfig.CHAIN_SOCKS_PORT) {
                AtomCoreConfig.chainOuterJson(
                    appContext,
                    protocol,
                    currentSettings,
                    discovery = discovery,
                    scanMode = attemptScan,
                    mci = mci,
                )
            } else {
                AtomGhostHandover.shadowChainJson(appContext, protocol, currentSettings, chainPort, mci)
            }
            val prepared = try {
                Result.success(AetherNative.prepareCancellable(config))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (stopRequested.get()) return null
            if (prepared.isFailure) {
                AppLogRepository.warning(
                    LogSource.ATOM,
                    "$label could not be prepared: ${AetherNative.lastError()}",
                )
                stopOuterLeg()
                continue
            }
            val generation = sessionGeneration.get()
            val thread = Thread({
                try {
                    if (stopRequested.get() || generation != sessionGeneration.get()) return@Thread
                    val result = AetherNative.startProxy(config)
                    if (result != 0 && !stopRequested.get() && generation == sessionGeneration.get()) {
                        AppLogRepository.warning(
                            LogSource.ATOM,
                            "$label ended: ${AetherNative.lastError().ifBlank { "exit $result" }}",
                        )
                        qualityScope.launch {
                            onOuterLegDied(label)
                        }
                    }
                } catch (error: Throwable) {
                    AppLogRepository.warning(LogSource.ATOM, "$label threw: ${error.message}")
                }
            }, "atom-engine-outer-$protocol")
            thread.isDaemon = true
            outerThread = thread
            thread.start()
            if (awaitOuterProxy(budget, chainPort)) {
                store.saveOuterProtocol(protocol)
                lastOuterProtocol = protocol
                wonTransport = if (protocol == "masque") plan.masqueTransport else ""
                wonIp = AtomCoreConfig.ipScanFor(currentSettings, plan, protocol)
                outerCommitted = true
                AppLogRepository.success(LogSource.ATOM, "$label is carrying the outer leg")
                return label
            }
            if (stopRequested.get()) return null
            AppLogRepository.warning(LogSource.ATOM, "$label did not come up in ${budget / 1000}s")
            stopOuterLeg()
            if (protocol == "masque") {
                AppLogRepository.warning(
                    LogSource.ATOM,
                    "$label address scan finished. Staying on this method.",
                )
                AtomStatusStore.note("Scan finished. Staying on this method.", true)
                return null
            }
        }
        return null
    }

    private fun rememberConnectedMode() {
        val protocol = if (directPsiphon || lastOuterProtocol.isBlank()) "psiphon" else lastOuterProtocol
        AtomLiveExit.updatePath {
            it.copy(
                mode = when {
                    warpOnly -> AtomEngineSettings.EXIT_WARP
                    psiphonOnly -> AtomEngineSettings.EXIT_PSIPHON
                    directPsiphon -> "direct"
                    else -> AtomEngineSettings.EXIT_CHAIN
                },
                mtu = sessionMtu,
                outerProtocol = if (directPsiphon) "" else lastOuterProtocol,
                outerTransport = if (directPsiphon) "" else wonTransport,
            )
        }
        store.saveLastConnect(
            protocol = protocol,
            transport = if (protocol == "masque") wonTransport else "",
            ip = if (protocol == "psiphon") "" else wonIp,
            throughPsiphon = !warpOnly,
        )
    }

    private fun outerFailureMessage(): String =
        if (AtomCoreConfig.candidates(currentSettings).size > 1) {
            "No WARP transport could carry Psiphon on this network"
        } else {
            "${AtomCoreConfig.outerLabel(AtomCoreConfig.candidates(currentSettings).first())} could not carry Psiphon"
        }

    private suspend fun awaitOuterProxy(budgetMs: Long, chainPort: Int = AtomCoreConfig.CHAIN_SOCKS_PORT): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + budgetMs
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            if (stopRequested.get()) return false
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed > OUTER_START_GRACE_MS && !AetherNative.isRunning()) return false
            if (AetherNative.isReady() && outerProxyAccepts(chainPort)) return true
            delay(300)
        }
        return false
    }

    private fun outerProxyAccepts(port: Int = AtomCoreConfig.CHAIN_SOCKS_PORT): Boolean = runCatching {
        Socket().use { probe ->
            probe.connect(InetSocketAddress("127.0.0.1", port), 1_000)
        }
    }.isSuccess

    private suspend fun outerProxyResponsive(port: Int = AtomCoreConfig.CHAIN_SOCKS_PORT): Boolean {
        if (!outerProxyAccepts(port)) return false
        val sample = runCatching { AtomPathProbe.measureMsLite(port, 2_000) }.getOrDefault(-1L)
        return sample > 0L
    }

    private suspend fun stopOuterLeg() {
        outerCommitted = false
        AetherNative.abortPrepare()
        AetherNative.stop()
        val thread = outerThread
        val deadline = SystemClock.elapsedRealtime() + OUTER_STOP_GRACE_MS
        while (
            (AetherNative.isRunning() || thread?.isAlive == true) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            // The outer thread may not have entered the native core yet when the first
            // stop was sent (aether_stop ignores a core that is not RUNNING), so repeat it.
            AetherNative.stop()
            delay(200)
        }
        if (thread?.isAlive == true) {
            AppLogRepository.warning(LogSource.ATOM, "Outer leg thread still alive after stop grace; detaching it")
        }
        outerThread = null
    }

    private fun openDeviceTun(
        establishTun: (AtomTun2Socks.PrivateAddress, Int) -> ParcelFileDescriptor?,
    ) {
        if (tunFd != null) return
        AtomStatusStore.update(AtomPhase.BRIDGING, 88, "Creating device VPN interface")
        tunFd = establishTun(tunAddress, sessionMtu) ?: error("Android could not establish the VPN interface")
        ownsTun = true
    }

    private suspend fun finishWithoutPsiphon() {
        warpOnly = true
        directPsiphon = false
        val warpSocks = AtomCoreConfig.CHAIN_SOCKS_PORT
        if (proxyMode) {
            if (socksPort != warpSocks && !AtomSocksConnectOnly.start(socksPort, warpSocks)) {
                error("Could not expose the WARP proxy")
            }
        } else {
            val fd = tunFd ?: error("VPN interface missing")
            AtomStatusStore.update(AtomPhase.BRIDGING, 90, "Routing device traffic through WARP")
            if (!AtomTun2Socks.start(appContext, fd, warpSocks, sessionMtu, tunAddress)) {
                error("Could not start whole-device routing")
            }
            routing = true
            socksPort = warpSocks
        }
        AtomLiveExit.publish(socksPort, warp = true)
    }

    private suspend fun startPsiphonTunnel(keepExit: Boolean = false, socks: Int = socksPort) {
        val generation = sessionGeneration.get()
        val ladder = activeLadder()
        val strategy = ladder.getOrElse(ladderIndex) { ladder.first() }
        val bias = regionPhase && regionPhaseBias
        val preferred = when {
            bias -> AtomRegions.egressRegion(regionPhaseTried)
            regionPhase || keepExit -> AtomRegions.egressRegion(currentSettings.exitCountryCode)
            else -> null
        }
        val configJson = AtomPsiphonProtocols.buildConfig(
            socksPort = socks,
            dataDirectory = innerDataDirectory(),
            preferredRegion = preferred,
            strategy = strategy,
            optimizedMode = currentSettings.optimizedMode,
            direct = directPsiphon,
            regionBias = bias,
        )
        psiphonGeneration.set(generation)
        regionAttemptCode = if (bias) preferred.orEmpty() else ""
        regionAttemptStartedAtMs = if (regionAttemptCode.isNotBlank()) SystemClock.elapsedRealtime() else 0L
        connectedServerRegion = ""
        lastPsiphonConfig = configJson
        innerStarted = true
        AppLogRepository.info(LogSource.ATOM, "Starting Psiphon inner hop in :Atom")
        AppLogRepository.info(
            LogSource.ATOM,
            "Psiphon tunnels: " + if (sidecarWanted()) "2 (each service pinned to one tunnel)" else "1",
        )
        innerClient().start(configJson)
        val protocolList = runCatching {
            JSONObject(configJson).optJSONArray("LimitTunnelProtocols")?.toString()
        }.getOrNull().orEmpty()
        AppLogRepository.info(
            LogSource.ATOM,
            "Psiphon starting strategy ${strategy.name} (${strategy.label})" +
                (
                    when {
                        preferred == null -> " region=any"
                        bias -> " region=$preferred (nearby, ${strategy.timeoutSeconds}s then any)"
                        else -> " region=$preferred"
                    }
                    ) +
                (if (protocolList.isNotBlank()) " protocols=$protocolList" else ""),
        )
    }

    private fun innerDataDirectory(): String {
        val dir = appContext.getDir("atom-engine-inner", 0)
        return dir.absolutePath
    }

    private suspend fun watchLadder() {
        while (!stopRequested.get() && !innerReady) {
            ladderActive.set(true)
            val strategy = activeLadder().getOrElse(ladderIndex) { return }
            val nearby = regionPhase && regionPhaseBias
            val seconds = if (nearby) {
                strategy.timeoutSeconds
            } else if (regionPhase && AtomRegions.egressRegion(currentSettings.exitCountryCode) != null) {
                AtomPsiphonProtocols.REGION_PHASE_TIMEOUT_SECONDS
            } else if (directPsiphon && strategy.preferredProtocols.all { it.startsWith("FRONTED-") }) {
                90
            } else {
                strategy.timeoutSeconds
            }
            delay((seconds.toLong() + if (nearby) 3L else 8L) * 1_000L)
            if (stopRequested.get() || innerReady || !ladderActive.get()) return
            escalateLadder()
        }
    }

    private suspend fun escalateLadder() {
        if (stopRequested.get() || innerReady) return
        if (!ladderActive.compareAndSet(true, false)) return
        val ladder = activeLadder()
        if (regionPhase) {
            recordRegionAttempt(success = false)
            if (regionPhaseBias && regionCandidateIndex + 1 < regionCandidates.size) {
                regionCandidateIndex += 1
                regionPhaseTried = regionCandidates[regionCandidateIndex]
                val nextRegion = regionPhaseTried
                AppLogRepository.info(
                    LogSource.ATOM,
                    "Nearby country failed; trying next candidate ${AtomRegions.name(nextRegion)} ($nextRegion) " +
                        "(${regionCandidateIndex + 1}/${regionCandidates.size})",
                )
                restartPsiphonController()
                return
            }
            val nearby = regionPhaseBias
            regionPhase = false
            regionPhaseBias = false
            regionCandidates = emptyList()
            regionCandidateIndex = 0
            AppLogRepository.info(
                LogSource.ATOM,
                (if (nearby) "Nearby country candidates exhausted" else "Preferred country ") +
                    (if (nearby) "" else "${AtomRegions.name(regionPhaseTried)} did not connect — ") +
                    "trying all countries",
            )
            restartPsiphonController()
            return
        }
        ladderAttempts += 1
        if (ladderAttempts >= ladder.size) {
            val error = IllegalStateException("Could not connect on this carrier. Try Wi-Fi or another SIM.")
            connectedSignal?.completeExceptionally(error)
            retuneSignal?.completeExceptionally(error)
            return
        }
        ladderIndex = (ladderIndex + 1) % ladder.size
        val next = ladder[ladderIndex]
        AppLogRepository.info(LogSource.ATOM, "Trying strategy ${next.name} (${next.label})")
        if (!retuning.get()) {
            AtomStatusStore.update(AtomPhase.INNER, 60, "Trying ${next.label}")
        }
        restartPsiphonController()
    }

    private suspend fun restartPsiphonController() {
        // onConnected may race a ladder escalate: never tear down a tunnel that
        // already published ready / is serving traffic.
        if (innerReady || stopRequested.get()) {
            AppLogRepository.debug(LogSource.ATOM, "Psiphon restart skipped (ready=$innerReady)")
            return
        }
        innerStarted = false
        withContext(Dispatchers.IO) {
            runCatching { innerClient().stop() }
        }
        delay(1_200)
        if (stopRequested.get()) return
        startPsiphonTunnel(
            keepExit = retuning.get() && AtomRegions.egressRegion(currentSettings.exitCountryCode) != null,
        )
    }

    /** Name warm-up for TUN mode only: proxy clients do their own lookups. */
    private fun kickDnsWarm(force: Boolean) {
        if (proxyMode || stopRequested.get()) return
        AtomDnsWarm.kick(
            qualityScope,
            appContext,
            if (warpOnly) AtomCoreConfig.CHAIN_SOCKS_PORT else socksPort,
            optimized = currentSettings.optimizedMode,
            exitLabel = if (warpOnly) "WARP" else "Psiphon",
            powerSave = isPowerSave(),
            force = force,
        )
    }

    private fun winnerIndex(): Int {
        val protocol = activeTunnelProtocol
        val ladder = activeLadder()
        val matches = ladder.indices.filter { index ->
            ladder[index].preferredProtocols.contains(protocol)
        }
        return when {
            matches.size == 1 -> matches[0]
            matches.contains(ladderIndex) -> ladderIndex
            matches.isNotEmpty() -> matches[0]
            else -> ladderIndex
        }
    }

    private fun recordLadderWinner() {
        val winner = winnerIndex()
        val shape = AtomPsiphonProtocols.signature(directPsiphon)
        val rtt = baselineRttMs
        if (rtt > 0) store.saveStrategyWinnerWithRtt(winner, shape, rtt)
        else store.saveStrategyWinner(winner, shape)
    }

    private fun activeLadder(): List<AtomPsiphonStrategy> = AtomPsiphonProtocols.ladder(directPsiphon)

    // RTTs are kept per leg: "c" behind WARP, "d" direct.
    private fun rttKey(strategy: String): String = (if (directPsiphon) "d" else "c") + strategy

    /** Remembered winner for this network, or the lower-RTT strategy when both were measured. */
    private fun rememberedLadderStart(): Int {
        val ladder = activeLadder()
        val remembered = store.rememberedStrategyIndex(AtomPsiphonProtocols.signature(directPsiphon), ladder.size)
        val saved = store.strategyRtts(ladder.map { rttKey(it.name) })
        val rtts = ladder.mapNotNull { strategy -> saved[rttKey(strategy.name)]?.let { strategy.name to it } }.toMap()
        return AtomPsiphonProtocols.startIndex(ladder, remembered, rtts)
    }

    private fun recordStrategyRtt(rttMs: Long) {
        if (rttMs <= 0L || warpOnly || !innerReady) return
        val strategy = activeLadder().getOrNull(winnerIndex()) ?: return
        store.saveStrategyRtt(rttKey(strategy.name), rttMs)
    }

    /**
     * Region for the next Psiphon start. A country the user picked keeps the
     * old strict phase (25s, then any country). Automatic behind WARP gets a
     * short head start in a nearby country: the one that worked on this
     * network, else the nearest Psiphon advertises. A retune only reuses a
     * remembered country. Direct Psiphon is left unbiased.
     */
    private fun armRegionPhase(retune: Boolean = false) {
        val explicit = AtomRegions.egressRegion(currentSettings.exitCountryCode)
        regionCandidateIndex = 0
        if (explicit != null) {
            regionCandidates = emptyList()
            regionPhase = !retune
            regionPhaseBias = false
            regionPhaseTried = currentSettings.exitCountryCode
            return
        }
        regionCandidates = if (directPsiphon) {
            emptyList()
        } else {
            AtomRegions.autoBiasRegions(
                store.rememberedRegion(),
                store.availableRegions(),
                rememberedOnly = retune,
                performance = store.regionStats(),
            )
        }
        regionPhase = regionCandidates.isNotEmpty()
        regionPhaseBias = regionPhase
        regionPhaseTried = regionCandidates.firstOrNull().orEmpty()
    }

    private fun recordRegionAttempt(success: Boolean) {
        val attempted = regionAttemptCode
        if (attempted.isBlank()) return
        val elapsed = (SystemClock.elapsedRealtime() - regionAttemptStartedAtMs).coerceAtLeast(1L)
        val actualMatches = connectedServerRegion.isBlank() ||
            connectedServerRegion.equals(attempted, ignoreCase = true)
        val recordedSuccess = success && actualMatches
        store.recordRegionResult(attempted, recordedSuccess, elapsed)
        AppLogRepository.info(
            LogSource.ATOM,
            "Automatic exit score: $attempted " +
                (if (recordedSuccess) "connected in ${elapsed}ms" else "attempt failed after ${elapsed}ms"),
        )
        regionAttemptCode = ""
        regionAttemptStartedAtMs = 0L
        connectedServerRegion = ""
    }

    private fun logPsiphonPlan() {
        val ladder = activeLadder()
        val start = ladder.getOrNull(ladderIndex)?.name ?: "?"
        AppLogRepository.info(
            LogSource.ATOM,
            "Psiphon strategy order (" + (if (directPsiphon) "direct" else "behind WARP") + "): " +
                ladder.joinToString(" -> ") { "${it.name} ${it.label} ${it.timeoutSeconds}s" } +
                "; starting at $start",
        )
        AppLogRepository.info(
            LogSource.ATOM,
            when {
                directPsiphon && proxyMode -> "Split: Psiphon sends IR destinations direct (SplitTunnelRegions=IR)"
                directPsiphon -> "Split: Psiphon sends IR destinations direct; .ir names go direct from the TUN relay"
                proxyMode -> "Split: off in proxy mode behind WARP (Psiphon own-region split would exit via WARP)"
                else -> "Split: .ir names go direct over the phone network; Psiphon region split stays off behind WARP"
            },
        )
        AppLogRepository.info(
            LogSource.ATOM,
            when {
                regionPhase && regionPhaseBias -> {
                    val remembered = store.rememberedRegion()?.equals(regionPhaseTried, ignoreCase = true) == true
                    "Psiphon region: Automatic -> nearby ${AtomRegions.name(regionPhaseTried)} ($regionPhaseTried" +
                        (if (remembered) ", remembered" else "") +
                        "; candidate ${regionCandidateIndex + 1}/${regionCandidates.size}) for " +
                        "${ladder.getOrNull(ladderIndex)?.timeoutSeconds ?: AtomPsiphonProtocols.CHAINED_LIGHT_TIMEOUT_SECONDS}s each, then any country"
                }
                regionPhase -> "Psiphon region: user choice ${AtomRegions.name(regionPhaseTried)}"
                else -> "Psiphon region: any country"
            },
        )
    }

    private suspend fun stopLocked() {
        stopRequested.set(true)
        cancelZeroTunnelTimeout()
        psiphonTunnelCount.set(0)
        cancelQualityWork()
        AtomPageTurbo.cancel()
        AtomDnsWarm.cancel()
        AtomLiveExit.reset()
        runCatching { AtomDnsWarm.persist(appContext) }
        unregisterNetworkCallback()
        ghostInFlight.set(false)
        ladderActive.set(false)
        ladderJob?.cancel()
        ladderJob = null
        connectedSignal?.cancel()
        connectedSignal = null
        innerReady = false
        setRetuning(false)
        innerCallbacksArmed.set(true)
        AtomSocksConnectOnly.setFailFast(false)
        withContext(Dispatchers.IO + NonCancellable) {
            runCatching { sidecar.stop() }
            ghostServing = false
            runCatching { ghostInner.release("session stopped") }
            val client = psiphon
            if (innerStarted || client != null) {
                runCatching { client?.stop() }
            }
            innerStarted = false
            client?.close()
            psiphon = null
            AtomTun2Socks.stop()
            AtomTun2Socks.awaitNativeExit()
            TunPacketFilter.stop()
            AtomSocksConnectOnly.stop()
            stopOuterLeg()
            AetherNative.detach()
        }
        if (ownsTun) {
            runCatching { tunFd?.close() }
        }
        tunFd = null
        ownsTun = false
        routing = false
        regionPhase = AtomRegions.egressRegion(store.snapshot().exitCountryCode) != null
        regionPhaseBias = false
        regionPhaseTried = currentSettings.exitCountryCode
        ladderIndex = rememberedLadderStart()
        ladderAttempts = 0
        recentSamples = mutableListOf()
        if (AtomStatusStore.status.value.phase != AtomPhase.FAILED) {
            AtomStatusStore.reset()
        }
    }

    private fun sidecarWanted(): Boolean =
        currentSettings.psiphonTunnelPool && !proxyMode && !warpOnly && !stopRequested.get()

    /** Tunnel 2 follows tunnel 1: started (or re-routed) each time tunnel 1 connects. */
    private fun maybeStartSidecar() {
        if (!sidecarWanted()) return
        val config = lastPsiphonConfig.ifBlank { return }
        sidecar.ensure(config, directPsiphon)
    }

    private fun innerClient(): AtomPsiphonClient {
        return psiphon ?: AtomPsiphonClient(service, callbacks).also { psiphon = it }
    }

    private fun scheduleZeroTunnelTimeout(reason: String) {
        if (zeroTunnelJob?.isActive == true) return
        zeroTunnelJob = qualityScope.launch {
            delay(10_000L)
            if (stopRequested.get() || retuning.get() || !innerReady || restoreWaiting.get()) return@launch
            if (psiphonTunnelCount.get() <= 0) {
                AppLogRepository.warning(
                    LogSource.ATOM,
                    "Psiphon tunnel dropped and failed to recover in 10s ($reason); recovering path",
                )
                notifyPathLost("psiphon tunnel dropped ($reason)")
            }
        }
    }

    private fun cancelZeroTunnelTimeout() {
        zeroTunnelJob?.cancel()
        zeroTunnelJob = null
    }

    private inner class PsiphonCallbacks : AtomPsiphonClient.Callbacks {
        override fun onConnecting() {
            if (psiphonGeneration.get() != sessionGeneration.get()) return
            if (!innerCallbacksArmed.get()) return
            AppLogRepository.info(LogSource.ATOM, "Psiphon connecting")
            if (innerReady && !retuning.get() && !stopRequested.get() && !restoreWaiting.get()) {
                scheduleZeroTunnelTimeout("psiphon reconnecting")
            }
        }

        override fun onConnected() {
            if (psiphonGeneration.get() != sessionGeneration.get()) return
            if (!innerCallbacksArmed.get()) return
            cancelZeroTunnelTimeout()
            psiphonTunnelCount.set(1)
            ladderActive.set(false)
            ladderJob?.cancel()
            if (regionPhase) {
                recordRegionAttempt(success = true)
                regionPhase = false
                AppLogRepository.info(
                    LogSource.ATOM,
                    (if (regionPhaseBias) "Connected in nearby country " else "Connected in preferred country ") +
                        AtomRegions.name(regionPhaseTried),
                )
            }
            innerReady = true
            connectedSignal?.complete(Unit)
            retuneSignal?.complete(Unit)
            if (ghostServing) qualityScope.launch { finishGhost() }
            AppLogRepository.success(
                LogSource.ATOM,
                if (directPsiphon) "Psiphon connected" else "Psiphon connected through WARP",
            )
            maybeStartSidecar()
        }

        override fun onExiting() {
            if (psiphonGeneration.get() != sessionGeneration.get()) return
            cancelZeroTunnelTimeout()
            psiphonTunnelCount.set(0)
            if (stopRequested.get() || retuning.get() || !innerCallbacksArmed.get()) return
            if (!innerReady && ladderActive.get()) {
                AppLogRepository.warning(LogSource.ATOM, "Psiphon exited before connect; trying the next strategy")
            }
            if (innerReady) {
                AppLogRepository.warning(LogSource.ATOM, "Psiphon tunnel stopped; waiting to reconnect")
                notifyPathLost("psiphon exited")
            }
        }

        override fun onListeningSocksProxyPort(port: Int) {
            if (psiphonGeneration.get() != sessionGeneration.get()) return
            socksPort = port
            // While the ghost carries traffic, tunnel 1 must not take the relay back before it connects.
            if (!ghostServing) AtomSocksConnectOnly.setUpstreamPort(port)
            AppLogRepository.info(LogSource.ATOM, "Psiphon SOCKS listening on $port")
        }

        override fun onDiagnosticMessage(message: String) {
            val text = message.ifBlank { return }
            if (text.startsWith("Tunnels:")) {
                runCatching {
                    val count = JSONObject(text.substringAfter("Tunnels:").trim()).optInt("count", 0)
                    psiphonTunnelCount.set(count)
                    if (count > 0) {
                        cancelZeroTunnelTimeout()
                    } else if (innerReady && !retuning.get() && !stopRequested.get() && !restoreWaiting.get()) {
                        scheduleZeroTunnelTimeout("0 active tunnels")
                    }
                }
            }
            if (text.startsWith("ActiveTunnel:")) {
                runCatching {
                    activeTunnelProtocol = JSONObject(text.substringAfter("ActiveTunnel:").trim())
                        .optString("protocol")
                    AtomLiveExit.updatePath { it.copy(psiphonProtocol = activeTunnelProtocol) }
                }
            }
            AppLogRepository.info(LogSource.ATOM, text.take(300))
            if (!text.contains("updated server")) {
                runCatching {
                    java.io.File(appContext.filesDir, "atom-diag.log").appendText(text.take(500) + "\n")
                }
            }
        }

        override fun onBytesTransferred(sent: Long, received: Long) {
            if (sent > 0) {
                txBytes.addAndGet(sent)
                txPackets.incrementAndGet()
            }
            if (received > 0) {
                rxBytes.addAndGet(received)
                rxPackets.incrementAndGet()
            }
        }

        override fun onClientRegion(region: String) {
            AppLogRepository.debug(LogSource.ATOM, "Psiphon client region $region")
        }

        override fun onConnectedServerRegion(region: String) {
            connectedServerRegion = region.trim().uppercase()
            AtomStatusStore.update(
                AtomStatusStore.status.value.phase,
                AtomStatusStore.status.value.progressPercent,
                AtomStatusStore.status.value.detail,
                exitRegion = region.uppercase(),
            )
            AppLogRepository.info(LogSource.ATOM, "Exit country ${AtomRegions.name(region)} ($region)")
            recordLadderWinner()
            val code = region.trim().uppercase()
            if (!directPsiphon &&
                AtomRegions.egressRegion(currentSettings.exitCountryCode) == null &&
                code in AtomRegions.NEARBY &&
                store.rememberedRegion() != code
            ) {
                store.saveRegion(code)
                AppLogRepository.info(
                    LogSource.ATOM,
                    "Remembered exit ${AtomRegions.name(code)} ($code) for this network; next Automatic connect tries it first",
                )
            }
        }

        override fun onAvailableEgressRegions(regions: List<String>) {
            if (regions.isEmpty()) return
            AtomRegions.remember(appContext, regions)
        }

        override fun onClientAddress(address: String) {
            AppLogRepository.info(LogSource.ATOM, "Psiphon exit IP $address")
        }

        override fun onStartFailed(message: String) {
            if (psiphonGeneration.get() != sessionGeneration.get()) return
            if (!innerCallbacksArmed.get()) return
            connectedSignal?.completeExceptionally(IllegalStateException(message))
            retuneSignal?.completeExceptionally(IllegalStateException(message))
        }
    }

    init {
        regionPhase = AtomRegions.egressRegion(store.snapshot().exitCountryCode) != null
        regionPhaseTried = store.snapshot().exitCountryCode
        ladderIndex = rememberedLadderStart()
    }

    private fun underlyingLinkMtu(): Int = runCatching {
        val connectivity = service.getSystemService(ConnectivityManager::class.java) ?: return 0
        val network = connectivity.activeNetwork ?: return 0
        connectivity.getLinkProperties(network)?.mtu ?: 0
    }.getOrDefault(0)

    private fun isScreenOn(): Boolean = runCatching {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isInteractive ?: true
    }.getOrDefault(true)

    private fun isPowerSave(): Boolean = runCatching {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode ?: false
    }.getOrDefault(false)

    private fun processesAlive(): Boolean {
        if (stopRequested.get() || !innerReady) return false
        if (!proxyMode && !AtomTun2Socks.isRunning) return false
        if (directPsiphon) return true
        return AetherNative.isRunning()
    }

    private fun resetWatchdogBaseline() {
        val stats = tunStats()
        watchdogTx = stats.txBytes
        watchdogRx = stats.rxBytes
        watchdogProcessMisses = 0
        watchdogStallStrikes = 0
    }

    private fun startSessionWatch() {
        // Push-based and free: keep the network callback for the whole session so a
        // Wi-Fi/cell handoff is verified in seconds instead of waiting on the poll.
        registerNetworkCallback()
        if (currentSettings.optimizedMode) {
            startWatchdog()
            startPassiveWatch()
        } else {
            startQualityWatch()
        }
    }

    private fun startWatchdog() {
        qualityJob?.cancel()
        qualityJob = null
        // Liveness poll reads local byte counters only — never touches the radio.
        watchdogJob?.cancel()
        watchdogTx = -1L
        watchdogRx = -1L
        watchdogProcessMisses = 0
        watchdogStallStrikes = 0
        watchdogJob = qualityScope.launch {
            try {
                watchLiveness()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogRepository.debug(LogSource.ATOM, "Watchdog ended: ${error.message}")
            }
        }
    }

    private fun startQualityWatch() {
        watchdogJob?.cancel()
        watchdogJob = null
        qualityJob?.cancel()
        qualityJob = qualityScope.launch {
            try {
                watchQuality()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogRepository.debug(LogSource.ATOM, "Quality watch ended: ${error.message}")
            }
        }
    }

    private fun cancelQualityWork() {
        passiveJob?.cancel()
        passiveJob = null
        qualityJob?.cancel()
        qualityJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        retuneSignal?.cancel()
        retuneSignal = null
    }
    /**
     * Battery-friendly quality watch for optimized mode.
     *
     * The RTT signal comes from the SOCKS relay itself, which already sits in the data
     * path of every user connection — timing connect to first byte costs zero extra
     * radio energy. An active probe is fired only when the relay has been quiet for a
     * while, and it is the cheap single-target header-only variant.
     */
    private fun startPassiveWatch() {
        passiveJob?.cancel()
        passiveJob = qualityScope.launch {
            try {
                watchPassiveQuality()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogRepository.debug(LogSource.ATOM, "Passive watch ended: ${error.message}")
            }
        }
    }

    private suspend fun watchPassiveQuality() {
        delay(AtomQualityPolicy.SETTLE_MS)
        if (stopRequested.get() || !innerReady) return
        var badStreak = 0
        var busyLogged = false
        var busySkips = 0
        var lastBytes = tunByteTotal()
        var lastBytesAt = SystemClock.elapsedRealtime()
        while (!stopRequested.get()) {
            val screenOn = isScreenOn()
            val idle = AtomSocksConnectOnly.msSinceLastActivity() > AtomQualityPolicy.IDLE_PROBE_AFTER_MS
            delay(
                if (screenOn) AtomQualityPolicy.PASSIVE_INTERVAL_MS
                else AtomQualityPolicy.PASSIVE_INTERVAL_IDLE_MS,
            )
            val intervalBytes = tunByteTotal()
            val intervalAt = SystemClock.elapsedRealtime()
            val intervalRate = AtomQualityPolicy.bytesPerSecond(intervalBytes - lastBytes, intervalAt - lastBytesAt)
            lastBytes = intervalBytes
            lastBytesAt = intervalAt
            if (stopRequested.get() || retuning.get() || !innerReady) continue
            // Screen off: liveness only. No probes, the radio stays asleep.
            if (!screenOn) continue

            // Busy gate: the interval average plus a short look at right now.
            delay(AtomQualityPolicy.BUSY_SPOT_WINDOW_MS)
            val spotBytes = tunByteTotal()
            val spotAt = SystemClock.elapsedRealtime()
            val spotRate = AtomQualityPolicy.bytesPerSecond(spotBytes - lastBytes, spotAt - lastBytesAt)
            lastBytes = spotBytes
            lastBytesAt = spotAt
            if (stopRequested.get() || retuning.get() || !innerReady) continue
            val rate = maxOf(intervalRate, spotRate)
            val streams = AtomSocksConnectOnly.activeStreamCount()
            if (AtomQualityPolicy.isBusy(rate, streams)) {
                badStreak = 0
                busySkips += 1
                AtomSocksConnectOnly.discardPassiveSamples()
                val message = "Quality check skipped: busy (${rate / 1024L} KB/s, $streams streams)"
                if (!busyLogged) {
                    busyLogged = true
                    AppLogRepository.info(LogSource.ATOM, message)
                } else {
                    AppLogRepository.debug(LogSource.ATOM, message)
                }
                continue
            }
            if (busyLogged) {
                AppLogRepository.info(
                    LogSource.ATOM,
                    "Quality check resumed after $busySkips busy skip(s) (${rate / 1024L} KB/s, $streams streams)",
                )
                busyLogged = false
                busySkips = 0
            }

            val samples = AtomSocksConnectOnly.recentLatencies()
            val sample = if (samples.isEmpty() || idle) {
                measureQualityProbeLite()
            } else {
                AtomQualityPolicy.p25(samples)
            }
            val jitterHigh = AtomQualityPolicy.isJitterHigh(samples + sample)
            AtomAdaptiveObfuscation.onProbe(sample > 0L, jitterHigh)
            if (jitterHigh && sample > 0L) {
                AtomAdaptiveObfuscation.shouldEnableFragmentation(appContext, true)
            } else {
                AtomAdaptiveObfuscation.shouldDisableFragmentation(
                    appContext,
                    SystemClock.elapsedRealtime() - lastStableAt,
                )
            }

            when {
                sample <= 0L -> badStreak += 1
                baselineRttMs <= 0L -> {
                    baselineRttMs = sample
                    badStreak = 0
                    lastStableAt = SystemClock.elapsedRealtime()
                }
                AtomQualityPolicy.isDegraded(baselineRttMs, sample) -> badStreak += 1
                else -> {
                    badStreak = 0
                    lastStableAt = SystemClock.elapsedRealtime()
                    if (sample < baselineRttMs) {
                        baselineRttMs = (baselineRttMs * 3L + sample) / 4L
                    }
                }
            }

            if (badStreak < AtomQualityPolicy.PASSIVE_BAD_STREAK) continue
            val elapsed = SystemClock.elapsedRealtime() - lastRetuneAt
            if (lastRetuneAt > 0L && elapsed < AtomQualityPolicy.RETUNE_COOLDOWN_MS) continue
            if (qualityRetunes.get() >= AtomQualityPolicy.PASSIVE_MAX_RETUNES) continue
            badStreak = 0
            requestQualityRetune(
                "passive rtt p25 ${sample}ms vs ${baselineRttMs}ms, ${AtomQualityPolicy.PASSIVE_BAD_STREAK} strikes",
            )
        }
    }

    private suspend fun measureQualityProbeLite(): Long {
        AtomUiProbeGate.beginEngineProbe()
        return try {
            AtomPathProbe.measureMsLite(socksPort)
        } finally {
            AtomUiProbeGate.endEngineProbe()
        }
    }

    /** True while Engine-3 is supposed to be serving traffic (not paused / idle). */
    private fun sessionSupposedUp(): Boolean {
        if (stopRequested.get() || VpnPauseStore.isPaused()) return false
        if (restoreWaiting.get()) return false
        return innerReady || isRunning()
    }

    private fun scheduleSimSwitchReset(reason: String) {
        if (stopRequested.get() || VpnPauseStore.isPaused()) return
        if (!sessionSupposedUp() && !restoreWaiting.get()) return
        simSwitchJob?.cancel()
        simSwitchJob = qualityScope.launch {
            delay(AtomNetworkRestore.SIM_SWITCH_DEBOUNCE_MS)
            if (stopRequested.get() || VpnPauseStore.isPaused()) return@launch
            runSimSwitchReset(reason)
        }
    }

    /**
     * Irancell ↔ MCI (or any default-data SIM change): ordered rebuild —
     * stop Psiphon → tear MASQUE → re-attach/protect on NEW radio → raise
     * outer successfully → only then start Psiphon. Never leave Psiphon
     * dialing a dead upstream SOCKS.
     */
    private suspend fun runSimSwitchReset(reason: String) {
        if (!simSwitchInFlight.compareAndSet(false, true)) return
        try {
            if (stopRequested.get() || VpnPauseStore.isPaused()) return

            val previous = lastRadioIdentity
            val current = AtomCarrier.radioIdentity(appContext)
            if (previous == null) {
                lastRadioIdentity = current
                AppLogRepository.info(LogSource.ATOM, "SIM reset skipped - no radio baseline yet")
                return
            }
            if (!current.isSimOrOperatorChange(previous)) {
                AppLogRepository.info(
                    LogSource.ATOM,
                    "SIM reset skipped - same radio ${current.summary()}",
                )
                return
            }

            AppLogRepository.warning(
                LogSource.ATOM,
                "SIM/operator switch - ordered outer→Psiphon reset ($reason) " +
                    "${previous.summary()} → ${current.summary()}",
            )
            publishSimSwitchReconnecting()

            val carrierFlip = current.isConfirmedCarrierFlip(previous)

            AtomSocksConnectOnly.setFailFast(true)
            AtomSocksConnectOnly.dropRelays()
            dropGhost("sim switch")
            innerReady = false
            cancelQualityWork()

            // 1) Stop Psiphon first so it cannot hammer a dying outer SOCKS.
            innerCallbacksArmed.set(false)
            innerStarted = false
            withContext(Dispatchers.IO) {
                runCatching { innerClient().stop() }
            }
            // 2) Tear outer MASQUE/WARP.
            stopOuterLeg()

            val forgotten = AtomCoreConfig.forgetPathMemory(appContext)
            var keyDropped = false
            if (carrierFlip) {
                keyDropped = AtomIdentityStatus.forget(appContext)
            }
            AppLogRepository.info(
                LogSource.ATOM,
                "SIM switch cleared path cache=$forgotten identityDropped=$keyDropped " +
                    "(carrierFlip=$carrierFlip)",
            )

            // 3) Re-bind VpnService.protect to the NEW active network.
            runCatching { AetherNative.attach(service) }
            delay(500)
            if (stopRequested.get() || VpnPauseStore.isPaused()) return

            // 4) Full rebuild: raise outer, then Psiphon (retuneLocked order).
            val recovered = try {
                retuneMutex.withLock {
                    if (stopRequested.get()) return@withLock false
                    retuneLocked("sim-switch:$reason", quality = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogRepository.warning(LogSource.ATOM, "SIM switch rebuild failed: ${error.message}")
                false
            } finally {
                AtomSocksConnectOnly.setFailFast(false)
            }

            if (stopRequested.get() || VpnPauseStore.isPaused()) return
            if (recovered && outerProxyAccepts()) {
                val check = measureQualityProbeLite()
                if (check > 0L) {
                    lastRadioIdentity = AtomCarrier.radioIdentity(appContext)
                    publishConnected()
                    AppLogRepository.success(LogSource.ATOM, "SIM switch reconnect ok (${check}ms)")
                    return
                }
            }

            if (!carrierFlip && !stopRequested.get() && !VpnPauseStore.isPaused()) {
                AppLogRepository.info(LogSource.ATOM, "SIM switch retry with fresh identity/key")
                AtomIdentityStatus.forget(appContext)
                AtomCoreConfig.forgetPathMemory(appContext)
                runCatching { AetherNative.attach(service) }
                val retry = try {
                    retuneMutex.withLock {
                        if (stopRequested.get()) return@withLock false
                        retuneLocked("sim-switch-retry:$reason", quality = false)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    false
                }
                if (retry && outerProxyAccepts()) {
                    val check = measureQualityProbeLite()
                    if (check > 0L) {
                        lastRadioIdentity = AtomCarrier.radioIdentity(appContext)
                        publishConnected()
                        AppLogRepository.success(LogSource.ATOM, "SIM switch retry ok (${check}ms)")
                        return
                    }
                }
            }

            beginNetworkRestore("sim switch reconnect failed")
        } finally {
            simSwitchInFlight.set(false)
            AtomSocksConnectOnly.setFailFast(false)
        }
    }

    /**
     * MASQUE/WARP died while the session should still be up. Cancel Psiphon
     * establish against the dead SOCKS and rebuild outer first, then Psiphon.
     */
    private suspend fun onOuterLegDied(label: String) {
        if (stopRequested.get() || VpnPauseStore.isPaused()) return
        if (retuning.get() || simSwitchInFlight.get() || restoreWaiting.get()) return
        if (!sessionSupposedUp() && !innerReady) return
        AppLogRepository.warning(
            LogSource.ATOM,
            "$label died mid-session - stopping Psiphon and rebuilding from outer",
        )
        publishReconnecting("$label ended")
        AtomSocksConnectOnly.setFailFast(true)
        AtomSocksConnectOnly.dropRelays()
        innerReady = false
        cancelQualityWork()
        innerCallbacksArmed.set(false)
        withContext(Dispatchers.IO) {
            runCatching { innerClient().stop() }
        }
        stopOuterLeg()
        runCatching { AetherNative.attach(service) }
        delay(500)
        if (stopRequested.get() || VpnPauseStore.isPaused()) return
        val ok = try {
            retuneMutex.withLock {
                if (stopRequested.get()) return@withLock false
                retuneLocked("outer-died:$label", quality = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLogRepository.warning(LogSource.ATOM, "Outer-died rebuild failed: ${error.message}")
            false
        } finally {
            AtomSocksConnectOnly.setFailFast(false)
        }
        if (stopRequested.get() || VpnPauseStore.isPaused()) return
        if (ok && outerProxyAccepts()) {
            publishConnected()
        } else {
            beginNetworkRestore("$label ended")
        }
    }

    private fun publishSimSwitchReconnecting() {
        if (stopRequested.get()) return
        ConnectionStateStore.markConnecting()
        AtomStatusStore.update(
            AtomPhase.STARTING,
            20,
            AtomNetworkRestore.simSwitchDetail(),
        )
        notificationSink?.invoke(false)
    }

    private fun cancelSimSwitchWork() {
        simSwitchJob?.cancel()
        simSwitchJob = null
        simSwitchInFlight.set(false)
    }

    /**
     * onLost is not proof the tunnel died — the default network may simply have
     * changed. Probe once; only enter the restore waiter if the path is dead.
     */
        private suspend fun verifyPathAfterNetworkChange() {
        if (SystemClock.elapsedRealtime() < connectGraceUntilMs) {
            AppLogRepository.debug(LogSource.ATOM, "Network change ignored during connect grace")
            return
        }
        if (!isHealthyPath()) {
            beginNetworkRestore("network changed")
            return
        }
        val sample = measureQualityProbeLite()
        if (sample <= 0L && !retuning.get() && !stopRequested.get() && !VpnPauseStore.isPaused()) {
            beginNetworkRestore("network lost")
        }
    }


    private suspend fun watchLiveness() {
        delay(AtomWatchdog.SETTLE_MS)
        if (stopRequested.get() || !innerReady) return
        while (!stopRequested.get()) {
            delay(AtomWatchdog.intervalMs(isScreenOn(), AtomSocksConnectOnly.activeStreamCount()))
            if (stopRequested.get() || retuning.get() || !innerReady) continue
            val stats = tunStats()
            when (
                val verdict = AtomWatchdog.judge(
                    processesAlive = processesAlive(),
                    txBytes = stats.txBytes,
                    rxBytes = stats.rxBytes,
                    previousTx = watchdogTx,
                    previousRx = watchdogRx,
                    processMisses = watchdogProcessMisses,
                    stallStrikes = watchdogStallStrikes,
                )
            ) {
                is AtomWatchdog.Verdict.Healthy -> {
                    watchdogProcessMisses = 0
                    watchdogStallStrikes = 0
                    watchdogTx = stats.txBytes
                    watchdogRx = stats.rxBytes
                }
                is AtomWatchdog.Verdict.Strike -> {
                    if (verdict.reason == "engine process exited") {
                        watchdogProcessMisses = verdict.nextStrikes
                    } else {
                        watchdogProcessMisses = 0
                        watchdogStallStrikes = verdict.nextStrikes
                    }
                    watchdogTx = stats.txBytes
                    watchdogRx = stats.rxBytes
                    AppLogRepository.debug(
                        LogSource.ATOM,
                        "Watchdog strike ${verdict.nextStrikes}: ${verdict.reason}",
                    )
                }
                is AtomWatchdog.Verdict.Dead -> {
                    watchdogProcessMisses = 0
                    watchdogStallStrikes = 0
                    watchdogTx = stats.txBytes
                    watchdogRx = stats.rxBytes
                    AppLogRepository.warning(LogSource.ATOM, "Watchdog: ${verdict.reason}")
                    notifyPathLost(verdict.reason)
                }
            }
        }
    }

    private suspend fun measureQualityProbe(): Long {
        AtomUiProbeGate.beginEngineProbe()
        return try {
            AtomPathProbe.measureMs(socksPort)
        } finally {
            AtomUiProbeGate.endEngineProbe()
        }
    }

    private fun setRetuning(active: Boolean) {
        retuning.set(active)
        AtomUiProbeGate.setRetuning(active)
        AtomLiveExit.updatePath { it.copy(retuning = active) }
    }

    private suspend fun watchQuality() {
        delay(AtomQualityPolicy.SETTLE_MS)
        if (stopRequested.get() || !innerReady) return
        val opening = mutableListOf<Long>()
        repeat(AtomQualityPolicy.BASELINE_SAMPLES) { index ->
            while (retuning.get() && !stopRequested.get()) delay(350)
            if (stopRequested.get() || !innerReady) return
            val sample = measureQualityProbe()
            if (sample > 0L) opening += sample
            AtomAdaptiveObfuscation.onProbe(sample > 0, false)
            if (index + 1 < AtomQualityPolicy.BASELINE_SAMPLES) delay(550)
        }
        baselineRttMs = AtomQualityPolicy.median(opening)
        if (baselineRttMs > 0L) {
            AppLogRepository.info(LogSource.ATOM, "Atom path baseline ${baselineRttMs}ms")
            recordStrategyRtt(baselineRttMs)
        }
        recentSamples = opening.takeLast(3).toMutableList()
        lastStableAt = SystemClock.elapsedRealtime()
        var badStreak = 0
        while (!stopRequested.get()) {
            val interval = AtomQualityPolicy.adaptiveIntervalMs(isScreenOn(), isPowerSave())
            delay(interval)
            if (stopRequested.get() || retuning.get()) continue
            if (!innerReady) continue
            // Adaptive obfuscation check
            val elapsedStable = SystemClock.elapsedRealtime() - lastStableAt
            AtomAdaptiveObfuscation.shouldDisableFragmentation(appContext, elapsedStable)
            val sample = measureQualityProbe()
            val jitterHigh = AtomQualityPolicy.isJitterHigh(recentSamples + sample)
            AtomAdaptiveObfuscation.onProbe(sample > 0L, jitterHigh)
            if (jitterHigh && sample > 0L) {
                AtomAdaptiveObfuscation.shouldEnableFragmentation(appContext, true)
            }
            recentSamples.add(sample)
            if (recentSamples.size > 4) recentSamples.removeAt(0)
            val degraded = if (baselineRttMs > 0L) {
                AtomQualityPolicy.isDegraded(baselineRttMs, sample)
            } else {
                sample <= 0L
            }
            if (!degraded && sample > 0L) {
                badStreak = 0
                lastStableAt = SystemClock.elapsedRealtime()
                if (baselineRttMs <= 0L) {
                    baselineRttMs = sample
                } else if (sample < baselineRttMs) {
                    baselineRttMs = (baselineRttMs * 3L + sample) / 4L
                }
                // Re-prime page turbo on recovery
                if (recentSamples.count { it <= 0L } >= 2) {
                    AtomPageTurbo.kick(qualityScope, socksPort)
                    kickDnsWarm(force = false)
                }
                continue
            }
            badStreak += 1
            val required = AtomQualityPolicy.requiredBadStreak(recentSamples)
            if (badStreak < required) continue
            val elapsed = SystemClock.elapsedRealtime() - lastRetuneAt
            if (lastRetuneAt > 0L && elapsed < AtomQualityPolicy.RETUNE_COOLDOWN_MS) continue
            if (qualityRetunes.get() >= AtomQualityPolicy.MAX_QUALITY_RETUNES) continue
            badStreak = 0
            val detail = if (sample <= 0L) "probe failed streak=$required" else "rtt ${sample}ms vs ${baselineRttMs}ms jitter=$jitterHigh"
            requestQualityRetune(detail)
        }
    }

    private suspend fun requestQualityRetune(reason: String) {
        if (!retuneMutex.tryLock()) return
        try {
            if (stopRequested.get() || qualityRetunes.get() >= AtomQualityPolicy.MAX_QUALITY_RETUNES) return
            qualityRetunes.incrementAndGet()
            // Make-before-break: a second Psiphon comes up beside the live one. If it
            // fails, the live tunnel is kept as is and the ladder is not restarted.
            if (ghostEligible() && ghostInFlight.compareAndSet(false, true)) {
                try {
                    ghostInnerHandover(reason)
                    return
                } finally {
                    ghostInFlight.set(false)
                }
            }
            // Level C: try ghost handover first for zero-downtime, fallback to hard retune.
            if (!proxyMode && AtomTun2Socks.isRunning && ghostInFlight.compareAndSet(false, true)) {
                try {
                    val ghostOk = tryGhostHandover(reason)
                    if (ghostOk) return
                } finally {
                    ghostInFlight.set(false)
                }
            }
            retuneLocked(reason, quality = true)
        } finally {
            retuneMutex.unlock()
        }
    }

    private fun ghostEligible(): Boolean =
        currentSettings.ghostHandover &&
            !currentSettings.psiphonTunnelPool &&
            !proxyMode && !warpOnly && !stopRequested.get() &&
            AtomTun2Socks.isRunning && innerReady &&
            lastPsiphonConfig.isNotBlank() &&
            isScreenOn()

    /** Returns true when traffic moved to a new tunnel; false keeps the old one untouched. */
    private suspend fun ghostInnerHandover(reason: String): Boolean {
        AppLogRepository.info(LogSource.ATOM, "Ghost handover ($reason)")
        setRetuning(true)
        try {
            val port = ghostInner.warm(lastPsiphonConfig, AtomQualityPolicy.GHOST_HANDOVER_TIMEOUT_MS)
            if (port <= 0 || stopRequested.get()) {
                if (port > 0) ghostInner.release("session stopping")
                AppLogRepository.warning(LogSource.ATOM, "Ghost handover failed; keeping the current tunnel")
                return false
            }
            val ghostMs = AtomPathProbe.measureMsLite(port)
            if (ghostMs <= 0L) {
                ghostInner.release("probe failed")
                AppLogRepository.warning(LogSource.ATOM, "Ghost tunnel did not answer a probe; keeping the current tunnel")
                return false
            }
            ghostServing = true
            AtomSocksConnectOnly.setUpstreamPort(port)
            AppLogRepository.success(LogSource.ATOM, "Ghost handover: traffic moved to the new tunnel (${ghostMs}ms)")
            // Rebuild tunnel 1 while the ghost serves; when it connects, onConnected flips back.
            innerReady = false
            innerCallbacksArmed.set(false)
            innerStarted = false
            withContext(Dispatchers.IO) { runCatching { innerClient().stop() } }
            if (stopRequested.get()) return true
            delay(400)
            ladderActive.set(false)
            ladderJob?.cancel()
            val keepExit = AtomRegions.egressRegion(currentSettings.exitCountryCode) != null
            val connected = CompletableDeferred<Unit>()
            retuneSignal = connected
            innerCallbacksArmed.set(true)
            startPsiphonTunnel(keepExit = keepExit)
            val back = withTimeoutOrNull(AtomQualityPolicy.INNER_RETUNE_TIMEOUT_MS) { connected.await(); true } ?: false
            if (back) {
                finishGhost()
            } else {
                // No ladder restart: the ghost keeps serving and tunnel 1 keeps trying;
                // its onConnected hands traffic back later.
                AppLogRepository.warning(LogSource.ATOM, "Tunnel 1 not back yet; the ghost tunnel keeps serving")
            }
            lastRetuneAt = SystemClock.elapsedRealtime()
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLogRepository.warning(LogSource.ATOM, "Ghost handover error: ${error.message}")
            return false
        } finally {
            retuneSignal = null
            setRetuning(false)
        }
    }

    /** Tunnel 1 is connected again: move the relay back and stop the ghost. */
    private suspend fun finishGhost() {
        if (!ghostServing || !ghostFinishing.compareAndSet(false, true)) return
        try {
            AtomSocksConnectOnly.setUpstreamPort(socksPort)
            ghostServing = false
            val sample = AtomPathProbe.measureMsLite(socksPort)
            if (sample > 0L) {
                baselineRttMs = sample
                recordStrategyRtt(sample)
            }
            AppLogRepository.success(
                LogSource.ATOM,
                "Ghost handover done; tunnel 1 serves again" + if (sample > 0L) " ${sample}ms" else "",
            )
            // Give streams opened on the ghost a moment before it goes.
            delay(GHOST_DRAIN_MS)
            if (!ghostServing) ghostInner.release("handover finished")
        } finally {
            ghostFinishing.set(false)
        }
    }

    /** Any other rebuild owns tunnel 1 again: drop the ghost and point the relay at tunnel 1. */
    private suspend fun dropGhost(reason: String) {
        if (!ghostServing && !ghostInner.active) return
        ghostServing = false
        AtomSocksConnectOnly.setUpstreamPort(socksPort)
        ghostInner.release(reason)
    }

    private suspend fun tryGhostHandover(reason: String): Boolean {
        AppLogRepository.info(LogSource.ATOM, "Ghost handover attempt ($reason)")
        setRetuning(true)
        // Warm shadow outer on 1821 — does not disturb live 1820.
        val shadowOuter = runCatching {
            // We cannot truly run two AetherNative instances simultaneously (native singleton),
            // so ghost handover degrades to inner-only if outer is singleton-locked.
            // Attempt inner-only ghost: keep outer, rebuild inner on shadow port.
            null as String?
        }.getOrNull()
        // Fallback path: inner shadow handover (outer stays, inner flips port)
        return try {
            retuneInnerOnly("ghost:$reason")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) { false }
        finally { setRetuning(false) }
    }

    private suspend fun retuneLocked(reason: String, quality: Boolean): Boolean {
        if (stopRequested.get()) return false
        if (!proxyMode && !AtomTun2Socks.isRunning) return false
        // Psiphon only: there is no WARP hop to refresh; rebuild Psiphon alone.
        if (psiphonOnly) return retuneInnerOnly("psiphon-only:$reason")
        dropGhost("full retune")
        val optimized = currentSettings.optimizedMode
        setRetuning(true)
        AtomSocksConnectOnly.setFailFast(true)
        // A quality retune always lets open relays finish gracefully; only
        // recovery (hard failure) drops them at once.
        if (quality) AtomSocksConnectOnly.drainRelaysGracefully(600)
        else AtomSocksConnectOnly.dropRelays()
        AppLogRepository.info(
            LogSource.ATOM,
            "Refreshing Atom path behind the VPN ($reason, ${if (quality) "quality" else "recovery"})",
        )
        innerReady = false
        return try {
            if (!optimized || !quality) {
                // The cached WARP endpoint may be why the path died — forget it so the
                // reconnect cannot land back on the same dead hop.
                val forgotten = AtomCoreConfig.forgetPathMemory(appContext)
                if (forgotten > 0) {
                    AppLogRepository.info(LogSource.ATOM, "Cleared $forgotten cached WARP endpoint(s) for a fresh hop")
                }
            }
            innerCallbacksArmed.set(false)
            innerStarted = false
            withContext(Dispatchers.IO) {
                runCatching { innerClient().stop() }
            }
            delay(350)
            if (stopRequested.get()) return false
            stopOuterLeg()
            // Re-bind VpnService.protect to the active network after tearing
            // the outer leg (SIM switch / radio change / mid-session death).
            runCatching { AetherNative.attach(service) }
            if (stopRequested.get()) return false
            val outer = if (optimized && quality) {
                raiseOuterLeg(
                    discovery = AtomCoreConfig.DISCOVERY_CACHE,
                    scanMode = AtomCoreConfig.normalizeScanMode(currentSettings.scanMode),
                    silent = true,
                    retune = true,
                )
            } else {
                withRetuneHunt {
                    raiseOuterLeg(
                        discovery = AtomCoreConfig.DISCOVERY_FRESH,
                        scanMode = AtomCoreConfig.SCAN_TURBO,
                        silent = true,
                        retune = true,
                    )
                }
            }
            if (outer == null) {
                AppLogRepository.warning(LogSource.ATOM, "Could not raise a fresh WARP hop")
                return false
            }
            if (stopRequested.get()) return false
            armRegionPhase(retune = true)
            ladderIndex = rememberedLadderStart()
            ladderAttempts = 0
            ladderActive.set(false)
            ladderJob?.cancel()
            val keepExit = AtomRegions.egressRegion(currentSettings.exitCountryCode) != null
            val connected = CompletableDeferred<Unit>()
            retuneSignal = connected
            innerCallbacksArmed.set(true)
            startPsiphonTunnel(keepExit = keepExit)
            ladderJob = qualityScope.launch { watchLadder() }
            try {
                withTimeout(AtomQualityPolicy.INNER_RETUNE_TIMEOUT_MS) { connected.await() }
            } catch (_: TimeoutCancellationException) {
                AppLogRepository.warning(LogSource.ATOM, "Psiphon did not reconnect through the new hop in time")
                return false
            }
            if (stopRequested.get()) return false
            lastRetuneAt = SystemClock.elapsedRealtime()
            delay(400)
            val sample = AtomPathProbe.measureMsLite(socksPort)
            if (sample > 0L) {
                baselineRttMs = sample
                recordStrategyRtt(sample)
                recentSamples = mutableListOf(sample)
                lastStableAt = SystemClock.elapsedRealtime()
            }
            AtomPageTurbo.kick(qualityScope, socksPort, keepWarm = true)
            kickDnsWarm(force = false)
            AppLogRepository.success(
                LogSource.ATOM,
                "Atom path refreshed via $outer" + if (sample > 0L) " (${sample}ms)" else "",
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLogRepository.warning(LogSource.ATOM, "Atom path refresh failed: ${error.message}")
            false
        } finally {
            retuneSignal = null
            innerCallbacksArmed.set(true)
            AtomSocksConnectOnly.setFailFast(false)
            setRetuning(false)
        }
    }

    private suspend fun <T> withRetuneHunt(block: suspend () -> T): T {
        val previousScan = runCatching { Os.getenv("AETHER_SCAN") }.getOrNull()
        val previousQuick = runCatching { Os.getenv("AETHER_QUICK_RECONNECT") }.getOrNull()
        try {
            runCatching { Os.setenv("AETHER_SCAN", "turbo", true) }
            runCatching { Os.setenv("AETHER_QUICK_RECONNECT", "0", true) }
            return block()
        } finally {
            restoreEnv("AETHER_SCAN", previousScan)
            restoreEnv("AETHER_QUICK_RECONNECT", previousQuick)
        }
    }

    private fun restoreEnv(key: String, previous: String?) {
        runCatching {
            if (previous == null) {
                Os.unsetenv(key)
            } else {
                Os.setenv(key, previous, true)
            }
        }
    }

    companion object {
        private const val CONNECT_GRACE_MS = 8_000L
        private const val OUTER_START_GRACE_MS = 4_000L
        private const val OUTER_STOP_GRACE_MS = 4_000L
        private const val FORCE_NATIVE_EXIT_MS = 2_000L
        private const val INNER_CONNECT_TIMEOUT_MS = 8 * 60_000L
        private const val GHOST_DRAIN_MS = 3_000L
    }
}
