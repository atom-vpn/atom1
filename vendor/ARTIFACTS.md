# Atom runtime artifacts

The vendor tree contains the prebuilt runtime inputs consumed by the Android engine:

- Psiphon and V2Ray AAR inputs under `vendor/aars/`
- Aether and HEV shared libraries under `vendor/jniLibs/`
- Psiphon server entries under `vendor/assets/server_entries.txt`

The AARs are treated as immutable inputs. Gradle pre-build tasks create isolated and patched copies under the engine build directory; generated outputs are not committed back over the vendor inputs.

## Build preparation

- `tools/isolate_psiphon_aar.py` removes embedded Go classes from the Psiphon AAR and isolates its Go runtime library.
- `tools/patch_v2ray_seq.py` updates the V2Ray sequence loader to call the Atom `GoJniLoader`.
- `tools/flatten_engine_aars.py` extracts the patched AAR contents into the engine module's generated inputs.

The native binaries are consumed as prebuilt dependencies. Rebuilding Aether or HEV requires their corresponding native source trees and a separately validated native build process.

## Compatibility

The HEV binary retains its existing JNI symbol contract. The application calls it through `AtomHevSocks5Tunnel`, which delegates to the retained bridge class. Do not rename or remove that bridge unless the native library is rebuilt with matching JNI symbols.
