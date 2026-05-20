-keep class com.hitif.videodownloader.network.JsInterface { *; }
-keepclassmembers class com.hitif.videodownloader.network.JsInterface {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.hitif.videodownloader.download.WebViewFetchHelper { *; }
-keepclassmembers class com.hitif.videodownloader.download.WebViewFetchHelper {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.hitif.videodownloader.model.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
