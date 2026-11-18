# Extraction status

Source repository: https://github.com/atom-vpn/POW-Source
Source ref: main
Target repository: https://github.com/atom-vpn/atom1
Target ref: pow-standalone

## Transferred source

All 36 Kotlin files under `app/src/main/java/com/uacspoofer/mobile/engine/pow/` were copied without changing their implementation or package declarations into:

`engine/src/main/kotlin/com/uacspoofer/mobile/engine/pow/`

Also copied:
- `app/src/main/java/com/uacspoofer/mobile/engine/tor/HevSocks5Tunnel.java` (the Java JNI binding used by PowTun2Socks; it is a tunnel binding, not the Tor engine)
- `app/src/main/cpp/aether_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt` (with its imported Aether library path adapted to this repository's vendor tree)
- `scripts/isolate_psiphon_aar.py`
- `scripts/patch_v2ray_seq.py`

Source-backed support classes for connection state, logging, tunnel statistics, pause state, socket protection, network guard, advanced settings, domestic-direct routing, GeoAssets and direct-domain whitelist are also present under `engine/src/main/kotlin/com/uacspoofer/mobile/`.

The missing imported `vpn/NetworkFingerprintResolver` implementation was not present in the checked-in source tree. A new narrow Android implementation was added at `engine/src/main/kotlin/com/uacspoofer/mobile/vpn/NetworkFingerprintResolver.kt`; it is not a source-identical copy and has not been device-tested.

## Vendor artifact transfer

All six outstanding artifacts have now been transferred into the destination vendor tree and their byte sizes and Git blob SHA-1 values were verified against the source repository:
- Psiphon tunnel AAR
- V2Ray native TUN AAR
- Aether `libaether.so` for arm64-v8a, armeabi-v7a and x86_64
- Psiphon `server_entries.txt`

The four HEV `libhev-socks5-tunnel.so` ABI variants were also transferred and source-verified. Exact source blob IDs and sizes are recorded in `../vendor/ARTIFACTS.md`.

The AARs currently stored under `vendor/aars/` are the original inputs. Before runtime packaging, run the included patch scripts:
- Psiphon: remove embedded `go/` classes and rename `libgojni.so` to `libgopsi.so`.
- V2Ray: patch `go/Seq.class` to use `GoJniLoader.load()`.

No Maven replacement coordinates were verified for these AARs. Do not substitute similarly named public artifacts.

## Remaining integration work

1. Add a proper Android library Gradle module and its dependency declarations. The repository currently has no root Gradle project or `engine/build.gradle.kts`.
2. Wire the two AAR patch tasks and package their generated outputs.
3. Package `vendor/assets/server_entries.txt` as the app asset `server_entries.txt`.
4. Package `vendor/jniLibs/` for the supported ABIs and build the Aether JNI bridge through the corrected `native/CMakeLists.txt`.
5. The consuming app must register both Psiphon services in its Manifest:
   - `PowPsiphonService`, process `:uacpow`
   - `PowPsiphonSidecarService`, process `:uacpow2`
6. The consuming app must supply a real `VpnService`, TUN establishment callback, permission flow, foreground-service lifecycle/notification, package routing, kill-switch enforcement and descriptor ownership/closure.
7. Validate the new network-fingerprint resolver on real Wi-Fi, cellular, dual-SIM and permission-restricted states.

## Aether rebuild status

The prebuilt Aether libraries for the three source-supported ABIs are present. The source Rust crate and patched quiche tree are not yet copied into this repository, so rebuilding Aether from this destination is not currently possible. The source build script is Windows/PowerShell-specific and writes into the source repository's original paths; it will need adaptation if source rebuild support is later required.

## No application shell

No Activity, app Manifest, app theme, app navigation, Tor/Xray/Direct engine, or APK configuration was added. This branch remains an engine extraction layer.

## Build status

Not built. No Gradle module configuration has been added, no Android compile has been run, and no device validation has been performed. Do not treat the extracted source as a certified standalone build yet.

## Support dependency extraction (2026-11-18)

Important source findings:
- `AdvancedSettingsData` and `CONNECTION_MODE_PROXY` are declared in `settings/AdvancedSettingsStore.kt`.
- `LogSource` is declared in `logging/AppLogRepository.kt`.
- `DomesticDirect` depends on GeoAssets and DirectDomainWhitelist; it cannot be replaced by a boolean-only placeholder.
- The real host boundary is `PowConnectionCoordinator(VpnService)` plus a TUN establishment callback returning `ParcelFileDescriptor?`.
- Carrier classification may require `READ_PHONE_STATE`; without access, the resolver reports unknown identity fields.

No fake VPN service or TUN implementation was added. The original 36 PoW engine files remain byte-identical; the resolver is a new implementation. No APK or build-success claim is made.
