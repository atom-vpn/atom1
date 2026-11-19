plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.atomvpn.mobile"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.atomvpn.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // Publish a broad-compatibility APK and a smaller ARM64-only APK.
    flavorDimensions += "distribution"
    productFlavors {
        create("universal") {
            dimension = "distribution"
        }
        create("arm64") {
            dimension = "distribution"
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation("com.posthog:posthog-android:3.72.10")
    implementation(project(":engine"))
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
// Generate the flattened engine AAR inputs before the app resolves its runtime classpath.
tasks.named("preBuild").configure {
    dependsOn(":engine:flattenPowAars")
}
