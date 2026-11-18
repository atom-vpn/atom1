plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.uacspoofer.mobile"
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

    val flattenedPowAars = layout.buildDirectory.dir("generated/pow-aar-flat")
    sourceSets {
        getByName("main") {
            manifest.srcFile(flattenedPowAars.map { it.file("AndroidManifest.xml") })
            assets.srcDirs(rootProject.file("vendor/assets"), flattenedPowAars.map { it.dir("assets") })
            res.srcDir(flattenedPowAars.map { it.dir("res") })
            jniLibs.srcDirs(rootProject.file("vendor/jniLibs"), flattenedPowAars.map { it.dir("jniLibs") })
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

val generatedPowAars = layout.buildDirectory.dir("generated/pow-aars")
val flattenedPowAars = layout.buildDirectory.dir("generated/pow-aar-flat")
val psiphonInput = rootProject.file("vendor/aars/psiphontunnel-2.0.39.aar")
val v2rayInput = rootProject.file("vendor/aars/libv2ray-native-tun.aar")

val isolatePsiphonAar = tasks.register<Exec>("isolatePsiphonAar") {
    val output = generatedPowAars.map { it.file("psiphontunnel-isolated.aar") }
    val script = rootProject.file("tools/isolate_psiphon_aar.py")
    inputs.file(psiphonInput)
    inputs.file(script)
    outputs.file(output)
    commandLine("python", script.absolutePath)
    environment("POW_PSIPHON_SRC", psiphonInput.absolutePath)
    environment("POW_PSIPHON_DST", output.get().asFile.absolutePath)
}

val patchV2raySeqAar = tasks.register<Exec>("patchV2raySeqAar") {
    val output = generatedPowAars.map { it.file("libv2ray-seqpatched.aar") }
    val script = rootProject.file("tools/patch_v2ray_seq.py")
    inputs.file(v2rayInput)
    inputs.file(script)
    outputs.file(output)
    commandLine("python", script.absolutePath)
    environment("POW_V2RAY_SRC", v2rayInput.absolutePath)
    environment("POW_V2RAY_DST", output.get().asFile.absolutePath)
}

val flattenPowAars = tasks.register<Exec>("flattenPowAars") {
    val script = rootProject.file("tools/flatten_pow_aars.py")
    inputs.file(isolatePsiphonAar)
    inputs.file(patchV2raySeqAar)
    inputs.file(script)
    inputs.file("src/main/AndroidManifest.xml")
    outputs.dir(flattenedPowAars)
    dependsOn(isolatePsiphonAar, patchV2raySeqAar)
    commandLine("python", script.absolutePath)
    environment("POW_PSIPHON_AAR", generatedPowAars.get().file("psiphontunnel-isolated.aar").asFile.absolutePath)
    environment("POW_V2RAY_AAR", generatedPowAars.get().file("libv2ray-seqpatched.aar").asFile.absolutePath)
    environment("POW_FLAT_OUT", flattenedPowAars.get().asFile.absolutePath)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation(files(flattenedPowAars.map { it.file("libs/psiphon-classes.jar") }))
    implementation(files(flattenedPowAars.map { it.file("libs/v2ray-classes.jar") }))
    implementation(fileTree(flattenedPowAars.map { it.dir("libs") }) { include("*.jar") })
}

tasks.named("preBuild").configure {
    dependsOn(flattenPowAars)
}
