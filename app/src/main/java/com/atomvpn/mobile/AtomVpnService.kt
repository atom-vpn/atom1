package com.atomvpn.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.atomvpn.mobile.engine.core.ConnectionStateStore
import com.atomvpn.mobile.engine.atom.AtomConnectionCoordinator
import com.atomvpn.mobile.engine.atom.AtomEngineStore
import com.atomvpn.mobile.engine.atom.AtomStatusStore
import com.atomvpn.mobile.engine.atom.AtomTun2Socks
import com.atomvpn.mobile.engine.settings.NetworkGuardStore
import com.atomvpn.mobile.engine.vpn.TunPacketFilter
import com.atomvpn.mobile.engine.settings.AdvancedSettingsData
import com.atomvpn.mobile.engine.settings.AdvancedSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AtomVpnService : VpnService() {
    /**
     * JNI callback used by libaether_jni to protect native sockets from VPN routing.
     * Keep the exact public JVM signature: protectSocket(int): boolean.
     */
    @androidx.annotation.Keep
    fun protectSocket(socketFd: Int): Boolean = protect(socketFd)

    /**
     * Receives asynchronous lifecycle/status events from the Aether native core.
     * Keep this method public with the exact signature expected by JNI.
     */
    @androidx.annotation.Keep
    fun onEvent(eventJson: String) {
        android.util.Log.i("AtomVPN-Aether", eventJson)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: AtomConnectionCoordinator
    private var connectJob: kotlinx.coroutines.Job? = null
    private var exitIpJob: kotlinx.coroutines.Job? = null
    private var trafficSpeedJob: kotlinx.coroutines.Job? = null
    @Volatile private var shutdownCompleted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        coordinator = AtomConnectionCoordinator(this)
        val networkGuard = NetworkGuardStore.get(this)
        TunPacketFilter.applyFlags(networkGuard.snapshot())
        scope.launch { networkGuard.settings.collect { TunPacketFilter.applyFlags(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_CONNECT, null -> connect()
        }
        return START_STICKY
    }

    private fun connect() {
        // ثبت حالت قبل از شروع موتور ضروری است؛ موتور فقط از CONNECTING به CONNECTED می‌رود.
        if (!ConnectionStateStore.tryBeginConnect()) return
        exitIpJob?.cancel()
        trafficSpeedJob?.cancel()
        ExitIpInfoRepository.clear()
        TrafficSpeedRepository.clear()
        startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی اتصال"))
        connectJob = scope.launch {
            try {
                val settings = AdvancedSettingsStore(this@AtomVpnService).snapshot()
                coordinator.connect(settings) { address, mtu -> establishTun(address, mtu) }
                if (ConnectionStateStore.state.value == com.atomvpn.mobile.engine.core.ConnectionState.CONNECTED) {
                    updateNotification("اتصال امن برقرار شد")
                    startTrafficSpeedMonitor()
                    // Resolve the public IP through the engine's own SOCKS listener,
                    // never through the phone's direct network.
                    exitIpJob?.cancel()
                    exitIpJob = scope.launch {
                        // The VPN can be usable before the upstream SOCKS tunnel is ready.
                        // Back off between attempts and keep transient failures out of the UI.
                        EXIT_IP_RETRY_DELAYS_MS.forEachIndexed { attempt, retryDelay ->
                            if (ConnectionStateStore.state.value != com.atomvpn.mobile.engine.core.ConnectionState.CONNECTED) {
                                return@launch
                            }
                            delay(retryDelay)
                            val isFinalAttempt = attempt == EXIT_IP_RETRY_DELAYS_MS.lastIndex
                            val resolved = ExitIpInfoRepository.refresh(
                                coordinator.socksPort(),
                                keepLoadingOnFailure = !isFinalAttempt
                            )
                            if (resolved) return@launch
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // لغو دستی، خطای اتصال محسوب نمی‌شود.
            } catch (error: Throwable) {
                if (ConnectionStateStore.state.value != com.atomvpn.mobile.engine.core.ConnectionState.DISCONNECTING) {
                    AtomStatusStore.update(
                        com.atomvpn.mobile.engine.atom.AtomPhase.FAILED,
                        0,
                        error.message ?: "اتصال ناموفق بود"
                    )
                    ConnectionStateStore.markError()
                    exitIpJob?.cancel()
                    trafficSpeedJob?.cancel()
                    ExitIpInfoRepository.clear()
                    TrafficSpeedRepository.clear()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun startTrafficSpeedMonitor() {
        trafficSpeedJob?.cancel()
        trafficSpeedJob = scope.launch {
            var previous = coordinator.tunStats()
            var previousAt = SystemClock.elapsedRealtime()
            TrafficSpeedRepository.clear()
            while (ConnectionStateStore.state.value == com.atomvpn.mobile.engine.core.ConnectionState.CONNECTED) {
                delay(1_000L)
                val current = coordinator.tunStats()
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - previousAt).coerceAtLeast(1L)
                val download = ((current.rxBytes - previous.rxBytes).coerceAtLeast(0L) * 1_000L) / elapsed
                val upload = ((current.txBytes - previous.txBytes).coerceAtLeast(0L) * 1_000L) / elapsed
                TrafficSpeedRepository.update(download, upload)
                previous = current
                previousAt = now
            }
            TrafficSpeedRepository.clear()
        }
    }

    private fun establishTun(address: AtomTun2Socks.PrivateAddress, mtu: Int): ParcelFileDescriptor? {
        val tunMtu = mtu.coerceIn(576, 9000)
        val realTun = Builder()
            .setSession("Atom VPN")
            .setBlocking(false)
            .setMtu(tunMtu)
            .addAddress(address.ipAddress, address.prefixLength)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(address.router)
            .addDisallowedApplication(packageName)
            .allowFamily(OsConstants.AF_INET)
            .establish() ?: return null

        // The filter owns the Android TUN; HEV receives only the filtered socketpair endpoint.
        TunPacketFilter.applyFlags(NetworkGuardStore.get(this).snapshot())
        return runCatching { TunPacketFilter.attach(realTun, tunMtu) }
            .onFailure { error ->
                android.util.Log.w("AtomVPN", "QUIC packet filter unavailable; using direct TUN", error)
            }
            .getOrDefault(realTun)
    }

    private fun disconnect() {
        ConnectionStateStore.tryBeginDisconnect()
        // اتصالِ درحال اجرا نباید پس از درخواست قطع، وضعیت را دوباره CONNECTED کند.
        connectJob?.cancel()
        exitIpJob?.cancel()
        trafficSpeedJob?.cancel()
        ExitIpInfoRepository.clear()
        TrafficSpeedRepository.clear()
        coordinator.signalStop()
        scope.launch {
            val stopped = runCatching { coordinator.stop() }.isSuccess
            if (stopped) {
                shutdownCompleted = true
            } else {
                coordinator.forceRelease()
            }
            ConnectionStateStore.markDisconnected()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Atom VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Atom VPN")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        // Android may destroy/revoke the VPN service without ACTION_DISCONNECT.
        // In that path, a signal alone does not unbind/stop the separate Psiphon
        // services or release the TUN/HEV resources. Use the coordinator's bounded,
        // non-blocking fallback unless the normal teardown already completed.
        if (::coordinator.isInitialized && !shutdownCompleted) {
            coordinator.forceRelease()
            ConnectionStateStore.markDisconnected()
        }
        exitIpJob?.cancel()
        trafficSpeedJob?.cancel()
        ExitIpInfoRepository.clear()
        TrafficSpeedRepository.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_CONNECT = "com.atomvpn.mobile.CONNECT"
        const val ACTION_DISCONNECT = "com.atomvpn.mobile.DISCONNECT"
        private const val CHANNEL_ID = "atom_vpn_connection"
        private const val NOTIFICATION_ID = 4101
        // 58 seconds of bounded backoff, in addition to each request's own timeout.
        private val EXIT_IP_RETRY_DELAYS_MS = longArrayOf(
            2_000L, 3_000L, 5_000L, 8_000L, 10_000L, 10_000L, 10_000L, 10_000L
        )
    }
}
