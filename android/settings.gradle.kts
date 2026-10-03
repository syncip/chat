pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
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
