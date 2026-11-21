package com.atomvpn.mobile.engine.atom

import android.os.Build
import java.io.File

object GoJniLoader {
    const val XRAY_LIBRARY = "gojni"
    const val ATOM_LIBRARY = "gopsi"
    const val ATOM_PROCESS_SUFFIX = ":atomengine"
    const val ATOM_SIDECAR_PROCESS_SUFFIX = ":atomengine2"

    @Volatile
    private var forcedLibrary: String? = null

    @JvmStatic
    fun forceAtomLibrary() {
        forcedLibrary = ATOM_LIBRARY
    }

    @JvmStatic
    fun load() {
        System.loadLibrary(forcedLibrary ?: if (isAtomProcess()) ATOM_LIBRARY else XRAY_LIBRARY)
    }

    @JvmStatic
    fun isAtomProcess(): Boolean = isAtomProcessName(processName())

    @JvmStatic
    fun isAtomProcessName(name: String): Boolean =
        name.endsWith(ATOM_PROCESS_SUFFIX) || name.endsWith(ATOM_SIDECAR_PROCESS_SUFFIX)

    private fun processName(): String {
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { return android.app.Application.getProcessName() }
        }
        return runCatching {
            File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
        }.getOrDefault("").trim { it <= ' ' || it == '\u0000' }
    }
}
