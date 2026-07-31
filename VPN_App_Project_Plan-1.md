# OpenVPN Android App - Complete Project Plan

**Project:** Budget VPN App for Indian Market  
**Target Release:** Q3 2026  
**Status:** Planning Phase  
**Last Updated:** July 28, 2026

---

## 1. Executive Summary

A lightweight, solo-developer VPN app for the Indian market using:
- **OpenVPN** backend (via VPNResellers API)
- **Google Play Billing** for subscriptions with automatic multi-currency conversion
- **Cloudflare Workers** for secure API proxying & purchase verification
- **Supabase** for user account management
- **Kotlin + Jetpack Compose** for Android UI

**Revenue Model:** Subscription-based with free 3-day trial  
**Target Users:** Indian market, expandable to South Asia  
**Competitive Advantage:** 5-10x cheaper than major competitors (NordVPN/Surfshark monthly rates)

---

## 2. Pricing Strategy

### Tier Breakdown (via Google Play Console)

| Plan | Duration | Price (INR) | Price (USD equiv) | Annual Value |
|------|----------|------------|------------------|--------------|
| Monthly | 1 month | ₹600 | ~$7.20 | ₹7,200 |
| 6-Month | 6 months | ₹3,000 | ~$36 | ₹6,000 |
| Annual | 12 months | ₹5,000 | ~$60 | ₹5,000 |

**Trial Offer:** 3-day free trial (no payment method required initially)

### Pricing Competitiveness

<cite index="22-1,23-1">Surfshark's 2-year plan costs roughly ₹149/month; NordVPN runs ₹249-379/month on longer plans; ExpressVPN monthly tier costs ₹550+</cite>. Your ₹600/month is still **5-9x cheaper** than competitors' monthly-tier pricing while positioning above bottom-of-market budget apps.

### Multi-Currency Automation

<cite index="33-1">Google Play now splits subscription fees into a 10% service fee plus a 5% billing fee (still 15% total for most apps)</cite>. Google Play Console automatically:
- Converts ₹600 base price to USD, EUR, GBP for each market daily
- Displays localized pricing in user's local currency in-app
- Handles all FX risk and regulatory pricing compliance

**Your Margin Calculation:**
```
User pays: ₹600/month (~$7.20)
Google Play cut (15%): ~$1.08
Your revenue: ~$6.12
VPNResellers cost: ~$1.99/month per active account
Net profit per user: ~$4.13/month
```

At 10% trial-to-paid conversion:
- 100 trials → 10 conversions = $41.30 revenue
- Trial cost: 100 × $0.20 = $20
- **Net profit from trials: $21.30** ✅

---

## 3. Technology Stack

### Backend Architecture

```
┌─────────────────┐
│  Android App    │ (Kotlin + Compose)
│  (User Device)  │
└────────┬────────┘
         │ HTTPS
         ▼
┌──────────────────────────────────────┐
│  Cloudflare Worker (API Gateway)     │ ← Holds VPNResellers API token
│  - Verify Play Billing purchase      │
│  - Create/enable/disable VPN account │
│  - Rate limiting & authentication    │
└────────┬─────────────────────────────┘
         │
    ┌────┴──────┬──────────────────┐
    │            │                  │
    ▼            ▼                  ▼
Supabase   VPNResellers   Google Play API
(User DB)   (VPN Ops)     (Verify Purchase)
```

### Service Details

#### 3.1 Android App (Kotlin + Compose)

**Minimum Specs:**
- Min SDK: 26 (Android 8.0)
- Target SDK: 34 (Android 14)
- Language: Kotlin
- UI Framework: Jetpack Compose
- VPN Client: ics-openvpn library

**Key Dependencies:**
```gradle
// Billing
implementation("com.android.billingclient:billing:7.0.0")

// VPN
implementation(project(":ics-openvpn"))

// Network
implementation("com.squareup.okhttp3:okhttp:4.11.0")

// Crypto
implementation("androidx.security:security-crypto:1.1.0-alpha06")

// Supabase client
implementation("io.github.supabase:gotrue-kt:3.0.0")
```

