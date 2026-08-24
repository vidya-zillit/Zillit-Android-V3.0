# kotlinx.serialization — keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.zillit.zillitapp.**$$serializer { *; }
-keepclassmembers class com.zillit.zillitapp.** {
    *** Companion;
}

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# Socket.IO / engine.io
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# Realm Kotlin
-keep class io.realm.kotlin.** { *; }
-dontwarn io.realm.kotlin.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
