# Atom VPN for Android

Standalone Android VPN application integrating the Atom connection engine.

## Modules

- `app/`: Atom VPN application, Compose interface, VPN service and launcher resources.
- `engine/`: connection lifecycle, routing, Psiphon/Aether integration, settings and network helpers.
- `native/`: Aether JNI bridge and CMake configuration.
- `vendor/aars/`: original Psiphon and V2Ray AAR inputs.
- `vendor/jniLibs/`: Aether and HEV native libraries.
- `vendor/assets/`: Psiphon server entries asset.
- `tools/`: AAR preparation and packaging scripts.
- `integration/`: native and Android integration contracts.

## Android identity

- Application ID: `com.atomvpn.mobile`
- Engine namespace: `com.atomvpn.mobile.engine`
- Engine implementation package: `com.atomvpn.mobile.engine.atom`
- Isolated Psiphon processes: `:atomengine` and `:atomengine2`

The engine class names, source paths, manifest components, JNI entry points and build tooling use Atom terminology. The connection algorithms and tunnel orchestration are intended to remain unchanged by this naming refactor.

## Compatibility safeguards

Existing engine preferences and files are migrated to Atom-named storage locations on first use. Legacy values are copied rather than deleted, so user settings, identity material and learned route data remain available.

The bundled HEV native library exports JNI symbols for its original Java bridge class. An Atom-facing wrapper delegates to that bridge; this compatibility layer is internal and does not change the tunnel API or packet path.

The V2Ray AAR patcher redirects its Go sequence loader to the current Atom `GoJniLoader` class. Psiphon and V2Ray runtime libraries continue to be packaged from the verified vendor inputs.

## Validation

GitHub Actions workflow: [Atom VPN Android Build](https://github.com/atom-vpn/atom1/actions/workflows/atom-engine-build.yml)

A successful Android build validates compilation, APK structure, manifest, alignment and signing. It does not by itself prove that a VPN tunnel connects on a physical device. Device-level connection tests are still required before merging the rename branch into the stable application branch.