**Critical Files:**
- `MainActivity.kt` — Entry point, onboarding flow
- `BillingManager.kt` — Google Play Billing integration
- `VPNService.kt` — Manages ics-openvpn lifecycle
- `CloudflareWorkerClient.kt` — Communicates with Worker
- `TrialManager.kt` — Tracks 3-day trial expiry

#### 3.2 Cloudflare Workers (API Gateway)

**Free Tier:** 100,000 requests/day (sufficient for 10k+ users)

**Environment Secrets:**
```
VPNRESELLERS_API_TOKEN = "your-api-token"
GOOGLE_PLAY_PACKAGE_NAME = "com.yourcompany.vpnapp"
SUPABASE_URL = "https://your-project.supabase.co"
SUPABASE_SERVICE_ROLE_KEY = "service-key"
WORKER_AUTH_SECRET = "random-32-char-string"
```

**Endpoints:**

```javascript
// 1. Purchase Verification → Create VPN Account
POST /api/v1/purchase/verify
Headers: Authorization: Bearer {auth-token}
Body: {
  packageName: "com.yourcompany.vpnapp",
  productId: "vpn_monthly", // or vpn_6month, vpn_annual
  purchaseToken: "google-play-purchase-token",
  userId: "unique-user-id"
}
Response: { vpnUsername, expiresAt, status: "active" }

// 2. Check VPN Account Status
GET /api/v1/account/status
Headers: Authorization: Bearer {user-id}
Response: { status, vpnUsername, expiresAt, daysRemaining }

// 3. Disable Account (on trial expiry)
PUT /api/v1/account/disable
Headers: Authorization: Bearer {user-id}
Response: { status: "disabled" }
```

**Key Logic:**
```
1. Receive purchase token from Android app
2. Call Google Play Developer API to verify token is authentic
3. Extract productId (month/6month/year) & calculate expiry
4. Call VPNResellers POST /accounts to create account
5. Store in Supabase: user_id → vpn_username + expiry_date
6. Return credentials to app
7. On renewal: update expiry in Supabase
8. On trial expiry without purchase: disable account via PUT /accounts/{id}/disable
```

**Deploy:**
```bash
wrangler login
wrangler deploy
# Test: curl https://your-worker.workers.dev/api/v1/account/status
```

#### 3.3 Supabase (User Database)

**Free Tier:** 500MB storage, 50,000 MAU

**Schema:**

```sql
-- Users table
CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email TEXT UNIQUE,
  play_store_user_id TEXT UNIQUE,
  created_at TIMESTAMP DEFAULT NOW(),
  trial_started_at TIMESTAMP,
  trial_ended_at TIMESTAMP,
  first_paid_at TIMESTAMP
);

-- VPN Accounts table
CREATE TABLE vpn_accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  vpn_username TEXT UNIQUE,
  vpn_account_id INT, -- VPNResellers account ID
  status TEXT DEFAULT 'active', -- active, disabled, expired
  expires_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT NOW(),
  plan_type TEXT -- monthly, 6month, annual
);

-- Billing Events table (for analytics)
CREATE TABLE billing_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id),
  event_type TEXT, -- purchase, renewal, cancellation, refund
  product_id TEXT,
  amount_paid DECIMAL,
  play_billing_token TEXT UNIQUE,
  processed_at TIMESTAMP DEFAULT NOW()
);
```

**Row-Level Security (RLS):**
```sql
ALTER TABLE vpn_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users see own accounts"
  ON vpn_accounts
  FOR SELECT
  USING (auth.uid() = user_id);
```

#### 3.4 VPNResellers API

**Pricing:** $1.99 per active account per day (prorated)

**Critical Endpoints** (from API docs):

