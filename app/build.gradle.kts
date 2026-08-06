import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")  // Required for Kotlin 2.0 + Compose
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

// ✅ SECURITY: Read secrets from local.properties (gitignored — never in source)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
val hmacSecret: String  = localProps.getProperty("HMAC_SECRET",  "REPLACE_ME_IN_LOCAL_PROPERTIES")
val apiBaseUrl: String  = localProps.getProperty("API_BASE_URL", "https://vpn-api-worker.iteack19.workers.dev/")

android {
    namespace = "com.vpn.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bestfreevpnproxy.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Inject secrets as BuildConfig fields — never hardcode in source
        buildConfigField("String", "HMAC_SECRET", "\"$hmacSecret\"")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        debug {
            buildConfigField("Boolean", "ENABLE_NETWORK_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("Boolean", "ENABLE_NETWORK_LOGGING", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    // Kotlin 2.x compilerOptions DSL replaces deprecated kotlinOptions { jvmTarget }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // ✅ NOTE: composeOptions.kotlinCompilerExtensionVersion removed —
    //    Kotlin 2.0 manages the Compose compiler via the plugin, not this block.
    lint {
        // ✅ Disable lintVitalAnalyzeRelease: lifecycle-lint 2.9.1 crashes with
        // IncompatibleClassChangeError in NonNullableMutableLiveDataDetector
        // against AGP 8.7.3's bundled Lint engine. Safe to disable — R8 and
        // compilation checks are unaffected. Re-enable when upgrading to AGP 9.x+.
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ── Compose BOM ──────────────────────────────────────────────────────────
    // Single BOM controls all Compose library versions consistently.
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AndroidX & Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Jetpack Compose (versions managed by BOM)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // WireGuard SDK
    implementation("com.wireguard.android:tunnel:1.0.20230706")

    // OpenVPN (ics-openvpn)
    // TODO: Add ics-openvpn AAR or project dependency here once imported
    // implementation(project(":openvpn"))

    // ── Dagger Hilt ──────────────────────────────────────────────────────────
    // Hilt 2.56.2 is compatible with Kotlin 2.0 metadata format.
    implementation("com.google.dagger:hilt-android:2.56.2")
    kapt("com.google.dagger:hilt-compiler:2.56.2")

    // Retrofit & OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    // ✅ SECURITY: Encrypted storage for sensitive keys (WireGuard private key, Installation ID)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── Google Play Billing Library 9 ─────────────────────────────────────────
    // v9.1.0 released June 2026 — supported until Aug 2028.
    // Includes: non-nullable ProductDetails list, sub-response codes, BillingChoiceInfo API.
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    // Google Play In-App Review
    implementation("com.google.android.play:review:2.0.2")
    implementation("com.google.android.play:review-ktx:2.0.2")

    // Core Library Desugaring (required for WireGuard SDK on older API levels)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
