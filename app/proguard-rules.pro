# Zaslon Browser Android - R8 / ProGuard Kuralları
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.geckoview.**
-dontwarn org.mozilla.gecko.**

# Glean veya Mozilla Telemetri sınıfları kalıntı olarak çağrılırsa sessizce yoksay
-dontwarn mozilla.telemetry.glean.**
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