1. **Check username availability** (pre-create):
```bash
GET v3/accounts/check_username?username=user123
Response: { message: "The username is not taken." }
```

2. **Create account** (on purchase):
```bash
POST v3/accounts
Body: { username: "user_<timestamp>", password: "<random-32-char>" }
Response: { id: 123, username: "user_...", status: "Active" }
```

3. **Get configuration file** (for connecting):
```bash
GET v3/configuration?server_id=3&port_id=1
Response: { download_url, file_body: "client\ndev tun\n..." }
```

4. **Disable account** (on trial expiry):
```bash
PUT v3/accounts/123/disable
Response: { status: "Disabled" }
```

5. **Enable account** (on renewal):
```bash
PUT v3/accounts/123/enable
Response: { status: "Active" }
```

6. **List servers** (for UI dropdown):
```bash
GET v3/servers
Response: [
  { id: 1, name: "ams-s02.321inter.net", country_code: "NL", city: "Amsterdam" },
  { id: 3, name: "bkk-s02.321inter.net", country_code: "TH", city: "Bangkok" }
]
```

**Setup:**
- <cite index="29-1">Sign up at vpnresellers.com, add credit ($100+ recommended for buffer), get API token</cite>
- Store token in Cloudflare Worker secrets, never in app

#### 3.5 Google Play Billing (Purchase Verification)

<cite index="32-1">Google recommends using a secure backend server to verify purchases via the Google Play Developer API</cite>. This is exactly what your Cloudflare Worker does.

**Setup Steps:**
1. Create Google Play Service Account via Google Cloud Console
2. Grant it access to Google Play Developer API
3. Download service account JSON key
4. Store in Cloudflare Worker secrets
5. Call Google Play Developer API from Worker to verify tokens

---

## 4. Development Phases

### Phase 1: Core Architecture (Week 1-2)

**Goals:**
- Set up Android project with Compose
- Deploy basic Cloudflare Worker
- Create Supabase tables
- Integrate ics-openvpn

**Deliverables:**
- [ ] Android project scaffolded with Compose
- [ ] Cloudflare Worker deployed with test endpoint
- [ ] Supabase DB schema created
- [ ] ics-openvpn library integrated & compiling
- [ ] Mock VPN connection flow (offline)

**Testing:**
```bash
# Test Worker
curl -H "Authorization: Bearer test-secret" \
  https://your-worker.workers.dev/api/v1/account/status

# Check Supabase tables exist
psql "postgresql://..." -c "\dt"
```

**Web Search Verification:**
- [ ] <cite index="37-1">Confirm Billing Library version 8+ is required by Aug 31, 2026</cite>
- [ ] <cite index="40-1">Verify ics-openvpn target Java 17, Kotlin 1.9+</cite>

---

### Phase 2: Google Play Billing (Week 3-4)

**Goals:**
- Implement Play Billing Library for 3 SKUs
- Add purchase verification in Worker
- Connect trial logic

**Deliverables:**
- [ ] 3 subscription products in Google Play Console (monthly, 6-month, annual)
- [ ] BillingManager.kt written & tested locally
- [ ] Worker endpoint for purchase verification working
- [ ] Trial 3-day countdown UI in app
- [ ] Trial expiry → account disable flow working

**Testing:**
```kotlin
// Simulated purchase (before real Play testing)
// Use Play Console's "License Testers" for testing
val mockPurchase = PurchaseHistoryRecord(
  sku = "vpn_monthly",
  purchaseToken = "test-token-123"
)
// Pass to verification endpoint
```

**Checklist:**
- [ ] <cite index="34-1">Implement grace period & account hold for failed renewals (default 60-day hold since Dec 2025)</cite>
- [ ] <cite index="35-1">Enable Real-Time Developer Notifications (RTDN) for purchase events</cite>

---

### Phase 3: VPN Connectivity (Week 5-6)

