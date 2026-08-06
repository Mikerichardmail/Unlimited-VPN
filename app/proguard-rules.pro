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

# ── Google Play Billing Library 9 ────────────────────────────────────────────
# Required for R8 Full Mode: keeps all public API classes including new v9 types
# (BillingChoiceInfo, sub-response code helpers, PendingPurchasesParams, etc.)
-keep class com.android.billingclient.api.** { *; }
-keepnames class com.android.billingclient.api.** { *; }
-dontwarn com.android.billingclient.api.**

# Keep AIDL-generated IPC stubs used by the Billing client to communicate
# with the Google Play Store app. Without this, R8 Full Mode strips these
# interfaces and causes ClassNotFoundException / NoSuchMethodError at runtime.
-keep class com.android.vending.billing.** { *; }
-dontwarn com.android.vending.billing.**

# ✅ SECURITY: Keep AndroidX Security Crypto (EncryptedSharedPreferences + MasterKey)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Keep OkHttp CertificatePinner (used for cert pinning in NetworkModule)
-keep class okhttp3.CertificatePinner { *; }
-keep class okhttp3.CertificatePinner$Builder { *; }
