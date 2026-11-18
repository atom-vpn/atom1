# Adapter API specification (source-verified)

This specification was derived from the current `atom-vpn/POW-Source` source files and actual PoW call sites. It is deliberately a contract document, not a set of no-op implementations.

## 1. VPN host boundary (required)

The coordinator currently has this construction and connect boundary:

```kotlin
PowConnectionCoordinator(service: VpnService)

suspend fun connect(
    settings: AdvancedSettingsData,
    establishTun: (PowTun2Socks.PrivateAddress, Int) -> ParcelFileDescriptor?,
)
```

The host must supply a real `VpnService`, and the callback must establish/configure the Android TUN interface and return its live `ParcelFileDescriptor` (or null only where the selected proxy mode does not require a device TUN). The host remains responsible for VPN permission, foreground-service lifecycle/notification, allowed/disallowed applications, kill-switch enforcement, and descriptor ownership/closure. Do not substitute a fake service or descriptor.

## 2. Socket protection

Observed original API:
- `SocketProtectGate.isEnabled(context: Context): Boolean`
- Actual behavior in the source: always returns `true`.
- Call sites: `OobKeyFetch` calls `vpn.protect(remoteSocket)` before connecting; `PowPsiphonClient` calls `vpn.protect(pfd.fd)` for the Psiphon tunnel descriptor.

Standalone host contract should expose socket protection as an operation on the owning `VpnService` (or an equivalent injected protector), not as a preference toggle. A false/failed protect result must remain observable; silently returning success would create VPN routing loops.

## 3. Network fingerprint / carrier identity

`PowCarrier` consumes these values from `NetworkFingerprintResolver(context).capture()`:
- `transport: String` (known values include `cellular` and `unknown`)
- `dataSubscriptionId: Int`
- `carrierClass: String` (including `mci` and `unknown`)

It additionally reads `TelephonyManager.networkOperator` for the selected data subscription and combines it into a `PowRadioIdentity`. This is used to distinguish Wi-Fi/cellular transitions and SIM/operator/carrier flips.

**Extraction status:** the checked-in source tree has no implementation of this imported resolver. A new, narrowly scoped Android implementation has been added at `engine/src/main/kotlin/com/uacspoofer/mobile/vpn/NetworkFingerprintResolver.kt`. It reports the three fields consumed by `PowCarrier` and degrades to `unknown` / invalid subscription ID when Android restricts access. This is a new implementation, not a source-identical recovery; it still requires device validation on Wi-Fi, cellular, dual-SIM, and permission-restricted states.

## 4. Logging

The original `AppLogRepository` is an object with:
- `debug(source: LogSource, message: String)`
- `info(source: LogSource, message: String)`
- `success(source: LogSource, message: String)`
- `warning(source: LogSource, message: String, error: Throwable? = null)`
- `error(source: LogSource, message: String, error: Throwable? = null)`

`LogSource` is declared in the same `AppLogRepository.kt` file (not a separate source file); PoW uses `LogSource.POW`. The implementation retains a bounded in-memory log and mirrors PoW lines to Android logcat. An adapter may map these calls to Atom's logger, but must preserve severity, source and throwable context.

## 5. Domestic-direct routing

Actual source API used by the coordinator:
- `DomesticDirect.ensure(context: Context)`
- `DomesticDirect.applyEnabled(value: Boolean)`

The source implementation also exposes `isDomesticHost(host: String): Boolean`; it checks a manual domain whitelist, a fallback host set, and loaded GeoSite CATEGORY-IR domains. It depends on `GeoAssets` and `DirectDomainWhitelist`, so transferring only the two coordinator calls without the matching policy/data dependencies would not preserve behavior. The coordinator reads `NetworkGuardStore.get(context).snapshot().iranDirect` and passes that policy into both `DomesticDirect` and `PowSocksConnectOnly`.

## 6. Network guard

Observed call surface from PoW:
- `NetworkGuardStore.get(context).snapshot().iranDirect`

The original store is backed by Android SharedPreferences and exposes a `NetworkGuardSettings` snapshot. Its other state includes kill-switch, auto-connect, LAN share, DNS sinkhole, WebRTC/QUIC/IPv6 blocking and a process-local user-stopped flag. Only `iranDirect` is read directly by the current coordinator, but the host's kill-switch remains a separate VPN-service responsibility.

## 7. Advanced engine settings

The original `AdvancedSettingsData` is declared in `AdvancedSettingsStore.kt`, not in a file named `AdvancedSettingsData.kt`. The coordinator's direct reads are:
- `connectionMode`, compared with `CONNECTION_MODE_PROXY` (`"proxy"`)
- `socksPort`
- `tunMtu`

The settings object also supplies the WARP/Aether, transport, TLS, SOCKS, DNS and TUN configuration consumed by the PoW configuration helpers. Do not replace it with a reduced DTO until every access in all 36 engine files and their helper call sites has been inventoried.

## 8. Traffic statistics, pause and lifecycle

- `TunStats(txPackets, txBytes, rxPackets, rxBytes)` with `ZERO` and growth helpers.
- `VpnPauseStore.isPaused(): Boolean`; the original pause state is process-local and session-only.
- `ConnectionStateStore.markConnecting()` and `markConnected(): Boolean` are the only methods directly called by the coordinator. The source state machine has idempotent connected semantics and explicit connecting/disconnecting/error states; an adapter must preserve these transitions.

## Extraction decision

Source-backed support classes have been copied into the engine tree, and the missing narrow network-fingerprint API has a real Android implementation. No fake VPN service or TUN descriptor has been added: the consumer must supply those through the documented host boundary. The resolver is not device-tested, and no Gradle compile has been run. The remaining vendor binaries and host lifecycle integration still prevent a standalone build claim.
