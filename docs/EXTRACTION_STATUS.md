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
- The prebuilt HEV library retains its original JNI bridge class. `AtomHevSocks5Tunnel` is the Atom-facing wrapper; the bridge is kept only for binary compatibility.

## Data compatibility

Legacy engine preferences, MTU/DNS cache preferences, identity material and learned route files are copied into Atom-named locations on first use. The legacy values are not deleted during migration.

## Validation boundary

The rename is performed on `atom-engine-rename`; the stable application branch remains unchanged. CI build success validates compilation and APK packaging only. It does not prove that the VPN tunnel connects on a physical device. A real-device connection test is required before merging this branch.
