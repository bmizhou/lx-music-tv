# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Rhino JavaScript engine
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.lxmusic.tv.**$$serializer { *; }
-keepclassmembers class com.lxmusic.tv.** {
    *** Companion;
}
-keepclasseswithmembers class com.lxmusic.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Netease weapi 加密（AES/RSA，R8 优化会破坏常量模数/IV/密钥，导致排行榜 weapi 兜底失效）
-keep class com.lxmusic.tv.network.NeteaseWeApi { *; }
-keep class com.lxmusic.tv.network.NeteaseWeApi$* { *; }
-keep class com.lxmusic.tv.network.NeteaseApi { *; }
-keep class com.lxmusic.tv.network.NeteaseApi$* { *; }

-keep class com.lxmusic.tv.data.source.BrowseDataService { *; }
-keep class com.lxmusic.tv.network.BrowseDataService$* { *; }

# QuickJS 引擎（native 桥接，反射加载 .so）
-keep class wang.harlon.quickjs.** { *; }
-dontwarn wang.harlon.quickjs.**