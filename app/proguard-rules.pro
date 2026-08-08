# JavaScript bridge methods are annotated and only enabled for the trusted club origin.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
