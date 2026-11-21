package com.atomvpn.mobile.engine.tor;

/**
 * Atom-facing wrapper around the prebuilt HEV JNI entry point.
 * The legacy bridge package is retained only because the shipped native
 * library exports JNI symbols for that exact class name.
 */
public final class AtomHevSocks5Tunnel {
    private AtomHevSocks5Tunnel() {}

    public static boolean TProxyStartService(String configPath, int fd) {
        return com.uacspoofer.mobile.engine.tor.HevSocks5Tunnel.TProxyStartService(configPath, fd);
    }

    public static boolean TProxyStopService() {
        return com.uacspoofer.mobile.engine.tor.HevSocks5Tunnel.TProxyStopService();
    }

    public static boolean TProxyIsRunning() {
        return com.uacspoofer.mobile.engine.tor.HevSocks5Tunnel.TProxyIsRunning();
    }

    public static long[] TProxyGetStats() {
        return com.uacspoofer.mobile.engine.tor.HevSocks5Tunnel.TProxyGetStats();
    }
}
