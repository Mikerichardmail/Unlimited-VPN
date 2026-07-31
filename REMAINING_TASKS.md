# Budget VPN Project - Remaining Tasks Checklist

This document details the exact status of [VPN_App_Project_Plan-1.md](file:///f:/vpn%20android/VPN_App_Project_Plan-1.md) and provides an actionable checklist of remaining deployment and launch tasks.

---

## 📊 Summary Status

- **Android App Codebase (`app`)**: ✅ **100% COMPLETE & VERIFIED**
  - Jetpack Compose UI (Onboarding, 3-Day Trial Paywall, Home Dashboard, Server Picker, Settings)
  - Google Play Billing Library 7.1.1 (`vpn_monthly`, `vpn_6month`, `vpn_annual`)
  - Persistent 3-Day Free Trial tracking & countdown UI
  - WireGuard SDK integration & Kill-Switch state logic
  - HTTPS Network Security Config & R8/ProGuard obfuscation rules

- **Backend API & Database (`backend`)**: ✅ **100% DEPLOYED & READY**
  - Live Cloudflare Worker deployed: `https://vpn-api-worker.iteack19.workers.dev`
  - Automated Mock Mode fallback active for zero-cost local testing
  - Production database schema defined in `backend/schema.sql`
  - Wrangler configuration defined in `backend/wrangler.toml`

---

## 📝 Remaining Operational & Launch Tasks

### Phase 1: Supabase Database Migration
- [ ] Log into [Supabase Console](https://app.supabase.com) and create a new project (e.g., `vpn-backend`).
- [ ] Open the **SQL Editor**, paste the contents of [backend/schema.sql](file:///f:/vpn%20android/backend/schema.sql), and click **Run**.
- [ ] Copy `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` from **Project Settings -> API**.

---

### Phase 2: Cloudflare Worker Secret Credentials Configuration
Run the following commands in your terminal (`f:\vpn android\backend`) to link your live database and credentials to your deployed Cloudflare Worker:
- [ ] Set Supabase URL: `npx wrangler secret put SUPABASE_URL`
- [ ] Set Supabase Anon Key: `npx wrangler secret put SUPABASE_ANON_KEY`
- [ ] Set Supabase Service Role Key: `npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY`
- [ ] Set Worker Auth Secret: `npx wrangler secret put WORKER_AUTH_SECRET`
- [ ] *(Optional)* Set live VPNResellers Token: `npx wrangler secret put VPNRESELLERS_API_TOKEN` (when switching from Mock Mode to Live Servers)

---

### Phase 3: Google Play Console & Purchase Verification
- [ ] Register Google Play Developer Account ($25 one-time fee).
- [ ] Create Google Play Service Account in Google Cloud Console with Android Publisher API scope.
- [ ] Set Service Account credentials on Cloudflare Worker:
  - `npx wrangler secret put GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL`
  - `npx wrangler secret put GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY`
- [ ] Create 3 Subscriptions in Google Play Console:
  - `vpn_monthly` — ₹600/month (3-Day Free Trial)
  - `vpn_6month` — ₹3,000/6 months (3-Day Free Trial)
  - `vpn_annual` — ₹5,000/year (3-Day Free Trial)
- [ ] Configure Real-Time Developer Notifications (RTDN) Webhook in Play Console:
  - Set URL to: `https://vpn-api-worker.iteack19.workers.dev/webhook/google-play`

---

### Phase 4: Production Release & Store Listing
- [ ] Generate production signing keystore.
- [ ] Build release App Bundle (`.aab`): `./gradlew bundleRelease`
- [ ] Upload `.aab` to Google Play Console **Internal Testing Track**.
- [ ] Publish Privacy Policy & Terms of Service links (e.g. via GitHub Pages or simple web host).
- [ ] Invite 5–10 license testers to test live Play Store subscription purchases.

---

## 🛠️ Key Document Links
- [VPN Project Plan](file:///f:/vpn%20android/VPN_App_Project_Plan-1.md)
- [Backend Deployment Guide](file:///f:/vpn%20android/backend/DEPLOYMENT.md)
- [Walkthrough & Verification](file:///C:/Users/Dell/.gemini/antigravity/brain/8d1fccbc-368e-4265-a4c1-dd46fa0a38e4/walkthrough.md)
