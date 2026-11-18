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

The source tree also contains Tor, WebTunnel and Xray libraries. They are intentionally excluded from this PoW extraction.

## Transfer limitation

These are binary or >1 MB artifacts. The available GitHub file-write action accepts UTF-8 text files, so these files have not been fabricated, truncated, or replaced with placeholders. Transfer them with Git clone/Git LFS or a binary-capable upload path, then verify the resulting content against the listed source blob IDs.
