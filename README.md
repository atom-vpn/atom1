# UAC PoW Engine extraction

This repository contains an isolated extraction of the UAC PoW engine from `atom-vpn/POW-Source`.

## Scope

This is an engine-source extraction, **not an Android application**. No launcher Activity, app theme, product UI, app-level manifest, or Atom application shell is created here.

The extraction keeps the PoW implementation separate from Tor, Xray, and UAC Direct. Shared Android integration points are documented as host contracts rather than replaced with fake implementations.

## Layout

- `engine/src/main/kotlin/com/uacspoofer/mobile/engine/pow/`: original PoW engine sources.
- `native/`: PoW JNI and native build integration.
- `vendor/`: third-party runtime artifacts required by the engine, where repository transfer permits.
- `integration/`: explicit host contracts still required from the consuming VPN application.
- `docs/`: dependency and extraction notes.

## Important

The original engine is not a pure Kotlin library. It depends on Android `VpnService`, a TUN descriptor supplied by the host, SOCKS/TUN relay code, Psiphon, Aether native libraries, Android network protection, and host services/stores. Atom must implement the documented integration contracts before embedding this engine. This repository does not claim that a standalone APK or successful build has been produced.

Source: `https://github.com/atom-vpn/POW-Source`
