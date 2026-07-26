# kotlinx.serialization keeps its generated serializers via @Serializable companions; R8 needs the
# metadata to survive or JSON parsing fails at runtime in release builds only — a nasty class of bug
# that never shows up in debug.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.xtv.app.**$$serializer { *; }
-keepclassmembers class com.xtv.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.xtv.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio ship their own rules; these silence the platform-only warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
