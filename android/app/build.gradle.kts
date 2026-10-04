plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "chat.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.syncip.chat"
        minSdk = 26
        targetSdk = 35
        // Jeder CI-Build bekommt eine höhere versionCode (Updates lassen sich ohne Deinstallation einspielen).
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 300 + build
        versionName = "0.3.0" + if (build > 0) " (Build $build)" else ""
        // Nativer Rust-Kern (siehe scripts/build-android-core.sh): nur 64-bit + x86_64 (Emulator) + armv7
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release-Signatur über Umgebungsvariablen (nie im Repo):
    //   CHAT_KEYSTORE (Pfad), CHAT_KEYSTORE_PASSWORD, CHAT_KEY_ALIAS, CHAT_KEY_PASSWORD
    signingConfigs {
        // Öffentlicher Test-Schlüssel (liegt im Repository): gleich bleibende Signatur für Test-Builds, damit Updates installierbar sind.
        // Nicht für den Produktivbetrieb: wer den Schlüssel kennt, kann Updates signieren. Für eigene Builds CHAT_KEYSTORE setzen.
        create("testkey") {
            storeFile = file("debug-test.keystore")
            storePassword = "chat-debug"
            keyAlias = "chatdebug"
            keyPassword = "chat-debug"
        }
        getByName("debug") {
            storeFile = file("debug-test.keystore")
            storePassword = "chat-debug"
            keyAlias = "chatdebug"
            keyPassword = "chat-debug"
        }
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("testkey")
            resValue("string", "app_name", "Chat")
        }
        debug {
            applicationIdSuffix = ".test"
            resValue("string", "app_name", "Chat (Test)")
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    // QR-Code scannen (ohne Google Play Services) und erzeugen.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // UI-Tests auf dem Emulator (siehe .github/workflows/android.yml, Job „Emulator-Tests“)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
