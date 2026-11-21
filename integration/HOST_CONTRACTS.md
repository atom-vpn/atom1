# Atom engine integration contracts

This document records the Android host contracts required by the standalone Atom VPN application.

## 1. VPN service boundary

The application owns the Android `VpnService`, permission flow, foreground-service notification, allowed/disallowed applications, kill-switch enforcement and TUN descriptor lifecycle.

The engine coordinator is constructed with the active service:

```kotlin
AtomConnectionCoordinator(service: VpnService)
```

The coordinator establishes the selected route and reports state through `ConnectionStateStore` and `AtomStatusStore`. A real VPN service and live TUN descriptor must be used; a fake descriptor or placeholder service is not a valid connection test.

## 2. Psiphon services and process isolation

The engine manifest registers two non-exported services:

```xml
<service
    android:name="com.atomvpn.mobile.engine.atom.AtomPsiphonService"
    android:exported="false"
    android:process=":atomengine"
    android:stopWithTask="false" />

<service
    android:name="com.atomvpn.mobile.engine.atom.AtomPsiphonSidecarService"
    android:exported="false"
    android:process=":atomengine2"
    android:stopWithTask="false" />
```

The separate processes are required because the Psiphon Go tunnel uses a process-local singleton. `GoJniLoader` detects these process suffixes and selects the isolated `gopsi` library. The Psiphon AAR is processed by `tools/isolate_psiphon_aar.py`, which removes embedded Go classes and renames `libgojni.so` to `libgopsi.so`. Do not merge both services into one process.

## 3. Psiphon asset

`AtomPsiphonService` opens the exact asset name `server_entries.txt`. The packaged application must include `vendor/assets/server_entries.txt` as an application asset under that name.

## 4. Socket protection

Socket protection is performed through the owning `VpnService`. Native and Psiphon sockets must be protected from routing back into the VPN tunnel. A false or failed protect result must remain observable; silently returning success can create routing loops.

## 5. Network fingerprint and carrier policy

`AtomCarrier` consumes a network snapshot containing:

- `transport`: values include `cellular` and `unknown`
- `dataSubscriptionId`
- `carrierClass`: values include `mci` and `unknown`

The Android resolver reports unavailable or permission-restricted telephony data as unknown. Device validation is required on Wi-Fi, cellular, dual-SIM and permission-restricted states. Carrier classification may require the host to declare and obtain runtime `READ_PHONE_STATE` permission.

## 6. Domestic-direct routing

`DomesticDirect` provides:

- `ensure(context)`
- `applyEnabled(value)`
- `isDomesticHost(host)`

It checks a manual domain whitelist, fallback host set and loaded GeoSite CATEGORY-IR domains. It depends on `GeoAssets` and `DirectDomainWhitelist`. The coordinator reads `NetworkGuardStore` and applies the selected direct-routing policy.

## 7. Settings, logging and connection state

- `AdvancedSettingsData` and `CONNECTION_MODE_PROXY` are declared in `AdvancedSettingsStore.kt`.
- `AtomEngineStore` persists engine settings and learned route state.
- `AppLogRepository` and `LogSource` provide structured logs; the engine source is `LogSource.ATOM`.
- `NetworkGuardStore` is SharedPreferences-backed. Kill-switch enforcement remains an application VPN-service responsibility.
- `TunStats`, `VpnPauseStore` and `ConnectionStateStore` retain their connection-state and traffic-statistics responsibilities.

## 8. Native libraries and packaging

The application must package ABI-matching files from `vendor/jniLibs/`. The Aether JNI bridge is compiled from `native/CMakeLists.txt`; its imported Aether library path is relative to `vendor/jniLibs/`.

The HEV native binary exports JNI symbols for its original Java bridge class. `AtomHevSocks5Tunnel` is the Atom-facing wrapper around that bridge. Keep the bridge and its consumer ProGuard rule unless the native binary is rebuilt with matching new JNI symbols.

The V2Ray AAR must be processed by `tools/patch_v2ray_seq.py` so its Go sequence loader calls the current Atom `GoJniLoader.load()` entry point. Original AARs are inputs; the Gradle tasks generate the patched runtime outputs.

## 9. Data migration

The engine copies existing preferences and learned files into Atom-named locations on first use. Legacy data is retained after copying. This migration is intentional and must not be removed without a backward-compatibility plan.

## 10. Validation boundary

A successful CI build validates source compilation, native build integration, APK structure, manifest, alignment and signing. It does not establish that the tunnel connects successfully on a physical device. Before merging the rename branch, test VPN permission, connection, traffic flow, disconnect, process restart, network handover and each supported exit mode on a real device.
