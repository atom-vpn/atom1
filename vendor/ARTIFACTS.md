# Required PoW runtime artifacts

The following artifacts were identified from the source repository. Their original Git blob IDs are recorded for exact verification.

Source: https://github.com/atom-vpn/POW-Source (main)

| Artifact | Source path | Size (bytes) | Git blob SHA |
|---|---|---:|---|
| Psiphon tunnel AAR | `app/libs/psiphontunnel-2.0.39.aar` | 44,395,730 | `ab140ff46966e1097441b1342b74ec79e83efb24` |
| V2Ray native TUN AAR | `app/libs/libv2ray-native-tun.aar` | 58,464,519 | `4c75dc51233408d47bc083a83a3f47cb0e1c644f` |
| Aether arm64 | `app/src/main/jniLibs/arm64-v8a/libaether.so` | 9,518,480 | `10b89f17d9c4a234fff191cf456a5e8f54568f4f` |
| Aether armeabi-v7a | `app/src/main/jniLibs/armeabi-v7a/libaether.so` | 6,217,124 | `4a3b255e1d80eadb9318ce921a6f96c343ffe74f` |
| Aether x86_64 | `app/src/main/jniLibs/x86_64/libaether.so` | 10,763,128 | `b75f7ab054826174537adde60086506a9c2d548b` |
| HEV arm64 | `app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so` | 344,568 | `34bf44082cc593df33d10ef7a7bc90f186b5ac8b` |
| HEV armeabi-v7a | `app/src/main/jniLibs/armeabi-v7a/libhev-socks5-tunnel.so` | 233,272 | `54807b6169f73944b8f5f1225fa7c7475937b684` |
| HEV x86 | `app/src/main/jniLibs/x86/libhev-socks5-tunnel.so` | 365,036 | `937c7951612e21afdeb918d852be5014cf557b00` |
| HEV x86_64 | `app/src/main/jniLibs/x86_64/libhev-socks5-tunnel.so` | 343,984 | `bae426b9bde8886711852efad984745197a93761` |
| Psiphon server entries | `app/src/main/assets/server_entries.txt` | 1,151,228 | `bb99c2504a1e5324b79cf43c9665434c4658972f` |

## Transfer status

- All six outstanding artifacts (the two AARs, three Aether shared libraries and Psiphon server entries) are present in the destination vendor tree. Their byte sizes and Git blob SHA-1 values were verified against the source repository.
- All four HEV shared libraries are present under `vendor/jniLibs/` and their blob IDs match the source repository.
- Destination paths: AARs under `vendor/aars/`, Aether and HEV libraries under `vendor/jniLibs/<abi>/`, and Psiphon server entries at `vendor/assets/server_entries.txt`.

## Required AAR transformations

The transferred AARs are original inputs. The source build pipeline transforms them before runtime packaging:
- `psiphontunnel-2.0.39.aar` is processed by `tools/isolate_psiphon_aar.py`: remove embedded `go/` classes from `classes.jar` and rename `libgojni.so` to `libgopsi.so`.
- `libv2ray-native-tun.aar` is processed by `tools/patch_v2ray_seq.py`: rewrite `go/Seq.class` to call `GoJniLoader.load()` instead of `System.loadLibrary("gojni")`.

No verified Maven replacement coordinates were found. Do not replace these AARs with similarly named public artifacts.

## Aether rebuild findings

The source declares a Rust Aether crate (version 1.6.0) with a local patched quiche dependency. The prebuilt Aether shared libraries for the three source-supported ABIs have been transferred, so the engine can consume those binaries without rebuilding them. The Rust crate and patched quiche source tree have not yet been extracted into this repository. The source `core/build-android.ps1` is Windows/PowerShell-specific and writes outputs to the source repository's original paths; it will require adaptation if a source rebuild is later desired.

## Build/packaging status

The repository does not yet contain a root Gradle project or `engine/build.gradle.kts`. The AAR patch tasks, Android library packaging, assets source set, JNI library source set and CMake build still need to be wired into a standalone Gradle library module. No Android compile or device test has been run.

## Local binary import helper

`tools/import_pow_artifacts.ps1` copies the six outstanding artifacts from a local clone of `POW-Source` into the destination vendor tree. It verifies source Git blob IDs and byte sizes before copying, then verifies destination blob IDs. It refuses a dirty destination worktree or a destination branch other than `pow-standalone`.

Example:

```powershell
.\tools\import_pow_artifacts.ps1 -SourceRepo "C:\src\POW-Source" -DestinationRepo "C:\src\atom1"
```

After reviewing the copied files, rerun with `-CommitAndPush` to commit and push to `origin/pow-standalone`. The helper does not build Aether, patch the AARs, alter `main`, or create an APK.
