# JNA und die UniFFI-Bindings werden per Reflection/JNI genutzt.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**
-dontwarn javax.swing.**
# kotlinx.serialization (Engine-Modelle)
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class chat.engine.**$$serializer { *; }
-keepclassmembers class chat.engine.** { *** Companion; }
-keepclasseswithmembers class chat.engine.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
