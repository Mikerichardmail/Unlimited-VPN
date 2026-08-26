# ProGuard / R8 rules for the VPN application.

# ── Attributes & Reflection ───────────────────────────────────────────────────
-keepattributes Signature, EnclosingMethod, InnerClasses, AnnotationDefault, *Annotation*, Exceptions
-keepclassmembers enum * { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn kotlin.Metadata
-dontwarn kotlin.**

# ── Gson / Retrofit ───────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# ── App Data Models & Entities (Gson reflection) ──────────────────────────────
-keep class com.vpn.android.data.models.** { *; }
-keepclassmembers class com.vpn.android.data.models.** { *; }

# ── WireGuard SDK & JNI ───────────────────────────────────────────────────────
# FIX: Preserves com.wireguard.** (Tunnel, GoBackend, KeyPair, Config, Interface, Peer)
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**
-keep class org.wireguard.** { *; }
-dontwarn org.wireguard.**

# ── AndroidX Security Crypto & Google Tink ────────────────────────────────────
# FIX: EncryptedSharedPreferences and MasterKey depend on Google Tink crypto
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ── Google Play Billing Library 9 ─────────────────────────────────────────────
-keep class com.android.billingclient.api.** { *; }
-keepnames class com.android.billingclient.api.** { *; }
-dontwarn com.android.billingclient.api.**
-keep class com.android.vending.billing.** { *; }
-dontwarn com.android.vending.billing.**
-keep class com.google.android.gms.internal.play_billing.** { *; }
-dontwarn com.google.android.gms.internal.play_billing.**

# ── Google Play In-App Review ─────────────────────────────────────────────────
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**

# ── Dagger / Hilt ─────────────────────────────────────────────────────────────
-keep class * extends java.lang.annotation.Annotation { *; }
-keep class com.vpn.android.VpnApplication { *; }
-keep class com.vpn.android.ui.MainActivity { *; }
-keep class com.vpn.android.vpn.BootReceiver { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }
-dontwarn com.google.errorprone.annotations.**

# ── OkHttp Certificate Pinning ────────────────────────────────────────────────
-keep class okhttp3.CertificatePinner { *; }
-keep class okhttp3.CertificatePinner$Builder { *; }

