# Required PoW runtime artifacts

The following artifacts were identified from the source repository tree. Their original Git blob IDs are included so they can be verified and transferred from the source repository without guessing.

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
| Psiphon server entries | `app/src/main/assets/server_entries.txt` | 1,150,802 | `bb99c2504a1e5324b79cf43c9665434c4658972f` |

## Transfer status

- HEV SOCKS tunnel libraries for arm64-v8a, armeabi-v7a, x86 and x86_64 are now present under `vendor/jniLibs/` and their Git blob IDs match the source repository exactly.
- Aether `.so` files, both AARs, and `server_entries.txt` remain untransferred. The connector returned empty content for these large artifacts; no zero-byte placeholders were added to the vendor tree.

The source tree also contains Tor, WebTunnel and Xray libraries. They are intentionally excluded from this PoW extraction.

## Transfer limitation

These are binary or >1 MB artifacts. The available GitHub file-write action accepts UTF-8 text files, so these files have not been fabricated, truncated, or replaced with placeholders. Transfer them with Git clone/Git LFS or a binary-capable upload path, then verify the resulting content against the listed source blob IDs.


## Dependency and rebuild findings (2026-11-18)

- The source repository has no published GitHub Releases, and the two AARs are stored only as local files under `app/libs/`. The source Gradle file declares them through local-file tasks, not Maven coordinates:
  - `psiphontunnel-2.0.39.aar` is passed through `scripts/isolate_psiphon_aar.py`, which removes the embedded `go/` package from `classes.jar` and renames `libgojni.so` to `libgopsi.so`.
  - `libv2ray-native-tun.aar` is passed through `scripts/patch_v2ray_seq.py`, which rewrites `go/Seq.class` to call the PoW `GoJniLoader.load()` implementation instead of `System.loadLibrary("gojni")`.
- No verified Maven replacement coordinates were found in the source Gradle dependency declarations. Replacing either AAR with a similarly named public artifact would not preserve these required binary transformations.
- Aether is not an external prebuilt Maven dependency. The source declares a Rust `aether` crate (version 1.6.0) with a local patched `quiche` dependency and builds `libaether.so` for Android using `core/build-android.ps1`. Rebuilding it requires the matching Rust source tree, patched quiche source, Rust Android targets, Android NDK and native build toolchain. The checked-in script is Windows/PowerShell-specific; a Linux CI build script has not yet been validated.
- The source repository currently has no `.github/workflows/` directory and no GitHub Release assets to use as a binary transfer source.
- The current GitHub connector exposes text-file writes and Git object writes, but no binary upload/release-asset action. Its large-file reads returned empty content in prior attempts. Therefore the remaining binary files are still blocked on a binary-capable transfer route (for example, a local Git clone/push or a properly configured Git LFS workflow). No placeholder blobs have been committed.

### Required next actions

1. Transfer the exact source AARs and `server_entries.txt` through a binary-capable path, then verify byte size and Git blob SHA.
2. Rebuild Aether from the exact source revision and patched quiche tree for each required ABI, or transfer the existing binaries and verify them against the source blob IDs.
3. Only after those artifacts are present should the standalone Android library build be wired and compiled.


## Local binary import helper

A Windows PowerShell importer is available at `tools/import_pow_artifacts.ps1`. It copies the six outstanding artifacts from a local clone of `POW-Source` into this repository, verifies each source Git blob ID and byte size, then verifies the copied destination blob ID. It refuses to run against a dirty destination worktree or a destination branch other than `pow-standalone`.

From a PowerShell session, with both repositories cloned locally:

```powershell
.\tools\import_pow_artifacts.ps1 -SourceRepo "C:\src\POW-Source" -DestinationRepo "C:\src\atom1"
```

After reviewing the copied files, rerun with `-CommitAndPush` to commit and push them to `origin/pow-standalone`:

```powershell
.\tools\import_pow_artifacts.ps1 -SourceRepo "C:\src\POW-Source" -DestinationRepo "C:\src\atom1" -CommitAndPush
```

The helper does not build Aether, patch the AARs, alter `main`, or create an APK. It only transfers and verifies the original binary inputs.
