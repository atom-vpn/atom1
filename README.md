# UAC PoW Engine extraction

This repository contains the isolated source and runtime artifacts for the UAC PoW engine extracted from `atom-vpn/POW-Source`.

## Scope

This is an engine extraction, **not an Android application**. No launcher Activity, product UI, app-level manifest, or Atom application shell is included.

The extraction keeps PoW separate from Tor and Xray. The original PoW implementation and source-backed support classes are retained under `engine/src/`. A narrow Android network-fingerprint resolver was added because the source tree imported that API but did not contain its implementation.

## Layout

- `engine/src/main/kotlin/`: PoW engine and source-backed support Kotlin code.
- `engine/src/main/java/`: HEV SOCKS JNI binding.
- `native/`: Aether JNI bridge and CMake integration.
- `vendor/aars/`: original Psiphon and V2Ray AAR inputs.
- `vendor/jniLibs/`: Aether and HEV native libraries.
- `vendor/assets/`: Psiphon server entries asset.
- `integration/`: host contracts and adapter API specification.
- `docs/`: extraction and dependency status.
- `tools/`: local artifact import and AAR patching helpers.

## Current state

All six outstanding PoW runtime artifacts (two AARs, three Aether libraries, and Psiphon server entries) have been transferred to the vendor tree and their byte sizes and Git blob SHA-1 values were verified against the source repository. The four HEV libraries were also transferred and source-verified.

The AARs are the original inputs; the source build pipeline still needs to run the two required patch scripts to generate the isolated/Seq-patched AAR outputs. Aether's existing binaries are present, so rebuilding Aether is not required for consumption. Its Rust source and patched quiche tree have not yet been extracted into this repository.

## Host integration

The consumer must provide a real Android `VpnService`, establish the TUN interface, own the returned `ParcelFileDescriptor`, handle VPN permission and foreground-service lifecycle, and enforce package routing/kill-switch policy.

The host Manifest must register `PowPsiphonService` in process `:uacpow` and `PowPsiphonSidecarService` in process `:uacpow2`. Both services are non-exported. The host must also package `server_entries.txt` as an app asset. See `integration/HOST_CONTRACTS.md`.

## Build status

No Gradle module/build configuration has been added yet, and no Android compile or device test has been run. This branch is not yet a certified standalone build. No APK or app shell is claimed.

Source: https://github.com/atom-vpn/POW-Source
