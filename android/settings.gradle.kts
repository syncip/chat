pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    // Versionen zentral (werden nur aufgelöst, wenn ein Modul das Plugin anwendet).
    plugins {
        val kotlin = "2.0.21"
        kotlin("jvm") version kotlin
        kotlin("android") version kotlin
        kotlin("plugin.serialization") version kotlin
        id("org.jetbrains.kotlin.plugin.compose") version kotlin
        id("com.android.application") version "8.7.3"
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "chat-android"
include(":engine")

// Das Android-Modul benötigt ein Android SDK (ANDROID_HOME). Ohne SDK bleibt `:engine` (reines JVM) baubar und testbar.
val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }
if (sdk != null) include(":app")
