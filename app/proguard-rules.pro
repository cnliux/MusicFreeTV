# QuickJS native
-keep class io.github.taoweiji.quickjs.** { *; }
-keep class com.quickjs.** { *; }
-keepclassmembers class io.github.taoweiji.quickjs.** {
    @android.webkit.JavascriptInterface <methods>;
}

# JS 引擎：native pump（JNI）+ @JavascriptInterface 桥（addJavascriptInterface 反射探测）
-keepclasseswithmembernames class * { native <methods>; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.tvmusic.runtime.QuickJsEngine { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# CameraX + ZXing
-dontwarn androidx.camera.**

# Media3 播放会话：通知/元数据大量依赖反射，保留 MediaSession 相关类
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }

# zxing
-keep class com.google.zxing.** { *; }

# Coil
-dontwarn coil.**
-keep class coil.** { *; }