# QuickJS native
-keep class io.github.taoweiji.quickjs.** { *; }
-keepclassmembers class io.github.taoweiji.quickjs.** {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# CameraX + ZXing
-dontwarn androidx.camera.**