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

    sourceSets {
        getByName("main") {
            assets.srcDirs(rootProject.file("vendor/assets"))
            jniLibs.srcDirs(rootProject.file("vendor/jniLibs"))
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

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation(files(isolatePsiphonAar.map { it.outputs.files }))
    implementation(files(patchV2raySeqAar.map { it.outputs.files }))
}

tasks.named("preBuild").configure {
    dependsOn(isolatePsiphonAar, patchV2raySeqAar)
}