**Goals:**
- Fetch .ovpn configs from VPNResellers
- Connect via ics-openvpn
- Display connection status

**Deliverables:**
- [ ] Server picker UI with VPNResellers server list
- [ ] Fetch + parse .ovpn configuration files
- [ ] VPNService.kt lifecycle management
- [ ] Connection toggle UI
- [ ] Speed test / connection indicator
- [ ] Data usage display

**Testing:**
```kotlin
// Fetch server list
Worker.getServers() 
// Should return: [{ id: 1, city: "Amsterdam", country_code: "NL" }, ...]

// Import .ovpn config into ics-openvpn
val configFile = "client\ndev tun\nremote ams-s02.321inter.net\n..."
// ics-openvpn should parse and allow connection
```

**Checklist:**
- [ ] <cite index="47-1">Test .ovpn file import via ics-openvpn AIDL API</cite>
- [ ] Verify connection persists across app restarts
- [ ] Test disconnect → reconnect cycle

---

### Phase 4: Security Hardening (Week 7)

**Goals:**
- Encrypt stored credentials
- Secure API communication
- Rate limiting & auth

**Deliverables:**
- [ ] EncryptedSharedPreferences for VPN credentials
- [ ] HTTPS + certificate pinning for Worker calls
- [ ] Rate limiting in Worker (max 10 requests/min per user)
- [ ] Auth token rotation logic
- [ ] Obfuscate API calls with Proguard/R8

**Security Audit Checklist:**
- [ ] VPNResellers token NOT in APK (only in Worker)
- [ ] Purchase tokens validated server-side only
- [ ] .ovpn configs stored encrypted
- [ ] No plaintext passwords in logs
- [ ] VPN kill-switch logic (disconnect if tunnel drops)

**Implementation Example:**
```kotlin
// Encrypted storage
val encryptedPrefs = EncryptedSharedPreferences.create(
  context, "vpn_prefs", masterKey,
  EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
  EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

// Store VPN username encrypted
encryptedPrefs.edit().putString("vpn_username", username).apply()
```

---

### Phase 5: Testing & QA (Week 8)

**Goals:**
- Full integration testing
- Edge case handling
- Performance optimization

**Test Cases:**
- [ ] Trial expiry blocks VPN without error
- [ ] Renewal extends expiry correctly
- [ ] Refund → account disable workflow
- [ ] Network loss → auto-reconnect
- [ ] Multiple device login (verify only 1 active session)
- [ ] Server switching (mid-connection)
- [ ] Background reconnection after phone restart

**Load Testing:**
```bash
# Simulate 100 concurrent trial conversions
ab -n 100 -c 10 -H "Authorization: Bearer token" \
  https://your-worker.workers.dev/api/v1/purchase/verify
```

**Checklist:**
- [ ] <cite index="34-1">Verify price increase notification at 30-day minimum (60 days in some regions)</cite>
- [ ] Handle RTDN webhook failures gracefully

---

### Phase 6: Beta Launch (Week 9-10)

**Goals:**
- Closed beta on Google Play Console
- Real user testing
- Analytics setup

**Deliverables:**
- [ ] App published to "Internal Testing" track
- [ ] 50-100 beta testers (via personal network, Reddit)
- [ ] Firebase Crashlytics + Analytics integrated
- [ ] Feedback collection form in app
- [ ] Crash report monitoring

**Metrics to Track:**
```
- Installs per day
- Trial start → purchase conversion rate (target: 10%+)
- Trial start → expiry churn (target: <5% within 3 days)
- Average session length
- Server selection distribution
- Crash rate (target: <0.1%)
```

---

### Phase 7: Production Launch (Week 11+)

**Goals:**
- Submit to Google Play (Production track)
- Monitor real users
- Scale infrastructure if needed

