# 🔐 Unlimited VPN — Security Policy & Developer Guide

> **Version**: 1.0 | **Updated**: 2026-07-31 | **Status**: ENFORCED
> This document is mandatory reading for every developer on this project.
> All changes touching security must be reviewed against this policy.

---

## 📋 Table of Contents

1. [Security Fixes Applied](#security-fixes-applied)
2. [Threat Model](#threat-model)
3. [Secret Management](#secret-management)
4. [Encrypted Storage Architecture](#encrypted-storage-architecture)
5. [Certificate Pinning](#certificate-pinning)
6. [Backend API Security](#backend-api-security)
7. [Network Security Config](#network-security-config)
8. [ProGuard / R8 Obfuscation](#proguard--r8-obfuscation)
9. [Incident Response](#incident-response)
10. [Security Checklist for Releases](#security-checklist-for-releases)

---

## ✅ Security Fixes Applied

The following vulnerabilities were identified and fixed. All fixes are now in the codebase.

| # | Vulnerability | Severity | Fix Applied |
|---|---|---|---|
| 1 | HMAC secret hardcoded in `VpnRepository.kt` | 🔴 Critical | Moved to `BuildConfig.HMAC_SECRET` via `local.properties` |
| 2 | HMAC secret hardcoded in `worker.js` | 🔴 Critical | Removed. Must be set via `npx wrangler secret put HMAC_SECRET` |
| 3 | WireGuard private key in unencrypted DataStore | 🔴 Critical | Moved to `EncryptedSharedPreferences` (Android Keystore backed) |
| 4 | Backend signature check skipped outside "production" env | 🟠 High | Logic inverted — fails closed. Allowed only if `DEV_MODE=true` |
| 5 | Network logging enabled in release builds | 🟠 High | Disabled via `BuildConfig.ENABLE_NETWORK_LOGGING` |
| 6 | No certificate pinning | 🟠 High | OkHttp `CertificatePinner` added in `NetworkModule` |
| 7 | Installation ID in unencrypted DataStore | 🟡 Medium | Moved to `EncryptedSharedPreferences` |
| 8 | User email in unencrypted DataStore | 🟡 Medium | Moved to `EncryptedSharedPreferences` |
| 9 | `.dev.vars` Cloudflare secrets could be committed | 🟡 Medium | Added `backend/.dev.vars` to `.gitignore` |
| 10 | ProGuard missing rules for security-crypto | 🟢 Low | Added keep rules for `androidx.security.crypto.**` |
| 11 | Purchase acknowledged before server verification | 🔴 Critical | `BillingManager` now emits `PendingPurchase`; `acknowledgeIfVerified()` is called only after `verifySubscription()` returns success |
| 12 | Google Play webhook had no authentication | 🔴 Critical | `WEBHOOK_SECRET` query-param verified on every webhook request; set via `npx wrangler secret put WEBHOOK_SECRET` |
| 13 | `/api/bandwidth-sync` had no authentication | 🔴 Critical | `WORKER_AUTH_SECRET` header required (same as `/api/connection-log`) |
| 14 | Subscription active flag in unencrypted DataStore | 🟠 High | Moved to `EncryptedSharedPreferences` — rooted device can no longer flip it |
| 15 | Mock server fallback used fake WireGuard pubkeys | 🟠 High | Removed — error message shown instead; WireGuard no longer silently fails |
| 16 | Notification updated every second (battery drain) | 🟡 Medium | Timer notification now updates every 60 seconds; on-screen timer unchanged |
| 17 | Billing reconnect was an infinite recursive loop | 🟠 High | Replaced with exponential backoff (1s→64s max, 8 retries) |

---

## 🎯 Threat Model

### What We Protect Against

| Threat | Attacker | Mitigation |
|---|---|---|
| **API request forgery** | Decompiles APK, extracts HMAC key, forges requests | HMAC secret in BuildConfig (not in source); signature always verified |
| **MITM / proxy interception** | User installs proxy CA, intercepts VPN API calls | Certificate pinning in OkHttp |
| **WireGuard key theft** | Root access to device, reads app data files | EncryptedSharedPreferences (AES-256-GCM, Android Keystore) |
| **Source code inspection** | Reads GitHub repo for secrets | No secrets in source; `local.properties` + `worker.js` read from env |
| **Device backup extraction** | Restores device backup containing app data | `android:allowBackup="false"` in manifest |
| **Subscription bypass** | Modifies APK to skip paywall | Server-side subscription verification; HMAC-signed requests |
| **Data logging/exfiltration** | Network logger captures purchase tokens | `ENABLE_NETWORK_LOGGING=false` in release builds |

### What We Do NOT Protect Against
- A fully compromised device (attacker has root + hardware key extraction capability)
- An attacker who reverse-engineers the app AND has access to your `local.properties` or CI secrets

---

## 🔑 Secret Management

### The Golden Rule
> **Secrets never appear in source code, ever. Not as constants, not in comments, not in example strings.**

### Android App Secrets

Secrets are injected via `BuildConfig` at compile time from `local.properties`.

#### Setup (One-Time, Per Developer)

1. Open `f:\vpn android\local.properties` (already gitignored)
2. Replace the placeholder with a real secret:
```properties
HMAC_SECRET=<your-secret-from-cloudflare>
API_BASE_URL=https://vpn-api-worker.iteack19.workers.dev/
```
3. Generate a strong secret if you don't have one:
```bash
openssl rand -base64 32
```

#### How It Flows to Code
```
local.properties
    → build.gradle.kts  (buildConfigField)
    → BuildConfig.HMAC_SECRET  (generated class)
    → VpnRepository.kt  (reads BuildConfig.HMAC_SECRET)
```

#### CI/CD (GitHub Actions)
```yaml
# In your release workflow:
- name: Set secrets
  run: |
    echo "HMAC_SECRET=${{ secrets.HMAC_SECRET }}" >> local.properties
    echo "API_BASE_URL=${{ secrets.API_BASE_URL }}" >> local.properties
```

> [!CAUTION]
> Never add `local.properties` to git. Verify:
> `git check-ignore -v local.properties`

---

### Cloudflare Worker Secrets

Secrets are set via Wrangler CLI and stored encrypted in Cloudflare's infrastructure. They are NEVER in `worker.js`.

#### Required Secrets (set once in Cloudflare)

```bash
# Run from f:\vpn android\backend\
npx wrangler secret put HMAC_SECRET
npx wrangler secret put SUPABASE_URL
npx wrangler secret put SUPABASE_ANON_KEY
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
npx wrangler secret put WORKER_AUTH_SECRET
npx wrangler secret put WEBHOOK_SECRET                           # ✅ NEW — Google Play RTDN webhook auth
npx wrangler secret put GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL
npx wrangler secret put GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY
npx wrangler secret put VPNRESELLERS_API_TOKEN
```

#### Local Development Secrets

For `wrangler dev` (local testing only), edit `backend/.dev.vars` (gitignored):
```ini
HMAC_SECRET=same-secret-as-local.properties
DEV_MODE=true
```

> [!WARNING]
> `DEV_MODE=true` allows unsigned requests locally **only**. Never set this in Cloudflare production.

---

## 🗃️ Encrypted Storage Architecture

### Two-Tier Storage Model

```
┌─────────────────────────────────────────────────────────────────┐
│              TIER 1: EncryptedSharedPreferences                 │
│              (AES-256-GCM via Android Keystore)                 │
│                                                                 │
│  • WireGuard Private Key    ← Most sensitive value in the app   │
│  • WireGuard Public Key                                         │
│  • Installation ID          ← Device fingerprint               │
│  • User Email               ← Personal data (GDPR-relevant)    │
│  • Key Rotation Timestamp                                       │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│              TIER 2: DataStore Preferences (standard)           │
│                    (non-sensitive settings)                      │
│                                                                 │
│  • Subscription Active flag                                     │
│  • Subscription Expiry date                                     │
│  • Selected Server ID                                           │
│  • Kill Switch Enabled                                          │
│  • Trial Started At timestamp                                   │
└─────────────────────────────────────────────────────────────────┘
```

### Why Android Keystore?

The `MasterKey` used by `EncryptedSharedPreferences` is stored in the **Android Keystore** — a hardware-backed secure element on modern devices. The key material never leaves the secure hardware, even from root access.

```kotlin
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)  // Hardware-backed
    .build()
```

### Before vs After

**Before (DataStore — VULNERABLE):**
```
/data/data/com.bestfreevpnproxy.app/files/datastore/vpn_settings.preferences_pb
→ wg_private_key: "YourPrivateKeyInPlaintext=="  ← READABLE on rooted device
```

**After (EncryptedSharedPreferences — SECURE):**
```
/data/data/com.bestfreevpnproxy.app/shared_prefs/vpn_secure_prefs.xml
→ enc_wg_private_key: "AES256-GCM-CIPHERTEXT-BLOB"  ← Unreadable without Keystore
```

---

## 📌 Certificate Pinning

### What It Does
Pins the TLS public key of our Cloudflare Worker. Even if an attacker installs a trusted CA certificate, the connection will fail if the server certificate doesn't match our pins.

### Getting the Pin Values

```bash
openssl s_client -connect vpn-api-worker.iteack19.workers.dev:443 </dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | base64
```

Update `NetworkModule.kt` with the result:

```kotlin
private const val CERT_PIN_1 = "sha256/<output-from-above>=="
private const val CERT_PIN_2 = "sha256/<backup-pin>=="
```

> [!IMPORTANT]
> Always pin 2 keys (primary + backup). Check and update pins **every 60 days**.

### Pin Rotation Schedule

| Action | When |
|---|---|
| Check if pin still matches | Every 60 days |
| Update `CERT_PIN_1` in code | When pin changes |
| Publish updated app | Immediately after pin change |

### Emergency Bypass (Certificate Rotation Emergency)

If Cloudflare rotates the certificate unexpectedly:
1. Comment out `builder.certificatePinner(certificatePinner)` in `NetworkModule.kt`
2. Build and release emergency update to Play Store
3. Re-enable pinning with the new pin in the next release

---

## 🌐 Backend API Security

### Request Authentication Flow

```
Android App                          Cloudflare Worker
──────────                          ──────────────────
1. Compute HMAC-SHA256(payload, BuildConfig.HMAC_SECRET)
2. Add header: X-App-Signature: <hmac>
3. Send request
                    ─────────────────────────────────▶
                    4. Read env.HMAC_SECRET
                    5. Compute HMAC-SHA256(same payload)
                    6. Compare signatures
                    7. If match → process  /  If no match → 401
```

### Security Rules

1. `DEV_MODE` must be **false or unset** in production (Cloudflare Dashboard → Worker → Settings)
2. `HMAC_SECRET` must be a Cloudflare **Secret** (encrypted), not a plain Variable
3. Never log request bodies — they contain purchase tokens and WireGuard keys
4. Apply Cloudflare Rate Limiting on `/api/register-device` (max 5 req/min per IP)

---

## 🔒 ProGuard / R8 Obfuscation

Obfuscation is enabled in release: `isMinifyEnabled = true`, `isShrinkResources = true`.

> [!IMPORTANT]
> R8 must NOT strip these classes. All are covered in `proguard-rules.pro`:
> - `androidx.security.crypto.**` (EncryptedSharedPreferences / MasterKey)
> - `okhttp3.CertificatePinner` (certificate pinning)
> - `com.vpn.android.data.models.**` (Gson models)
> - `org.wireguard.android.backend.**` (WireGuard JNI)
> - `com.android.billingclient.api.**` (Google Play Billing)
>
> **Never remove these rules.**

---

## 🚨 Incident Response

### If the HMAC Secret is Compromised

1. **Immediately** rotate:
   ```bash
   openssl rand -base64 32          # new secret
   npx wrangler secret put HMAC_SECRET
   ```
2. Update `local.properties` on all developer machines
3. Update CI/CD secrets (GitHub Actions → Secrets)
4. Build and release a new app version — old versions will get `401` and stop working
5. Monitor Supabase for suspicious API activity

### If a WireGuard Private Key is Suspected Stolen

1. Deregister the device via `/api/deregister-device`
2. App generates a new key pair on next launch
3. Old key revoked on VPNResellers server

### If the Cloudflare Worker is Compromised

1. Delete the Worker from Cloudflare Dashboard
2. Rotate ALL Cloudflare secrets
3. Check Supabase `devices` and `subscriptions` for suspicious records
4. Re-deploy from source with new secrets

---

## ✅ Security Checklist for Releases

### Android App
- [ ] `local.properties` is NOT staged in git
- [ ] `BuildConfig.HMAC_SECRET` is NOT the placeholder `REPLACE_ME_IN_LOCAL_PROPERTIES`
- [ ] `BuildConfig.ENABLE_NETWORK_LOGGING = false` in release build type
- [ ] Certificate pins in `NetworkModule.kt` are current (check every 60 days)
- [ ] `isMinifyEnabled = true` in release
- [ ] `isShrinkResources = true` in release
- [ ] `android:allowBackup="false"` in manifest
- [ ] No `Log.d()` calls logging keys, tokens, or emails
- [ ] ProGuard rules cover all new libraries added

### Cloudflare Worker
- [ ] `DEV_MODE` is NOT set in Cloudflare Production
- [ ] `HMAC_SECRET` is a Cloudflare **Secret** (not plain variable)
- [ ] `SUPABASE_SERVICE_ROLE_KEY` is a Cloudflare **Secret**
- [ ] Signature verification enforced (test: request without header → expect 401)

### Repository
- [ ] `git grep -r "VPN_API_HMAC_SECRET_KEY" .` → no results
- [ ] `git grep -r "REPLACE_ME" .` → no results
- [ ] `backend/.dev.vars` is gitignored
- [ ] `local.properties` is gitignored

---

## 📚 References

- [Android Keystore System](https://developer.android.com/privacy-and-security/keystore)
- [EncryptedSharedPreferences](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)
- [OkHttp Certificate Pinning](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-certificate-pinner/)
- [Cloudflare Worker Secrets](https://developers.cloudflare.com/workers/configuration/secrets/)
- [OWASP Mobile Security Top 10](https://owasp.org/www-project-mobile-top-10/)

---

*This document is generated and maintained as part of the Unlimited VPN project security posture.*
*Any deviation from this policy requires explicit written approval from the project owner.*
