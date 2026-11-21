package com.atomvpn.mobile.engine.tor;

/**
 * Atom-owned JNI binding for the HEV SOCKS5 tunnel.
 * The matching native library is built with this exact package and class name.
 */
public final class AtomHevSocks5Tunnel {
    private AtomHevSocks5Tunnel() {}

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
