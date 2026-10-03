plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "chat.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.syncip.chat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Nativer Rust-Kern (siehe scripts/build-android-core.sh): nur 64-bit + x86_64 (Emulator) + armv7
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Release-Signatur über Umgebungsvariablen (nie im Repo):
    //   CHAT_KEYSTORE (Pfad), CHAT_KEYSTORE_PASSWORD, CHAT_KEY_ALIAS, CHAT_KEY_PASSWORD
    signingConfigs {
        val ks = System.getenv("CHAT_KEYSTORE")
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("CHAT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CHAT_KEY_ALIAS")
                keyPassword = System.getenv("CHAT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version")
    }
    lint { abortOnError = true; checkReleaseBuilds = true }
}

dependencies {
    implementation(project(":engine"))
    // JNA-AAR liefert libjnidispatch für Android (die UniFFI-Bindings laden den Rust-Kern darüber).
    implementation("net.java.dev.jna:jna:5.15.0@aar")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
