# Mantiene i metodi esposti al JavaScript del player
-keepattributes JavascriptInterface
-keepclassmembers class com.kairos.tv.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# WireGuard: GoBackend usa metodi nativi (JNI, nomi Java_com_wireguard_android_backend_GoBackend_*),
# il VpnService è dichiarato nel manifest della libreria. La libreria è piccola: si tiene tutta.
-keep class com.wireguard.** { *; }
# annotazioni JSR-305 usate dalla libreria solo in compilazione (compileOnly)
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
