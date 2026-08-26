package com.vpn.android.di

import com.vpn.android.BuildConfig
import com.vpn.android.data.api.VpnApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.google.gson.GsonBuilder
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * SECURITY: Certificate Pinning — prevents MITM attacks even if the user
     * installs a rogue CA or a proxy cert on their device.
     *
     * We pin against the full current certificate chain presented by the server.
     * Three pins are configured so a single certificate rotation doesn't break the app:
     *  - ISRG Root YE (primary)     — the root currently served by Cloudflare Workers
     *  - ISRG Root X2 (backup)      — ECDSA P-384 backup root, valid until 2035
     *  - Let's Encrypt E6 (intermediate) — intermediate CA in the current chain
     *
     * NOTE: ISRG Root X1 (sha256/C5+lpZ7tc...) was REMOVED — the server rotated
     * away from it and including it caused a pinning failure on all API calls.
     * Update these pins if the Cloudflare Worker certificate chain changes again.
     */
    private const val API_HOST   = "vpn-api-worker.iteack19.workers.dev"
    // ISRG Root YE — primary root currently in the server's certificate chain
    private const val CERT_PIN_1 = "sha256/sckq5UWXjg+7mKu9lMhhYF5bGLsy7Vl/UNW3tccdR7w="
    // ISRG Root X2 — ECDSA P-384 backup root, valid until 2035
    private const val CERT_PIN_2 = "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvmFZRIjUIWo="
    // Let's Encrypt E6 intermediate — also in the current chain (extra resilience)
    private const val CERT_PIN_3 = "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y="

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {

        // Only log in debug builds — never log request bodies in release
        // (bodies contain WireGuard public keys & purchase tokens)
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (BuildConfig.ENABLE_NETWORK_LOGGING) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(logging)
        }

        // SECURITY: Certificate pinning against rogue CAs.
        // Disabled in debug builds so Charles/Proxyman can be used for testing.
        if (!BuildConfig.DEBUG) {
            val certificatePinner = CertificatePinner.Builder()
                .add(API_HOST, CERT_PIN_1)  // ISRG Root YE — current primary root
                .add(API_HOST, CERT_PIN_2)  // ISRG Root X2 — backup root
                .add(API_HOST, CERT_PIN_3)  // Let's Encrypt E6 intermediate
                .build()
            builder.certificatePinner(certificatePinner)
        }

        return builder.build()
    }


    @Provides
    @Singleton
    fun provideVpnApiService(okHttpClient: OkHttpClient): VpnApiService {
        // FIX: "java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType"
        // Gson's default TypeAdapterFactory uses raw Class tokens when it encounters
        // Kotlin nullable types (e.g. Subscription?, VpnConfig?, String?) without
        // explicit generic info. Using GsonBuilder with lenient parsing and
        // serializeNulls ensures nullable fields are handled as proper JSON null
        // instead of being passed as raw Class objects to the ParameterizedType path.
        val gson = GsonBuilder()
            .serializeNulls()          // treat Kotlin nulls as JSON null (not absent)
            .setLenient()              // tolerate minor malformed JSON from the API
            .create()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(VpnApiService::class.java)
    }
}
