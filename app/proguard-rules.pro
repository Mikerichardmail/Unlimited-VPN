# ProGuard rules for the VPN application.

# Keep Retrofit and OkHttp models / annotations
-keepattributes Signature, InnerClasses, AnnotationDefault, *Annotation*

# Keep Hilt / Dagger generated classes
-keep class * extends java.lang.annotation.Annotation { *; }

# Keep WireGuard JNI libraries and classes
-keep class org.wireguard.crypto.** { *; }
-keep class org.wireguard.android.backend.** { *; }
-dontwarn org.wireguard.android.backend.**

# Keep API data models for Gson serialization
-keep class com.vpn.android.data.models.** { *; }
-keepclassmembers class com.vpn.android.data.models.** { *; }

# Keep Google Play Billing Client
-keep class com.android.billingclient.api.** { *; }
-dontwarn com.android.billingclient.api.**

