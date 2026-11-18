# Extraction status

Source repository: https://github.com/atom-vpn/POW-Source
Source ref: main
Target repository: https://github.com/atom-vpn/atom1
Target ref: pow-standalone

## Transferred source

All 36 Kotlin files under `app/src/main/java/com/uacspoofer/mobile/engine/pow/` were copied without changing their implementation or package declarations into:

`engine/src/main/kotlin/com/uacspoofer/mobile/engine/pow/`

Also copied:
- `app/src/main/java/com/uacspoofer/mobile/engine/tor/HevSocks5Tunnel.java` (the small Java JNI binding used by PowTun2Socks; it is a tunnel binding, not the Tor engine)
- `app/src/main/cpp/aether_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `scripts/isolate_psiphon_aar.py`
- `scripts/patch_v2ray_seq.py`

The PoW coordinator, Psiphon services/IPC, Aether bridge, key bootstrap, route selection, recovery, watchdog, SOCKS/TUN relay, settings and status sources are included.

## Not yet self-contained

The following are still external runtime inputs or host integration dependencies:

1. HEV `libhev-socks5-tunnel.so` for arm64-v8a, armeabi-v7a, x86 and x86_64 has been transferred to `vendor/jniLibs/` with source-matching blob IDs.
2. Vendor artifacts still missing: Psiphon AAR (44,395,730 bytes), V2Ray native TUN AAR (58,464,519 bytes), Aether `libaether.so` for three ABIs, and Psiphon `server_entries.txt` (1,150,802 bytes). Their exact source Git blob IDs and sizes are recorded in `../vendor/ARTIFACTS.md`. The available connector returned empty content for these larger binary/text assets, so no zero-byte placeholders were committed.
3. Host APIs: Android VPN service/socket protection, app logging, network fingerprinting, domestic-direct routing, connection state, pause state, network guard, advanced settings and TUN statistics. See `../integration/HOST_CONTRACTS.md`.

## No application shell

No Activity, app Manifest, app theme, app navigation, Tor/Xray/Direct engine, or APK build configuration was added. This branch is the PoW engine extraction layer only.

## Build status

Not built. The missing binary artifacts and host adapters prevent a truthful claim of an independent compilable module at this stage. The next extraction pass must bring in the vendor artifacts and replace the listed host dependencies with a small explicit adapter API.
