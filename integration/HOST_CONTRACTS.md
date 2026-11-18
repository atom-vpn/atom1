# Host integration contracts required by the extracted PoW engine

The PoW engine and its source-backed support classes are present in the engine source tree. These are source copies, not placeholder implementations. The network-fingerprint resolver is a new, narrow Android implementation because the source repository imported the API but did not contain its implementation.

## 1. VPN host boundary

The consumer must provide:

```kotlin
PowConnectionCoordinator(service: VpnService)

suspend fun connect(
    settings: AdvancedSettingsData,
    establishTun: (PowTun2Socks.PrivateAddress, Int) -> ParcelFileDescriptor?,
)
```

The callback must establish/configure the Android TUN interface and return its live `ParcelFileDescriptor` (or null only where the selected proxy mode does not require a device TUN). The host owns VPN permission flow, foreground-service lifecycle/notification, allowed/disallowed applications, kill-switch enforcement, and TUN descriptor ownership/closure. Do not substitute a fake service or descriptor.

## 2. Psiphon service declarations

The engine binds to two concrete Android Service classes. The host app Manifest must merge declarations equivalent to:

```xml
<service
    android:name="com.uacspoofer.mobile.engine.pow.PowPsiphonService"
    android:exported="false"
    android:process=":uacpow"
    android:stopWithTask="false" />

<service
    android:name="com.uacspoofer.mobile.engine.pow.PowPsiphonSidecarService"
    android:exported="false"
    android:process=":uacpow2"
    android:stopWithTask="false" />
```

The separate processes are required because the Psiphon Go tunnel uses a process-local singleton. `GoJniLoader` detects these process suffixes and selects the isolated `gopsi` library. The Psiphon AAR must first be processed by `tools/isolate_psiphon_aar.py`, which removes the embedded `go/` classes and renames `libgojni.so` to `libgopsi.so`. Do not merge the two services into the same process.

## 3. Psiphon server entries asset

`PowPsiphonService` opens the exact asset name `server_entries.txt` through Android `assets.open()`. The host package must therefore include `vendor/assets/server_entries.txt` as an application asset named `server_entries.txt`. Merely keeping it in the repository's vendor directory does not make it available at runtime.

## 4. Socket protection

Observed source API:
- `SocketProtectGate.isEnabled(context: Context): Boolean`
- Actual behavior in the source: always returns `true`.
- Call sites: `OobKeyFetch` calls `vpn.protect(remoteSocket)`; `PowPsiphonClient` protects the Psiphon tunnel descriptor.

Standalone host integration should expose socket protection as an operation on the owning `VpnService` (or equivalent injected protector), not as a preference toggle. A false/failed protect result must remain observable; silently returning success risks VPN routing loops.

## 5. Network fingerprint / carrier identity

`PowCarrier` consumes:
- `transport: String` (known values include `cellular` and `unknown`)
- `dataSubscriptionId: Int`
- `carrierClass: String` (including `mci` and `unknown`)

It also reads `TelephonyManager.networkOperator` for the selected data subscription. The new resolver reports unavailable or permission-restricted telephony data as unknown. It is not source-identical and requires device validation on Wi-Fi, cellular, dual-SIM and permission-restricted states. Carrier classification may require the host to declare and obtain runtime `READ_PHONE_STATE` permission.

## 6. Domestic-direct routing

Actual source API:
- `DomesticDirect.ensure(context: Context)`
- `DomesticDirect.applyEnabled(value: Boolean)`
- `DomesticDirect.isDomesticHost(host: String): Boolean`

It checks a manual domain whitelist, fallback host set and loaded GeoSite CATEGORY-IR domains. It depends on GeoAssets and DirectDomainWhitelist. The coordinator reads `NetworkGuardStore.get(context).snapshot().iranDirect` and passes that policy into routing. Do not replace it with a boolean-only implementation.

## 7. Settings, logging and state

- `AdvancedSettingsData` and `CONNECTION_MODE_PROXY` are declared in `AdvancedSettingsStore.kt`. The coordinator directly reads `connectionMode`, `socksPort` and `tunMtu`, while configuration helpers consume additional WARP, transport, TLS, SOCKS, DNS and TUN fields.
- `AppLogRepository` and `LogSource` are declared together in `AppLogRepository.kt`. Preserve severity, source and throwable context when adapting logging.
- `NetworkGuardStore` is SharedPreferences-backed; the coordinator reads its `iranDirect` policy. Kill-switch enforcement remains a host VPN responsibility.
- `TunStats`, `VpnPauseStore` and `ConnectionStateStore` retain source behavior and state transitions.

## 8. Native libraries and packaging

The consumer must package the ABI-matching files from `vendor/jniLibs/`. Aether is available for arm64-v8a, armeabi-v7a and x86_64; HEV is available for arm64-v8a, armeabi-v7a, x86 and x86_64. The Aether JNI bridge is built from `native/CMakeLists.txt`; its imported Aether path is relative to `vendor/jniLibs/`.

The V2Ray AAR must be processed by `tools/patch_v2ray_seq.py` so its Go sequence loader calls `GoJniLoader.load()`. The original AARs are inputs, not yet patched runtime outputs.

## Build status

No Gradle module/build configuration or Android compile has been run. The engine source, vendor inputs and host contracts are present, but the module still needs Gradle wiring and a real consuming VPN host. The new network-fingerprint resolver is not device-tested.
