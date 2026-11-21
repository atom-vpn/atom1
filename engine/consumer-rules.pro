# JNI entry points are resolved by native symbol names.
-keep class com.atomvpn.mobile.engine.atom.AetherNative { *; }
# Compatibility bridge: the bundled HEV binary exports JNI symbols for this legacy class.
-keep class com.uacspoofer.mobile.engine.tor.HevSocks5Tunnel { *; }

# The patched gomobile Seq class calls this loader by its stable class/method name.
-keep class com.atomvpn.mobile.engine.atom.GoJniLoader { *; }

# Services are launched by explicit component class names and registered in the library manifest.
-keep class com.atomvpn.mobile.engine.atom.AtomPsiphonService { *; }
-keep class com.atomvpn.mobile.engine.atom.AtomPsiphonSidecarService { *; }