**Pre-Launch Checklist:**
- [ ] App signed with release keystore
- [ ] Privacy policy published (link in Play Console)
- [ ] Terms of Service drafted
- [ ] Customer support email set up
- [ ] Supabase backups automated
- [ ] Cloudflare Worker monitoring + alerts enabled
- [ ] VPNResellers account has minimum $50 credit buffer

**Post-Launch Monitoring:**
- [ ] Daily check: Supabase row count (users table)
- [ ] Daily check: Cloudflare Worker error logs
- [ ] Weekly: VPNResellers balance (alert if < $20)
- [ ] Weekly: Google Play Console crash/ANR rates
- [ ] Monthly: Conversion rate & LTV analysis

---

## 5. User Flow Diagrams

### 5.1 Trial → Paid Conversion

```
┌──────────────────┐
│  Install App     │
└────────┬─────────┘
         │
         ▼
┌──────────────────────────┐
│ Onboarding Screen        │
│ [Start 3-Day Trial]      │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────────────────┐
│ Android app generates unique user_id │
│ Sends to: Worker /api/purchase/trial │
└────────┬─────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│ Worker:                                 │
│ 1. Create Supabase user (trial_started) │
│ 2. Create VPNResellers account          │
│ 3. Fetch .ovpn config                   │
│ 4. Return to app                        │
└────────┬────────────────────────────────┘
         │
         ▼
┌──────────────────────────────┐
│ App displays trial countdown │
│ User can connect VPN now     │
└────────┬─────────────────────┘
         │
         ├─ [Day 1-3: Free VPN access]
         │
         └─ [Day 4: Show "Subscribe" prompt]
                     │
              ┌──────┴──────┐
              │             │
              ▼             ▼
         [Subscribe]   [Not Now]
              │             │
              ▼             ▼
         ┌────────┐    ┌──────────────┐
         │  Play  │    │ Worker: PUT  │
         │ Billing│    │ /disable     │
         │        │    │ VPN account  │
         └───┬────┘    └──────────────┘
             │
             ▼
         ┌────────────────────┐
         │ User confirms      │
         │ purchase           │
         └────┬───────────────┘
             │
             ▼
         ┌──────────────────────────────────────┐
         │ Android sends purchase token to      │
         │ Worker /api/purchase/verify          │
         └────┬───────────────────────────────┘
             │
             ▼
         ┌──────────────────────────────────────┐
         │ Worker:                              │
         │ 1. Verify with Google Play API       │
         │ 2. Update Supabase: first_paid_at    │
         │ 3. Extend VPN expiry in DB           │
         │ 4. Enable account if disabled        │
         └────┬───────────────────────────────┘
             │
             ▼
         ┌──────────────────┐
         │ User is now paid │
         │ subscriber!      │
         └──────────────────┘
```

### 5.2 Renewal & Expiry

```
[Day 1 of subscription purchased]
    ↓
    ├─ Supabase: expires_at = now + 30 days
    ├─ VPNResellers: account active
    ├─ App: shows "Valid until [date]"
    │
    ├─ [Days 1-27: Normal operation]
    │
    ├─ [Day 28: Google Play Developer Notification (webhook)]
    │   ├─ Type: SUBSCRIPTION_RENEWED
    │   ├─ Worker receives RTDN
    │   ├─ Supabase: expires_at = now + 30 days (again)
    │   └─ VPNResellers: still active
    │
    ├─ [Day 30: Manual check on app open]
    │   ├─ GET /api/account/status
    │   ├─ Response: daysRemaining = 0
    │   └─ App shows "Renew Now" banner
    │
    └─ [Day 31 (no renewal): Expiry action]
        ├─ Worker: PUT /accounts/{id}/disable
        ├─ VPNResellers: account disabled
        ├─ Supabase: status = "expired"
        └─ App: shows "Subscription expired" → purchase prompt
```

---

## 6. Implementation Checklist

### Android App

**Project Setup:**
- [ ] Create new Android project (API 26+)
- [ ] Add Compose & Kotlin Gradle setup
- [ ] Configure ProGuard/R8 obfuscation rules

