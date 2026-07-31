# Budget VPN Backend & Production Deployment Guide

This guide details step-by-step instructions for deploying the Cloudflare Worker API, configuring Supabase, setting up Google Play purchase verification, and publishing the app.

---

## 1. Supabase Database Setup

1. Log into your [Supabase Dashboard](https://app.supabase.com) and create a new project.
2. Open the **SQL Editor** in your project.
3. Paste and run the contents of [backend/schema.sql](file:///f:/vpn%20android/backend/schema.sql).
4. Copy your project credentials from **Project Settings -> API**:
   - `SUPABASE_URL`
   - `SUPABASE_ANON_KEY`
   - `SUPABASE_SERVICE_ROLE_KEY`

---

## 2. Cloudflare Worker Deployment

1. Install Cloudflare Wrangler CLI (if not already installed):
   ```bash
   npm install -g wrangler
   ```
2. Authenticate with your Cloudflare account:
   ```bash
   wrangler login
   ```
3. Navigate to the `backend` directory and set the required production secret variables:
   ```bash
   wrangler secret put SUPABASE_URL
   wrangler secret put SUPABASE_ANON_KEY
   wrangler secret put SUPABASE_SERVICE_ROLE_KEY
   wrangler secret put WORKER_AUTH_SECRET
   wrangler secret put VPNRESELLERS_API_TOKEN
   ```
4. Deploy the worker:
   ```bash
   wrangler deploy
   ```
5. Note your deployed worker API endpoint URL (e.g. `https://vpn-api-worker.<your-subdomain>.workers.dev`).

---

## 3. Google Play Purchase Verification Setup

1. In **Google Cloud Console**, create a Service Account with access to Google Play Android Developer API.
2. Download the Service Account JSON credentials key.
3. Grant the Service Account permissions in **Google Play Console -> Users & Permissions**.
4. Configure service account secrets in Wrangler:
   ```bash
   wrangler secret put GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL
   wrangler secret put GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY
   ```
5. In **Google Play Console -> Monetization setup -> Real-time developer notifications**, set the Webhook URL to:
   `https://<your-worker-domain>/webhook/google-play`

---

## 4. Google Play Store Subscription Products Setup

Create the following 3 subscription products in **Google Play Console -> Subscriptions**:

| Product ID | Title | Price | Duration |
|---|---|---|---|
| `vpn_monthly` | Monthly Subscription | ₹600 | 1 Month |
| `vpn_6month` | 6-Month Subscription | ₹3,000 | 6 Months |
| `vpn_annual` | Annual Subscription | ₹5,000 | 1 Year |

---

## 5. Building Release AAB / APK

To generate the signed production release build:
```bash
./gradlew assembleRelease
# Or for Play Store upload bundle:
./gradlew bundleRelease
```
The output APK/AAB is located at `app/build/outputs/apk/release/` or `app/build/outputs/bundle/release/`.
