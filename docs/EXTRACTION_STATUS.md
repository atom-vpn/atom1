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

All six outstanding artifacts have been transferred into the destination vendor tree and their byte sizes and Git blob SHA-1 values were verified against the source repository. The four HEV ABI variants were also transferred and source-verified. Exact source blob IDs and sizes are recorded in `../vendor/ARTIFACTS.md`.

The AARs under `vendor/aars/` are original inputs. The Android library pre-build tasks are configured to run:
- Psiphon isolation: remove embedded `go/` classes and rename `libgojni.so` to `libgopsi.so`.
- V2Ray Seq patch: rewrite `go/Seq.class` to use `GoJniLoader.load()`.

These tasks have not yet been executed. No Maven replacement coordinates were verified; do not substitute similarly named public artifacts.

## Standalone Android library module

A Gradle project skeleton has been added:
- Root project includes only `:engine`.
- `engine/build.gradle.kts` applies `com.android.library` and `org.jetbrains.kotlin.android`.
- Toolchain: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, compileSdk 35, minSdk 24, Java 17, NDK 26.3.11579264, CMake 3.22.1.
- Runtime dependencies: coroutines-android 1.9.0, Bouncy Castle bcpkix 1.78.1, and the two locally patched AARs.
- Vendor assets and JNI libraries are wired as Android source sets.
- The library manifest declares the two non-exported Psiphon services in processes `:uacpow` and `:uacpow2`, plus network and optional telephony permissions.
- Consumer keep rules protect JNI entry points and service classes.

The Gradle wrapper properties file is present, but `gradlew`, `gradlew.bat` and `gradle-wrapper.jar` have not yet been copied into this repository. No Gradle task or Android compile has been run, so the module configuration remains unvalidated.

## Remaining integration work

1. Run the two AAR patch tasks and verify the resulting archives.
2. Compile the Android library module and resolve any Kotlin, Gradle, CMake or JNI packaging errors.
3. The consuming app must supply a real `VpnService`, TUN establishment callback, permission flow, foreground-service lifecycle/notification, package routing, kill-switch enforcement and descriptor ownership/closure.
4. Validate the new network-fingerprint resolver on real Wi-Fi, cellular, dual-SIM and permission-restricted states.
5. Verify runtime startup of both Psiphon processes, Aether, HEV SOCKS tunnel and all three supported Aether ABIs.

## Aether rebuild status

The prebuilt Aether libraries for the three source-supported ABIs are present. The source Rust crate and patched quiche tree are not yet copied into this repository, so rebuilding Aether from this destination is not currently possible. The source build script is Windows/PowerShell-specific and writes into the source repository's original paths; it will need adaptation if a source rebuild is later required.

## No application shell

No Activity, app theme, app navigation, Tor/Xray/Direct engine, or APK target was added. This branch contains only the standalone Android library module and its extraction artifacts.

## Build status

Not built. No Gradle task, Android compile or device validation has been performed. Do not treat the module as a certified standalone build yet.

## Support dependency findings

- `AdvancedSettingsData` and `CONNECTION_MODE_PROXY` are declared in `settings/AdvancedSettingsStore.kt`.
- `LogSource` is declared in `logging/AppLogRepository.kt`.
- `DomesticDirect` depends on GeoAssets and DirectDomainWhitelist; it cannot be replaced by a boolean-only placeholder.
- `GeoAssets` first looks for `geoip.dat` and `geosite.dat` in app assets, then manages copies under app files and can download them from its configured upstream.
- The real host boundary is `PowConnectionCoordinator(VpnService)` plus a TUN establishment callback returning `ParcelFileDescriptor?`.
- Carrier classification may require `READ_PHONE_STATE`; without access, the resolver reports unknown identity fields.

No fake VPN service or TUN implementation was added. The original 36 PoW engine files remain byte-identical; the resolver is a new implementation. No APK or build-success claim is made.
