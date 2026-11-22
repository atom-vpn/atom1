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

The HEV native library is rebuilt from a pinned upstream revision with the JNI package and class bound directly to `com.atomvpn.mobile.engine.tor.AtomHevSocks5Tunnel`. The Atom Java class declares the native methods directly; the previous Java forwarding bridge is not required at runtime. The build script writes HEV outputs to a temporary directory and copies only the HEV library into `vendor/jniLibs`, preserving the separately packaged Aether libraries.

The V2Ray AAR patcher redirects its Go sequence loader to the current Atom `GoJniLoader` class. Psiphon and V2Ray runtime libraries continue to be packaged from the verified vendor inputs.

## Validation

GitHub Actions workflow: [Atom VPN Android Build](https://github.com/atom-vpn/atom1/actions/workflows/atom-engine-build.yml)

The HEV JNI rebuild and APK packaging are covered by the GitHub Actions workflow. PR #4 was merged into `pow-standalone` after the ARM64 APK passed native-library validation and a real-device VPN connection test. Post-merge CI is run separately against the merge commit.