**UI Components:**
- [ ] Onboarding/welcome screen
- [ ] Server picker dropdown
- [ ] Connect/disconnect toggle button
- [ ] Trial countdown timer
- [ ] VPN status display (connected/disconnected/error)
- [ ] Settings page (account, profile, privacy policy link)
- [ ] Subscription management screen

**Core Logic:**
- [ ] BillingManager.kt — Google Play Billing integration
- [ ] VPNService.kt — ics-openvpn lifecycle
- [ ] CloudflareWorkerClient.kt — API communication
- [ ] TrialManager.kt — Trial expiry tracking
- [ ] NotificationManager.kt — VPN status notifications

**Security:**
- [ ] EncryptedSharedPreferences for storing VPN credentials
- [ ] Certificate pinning for HTTPS
- [ ] Obfuscation via R8
- [ ] VPN kill-switch (disconnect on tunnel drop)

### Cloudflare Worker

**Endpoints:**
- [ ] POST /api/v1/purchase/verify (create account from purchase token)
- [ ] GET /api/v1/account/status (check account validity)
- [ ] PUT /api/v1/account/disable (on trial expiry)
- [ ] GET /api/v1/servers (list available servers)
- [ ] POST /api/v1/webhook/rtdn (handle renewal/cancellation notifications)

**Error Handling:**
- [ ] Retry logic for VPNResellers API failures
- [ ] Detailed error messages to app
- [ ] Rate limiting (10 req/min per user)
- [ ] Logging for debugging

### Supabase

**Tables:**
- [ ] users (id, email, play_store_user_id, trial_started_at, first_paid_at)
- [ ] vpn_accounts (user_id, vpn_username, status, expires_at, plan_type)
- [ ] billing_events (user_id, event_type, product_id, amount_paid, processed_at)

**Security:**
- [ ] Enable RLS on vpn_accounts
- [ ] Create policies for user access

**Backups:**
- [ ] Enable automated daily backups
- [ ] Test restore procedure

### Google Play Console

**Setup:**
- [ ] Create app listing
- [ ] Add 3 subscription products:
  - [ ] vpn_monthly (₹600/month)
  - [ ] vpn_6month (₹3000/6 months)
  - [ ] vpn_annual (₹5000/year)
- [ ] Configure base plans & pricing for each region
- [ ] Enable RTDN (Real-Time Developer Notifications)
- [ ] Create Google Play Service Account for API access

**Compliance:**
- [ ] Write Privacy Policy (data collection, VPN logs)
- [ ] Write Terms of Service
- [ ] Add store description with feature list
- [ ] Add screenshots showing trial & subscription options

### VPNResellers

**Account Setup:**
- [ ] Sign up & create account
- [ ] Add payment method & load credit ($100+ minimum)
- [ ] Get API token
- [ ] Test API endpoints with curl
- [ ] Monitor daily balance

---

## 7. Cost Analysis

### Monthly Operating Costs (at scale)

| Component | Cost | Usage |
|-----------|------|-------|
| Cloudflare Workers | Free | 100k req/day |
| Supabase | Free | 50k MAU |
| Google Play Billing | 15% cut | Included above |
| VPNResellers (per user) | $1.99/month | Variable |
| **Total (100 users)** | **~$199** | — |
| **Total (1000 users)** | **~$1,999** | — |

### Revenue at Scale

```
100 active users × ₹600/month = ₹60,000/month (~$720)
Google Play cut (15%): ₹9,000 (~$108)
Your revenue: ₹51,000 (~$612)
VPNResellers cost (100 × $1.99): ~$199
NET PROFIT: ₹50,801/month (~$613)
```

### Break-even Analysis

```
Fixed costs: ~$0 (all free tiers used)
Variable cost per user: $1.99
Revenue per user: $6.12 (avg across 3 plans, minus Play cut)
Gross margin: 68%
Break-even: Basically at first user (no fixed costs!)
```

