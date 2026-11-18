# JNI entry points are resolved by native symbol names.
-keep class com.uacspoofer.mobile.engine.pow.AetherNative { *; }
-keep class com.uacspoofer.mobile.engine.tor.HevSocks5Tunnel { *; }

# The patched gomobile Seq class calls this loader by its stable class/method name.
-keep class com.uacspoofer.mobile.engine.pow.GoJniLoader { *; }

# Services are launched by explicit component class names and registered in the library manifest.
-keep class com.uacspoofer.mobile.engine.pow.PowPsiphonService { *; }
-keep class com.uacspoofer.mobile.engine.pow.PowPsiphonSidecarService { *; }
