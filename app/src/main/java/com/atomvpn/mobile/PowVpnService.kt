package com.atomvpn.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.pow.PowConnectionCoordinator
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.engine.pow.PowTun2Socks
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PowVpnService : VpnService() {
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
    private lateinit var coordinator: PowConnectionCoordinator

    override fun onCreate() {
        super.onCreate()
        createChannel()
        coordinator = PowConnectionCoordinator(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_CONNECT, null -> connect()
        }
        return START_STICKY
    }

    private fun connect() {
        startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی UAC PoW"))
        scope.launch {
            try {
                val settings = AdvancedSettingsStore(this@PowVpnService).snapshot()
                coordinator.connect(settings) { address, mtu -> establishTun(address, mtu) }
                ConnectionStateStore.markConnected()
                updateNotification("اتصال امن برقرار شد")
            } catch (error: Throwable) {
                PowStatusStore.update(
                    com.uacspoofer.mobile.engine.pow.PowPhase.FAILED,
                    0,
                    error.message ?: "اتصال ناموفق بود"
                )
                ConnectionStateStore.markError()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun establishTun(address: PowTun2Socks.PrivateAddress, mtu: Int): ParcelFileDescriptor? {
        val tunMtu = mtu.coerceIn(576, 9000)
        return Builder()
            .setSession("Atom VPN")
            .setBlocking(false)
            .setMtu(tunMtu)
            .addAddress(address.ipAddress, address.prefixLength)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(address.router)
            .addDisallowedApplication(packageName)
            .allowFamily(OsConstants.AF_INET)
            .establish()
    }

    private fun disconnect() {
        coordinator.signalStop()
        scope.launch {
            runCatching { coordinator.stop() }
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
        coordinator.signalStop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_CONNECT = "com.atomvpn.mobile.CONNECT"
        const val ACTION_DISCONNECT = "com.atomvpn.mobile.DISCONNECT"
        private const val CHANNEL_ID = "atom_vpn_connection"
        private const val NOTIFICATION_ID = 4101
    }
}