---

## 8. Risk Mitigation

### Technical Risks

| Risk | Mitigation |
|------|-----------|
| VPNResellers API downtime | Use API error response codes; queue failover logic in Worker |
| Google Play Billing bugs | Test with mock purchases in sandbox; maintain debug build track |
| ics-openvpn crashes | Use latest stable version; implement crash reporting via Crashlytics |
| User session conflicts | Enforce single-session per account (VPNResellers has this built-in) |

### Business Risks

| Risk | Mitigation |
|------|-----------|
| India govt. VPN restrictions | Monitor local news; have exit strategy (pivot to other regions) |
| High churn (low LTV) | Implement in-app onboarding; show speed/server benefits early |
| Account fraud (fake trials) | Limit 1 trial per Play Store ID; require Play auth |
| Competitor price war | Build unique features (speed test, smart server selection) later |

### Security Risks

| Risk | Mitigation |
|------|-----------|
| VPNResellers token leaked | Rotate token quarterly; never commit to repo; use Cloudflare secrets |
| App reverse-engineering | Use ProGuard/R8; implement SSL pinning; check app integrity via Play |
| Purchase token theft | Verify tokens server-side only; use HTTPS; implement CSRF protection |
| Data breach (Supabase) | Enable RLS; regular backups; minimal data stored (no payment info) |

---

## 9. Deployment Checklist

### Before First Beta Release

- [ ] App signed with release keystore
- [ ] All secrets stored in Cloudflare Worker (never in code)
- [ ] Supabase DB backups enabled
- [ ] Error logging configured (Firebase Crashlytics)
- [ ] Analytics configured (Firebase Analytics)
- [ ] Privacy Policy published & linked
- [ ] Terms of Service drafted
- [ ] Support email set up (e.g., support@yourcompany.com)
- [ ] VPNResellers account balance > $50
- [ ] Cloudflare Worker logs monitored

### Before Production Launch

- [ ] 50+ beta testers completed trial
- [ ] Conversion rate > 5% observed
- [ ] No critical crashes in beta
- [ ] Renewals working (simulate via Play Console testers)
- [ ] Refund → account disable flow tested
- [ ] RTDN webhook tested with real Google notifications
- [ ] App reviewed by security consultant (optional)
- [ ] Google Play Store approval (usually 24-48 hours)

---

## 10. Web Research Verification & Sources

### Google Play Billing (2026 Changes)

<cite index="33-1">As of June 30, 2026, Google Play subscription fees split into 10% service fee + 5% billing fee (still 15% total for most apps). Users can now use alternative payment methods or web checkout in US, UK, EEA.</cite>

<cite index="34-1">By August 31, 2026, all new apps and updates must use Billing Library 8+. Grace period for failed renewals is recommended; Account Hold lasts 60 days by default (extended from 30 days in Dec 2025).</cite>

<cite index="35-1">Google Play Billing Library 9.0 (I/O 2026) adds subscription benefits translation via AI, agentic bulk price changes in Play Console, and flexible subscription management for plan changes at cancellation.</cite>

### VPN Market (India)

<cite index="22-1">Surfshark's 2-year plan costs ~₹149/month; NordVPN is ₹249-379/month depending on plan; ExpressVPN monthly is ₹550+. Your ₹600/month is 5-9x cheaper than competitors' monthly tiers.</cite>

### VPNResellers Pricing

<cite index="29-1">VPNResellers charges daily per active account (~$1.99/month). If you delete an account after 7 days, you pay only for those 7 days. No signup fees.</cite>

### Android Development (2026)

<cite index="37-1">Billing Library version 8+ required by August 31, 2026 for new/updated apps.</cite>

<cite index="46-1">ics-openvpn uses Android VPNService (API 14+); no root required. GitHub: schwabe/ics-openvpn; open source under GPLv2.</cite>

