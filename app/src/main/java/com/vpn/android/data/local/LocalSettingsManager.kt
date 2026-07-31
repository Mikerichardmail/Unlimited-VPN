package com.vpn.android.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.wireguard.crypto.KeyPair
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vpn_settings")

@Singleton
class LocalSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        private val KEY_INSTALLATION_ID = stringPreferencesKey("installation_id")
        private val KEY_WG_PRIVATE_KEY = stringPreferencesKey("wg_private_key")
        private val KEY_WG_PUBLIC_KEY = stringPreferencesKey("wg_public_key")
        private val KEY_SUBSCRIPTION_ACTIVE = booleanPreferencesKey("subscription_active")
        private val KEY_SUBSCRIPTION_EXPIRY = stringPreferencesKey("subscription_expiry")
        private val KEY_SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")
        private val KEY_KILL_SWITCH_ENABLED = booleanPreferencesKey("kill_switch_enabled")
        private val KEY_EMAIL = stringPreferencesKey("user_email")
        private val KEY_LAST_ROTATION_TIME = longPreferencesKey("last_rotation_time")
        private val KEY_TRIAL_STARTED_AT = longPreferencesKey("trial_started_at")
    }

    // 3-day Free Trial Tracking
    val trialStartedAtFlow: Flow<Long> = dataStore.data.map { preferences ->
        preferences[KEY_TRIAL_STARTED_AT] ?: 0L
    }

    suspend fun getOrCreateTrialStartedAt(): Long {
        val current = trialStartedAtFlow.first()
        if (current > 0L) return current
        val now = System.currentTimeMillis()
        dataStore.edit { preferences ->
            preferences[KEY_TRIAL_STARTED_AT] = now
        }
        return now
    }

    val isTrialActiveFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        val startedAt = preferences[KEY_TRIAL_STARTED_AT] ?: 0L
        if (startedAt == 0L) true // Trial starts on first use
        else {
            val threeDaysMillis = 3L * 24L * 60L * 60L * 1000L
            (System.currentTimeMillis() - startedAt) < threeDaysMillis
        }
    }

    val trialTimeRemainingMillisFlow: Flow<Long> = dataStore.data.map { preferences ->
        val startedAt = preferences[KEY_TRIAL_STARTED_AT] ?: 0L
        val threeDaysMillis = 3L * 24L * 60L * 60L * 1000L
        if (startedAt == 0L) threeDaysMillis
        else {
            val elapsed = System.currentTimeMillis() - startedAt
            (threeDaysMillis - elapsed).coerceAtLeast(0L)
        }
    }

    // 1. Installation ID (UUID)
    val installationIdFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_INSTALLATION_ID] ?: ""
    }

    suspend fun getOrCreateInstallationId(): String {
        val currentId = installationIdFlow.first()
        if (currentId.isNotEmpty()) return currentId

        val newId = UUID.randomUUID().toString()
        dataStore.edit { preferences ->
            preferences[KEY_INSTALLATION_ID] = newId
        }
        return newId
    }

    // 2. WireGuard Key Pair
    val wgPrivateKeyFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_WG_PRIVATE_KEY] ?: ""
    }

    val wgPublicKeyFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_WG_PUBLIC_KEY] ?: ""
    }

    suspend fun getOrCreateWireGuardKeys(): Pair<String, String> {
        val privateKey = wgPrivateKeyFlow.first()
        val publicKey = wgPublicKeyFlow.first()
        if (privateKey.isNotEmpty() && publicKey.isNotEmpty()) {
            return Pair(privateKey, publicKey)
        }

        // Generate keypair locally using WireGuard SDK
        val keyPair = KeyPair()
        val generatedPriv = keyPair.privateKey.toBase64()
        val generatedPub = keyPair.publicKey.toBase64()

        dataStore.edit { preferences ->
            preferences[KEY_WG_PRIVATE_KEY] = generatedPriv
            preferences[KEY_WG_PUBLIC_KEY] = generatedPub
            preferences[KEY_LAST_ROTATION_TIME] = System.currentTimeMillis()
        }
        return Pair(generatedPriv, generatedPub)
    }

    val lastRotationTimeFlow: Flow<Long> = dataStore.data.map { preferences ->
        preferences[KEY_LAST_ROTATION_TIME] ?: 0L
    }

    suspend fun updateWireGuardKeys(privateKey: String, publicKey: String) {
        dataStore.edit { preferences ->
            preferences[KEY_WG_PRIVATE_KEY] = privateKey
            preferences[KEY_WG_PUBLIC_KEY] = publicKey
            preferences[KEY_LAST_ROTATION_TIME] = System.currentTimeMillis()
        }
    }

    // 3. Subscription Status
    val isSubscriptionActiveFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_SUBSCRIPTION_ACTIVE] ?: false
    }

    val subscriptionExpiryFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_SUBSCRIPTION_EXPIRY] ?: ""
    }

    suspend fun setSubscriptionStatus(isActive: Boolean, expiryDate: String) {
        dataStore.edit { preferences ->
            preferences[KEY_SUBSCRIPTION_ACTIVE] = isActive
            preferences[KEY_SUBSCRIPTION_EXPIRY] = expiryDate
        }
    }

    // 4. Selected Server Location
    val selectedServerIdFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_SELECTED_SERVER_ID] ?: "" // Default to Auto Select (empty string)
    }

    suspend fun setSelectedServerId(serverId: String) {
        dataStore.edit { preferences ->
            preferences[KEY_SELECTED_SERVER_ID] = serverId
        }
    }

    // 5. Kill Switch (setBlocking)
    val isKillSwitchEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_KILL_SWITCH_ENABLED] ?: false
    }

    suspend fun setKillSwitchEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_KILL_SWITCH_ENABLED] = enabled
        }
    }

    // 6. User Email
    val emailFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_EMAIL] ?: ""
    }

    suspend fun setEmail(email: String) {
        dataStore.edit { preferences ->
            preferences[KEY_EMAIL] = email
        }
    }
}
