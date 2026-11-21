# Atom engine API specification

## VPN host

The Android application owns the VPN permission flow, foreground service, TUN interface, descriptor lifecycle, package routing and kill-switch policy.

The engine entry point is:

```kotlin
AtomConnectionCoordinator(service: VpnService)
```

The coordinator manages connection, retuning, recovery and disconnect. It requires a real `VpnService` and a live TUN descriptor for tunnel mode.

## Psiphon services

The engine declares:

- `AtomPsiphonService` in process `:atomengine`
- `AtomPsiphonSidecarService` in process `:atomengine2`

The processes must remain separate because the Psiphon Go tunnel uses a process-local singleton. `GoJniLoader` selects the isolated `gopsi` library based on the process suffix. The exact asset name `server_entries.txt` must be packaged in the application.

## Native and tunnel dependencies

- `AetherNative` owns the Aether JNI lifecycle and native entry points.
- `AtomTun2Socks` starts and monitors the HEV TUN-to-SOCKS bridge.
- `AtomHevSocks5Tunnel` is the Atom-facing wrapper around the bundled HEV JNI bridge.
- `OobKeyFetch` performs the Android TLS/OOB identity registration lane.
- The V2Ray AAR patcher redirects the Go sequence loader to `GoJniLoader.load()`.

Do not replace these components with no-op adapters or alter their packet and connection sequencing during a naming-only refactor.

## Network policy

`AtomCarrier` consumes `NetworkFingerprintResolver.capture()`, which reports transport, subscription ID and carrier class. Restricted telephony information is reported as unknown. Validate Wi-Fi, cellular, dual-SIM and permission-restricted behavior on a physical device.

`DomesticDirect` combines the manual domain whitelist, fallback host set and GeoSite CATEGORY-IR data. `NetworkGuardStore` provides policy settings; enforcement remains the responsibility of the host VPN service.

## Settings and state

- `AtomEngineSettings` and `AtomEngineStore`: engine configuration and persistence.
- `AtomStatusStore` and `ConnectionStateStore`: connection progress and lifecycle state.
- `AtomNetworkScoreboard` and `AtomPathMemory`: learned network/path state.
- `AppLogRepository` with `LogSource.ATOM`: structured engine diagnostics.
- `TunStats` and `VpnPauseStore`: traffic statistics and session pause state.

## Data compatibility

The engine copies legacy preferences and files into Atom-named storage locations before using the new locations. Do not remove the migration path unless a separate data migration has been designed and tested.
