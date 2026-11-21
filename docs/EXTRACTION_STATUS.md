# Atom engine integration status

## Current structure

The project is a standalone Android application with two Gradle modules:

- `app/`: Atom VPN application, Compose interface and Android VPN service.
- `engine/`: Atom connection engine and supporting services.
- `native/`: Aether JNI bridge and native build configuration.
- `vendor/`: verified runtime AARs, native libraries and Psiphon assets.

Engine implementation packages are under `com.atomvpn.mobile.engine`; the connection engine is under `com.atomvpn.mobile.engine.atom`. Engine source files and class names have been moved to the Atom namespace.

## Native integration

- Aether JNI entry points in `native/aether_jni.cpp` match `com.atomvpn.mobile.engine.atom.AetherNative`.
- The V2Ray AAR patcher targets the Atom `GoJniLoader` class.
- Psiphon runs in isolated processes `:atomengine` and `:atomengine2`.
- HEV is rebuilt from a pinned upstream revision with JNI symbols bound directly to `com.atomvpn.mobile.engine.tor.AtomHevSocks5Tunnel`; the UAC-era Java bridge is not part of the runtime binding.

## Data compatibility

Legacy engine preferences, MTU/DNS cache preferences, identity material and learned route files are copied into Atom-named locations on first use. Legacy preference values and identity/cache sources are retained for compatibility. The one-time connect-order reset deliberately clears only obsolete last-connected peer hints, including their UAC-era source files, so lazy migration cannot restore stale hints.

## Validation boundary

PR #4 (Atom-owned HEV JNI binding and safe native-library packaging) is merged into `pow-standalone`. The ARM64 APK passed native-library validation and a real-device VPN connection test. Post-merge CI validates the merge commit separately; compilation and APK packaging alone do not replace device-level testing.
