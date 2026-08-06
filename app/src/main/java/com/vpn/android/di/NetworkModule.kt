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
     * HOW TO REFRESH THE PIN (before cert expires 2026-10-18):
     *   openssl s_client -connect vpn-api-worker.iteack19.workers.dev:443 </dev/null \
     *     | openssl x509 -pubkey -noout \
     *     | openssl pkey -pubin -outform der \
     *     | openssl dgst -sha256 -binary | base64
     *
     * Always keep 2 pins (primary leaf + backup intermediate) so rotation
     * doesn't lock users out.
     */
    private const val API_HOST   = "vpn-api-worker.iteack19.workers.dev"
    // Leaf cert — expires 2026-10-18. Refresh before this date.
    private const val CERT_PIN_1 = "sha256/H6x1j1E7X4t0fWq/ejKU3ySh5pojTkLC7LDZu/Eghq4="
    // Let's Encrypt ISRG Root X1 — long-lived backup intermediate pin
    private const val CERT_PIN_2 = "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="

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

        // SECURITY: Certificate pinning — re-enabled.
        // Disabled in debug builds only so Charles/Proxyman can be used for testing.
        if (!BuildConfig.DEBUG) {
            val certificatePinner = CertificatePinner.Builder()
                .add(API_HOST, CERT_PIN_1)
                .add(API_HOST, CERT_PIN_2)
                .build()
            builder.certificatePinner(certificatePinner)
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideVpnApiService(okHttpClient: OkHttpClient): VpnApiService {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VpnApiService::class.java)
    }
}
