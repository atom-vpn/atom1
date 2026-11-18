# UAC PoW Engine extraction

This repository contains the isolated source and runtime artifacts for the UAC PoW engine extracted from `atom-vpn/POW-Source`.

## Scope

This is an Android **library module**, not an Android application. No launcher Activity, product UI, app-level shell, or APK target is included.

The extraction keeps PoW separate from Tor and Xray. The original PoW implementation and source-backed support classes are retained under `engine/src/`. A narrow Android network-fingerprint resolver was added because the source tree imported that API but did not contain its implementation.

## Layout

- `engine/`: Android library module and PoW Kotlin/Java sources.
- `native/`: Aether JNI bridge and CMake integration.
- `vendor/aars/`: original Psiphon and V2Ray AAR inputs.
- `vendor/jniLibs/`: Aether and HEV native libraries.
- `vendor/assets/`: Psiphon server entries asset.
- `integration/`: host contracts and adapter API specification.
- `docs/`: extraction and dependency status.
- `tools/`: local artifact import and AAR patching helpers.

## Build configuration

The standalone Gradle project now includes only `:engine` as an Android library. It uses AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, compileSdk 35, minSdk 24, Java 17, NDK 26.3.11579264 and CMake 3.22.1.

The module wires the AAR patch scripts as pre-build tasks, packages the vendor assets and JNI libraries, and declares the Psiphon services in its library manifest. The wrapper scripts and wrapper JAR are not yet included; the wrapper properties file is present.

## Current state

All six outstanding PoW runtime artifacts (two AARs, three Aether libraries, and Psiphon server entries) have been transferred to the vendor tree and their byte sizes and Git blob SHA-1 values were verified against the source repository. The four HEV libraries were also transferred and source-verified.

The AARs are original inputs; the Gradle pre-build tasks are configured to generate the isolated/Seq-patched outputs. These tasks and the Android library build have not yet been run.

## Host integration

The consuming app must provide a real Android `VpnService`, establish the TUN interface, own the returned `ParcelFileDescriptor`, handle VPN permission and foreground-service lifecycle, and enforce package routing/kill-switch policy.

The library manifest registers `PowPsiphonService` in process `:uacpow` and `PowPsiphonSidecarService` in process `:uacpow2`. Both services are non-exported. The module packages `server_entries.txt` as an app asset. See `integration/HOST_CONTRACTS.md`.

## Build status

Gradle module configuration is now present, but no Gradle task, Android compile, or device test has been run. This is not yet a certified standalone build. No APK or app shell is claimed.

Source: https://github.com/atom-vpn/POW-Source
