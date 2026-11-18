# UAC PoW Engine extraction

This repository contains an isolated extraction of the UAC PoW engine from `atom-vpn/POW-Source`.

## Scope

This is an engine-source extraction, **not an Android application**. No launcher Activity, app theme, product UI, app-level manifest, or Atom application shell is created here.

The extraction keeps the PoW implementation separate from Tor and Xray. Source-backed support code used by PoW has been copied into the engine tree; the missing narrow network-fingerprint resolver has a dedicated Android implementation. The real VPN/TUN service boundary remains a host responsibility and is documented rather than faked.

## Layout

- `engine/src/main/kotlin/com/uacspoofer/mobile/engine/pow/`: original PoW engine sources.
- `native/`: PoW JNI and native build integration.
- `vendor/`: third-party runtime artifacts required by the engine, where repository transfer permits.
- `integration/`: explicit host contracts and adapter API specification for the consuming VPN application.
- `docs/`: dependency and extraction notes.

## Important

The original engine is not a pure Kotlin library. It depends on Android `VpnService`, a TUN descriptor supplied by the host, SOCKS/TUN relay code, Psiphon, Aether native libraries, Android network protection, and host services/stores. Atom must supply a real `VpnService`, TUN establishment callback, VPN permission flow and foreground-service lifecycle before embedding this engine. Required vendor binaries are still missing. No standalone APK or successful build is claimed.

Source: `https://github.com/atom-vpn/POW-Source`
