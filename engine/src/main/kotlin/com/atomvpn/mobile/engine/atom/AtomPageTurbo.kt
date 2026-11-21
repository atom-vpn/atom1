package com.atomvpn.mobile.engine.atom

import com.atomvpn.mobile.engine.logging.AppLogRepository
import com.atomvpn.mobile.engine.logging.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Page Turbo — warms mapdns after connect. Optional keepalive probes
 * keep the first real navigation primed. Isolated to PoW SOCKS.
 */
internal object AtomPageTurbo {
    @Volatile private var job: Job? = null

    fun kick(scope: CoroutineScope, socksPort: Int, keepWarm: Boolean = true) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            delay(900)
            runCatching {
                val ok = AtomPathProbe.warmMapDns(socksPort)
                if (ok) AppLogRepository.info(LogSource.ATOM, "Page Turbo mapdns warm")
                else AppLogRepository.debug(LogSource.ATOM, "Page Turbo warm miss")
            }
            if (keepWarm) {
                repeat(2) {
                    delay(1500)
                    runCatching { AtomPathProbe.measureMs(socksPort, timeoutMs = 1_500) }
                }
                AppLogRepository.debug(LogSource.ATOM, "Page Turbo primed")
            }
        }
    }

    fun cancel() {
        job?.cancel(); job = null
    }
}
