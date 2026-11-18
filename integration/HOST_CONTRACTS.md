# Host integration contracts required by the extracted PoW engine

The PoW engine source was copied first without modification. The following source-backed support classes have now also been copied into the engine source tree under their original package names:

- `core/ConnectionState.kt`, `ConnectRouteProgress.kt`, `ConnectionStateMachine.kt`, `ConnectionStateStore.kt`
- `logging/AppLogRepository.kt` (includes `LogSource`)
- `mci/MciConfig.kt`, `DomesticDirect.kt`, `GeoAssets.kt`
- `settings/AdvancedSettingsStore.kt`, `NetworkGuardStore.kt`, `DirectDomainWhitelist.kt`
- `vpn/SocketProtectGate.kt`, `TunStats.kt`, `VpnPauseStore.kt`

These are source copies, not placeholder implementations. Their blob IDs match the source repository versions.

## Network fingerprint implementation

The source repository imports `com.uacspoofer.mobile.vpn.NetworkFingerprintResolver` but does not contain its implementation. The extracted engine now includes a narrowly scoped Android implementation at `vpn/NetworkFingerprintResolver.kt`, returning only the three values actually consumed by `PowCarrier`: transport, default data subscription ID, and carrier class. It uses Android connectivity and telephony APIs; unavailable or permission-restricted telephony data is reported as unknown rather than fabricated.

This is a new implementation, not a source-identical copy. It does not claim compatibility with any wider fingerprint API that may exist outside the checked-in source.

## Remaining Android host boundary

The consumer must provide a real Android `VpnService` and TUN establishment callback. The coordinator constructor and connection entry point are:

```kotlin
PowConnectionCoordinator(service: VpnService)

suspend fun connect(
    settings: AdvancedSettingsData,
    establishTun: (PowTun2Socks.PrivateAddress, Int) -> ParcelFileDescriptor?,
)
```

The host owns VPN permission flow, foreground-service lifecycle, notification policy, package routing, kill-switch enforcement, and TUN descriptor lifecycle. Do not substitute a fake service or descriptor.

## Remaining external runtime artifacts

The Psiphon AAR, V2Ray native TUN AAR, Aether native libraries, and Psiphon server entries are still missing from this branch. See `../vendor/ARTIFACTS.md`.

## Build status

No Gradle build or Android compile has been run. The extracted source tree is more complete, but is not yet certified as a standalone build. The Android host boundary and missing vendor artifacts remain blockers.
