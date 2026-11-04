# WireGuard: GoBackend usa metodi nativi (JNI) e il VpnService dichiarato nel manifest della libreria
-keep class com.wireguard.** { *; }
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-dontwarn com.bumptech.glide.**
