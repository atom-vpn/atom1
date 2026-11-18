# Extraction status

Source repository: https://github.com/atom-vpn/POW-Source
Source ref: main
Target repository: https://github.com/atom-vpn/atom1
Target ref: pow-standalone

## Transferred source

All 36 Kotlin files under `app/src/main/java/com/uacspoofer/mobile/engine/pow/` were copied without changing their implementation or package declarations into:

`engine/src/main/kotlin/com/uacspoofer/mobile/engine/pow/`

Also copied:
- `app/src/main/java/com/uacspoofer/mobile/engine/tor/HevSocks5Tunnel.java` (the small Java JNI binding used by PowTun2Socks; it is a tunnel binding, not the Tor engine)
- `app/src/main/cpp/aether_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `scripts/isolate_psiphon_aar.py`
- `scripts/patch_v2ray_seq.py`

The PoW coordinator, Psiphon services/IPC, Aether bridge, key bootstrap, route selection, recovery, watchdog, SOCKS/TUN relay, settings and status sources are included.

## Not yet self-contained

The following are still external runtime inputs or host integration dependencies:

1. HEV `libhev-socks5-tunnel.so` for arm64-v8a, armeabi-v7a, x86 and x86_64 has been transferred to `vendor/jniLibs/` with source-matching blob IDs.
2. Vendor artifacts still missing: Psiphon AAR (44,395,730 bytes), V2Ray native TUN AAR (58,464,519 bytes), Aether `libaether.so` for three ABIs, and Psiphon `server_entries.txt` (1,150,802 bytes). Their exact source Git blob IDs and sizes are recorded in `../vendor/ARTIFACTS.md`. The available connector returned empty content for these larger binary/text assets, so no zero-byte placeholders were committed.
3. Remaining host integration: a real Android `VpnService`, TUN establishment callback, foreground-service lifecycle, VPN permission/notification handling, package routing and kill-switch enforcement. Source-backed support classes are now copied into `engine/`; see `../integration/HOST_CONTRACTS.md`.

## No application shell

No Activity, app Manifest, app theme, app navigation, Tor/Xray/Direct engine, or APK build configuration was added. This branch is the PoW engine extraction layer only.

## Build status

Not built. The missing vendor artifacts and real Android VPN/TUN host boundary prevent a truthful claim of an independent compilable module at this stage.

## Support dependency extraction (2026-11-18)

The source-backed support classes used by the coordinator have now been copied into the engine source tree: connection state, logging, tunnel statistics, pause state, socket-protection gate, network guard, advanced settings, domestic-direct routing, GeoAssets, direct-domain whitelist, and MCI data types. Exact source blob IDs were retained for these copied files.

Important findings:
- `AdvancedSettingsData` and `CONNECTION_MODE_PROXY` are declared in `settings/AdvancedSettingsStore.kt`, not in a separate `AdvancedSettingsData.kt`.
- `LogSource` is declared in `logging/AppLogRepository.kt`, not in a separate `LogSource.kt`.
- The checked-in source tree does not contain the imported `vpn/NetworkFingerprintResolver` implementation. A new narrowly scoped Android resolver was added at `engine/src/main/kotlin/com/uacspoofer/mobile/vpn/NetworkFingerprintResolver.kt`; it exposes only the three fields consumed by `PowCarrier`. It is not a source-identical copy and has not been device-tested.
- The real host boundary is `PowConnectionCoordinator(VpnService)` plus a TUN establishment callback returning `ParcelFileDescriptor?`. This must be supplied by Atom's VPN service; it cannot be replaced with a stub. Carrier classification may require the host to declare and obtain runtime `READ_PHONE_STATE` permission; without access the resolver deliberately returns unknown identity fields.
- `DomesticDirect` depends on GeoAssets and DirectDomainWhitelist, so its routing behavior cannot be represented by a boolean-only placeholder.
- The adapter API specification remains at `integration/ADAPTER_API_SPEC.md`; the host boundary still requires a real `VpnService` and TUN callback.

No fake VPN service or TUN implementation was added. The original 36 PoW engine files remain byte-identical; the new resolver is explicitly a new implementation. No APK, app shell, or compile-claiming build files were added. A build has not been run.