---

## 11. Success Metrics

### Launch Metrics (First Month)

- [ ] 100+ installs
- [ ] 20+ trial starts
- [ ] 2+ paid conversions
- [ ] < 0.5% crash rate
- [ ] 95%+ backend availability

### Growth Metrics (3 Months)

- [ ] 1,000+ installs
- [ ] 100+ active paid users
- [ ] 10%+ trial → paid conversion
- [ ] 4+ star average rating
- [ ] 0% critical security issues

### Long-term Goals (12 Months)

- [ ] 10,000+ installs
- [ ] 500+ active paid users
- [ ] ₹30,000+/month recurring revenue
- [ ] Expand to 2-3 other Asian markets
- [ ] Add advanced features (split tunnel, kill switch, speed test)

---

## 12. Future Roadmap (Post-Launch)

### V1.1 (Month 2-3)
- [ ] Speed test feature (built-in)
- [ ] Smart server selection (fastest/nearest)
- [ ] Light/dark mode UI
- [ ] App store optimization (ASO)

### V1.2 (Month 4-5)
- [ ] Kill-switch toggle
- [ ] Split tunneling (selective app routing)
- [ ] VPN protocol selection (OpenVPN TCP/UDP)
- [ ] No-logs audit or transparency report

### V1.3+ (Month 6+)
- [ ] WireGuard protocol support (if VPNResellers adds it)
- [ ] Multi-account support (family plan)
- [ ] Ad-free tier (via in-app purchase)
- [ ] iOS app launch
- [ ] Referral program (₹100 bonus per ref)

---

## 13. Questions to Resolve Before Build Starts

1. **VPNResellers API Rate Limits?** — Check documentation for request throttling
2. **Google Play Service Account Setup?** — Verify process for obtaining credentials
3. **Cloudflare RTDN Webhook?** — How to securely receive Google Play notifications?
4. **Data Retention Laws (India)?** — Does storing user email in Supabase violate CERT-In?
5. **VPN Kill-Switch Implementation?** — Does ics-openvpn support this natively?
6. **Multi-Account Same Device?** — Should you allow 2 users on 1 device or enforce logout?

---

## 14. Repository Structure

```
your-vpn-app/
├── android/
│   ├── app/
│   │   ├── src/main/java/com/yourcompany/vpn/
│   │   │   ├── MainActivity.kt
│   │   │   ├── VPNService.kt
│   │   │   ├── BillingManager.kt
│   │   │   ├── CloudflareWorkerClient.kt
│   │   │   ├── TrialManager.kt
│   │   │   └── ...
│   │   ├── build.gradle.kts
│   │   └── proguard-rules.pro
│   └── settings.gradle.kts
├── cloudflare-worker/
│   ├── src/
│   │   ├── index.ts
│   │   ├── google-play-verifier.ts
│   │   ├── vpnresellers-client.ts
│   │   └── supabase-client.ts
│   ├── wrangler.toml
│   └── package.json
├── database/
│   ├── schema.sql
│   ├── rls-policies.sql
│   └── migrations/
└── docs/
    ├── API-DOCS.md
    ├── DEPLOYMENT.md
    └── TROUBLESHOOTING.md
```

---

## 15. Final Notes

- **Timeline:** This is a 10-week realistic solo dev timeline with AI-assisted coding
- **Budget:** ~$0 startup cost (all free tiers + $20-50 for Google Play registration)
- **Tools:** Use Anthropic Antigravity for code generation; review + iterate
- **Support:** Join Android dev communities (r/androiddev, AndroidChat Slack) for quick help

**You've got this.** The architecture is proven (VPNResellers + Play Billing + Workers is used by dozens of indie VPN apps). Just execute systematically through these phases.

---

**Document Version:** 1.0  
**Last Updated:** July 28, 2026  
**Author:** Shu (Indie Developer)  
**License:** Internal Use Only
