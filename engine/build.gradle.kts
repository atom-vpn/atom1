plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.atomvpn.mobile.engine"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }

    val flatEngineDir = layout.buildDirectory.dir("generated/engine-aar-flat")
    sourceSets {
        getByName("main") {
            manifest.srcFile(flatEngineDir.map { it.file("AndroidManifest.xml") })
            assets.srcDirs(rootProject.file("vendor/assets"), flatEngineDir.map { it.dir("assets") })
            res.srcDir(flatEngineDir.map { it.dir("res") })
            jniLibs.srcDirs(rootProject.file("vendor/jniLibs"), flatEngineDir.map { it.dir("jniLibs") })
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val generatedEngineAars = layout.buildDirectory.dir("generated/engine-aars")
val flattenedEngineAars = layout.buildDirectory.dir("generated/engine-aar-flat")
val psiphonInput = rootProject.file("vendor/aars/psiphontunnel-2.0.39.aar")
val v2rayInput = rootProject.file("vendor/aars/libv2ray-native-tun.aar")
val isolatedPsiphonFile = generatedEngineAars.map { it.file("psiphontunnel-isolated.aar") }
val patchedV2rayFile = generatedEngineAars.map { it.file("libv2ray-seqpatched.aar") }

val isolatePsiphonAar = tasks.register<Exec>("isolatePsiphonAar") {
    val script = rootProject.file("tools/isolate_psiphon_aar.py")
    inputs.file(psiphonInput)
    inputs.file(script)
    outputs.file(isolatedPsiphonFile)
    commandLine("python", script.absolutePath)
    environment("ATOM_PSIPHON_SRC", psiphonInput.absolutePath)
    environment("ATOM_PSIPHON_DST", isolatedPsiphonFile.get().asFile.absolutePath)
}

val patchV2raySeqAar = tasks.register<Exec>("patchV2raySeqAar") {
    val script = rootProject.file("tools/patch_v2ray_seq.py")
    inputs.file(v2rayInput)
    inputs.file(script)
    outputs.file(patchedV2rayFile)
    commandLine("python", script.absolutePath)
    environment("ATOM_V2RAY_SRC", v2rayInput.absolutePath)
    environment("ATOM_V2RAY_DST", patchedV2rayFile.get().asFile.absolutePath)
}

val flattenEngineAars = tasks.register<Exec>("flattenEngineAars") {
    val script = rootProject.file("tools/flatten_engine_aars.py")
    inputs.file(isolatedPsiphonFile)
    inputs.file(patchedV2rayFile)
    inputs.file(script)
    inputs.file("src/main/AndroidManifest.xml")
    outputs.dir(flattenedEngineAars)
    dependsOn(isolatePsiphonAar, patchV2raySeqAar)
    commandLine("python", script.absolutePath)
    environment("ATOM_PSIPHON_AAR", isolatedPsiphonFile.get().asFile.absolutePath)
    environment("ATOM_V2RAY_AAR", patchedV2rayFile.get().asFile.absolutePath)
    environment("ATOM_ENGINE_FLAT_OUT", flattenedEngineAars.get().asFile.absolutePath)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation(files(flattenedEngineAars.map { it.file("libs/psiphon-classes.jar") }))
    implementation(files(flattenedEngineAars.map { it.file("libs/v2ray-classes.jar") }))
    implementation(fileTree(flattenedEngineAars.map { it.dir("libs") }) { include("*.jar") })
}

tasks.named("preBuild").configure {
    dependsOn(flattenEngineAars)
}
