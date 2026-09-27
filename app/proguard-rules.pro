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

# Media3/OkHttp/zxing/Coil 自带 consumer-proguard，无需全量 keep；如运行出现反射崩溃再按最小规则回补

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil
-dontwarn coil.**