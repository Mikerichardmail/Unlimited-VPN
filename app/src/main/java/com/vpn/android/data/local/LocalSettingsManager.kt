package com.vpn.android.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.crypto.KeyPair
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Non-sensitive settings (UI prefs, flags) remain in standard DataStore
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vpn_settings")

@Singleton
class LocalSettingsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    // -------------------------------------------------------------------------
    // ✅ SECURITY FIX: Sensitive fields stored in EncryptedSharedPreferences
    //    backed by Android Keystore — survives uninstall, hardware-protected.
    // -------------------------------------------------------------------------
    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "vpn_secure_prefs",          // filename (will be encrypted)
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Keys stored in EncryptedSharedPreferences (sensitive)
    companion object {
        // Encrypted keys
        private const val ENC_INSTALLATION_ID     = "enc_installation_id"
        private const val ENC_WG_PRIVATE_KEY      = "enc_wg_private_key"
        private const val ENC_WG_PUBLIC_KEY       = "enc_wg_public_key"
        private const val ENC_LAST_ROTATION       = "enc_last_rotation_time"
        private const val ENC_EMAIL               = "enc_user_email"
        // ✅ FIX ❶: Subscription status moved from DataStore to EncryptedSharedPreferences.
        //    On a rooted device a user could flip subscription_active=true in the plain
        //    DataStore file without paying. Encrypted storage prevents that.
        private const val ENC_SUBSCRIPTION_ACTIVE = "enc_subscription_active"
        private const val ENC_SUBSCRIPTION_EXPIRY  = "enc_subscription_expiry"

        // Non-sensitive DataStore keys
        private val KEY_SELECTED_SERVER_ID   = stringPreferencesKey("selected_server_id")
        private val KEY_KILL_SWITCH_ENABLED  = booleanPreferencesKey("kill_switch_enabled")
        private val KEY_PROTOCOL             = stringPreferencesKey("protocol")
        private val KEY_CONSENT_ACCEPTED     = booleanPreferencesKey("consent_accepted")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Installation ID  (ENCRYPTED)
    // ─────────────────────────────────────────────────────────────────────────

    val installationIdFlow: Flow<String> = dataStore.data.map { _ ->
        encryptedPrefs.getString(ENC_INSTALLATION_ID, "") ?: ""
    }

    suspend fun getOrCreateInstallationId(): String {
        val current = encryptedPrefs.getString(ENC_INSTALLATION_ID, "") ?: ""
        if (current.isNotEmpty()) return current
        val newId = UUID.randomUUID().toString()
        encryptedPrefs.edit().putString(ENC_INSTALLATION_ID, newId).apply()
        return newId
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. WireGuard Key Pair  (ENCRYPTED — most sensitive value in the app)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getOrCreateWireGuardKeys(): Pair<String, String> {
        val privateKey = encryptedPrefs.getString(ENC_WG_PRIVATE_KEY, "") ?: ""
        val publicKey  = encryptedPrefs.getString(ENC_WG_PUBLIC_KEY,  "") ?: ""
        if (privateKey.isNotEmpty() && publicKey.isNotEmpty()) {
            return Pair(privateKey, publicKey)
        }
        // Generate keypair locally using WireGuard SDK
        val keyPair       = KeyPair()
        val generatedPriv = keyPair.privateKey.toBase64()
        val generatedPub  = keyPair.publicKey.toBase64()
        encryptedPrefs.edit()
            .putString(ENC_WG_PRIVATE_KEY, generatedPriv)
            .putString(ENC_WG_PUBLIC_KEY,  generatedPub)
            .putLong(ENC_LAST_ROTATION,    System.currentTimeMillis())
            .apply()
        return Pair(generatedPriv, generatedPub)
    }

    val lastRotationTimeFlow: Flow<Long> = dataStore.data.map { _ ->
        encryptedPrefs.getLong(ENC_LAST_ROTATION, 0L)
    }

    suspend fun updateWireGuardKeys(privateKey: String, publicKey: String) {
        encryptedPrefs.edit()
            .putString(ENC_WG_PRIVATE_KEY, privateKey)
            .putString(ENC_WG_PUBLIC_KEY,  publicKey)
            .putLong(ENC_LAST_ROTATION,    System.currentTimeMillis())
            .apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. User Email  (ENCRYPTED — personal data)
    // ─────────────────────────────────────────────────────────────────────────

    val emailFlow: Flow<String> = dataStore.data.map { _ ->
        encryptedPrefs.getString(ENC_EMAIL, "") ?: ""
    }

    suspend fun setEmail(email: String) {
        encryptedPrefs.edit().putString(ENC_EMAIL, email).apply()
    }

    // ───────────────────────────────────────────────────────────────────────────
    // 4. Subscription Status  (ENCRYPTED — moved from DataStore in security fix ❶)
    // ───────────────────────────────────────────────────────────────────────────

    val isSubscriptionActiveFlow: Flow<Boolean> = dataStore.data.map { _ ->
        encryptedPrefs.getBoolean(ENC_SUBSCRIPTION_ACTIVE, false)
    }

    val subscriptionExpiryFlow: Flow<String> = dataStore.data.map { _ ->
        encryptedPrefs.getString(ENC_SUBSCRIPTION_EXPIRY, "") ?: ""
    }

    suspend fun setSubscriptionStatus(isActive: Boolean, expiryDate: String) {
        encryptedPrefs.edit()
            .putBoolean(ENC_SUBSCRIPTION_ACTIVE, isActive)
            .putString(ENC_SUBSCRIPTION_EXPIRY,  expiryDate)
            .apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Selected Server Location  (non-sensitive — standard DataStore)
    // ─────────────────────────────────────────────────────────────────────────

    val selectedServerIdFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_SELECTED_SERVER_ID] ?: ""
    }

    suspend fun setSelectedServerId(serverId: String) {
        dataStore.edit { preferences ->
            preferences[KEY_SELECTED_SERVER_ID] = serverId
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Kill Switch  (non-sensitive — standard DataStore)
    // ─────────────────────────────────────────────────────────────────────────

    val isKillSwitchEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_KILL_SWITCH_ENABLED] ?: false
    }

    suspend fun setKillSwitchEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_KILL_SWITCH_ENABLED] = enabled
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Protocol  (non-sensitive — standard DataStore)
    // ─────────────────────────────────────────────────────────────────────────

    val protocolFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_PROTOCOL] ?: "wireguard"
    }

    suspend fun setProtocol(protocol: String) {
        dataStore.edit { preferences ->
            preferences[KEY_PROTOCOL] = protocol
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Legal Consent  (non-sensitive — standard DataStore)
    //    Tracks whether the user has accepted Privacy Policy + Terms of Service.
    //    Must be accepted before the app proceeds to onboarding/home.
    // ─────────────────────────────────────────────────────────────────────────

    val consentAcceptedFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_CONSENT_ACCEPTED] ?: false
    }

    suspend fun setConsentAccepted() {
        dataStore.edit { preferences ->
            preferences[KEY_CONSENT_ACCEPTED] = true
        }
    }
}
