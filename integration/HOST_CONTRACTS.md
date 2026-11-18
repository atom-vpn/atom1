# Host integration contracts required by the extracted PoW engine

The original PoW source was copied verbatim. These references are still imports from the original application and must be adapted before this directory can compile independently.

## Required host APIs observed in source imports

| Original API | Used by | Responsibility that the host must provide |
|---|---|---|
| `com.uacspoofer.mobile.vpn.SocketProtectGate` | OobKeyFetch, PowPsiphonClient | Protect outbound sockets from VPN routing loops |
| `com.uacspoofer.mobile.vpn.NetworkFingerprintResolver` | PowCarrier | Resolve current carrier/network identity |
| `com.uacspoofer.mobile.logging.AppLogRepository`, `LogSource` | multiple engine classes | Structured engine logging |
| `com.uacspoofer.mobile.mci.DomesticDirect` | PowConnectionCoordinator | Domestic/direct routing policy |
| `com.uacspoofer.mobile.settings.NetworkGuardStore` | PowConnectionCoordinator | Kill-switch/network-guard state |
| `com.uacspoofer.mobile.settings.AdvancedSettingsData` | PowConnectionCoordinator | Engine/TUN configuration data |
| `com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY` | PowConnectionCoordinator | Proxy-vs-full-TUN mode constant |
| `com.uacspoofer.mobile.vpn.TunStats` | PowConnectionCoordinator, PowTun2Socks | Traffic counters |
| `com.uacspoofer.mobile.vpn.VpnPauseStore` | PowConnectionCoordinator | Pause/resume state |
| `com.uacspoofer.mobile.core.ConnectionStateStore` | PowConnectionCoordinator | Connection lifecycle state |

## Android host responsibilities

The consumer must also provide a real Android `VpnService` and a TUN establishment callback. The engine must not create or own a fake VPN service. The host owns VPN permission flow, foreground-service lifecycle, notification policy, package routing, kill-switch policy, and closing the TUN descriptor.

## Important

This file is an inventory, not a fake implementation. The APIs above have behavior and lifecycle semantics in the original app; replacing them with no-op stubs would make the extraction appear independent while silently breaking routing, protection, or cleanup.
