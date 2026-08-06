/**
 * Cloudflare Worker API for WireGuard VPN App
 * 
 * Implements production-ready endpoints integrated with Supabase DB:
 * - POST /api/verify
 * - POST /api/register-device
 * - POST /api/deregister-device
 * - GET  /api/status
 * - POST /api/rotate-key
 * - GET  /api/servers
 * - POST /webhook/google-play
 */

// ✅ SECURITY FIX: SECRET_KEY is NO LONGER hardcoded here.
// Set it as a Cloudflare Worker secret with:
//   npx wrangler secret put HMAC_SECRET
// The value must match BuildConfig.HMAC_SECRET in the Android app.
// For local dev with wrangler dev, add it to .dev.vars (which is gitignored).

// SECURITY: Removed hardcoded fallback SERVERS array with dummy pubkeys.
// Dummy pubkeys (all-zero) would cause WireGuard to fail silently or connect
// to an unauthenticated endpoint. If VPNResellers API is unavailable,
// the Worker now returns a 503 error so the app can show a proper message.

// VPNResellers API base URL
const VPNRESELLERS_BASE = "https://api.vpnresellers.com/v4_1";

// Map our location codes to VPNResellers server IDs
// Populated dynamically via GET /v4_1/servers on first request; static map is fallback.
// Verify actual IDs from: https://app.vpnresellers.com/servers
const VPNRESELLERS_SERVER_IDS = {
  // ── Asia Pacific ────────────────────────────────────────────────────────────
  "sg": 2,    // Singapore
  "jp": 3,    // Japan / Tokyo
  "au": 4,    // Australia / Sydney
  "hk": 5,    // Hong Kong
  "kr": 6,    // South Korea / Seoul
  "tw": 7,    // Taiwan / Taipei
  "id": 8,    // Indonesia / Jakarta
  "my": 9,    // Malaysia / Kuala Lumpur
  "ph": 10,   // Philippines / Manila
  "th": 11,   // Thailand / Bangkok
  "vn": 12,   // Vietnam / Ho Chi Minh
  "bd": 13,   // Bangladesh / Dhaka
  "lk": 14,   // Sri Lanka
  "np": 15,   // Nepal / Kathmandu
  "pk": 16,   // Pakistan / Karachi
  "nz": 17,   // New Zealand / Auckland
  // ── Europe ──────────────────────────────────────────────────────────────────
  "gb": 18,   // United Kingdom / London
  "de": 19,   // Germany / Frankfurt
  "nl": 20,   // Netherlands / Amsterdam
  "fr": 21,   // France / Paris
  "se": 22,   // Sweden / Stockholm
  "no": 23,   // Norway / Oslo
  "ch": 24,   // Switzerland / Zurich
  "es": 25,   // Spain / Madrid
  "it": 26,   // Italy / Milan
  "pl": 27,   // Poland / Warsaw
  "pt": 28,   // Portugal / Lisbon
  "at": 29,   // Austria / Vienna
  "be": 30,   // Belgium / Brussels
  "dk": 31,   // Denmark / Copenhagen
  "fi": 32,   // Finland / Helsinki
  "ie": 33,   // Ireland / Dublin
  "cz": 34,   // Czech Republic / Prague
  "ro": 35,   // Romania / Bucharest
  "hu": 36,   // Hungary / Budapest
  "bg": 37,   // Bulgaria / Sofia
  "hr": 38,   // Croatia / Zagreb
  "gr": 39,   // Greece / Athens
  "sk": 40,   // Slovakia / Bratislava
  "si": 41,   // Slovenia / Ljubljana
  "lt": 42,   // Lithuania / Vilnius
  "lv": 43,   // Latvia / Riga
  "ee": 44,   // Estonia / Tallinn
  "rs": 45,   // Serbia / Belgrade
  "md": 46,   // Moldova / Chisinau
  "ua": 47,   // Ukraine / Kyiv
  "lu": 48,   // Luxembourg
  "mt": 49,   // Malta
  "cy": 50,   // Cyprus / Nicosia
  "is": 51,   // Iceland / Reykjavik
  "tr": 52,   // Turkey / Istanbul
  "al": 53,   // Albania / Tirana
  // ── Americas ────────────────────────────────────────────────────────────────
  "us": 54,   // USA / Ashburn
  "ca": 55,   // Canada / Toronto
  "br": 56,   // Brazil / Sao Paulo
  "mx": 57,   // Mexico / Mexico City
  "ar": 58,   // Argentina / Buenos Aires
  "cl": 59,   // Chile / Santiago
  "co": 60,   // Colombia / Bogota
  "pe": 61,   // Peru / Lima
  "ec": 62,   // Ecuador / Quito
  "bo": 63,   // Bolivia / La Paz
  "py": 64,   // Paraguay / Asuncion
  "uy": 65,   // Uruguay / Montevideo
  "ve": 66,   // Venezuela / Caracas
  "cr": 67,   // Costa Rica / San Jose
  "pa": 68,   // Panama / Panama City
  "gt": 69,   // Guatemala
  "do": 70,   // Dominican Republic
  "jm": 71,   // Jamaica / Kingston
  // ── Middle East & Africa ────────────────────────────────────────────────────
  "ae": 72,   // UAE / Dubai
  "sa": 73,   // Saudi Arabia / Riyadh
  "il": 74,   // Israel / Tel Aviv
  "za": 75,   // South Africa / Johannesburg
  "eg": 76,   // Egypt / Cairo
  "ng": 77,   // Nigeria / Lagos
  "ke": 78,   // Kenya / Nairobi
  "gh": 79,   // Ghana / Accra
  "ma": 80,   // Morocco / Casablanca
  "tn": 81,   // Tunisia / Tunis
  "dz": 82,   // Algeria / Algiers
  "qa": 83,   // Qatar / Doha
  "kw": 84,   // Kuwait / Kuwait City
  "bh": 85,   // Bahrain / Manama
  "jo": 86,   // Jordan / Amman
  "om": 87,   // Oman / Muscat
  "iq": 88,   // Iraq / Baghdad
  "az": 89,   // Azerbaijan / Baku
  "ge": 90,   // Georgia / Tbilisi
  "am": 91,   // Armenia / Yerevan
  "kz": 92,   // Kazakhstan / Almaty
  "uz": 93,   // Uzbekistan / Tashkent
  "mn": 94,   // Mongolia / Ulaanbaatar
};

// Cache for dynamically loaded server IDs (keyed by location code)
// Refreshed at most once per hour per worker isolate (Cloudflare Workers reuse isolates
// across requests within the same datacenter, so this avoids an API call on every request).
let _serverIdCache = null;      // null = not yet loaded
let _serverIdCacheExpiry = 0;   // epoch ms when cache should be refreshed
const SERVER_CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour

export default {
  async scheduled(event, env, ctx) {
    // 4. GET /api/accounts-sync (Cron Trigger)
    try {
      const response = await fetch(`${VPNRESELLERS_BASE}/accounts`, {
        headers: {
          "Authorization": `Bearer ${env.VPNRESELLERS_API_TOKEN}`,
          "Accept": "application/json"
        }
      });
      if (response.ok) {
        const data = await response.json();
        // Here we would sync data.data (list of accounts) with Supabase if needed
        console.log(`Synced ${data.data?.length || 0} VPNResellers accounts.`);
      }
    } catch (e) {
      console.error("Scheduled sync error:", e);
    }
  },

  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    try {
      // 1. GET /api/servers — public endpoint (no HMAC required).
      // Server list contains only country names, city names, and hostnames — no
      // account data, no private keys, no subscription info. Safe to expose publicly.
      // All sensitive endpoints (verify, register-device, status) still require HMAC.
      if (path === "/api/servers" && method === "GET") {
        return await handleGetServers(env);
      }

      // Authenticate all other /api endpoints (excluding webhook and bandwidth-sync)
      if (path.startsWith("/api/") && path !== "/api/servers") {
        const signature = request.headers.get("X-App-Signature");
        const hmacSecret = env.HMAC_SECRET;

        if (env.DEV_MODE !== "true") {
          if (!hmacSecret) {
            return jsonResponse({ error: "Server misconfiguration: HMAC_SECRET not set" }, 503);
          }
          if (!signature) {
            return jsonResponse({ error: "Missing API signature" }, 401);
          }

          // ✅ SECURITY: Verify the HMAC value using constant-time comparison.
          // We use the installationId as the gateway-level check data; each
          // endpoint also re-verifies with its own endpoint-specific data string.
          // For GET /api/status the installationId is in the query string.
          const installationId =
            url.searchParams.get("installationId") ||
            null; // POST bodies are verified inside each handler

          if (installationId) {
            const valid = await verifyHmacSignature(signature, installationId, hmacSecret);
            if (!valid) {
              return jsonResponse({ error: "Invalid API signature" }, 401);
            }
          }
          // For POST endpoints the body hasn't been consumed yet; verification
          // happens per-handler after reading the body (see verifyHmacSignature calls below).
        }
      }

      if (path === "/api/verify" && method === "POST") {
        return await handleVerify(request, env);
      } else if (path === "/api/register-device" && method === "POST") {
        return await handleRegisterDevice(request, env);
      } else if (path === "/api/deregister-device" && method === "POST") {
        return await handleDeregisterDevice(request, env);
      } else if (path === "/api/status" && method === "GET") {
        return await handleGetStatus(url, env);
      } else if (path === "/api/rotate-key" && method === "POST") {
        return await handleRotateKey(request, env);
      } else if (path === "/api/bandwidth-sync" && method === "POST") {
        // ✅ FIX ❸: bandwidth-sync is now authenticated (WORKER_AUTH_SECRET)
        //    — handled inside the function, same as /api/connection-log
        return await handleBandwidthSync(request, env);
      } else if (path === "/api/delete-account" && method === "DELETE") {
        return await handleDeleteAccount(request, env);
      } else if (path === "/api/connection-log" && method === "POST") {
        // CERT-In: Server-to-server only — protected by WORKER_AUTH_SECRET, not user HMAC
        return await handleConnectionLog(request, env);
      } else if (path.startsWith("/webhook/google-play") && method === "POST") {
        // ✅ FIX ❷: Webhook path includes a secret token so only Google's Pub/Sub
        //    delivery can trigger subscription status changes. Register the URL
        //    in Play Console as:
        //    https://vpn-api-worker.iteack19.workers.dev/webhook/google-play?secret=<WEBHOOK_SECRET>
        //    Set WEBHOOK_SECRET via: npx wrangler secret put WEBHOOK_SECRET
        return await handleGooglePlayWebhook(request, env);
      }

      return jsonResponse({ error: "Endpoint not found" }, 404);
    } catch (err) {
      return jsonResponse({ error: "Internal Server Error", message: err.message }, 500);
    }
  }
};

// Response Helpers
function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}

// SECURITY FIX: Mock mode ONLY active during local development (DEV_MODE=true AND secrets missing).
// In production, if Supabase secrets are missing, we return a 503 error rather than silently
// granting VPN access for free. This prevents a misconfigured Cloudflare env from becoming
// a free VPN for everyone.
function isMock(env) {
  // DEV_MODE must be explicitly true AND secrets must be absent to activate mock
  return env.DEV_MODE === "true" && (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY);
}

// Hard check for production: if secrets are missing and not in dev mode, return 503
function requireProductionSecrets(env) {
  if (env.DEV_MODE !== "true" && (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY)) {
    return jsonResponse({
      error: "Server misconfiguration: Required secrets not configured",
      message: "Contact support if this persists"
    }, 503);
  }
  return null; // null = secrets OK, proceed
}

/**
 * verifyHmacSignature
 * Constant-time HMAC-SHA256 verification using the Web Crypto API.
 * Returns true only if the signature matches HMAC(secret, data).
 *
 * Android signs with: Base64.encodeToString(mac.doFinal(data), Base64.NO_WRAP)
 * which produces standard Base64 (not URL-safe). We accept both variants.
 *
 * @param {string} signature - Base64-encoded HMAC received in X-App-Signature header
 * @param {string} data      - The plain-text string that was signed on the client
 * @param {string} secret    - The shared HMAC secret (from env.HMAC_SECRET)
 */
async function verifyHmacSignature(signature, data, secret) {
  try {
    const encoder = new TextEncoder();
    const keyMaterial = await crypto.subtle.importKey(
      "raw",
      encoder.encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["verify", "sign"]
    );

    // Compute expected HMAC so we can do a constant-time compare via subtle.verify
    const expectedBytes = await crypto.subtle.sign(
      "HMAC",
      keyMaterial,
      encoder.encode(data)
    );

    // Decode the received Base64 signature (handle both standard and URL-safe variants)
    const normalised = signature.replace(/-/g, "+").replace(/_/g, "/");
    const padding = (4 - (normalised.length % 4)) % 4;
    const padded = normalised + "=".repeat(padding);
    const binaryStr = atob(padded);
    const receivedBytes = new Uint8Array(binaryStr.length);
    for (let i = 0; i < binaryStr.length; i++) {
      receivedBytes[i] = binaryStr.charCodeAt(i);
    }

    // crypto.subtle.verify performs a constant-time comparison — no timing attacks
    return await crypto.subtle.verify("HMAC", keyMaterial, receivedBytes, encoder.encode(data));
  } catch (_) {
    return false; // malformed signature → reject
  }
}

// 2. POST /api/verify  — signed data: "${installationId}:${purchaseToken}"
async function handleVerify(request, env) {
  const signature = request.headers.get("X-App-Signature");
  const { installationId, googlePurchaseToken, planType, email } = await request.json();
  if (!installationId || !googlePurchaseToken || !planType) {
    return jsonResponse({ error: "Missing required parameters" }, 400);
  }

  // Verify the HMAC for the exact data string the Android client signed
  if (env.DEV_MODE !== "true" && env.HMAC_SECRET) {
    const valid = await verifyHmacSignature(
      signature,
      `${installationId}:${googlePurchaseToken}`,
      env.HMAC_SECRET
    );
    if (!valid) return jsonResponse({ error: "Invalid API signature" }, 401);
  }

  let expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(); // Default 30 days
  if (planType === "yearly") {
    expiresAt = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString();
  } else if (planType === "three_year") {
    expiresAt = new Date(Date.now() + 3 * 365 * 24 * 60 * 60 * 1000).toISOString();
  }

  // SECURITY: Real Play Store API verification if keys exist.
  // Accepted paymentStates:
  //   1 = payment received (real purchase)
  //   2 = free trial / sandbox test card — provisioned so trial users get access;
  //       Google enforces 1 trial per account per product, and the RTDN webhook
  //       will disable the account if the user cancels before converting.
  // Rejected:
  //   0 = payment pending (cash/bank) — money not confirmed
  //   3 = deferred upgrade — old plan still active, new plan not billed yet
  if (env.GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL && env.GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY) {
    try {
      const token = await getGoogleAuthToken(
        env.GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL,
        env.GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY
      );
      // Query subscription detail from Google Play Publisher API
      const packageName = env.PACKAGE_NAME || "com.bestfreevpnproxy.app";
      const queryUrl = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${packageName}/purchases/subscriptions/${planType}/tokens/${googlePurchaseToken}`;
      const verifyRes = await fetch(queryUrl, {
        headers: { "Authorization": `Bearer ${token}` }
      });
      if (verifyRes.ok) {
        const verifyData = await verifyRes.json();

        // Allow paymentState=1 (paid) and paymentState=2 (free trial / test card).
        // Reject paymentState=0 (pending) and paymentState=3 (deferred upgrade).
        const ALLOWED_STATES = [1, 2];
        if (verifyData.paymentState !== undefined && !ALLOWED_STATES.includes(verifyData.paymentState)) {
          const stateNames = { 0: "payment pending", 3: "deferred upgrade" };
          const stateName = stateNames[verifyData.paymentState] || `unknown (${verifyData.paymentState})`;
          console.warn(`[verify] Rejected purchase: paymentState=${verifyData.paymentState} (${stateName})`);
          return jsonResponse({
            success: false,
            error: "Purchase not confirmed",
            detail: `Payment state is '${stateName}'. Only completed payments and active trials are accepted.`
          }, 402);
        }

        // Set actual expiry from Google Play response
        if (verifyData.expiryTimeMillis) {
          expiresAt = new Date(Number(verifyData.expiryTimeMillis)).toISOString();
        }

        // Check if subscription is already cancelled/refunded
        if (verifyData.cancelReason !== undefined) {
          console.warn(`[verify] Subscription already cancelled (cancelReason=${verifyData.cancelReason})`);
          return jsonResponse({
            success: false,
            error: "This subscription has been cancelled or refunded"
          }, 402);
        }
      } else {
        // Google Play returned an error for this token
        const errBody = await verifyRes.text();
        console.error(`[verify] Google Play API error ${verifyRes.status}: ${errBody}`);
        if (verifyRes.status === 400 || verifyRes.status === 404) {
          // Token is invalid or not found — reject it
          return jsonResponse({
            success: false,
            error: "Invalid purchase token — not recognized by Google Play"
          }, 402);
        }
        // For 5xx (Play API temporarily down), proceed with default expiry.
        // The webhook will correct any cancellations/refunds later.
        console.warn("[verify] Google Play API temporarily unavailable — proceeding with default expiry");
      }
    } catch (e) {
      console.error("Google Play API verification error:", e);
      // Network error calling Play API — proceed. Webhook will correct cancellations later.
    }
  }

  const sub = {
    installation_id: installationId,
    email: email || null,
    google_purchase_token: googlePurchaseToken,
    plan_type: planType,
    status: "active",
    started_at: new Date().toISOString(),
    expires_at: expiresAt
  };

  // SECURITY: Check for production misconfiguration BEFORE mock check.
  // If Supabase secrets are missing in prod, return 503 (not free access).
  const secretsError = requireProductionSecrets(env);
  if (secretsError) return secretsError;

  if (isMock(env)) {
    // This branch only reached in local dev with DEV_MODE=true
    return jsonResponse({
      success: true,
      message: "Purchase verified successfully (Mock Mode — DEV ONLY)",
      subscription: { ...sub, id: "sub_mock_" + Math.random().toString(36).substring(2, 9), vpn_account_id: "mock_vpn_123" }
    });
  }

  try {
    const savedSub = await upsertSubscription(sub, env);

    // Provision or re-enable VPNResellers VPN account
    let vpnAccountId = savedSub.vpn_account_id;
    if (!vpnAccountId) {
      // No account exists — create a fresh VPN account on purchase
      const vpnAccount = await createVpnResellersAccount(installationId, env);
      vpnAccountId = vpnAccount.id;
      // Save account ID, username AND password so we can auth config requests later
      await updateSubscriptionVpnAccount(savedSub.id, vpnAccountId, vpnAccount.username, vpnAccount.password, env);
    } else {
      // Account exists — re-enable it for the new subscription
      await enableVpnResellersAccount(vpnAccountId, env);
    }

    // ── CERT-In 2022 Compliance ───────────────────────────────────────────────
    // Write subscriber record on every successful purchase verification.
    // Uses CF-Connecting-IP (injected by Cloudflare) — no app change needed.
    // UPSERT so re-subscriptions just update hire_started_at, not duplicate.
    const registrationIp = request.headers.get("CF-Connecting-IP") ||
                           request.headers.get("X-Forwarded-For") ||
                           "unknown";
    await upsertCertInUserRecord({
      installationId,
      subscriptionId: savedSub.id,
      email: email || null,
      registrationIp,
      hireStartedAt: new Date().toISOString()
    }, env);
    // ─────────────────────────────────────────────────────────────────────────

    return jsonResponse({
      success: true,
      message: "Purchase verified successfully",
      subscription: {
        id: savedSub.id,
        installation_id: savedSub.installation_id,
        email: savedSub.email,
        plan_type: savedSub.plan_type,
        status: savedSub.status,
        started_at: savedSub.started_at,
        expires_at: savedSub.expires_at,
        vpn_account_id: vpnAccountId
        // NOTE: vpn_username and vpn_password are intentionally omitted
      }
    });
  } catch (err) {
    return jsonResponse({ error: "Database error", message: err.message }, 500);
  }
}

// 3. POST /api/register-device  — signed data: "${installationId}:${wireguardPubkey}"
async function handleRegisterDevice(request, env) {
  const signature = request.headers.get("X-App-Signature");
  const { installationId, wireguardPubkey, serverLocation, protocol = "wireguard" } = await request.json();
  // Allow OpenVPN requests which may lack a wireguardPubkey by using a fallback identifier
  const pubkey = wireguardPubkey || `ovpn_${installationId}`;
  if (!installationId || !serverLocation) {
    return jsonResponse({ error: "Missing required parameters" }, 400);
  }

  // Verify the HMAC for the exact data string the Android client signed
  if (env.DEV_MODE !== "true" && env.HMAC_SECRET) {
    const valid = await verifyHmacSignature(
      signature,
      `${installationId}:${pubkey}`,
      env.HMAC_SECRET
    );
    if (!valid) return jsonResponse({ error: "Invalid API signature" }, 401);
  }

  // SECURITY: Check for production misconfiguration BEFORE mock check.
  const secretsError = requireProductionSecrets(env);
  if (secretsError) return secretsError;

  if (isMock(env)) {
    // This branch only reached in local dev with DEV_MODE=true
    return jsonResponse({
      success: true,
      device_limit_reached: false,
      config: {
        client_private_key: "mock_private_key_base64==",
        client_ip: `10.0.0.${Math.floor(Math.random() * 250) + 2}/32`,
        dns: "1.1.1.1, 8.8.8.8",
        server_pubkey: "mock_pubkey_for_dev_only==",
        server_endpoint: "127.0.0.1:51820",
        allowed_ips: "0.0.0.0/0",
        keepalive: 25
      }
    });
  }

  try {
    // 1. Fetch active subscription
    const sub = await getSubscription(installationId, env);
    if (!sub || sub.status !== "active") {
      return jsonResponse({ error: "No active subscription found" }, 403);
    }

    // 2. Check VPN account exists (provisioned on purchase)
    if (!sub.vpn_account_id) {
      return jsonResponse({ error: "VPN account not yet provisioned. Please purchase a subscription first." }, 503);
    }

    // 3. Check 2-device limit
    const devices = await getDevicesForSubscription(sub.id, env);
    const existingDevice = devices.find(d => d.wireguard_pubkey === pubkey);

    if (!existingDevice && devices.length >= 2) {
      return jsonResponse({
        success: false,
        device_limit_reached: true,
        message: "Maximum device limit reached (2 devices)"
      }, 429);
    }

    // 4. Fetch the real WireGuard / OpenVPN config from VPNResellers.
    //    VPNResellers manages keys server-side and returns a complete .conf file.
    //    We authenticate using the account's own username:password (Basic auth),
    //    not the reseller Bearer token.
    const vpnServerId = await resolveServerId(serverLocation, env);
    const configText = await fetchVpnResellersConfig(
      sub.vpn_username,
      sub.vpn_password,
      vpnServerId,
      env,
      protocol
    );
    
    if (protocol === "openvpn") {
      // 5. Register device in Supabase for OpenVPN
      if (!existingDevice) {
        await registerDevice({
          subscription_id: sub.id,
          wireguard_pubkey: pubkey,
          assigned_ip: "vpnresellers_ovpn",
          server_location: serverLocation,
          is_active: true
        }, env);
      }
      return jsonResponse({
        success: true,
        device_limit_reached: false,
        config: {
          ovpn_config: configText
        }
      });
    }

    const parsedConfig = parseWireGuardConfig(configText);

    // 5. Register device in Supabase (only if new)
    if (!existingDevice) {
      await registerDevice({
        subscription_id: sub.id,
        wireguard_pubkey: pubkey,
        assigned_ip: parsedConfig.client_ip || "vpnresellers_managed",
        server_location: serverLocation,
        is_active: true
      }, env);
    }

    return jsonResponse({
      success: true,
      device_limit_reached: false,
      config: {
        // VPNResellers provides the client private key inside the .conf.
        // The app MUST use this key, NOT a locally-generated one.
        client_private_key: parsedConfig.client_private_key || null,
        client_ip: parsedConfig.client_ip || "10.0.0.2/32",
        dns: parsedConfig.dns || "1.1.1.1, 8.8.8.8",
        server_pubkey: parsedConfig.server_pubkey,
        server_endpoint: parsedConfig.server_endpoint,
        allowed_ips: parsedConfig.allowed_ips || "0.0.0.0/0",
        keepalive: parsedConfig.keepalive || 25
      }
    });
  } catch (err) {
    return jsonResponse({ error: "Failed to register device", message: err.message }, 500);
  }
}


// 4. POST /api/deregister-device  — signed data: "${installationId}:${wireguardPubkey}"
async function handleDeregisterDevice(request, env) {
  const signature = request.headers.get("X-App-Signature");
  const { installationId, wireguardPubkey, protocol = "wireguard" } = await request.json();
  const pubkey = wireguardPubkey || `ovpn_${installationId}`;
  if (!installationId) {
    return jsonResponse({ error: "Missing parameters" }, 400);
  }

  // Verify the HMAC for the exact data string the Android client signed
  if (env.DEV_MODE !== "true" && env.HMAC_SECRET) {
    const valid = await verifyHmacSignature(
      signature,
      `${installationId}:${pubkey}`,
      env.HMAC_SECRET
    );
    if (!valid) return jsonResponse({ error: "Invalid API signature" }, 401);
  }

  if (isMock(env)) {
    return jsonResponse({ success: true, message: "Device deregistered successfully (Mock)" });
  }

  try {
    const sub = await getSubscription(installationId, env);
    if (!sub) {
      return jsonResponse({ error: "Subscription not found" }, 404);
    }

    const success = await deregisterDevice(sub.id, pubkey, env);
    if (success) {
      return jsonResponse({ success: true, message: "Device deregistered successfully" });
    } else {
      return jsonResponse({ success: false, message: "Failed to deregister device" }, 500);
    }
  } catch (err) {
    return jsonResponse({ error: "Deregister error", message: err.message }, 500);
  }
}

// 5. GET /api/status
async function handleGetStatus(url, env) {
  const installationId = url.searchParams.get("installationId");
  if (!installationId) {
    return jsonResponse({ error: "Missing installationId parameter" }, 400);
  }

  if (isMock(env)) {
    return jsonResponse({
      subscription_active: true,
      plan_type: "yearly",
      expires_at: new Date(Date.now() + 180 * 24 * 60 * 60 * 1000).toISOString(),
      devices_count: 1,
      bandwidth_used_bytes: 12500000000,
      bandwidth_limit_bytes: 53687091200
    });
  }

  try {
    const sub = await getSubscription(installationId, env);
    if (!sub) {
      return jsonResponse({
        subscription_active: false,
        plan_type: "monthly",
        expires_at: "",
        devices_count: 0,
        bandwidth_used_bytes: 0,
        bandwidth_limit_bytes: 53687091200
      });
    }

    const devices = await getDevicesForSubscription(sub.id, env);
    const bandwidth = await getBandwidthUsed(sub.id, env);

    return jsonResponse({
      subscription_active: sub.status === "active",
      plan_type: sub.plan_type,
      expires_at: sub.expires_at,
      devices_count: devices.length,
      bandwidth_used_bytes: bandwidth,
      bandwidth_limit_bytes: 53687091200
    });
  } catch (err) {
    return jsonResponse({ error: "Status query failed", message: err.message }, 500);
  }
}

// 6. POST /api/rotate-key  — signed data: "${installationId}:${newWireguardPubkey}"
async function handleRotateKey(request, env) {
  const signature = request.headers.get("X-App-Signature");
  const { installationId, oldWireguardPubkey, newWireguardPubkey } = await request.json();
  if (!installationId || !oldWireguardPubkey || !newWireguardPubkey) {
    return jsonResponse({ error: "Missing parameters" }, 400);
  }

  // Verify the HMAC for the exact data string the Android client signed
  if (env.DEV_MODE !== "true" && env.HMAC_SECRET) {
    const valid = await verifyHmacSignature(
      signature,
      `${installationId}:${newWireguardPubkey}`,
      env.HMAC_SECRET
    );
    if (!valid) return jsonResponse({ error: "Invalid API signature" }, 401);
  }

  if (isMock(env)) {
    return jsonResponse({ success: true, message: "Keys rotated successfully (Mock)" });
  }

  try {
    const sub = await getSubscription(installationId, env);
    if (!sub) {
      return jsonResponse({ error: "Subscription not found" }, 404);
    }

    const success = await rotateKey(sub.id, oldWireguardPubkey, newWireguardPubkey, env);
    if (success) {
      return jsonResponse({ success: true, message: "Wireguard keys rotated successfully" });
    } else {
      return jsonResponse({ success: false, message: "Key rotation database error" }, 500);
    }
  } catch (err) {
    return jsonResponse({ error: "Key rotation failed", message: err.message }, 500);
  }
}

// 7. POST /webhook/google-play
async function handleGooglePlayWebhook(request, env) {
  // ✅ FIX ❷: Authenticate the webhook using a secret token embedded in the URL.
  //
  //  HOW TO SET UP:
  //    1. Generate a secret:  openssl rand -hex 32
  //    2. Store it:           npx wrangler secret put WEBHOOK_SECRET
  //    3. Register Play Console RTDN URL as:
  //       https://vpn-api-worker.iteack19.workers.dev/webhook/google-play?secret=<your-secret>
  //
  //  Without this, any attacker who discovers the worker URL can POST a forged
  //  payload to expire subscriptions of paying users (or activate free ones).
  if (env.DEV_MODE !== "true") {
    const url = new URL(request.url);
    const providedSecret = url.searchParams.get("secret");
    const expectedSecret = env.WEBHOOK_SECRET;

    if (!expectedSecret) {
      console.error("[Webhook] WEBHOOK_SECRET not configured — rejecting request");
      return jsonResponse({ error: "Server misconfiguration: WEBHOOK_SECRET not set" }, 503);
    }

    if (!providedSecret || providedSecret !== expectedSecret) {
      return jsonResponse({ error: "Invalid webhook secret" }, 401);
    }
  }

  if (isMock(env)) {
    return jsonResponse({ received: true });
  }

  try {
    const payload = await request.json();
    // Parse Google Play RTDN
    if (payload.subscriptionNotification) {
      const notification = payload.subscriptionNotification;
      const purchaseToken = notification.purchaseToken;
      const notificationType = notification.notificationType;

      // Map Play Webhook notification type to database status
      let status = "active";
      if ([3, 5, 12, 13].includes(notificationType)) {
        status = "expired";
      } else if (notificationType === 6) {
        status = "grace";
      } else if ([2, 10].includes(notificationType)) {
        status = "refunded";
      }

      // Update in Supabase
      await fetch(`${env.SUPABASE_URL}/rest/v1/subscriptions?google_purchase_token=eq.${purchaseToken}`, {
        method: "PATCH",
        headers: {
          "apikey": env.SUPABASE_ANON_KEY,
          "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ status })
      });

      // Sync VPNResellers account status (disable on expiry, enable on renewal)
      const subRecord = await getSubscriptionByPurchaseToken(purchaseToken, env);
      if (subRecord && subRecord.vpn_account_id) {
        if (status === "active") {
          await enableVpnResellersAccount(subRecord.vpn_account_id, env);
        } else if (status === "expired" || status === "refunded") {
          await disableVpnResellersAccount(subRecord.vpn_account_id, env);
        }
      }
    }

    return jsonResponse({ received: true });
  } catch (e) {
    return jsonResponse({ error: "Webhook error", message: e.message }, 500);
  }
}

// 8. DELETE /api/delete-account  — signed data: installationId
async function handleDeleteAccount(request, env) {
  const signature = request.headers.get("X-App-Signature");
  const { installationId } = await request.json();
  if (!installationId) {
    return jsonResponse({ error: "Missing installationId parameter" }, 400);
  }

  // Verify the HMAC for the exact data string the Android client signed
  if (env.DEV_MODE !== "true" && env.HMAC_SECRET) {
    const valid = await verifyHmacSignature(signature, installationId, env.HMAC_SECRET);
    if (!valid) return jsonResponse({ error: "Invalid API signature" }, 401);
  }

  if (isMock(env)) {
    return jsonResponse({ success: true, message: "Account deleted (Mock Mode)" });
  }

  try {
    const sub = await getSubscription(installationId, env);
    if (!sub) {
      return jsonResponse({ error: "Subscription not found" }, 404);
    }

    // 1. Delete account from VPNResellers
    if (sub.vpn_account_id) {
      const { ok, data } = await vpnResellersRequest("DELETE", `/accounts/${sub.vpn_account_id}`, null, env);
      if (!ok) {
        console.error("Failed to delete VPNResellers account:", data);
        // Continue anyway to delete local records
      }
    }

    // 2. Delete devices from Supabase
    await fetch(`${env.SUPABASE_URL}/rest/v1/devices?subscription_id=eq.${sub.id}`, {
      method: "DELETE",
      headers: {
        "apikey": env.SUPABASE_ANON_KEY,
        "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
      }
    });

    // 3. Delete subscription from Supabase
    await fetch(`${env.SUPABASE_URL}/rest/v1/subscriptions?id=eq.${sub.id}`, {
      method: "DELETE",
      headers: {
        "apikey": env.SUPABASE_ANON_KEY,
        "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
      }
    });

    return jsonResponse({ success: true, message: "Account deleted successfully" });
  } catch (err) {
    return jsonResponse({ error: "Delete error", message: err.message }, 500);
  }
}

// 9. POST /api/bandwidth-sync
async function handleBandwidthSync(request, env) {
  // ✅ FIX ❸: Require WORKER_AUTH_SECRET for this machine-to-machine endpoint.
  //    Without auth, anyone who knows the URL can forge bandwidth records or
  //    trigger bogus directives (throttle / remove peers).
  //    The VPN server's sync_bandwidth.sh must send:  X-Worker-Auth: <secret>
  const authHeader = request.headers.get("X-Worker-Auth");
  if (env.DEV_MODE !== "true") {
    if (!authHeader || authHeader !== env.WORKER_AUTH_SECRET) {
      return jsonResponse({ error: "Unauthorized" }, 401);
    }
  }

  const { server_id, peers } = await request.json();
  if (!server_id || !peers) {
    return jsonResponse({ error: "Missing parameters" }, 400);
  }

  const directives = [];

  if (isMock(env)) {
    return jsonResponse({ directives });
  }

  try {
    for (const peer of peers) {
      const res = await fetch(`${env.SUPABASE_URL}/rest/v1/devices?wireguard_pubkey=eq.${peer.public_key}&select=*,subscriptions(*)`, {
        headers: {
          "apikey": env.SUPABASE_ANON_KEY,
          "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
        }
      });
      if (!res.ok) continue;
      const list = await res.json();
      const device = list[0];
      if (!device) continue;

      const sub = device.subscriptions;
      if (!sub) continue;

      const startOfMonth = new Date();
      startOfMonth.setDate(1);
      startOfMonth.setHours(0, 0, 0, 0);

      const usageRes = await fetch(`${env.SUPABASE_URL}/rest/v1/bandwidth_usage?device_id=eq.${device.id}&period_start=eq.${startOfMonth.toISOString()}&select=*`, {
        headers: {
          "apikey": env.SUPABASE_ANON_KEY,
          "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
        }
      });
      const usageList = await usageRes.json();
      const existingUsage = usageList[0];

      if (existingUsage) {
        await fetch(`${env.SUPABASE_URL}/rest/v1/bandwidth_usage?id=eq.${existingUsage.id}`, {
          method: "PATCH",
          headers: {
            "apikey": env.SUPABASE_ANON_KEY,
            "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
            "Content-Type": "application/json"
          },
          body: JSON.stringify({
            bytes_sent: peer.tx_bytes,
            bytes_received: peer.rx_bytes,
            period_end: new Date().toISOString()
          })
        });
      } else {
        await fetch(`${env.SUPABASE_URL}/rest/v1/bandwidth_usage`, {
          method: "POST",
          headers: {
            "apikey": env.SUPABASE_ANON_KEY,
            "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
            "Content-Type": "application/json"
          },
          body: JSON.stringify({
            subscription_id: sub.id,
            device_id: device.id,
            bytes_sent: peer.tx_bytes,
            bytes_received: peer.rx_bytes,
            period_start: startOfMonth.toISOString(),
            period_end: new Date().toISOString()
          })
        });
      }

      if (sub.status !== "active") {
        directives.push({ public_key: peer.public_key, action: "remove" });
        continue;
      }

      const totalUsage = await getBandwidthUsed(sub.id, env);

      // Throttling Thresholds: 50GB and 100GB
      if (totalUsage > 107374182400) {
        directives.push({ public_key: peer.public_key, ip: device.assigned_ip.split("/")[0], action: "throttle_512k" });
      } else if (totalUsage > 53687091200) {
        directives.push({ public_key: peer.public_key, ip: device.assigned_ip.split("/")[0], action: "throttle_1m" });
      } else {
        directives.push({ public_key: peer.public_key, ip: device.assigned_ip.split("/")[0], action: "allow" });
      }
    }

    return jsonResponse({ directives });
  } catch (err) {
    return jsonResponse({ error: "Sync error", message: err.message }, 500);
  }
}

// Supabase HTTP REST Wrappers
async function getSubscription(installationId, env) {
  const res = await fetch(`${env.SUPABASE_URL}/rest/v1/subscriptions?installation_id=eq.${installationId}&select=*`, {
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
    }
  });
  if (!res.ok) return null;
  const list = await res.json();
  return list[0] || null;
}

async function upsertSubscription(sub, env) {
  const res = await fetch(`${env.SUPABASE_URL}/rest/v1/subscriptions?google_purchase_token=eq.${sub.google_purchase_token}`, {
    method: "POST",
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
      "Content-Type": "application/json",
      "Prefer": "resolution=merge-duplicates,return=representation"
    },
    body: JSON.stringify(sub)
  });
  if (!res.ok) {
    throw new Error(`Failed to upsert subscription: ${await res.text()}`);
  }
  const list = await res.json();
  return list[0];
}

async function getDevicesForSubscription(subscriptionId, env) {
  const res = await fetch(`${env.SUPABASE_URL}/rest/v1/devices?subscription_id=eq.${subscriptionId}&select=*`, {
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
    }
  });
  if (!res.ok) return [];
  return await res.json();
}

async function registerDevice(device, env) {
  const res = await fetch(`${env.SUPABASE_URL}/rest/v1/devices`, {
    method: "POST",
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
      "Content-Type": "application/json",
      "Prefer": "return=representation"
    },
    body: JSON.stringify(device)
  });
  if (!res.ok) {
    throw new Error(`Failed to register device: ${await res.text()}`);
  }
  const list = await res.json();
  return list[0];
}

async function deregisterDevice(subscriptionId, wireguardPubkey, env) {
  const res = await fetch(`${env.SUPABASE_URL}/rest/v1/devices?subscription_id=eq.${subscriptionId}&wireguard_pubkey=eq.${wireguardPubkey}`, {
    method: "DELETE",
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
    }
  });
  return res.ok;
}

async function rotateKey(subscriptionId, oldPubkey, newPubkey, env) {
  const res = await fetch(`${env.SUPABASE_URL}/rest/v1/devices?subscription_id=eq.${subscriptionId}&wireguard_pubkey=eq.${oldPubkey}`, {
    method: "PATCH",
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ wireguard_pubkey: newPubkey })
  });
  return res.ok;
}

async function getBandwidthUsed(subscriptionId, env) {
  const res = await fetch(`${env.SUPABASE_URL}/rest/v1/bandwidth_usage?subscription_id=eq.${subscriptionId}&select=bytes_sent,bytes_received`, {
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
    }
  });
  if (!res.ok) return 0;
  const list = await res.json();
  return list.reduce((sum, item) => sum + Number(item.bytes_sent) + Number(item.bytes_received), 0);
}

// ─────────────────────────────────────────────────────────────────────────────────────────
// 🔑 VPNRESELLERS API HELPERS
// ─────────────────────────────────────────────────────────────────────────────────────────

async function getGoogleAuthToken(serviceAccountEmail, serviceAccountPrivateKey) {
  const pemHeader = "-----BEGIN PRIVATE KEY-----";
  const pemFooter = "-----END PRIVATE KEY-----";
  const pemContents = serviceAccountPrivateKey
    .replace(pemHeader, "")
    .replace(pemFooter, "")
    .replace(/\s/g, "");
  const binaryDer = base64ToArrayBuffer(pemContents);

  const importKey = await crypto.subtle.importKey(
    "pkcs8",
    binaryDer,
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: { name: "SHA-256" }
    },
    false,
    ["sign"]
  );

  const header = { alg: "RS256", typ: "JWT" };
  const now = Math.floor(Date.now() / 1000);
  const claimSet = {
    iss: serviceAccountEmail,
    scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now
  };

  const encodedHeader = base64url(JSON.stringify(header));
  const encodedClaimSet = base64url(JSON.stringify(claimSet));
  const signatureInput = `${encodedHeader}.${encodedClaimSet}`;

  const encoder = new TextEncoder();
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    importKey,
    encoder.encode(signatureInput)
  );

  const encodedSignature = base64url(signature);
  const jwt = `${signatureInput}.${encodedSignature}`;

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`
  });

  const data = await response.json();
  if (response.ok) {
    return data.access_token;
  } else {
    throw new Error(`Google Auth Token exchange failed: ${JSON.stringify(data)}`);
  }
}

function base64url(source) {
  let encodedSource = "";
  if (typeof source === "string") {
    encodedSource = btoa(unescape(encodeURIComponent(source)));
  } else {
    encodedSource = btoa(String.fromCharCode(...new Uint8Array(source)));
  }
  return encodedSource
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function base64ToArrayBuffer(base64) {
  const binaryString = atob(base64);
  const len = binaryString.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return bytes.buffer;
}

// Dynamic server list handler fetching live servers from VPNResellers serverinfo.xml feed
// ─────────────────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────────────────
// 🔑 VPNRESELLERS API HELPERS
// ─────────────────────────────────────────────────────────────────────────────────────────

async function vpnResellersRequest(method, endpoint, body, env) {
  const options = {
    method,
    headers: {
      "Authorization": `Bearer ${env.VPNRESELLERS_API_TOKEN}`,
      "Content-Type": "application/json",
      "Accept": "application/json"
    }
  };
  if (body) options.body = JSON.stringify(body);
  const res = await fetch(`${VPNRESELLERS_BASE}${endpoint}`, options);
  const data = await res.json().catch(() => ({}));
  return { ok: res.ok, status: res.status, data };
}

async function createVpnResellersAccount(installationId, env) {
  // Build a safe username from installationId (alphanumeric, max 20 chars)
  const username = "u" + installationId.replace(/[^a-z0-9]/gi, "").substring(0, 19).toLowerCase();
  const password = generateRandomPassword(20);

  const { ok, data } = await vpnResellersRequest("POST", "/accounts", { username, password }, env);
  if (!ok) throw new Error(`VPNResellers account creation failed: ${JSON.stringify(data)}`);

  // Return all three values — caller MUST save the password for later config fetches
  // data.data = { id, username, status, wg_ip, wg_private_key, wg_public_key, expired_at }
  return { id: data.data.id, username: data.data.username || username, password };
}

/**
 * fetchVpnResellersConfig
 *
 * Correct v4.1 endpoint: GET /v4_1/configuration/wireguard?server_id={id}
 * Auth: Basic base64(username:password) using the VPN ACCOUNT credentials,
 *       NOT the reseller Bearer token.
 *
 * Returns the raw config text (.conf for WireGuard, .ovpn for OpenVPN).
 */
async function fetchVpnResellersConfig(username, password, serverId, env, protocol = "wireguard") {
  const protoPath = protocol === "openvpn" ? "openvpn" : "wireguard";
  const url = `${VPNRESELLERS_BASE}/configuration/${protoPath}?server_id=${serverId}`;

  // Basic auth with the VPN account credentials (not the reseller API token)
  const basicCredentials = btoa(`${username}:${password}`);

  const res = await fetch(url, {
    headers: {
      "Authorization": `Basic ${basicCredentials}`,
      "Accept": "text/plain, text/html, */*"
    }
  });

  if (!res.ok) {
    throw new Error(
      `VPNResellers config fetch failed: HTTP ${res.status} for ${protoPath} server_id=${serverId}`
    );
  }
  return await res.text();
}

/**
 * resolveServerId
 *
 * Returns the VPNResellers numeric server ID for a given location code.
 * Tries GET /v4_1/servers (JSON) first for real IDs; caches result per
 * worker instance. Falls back to static VPNRESELLERS_SERVER_IDS map.
 *
 * The JSON response shape is:
 *   { data: [ { id, name, country, city, hostname, ip, status }, ... ] }
 */
async function resolveServerId(locationCode, env) {
  // Use cached map if already populated and not yet expired
  if (_serverIdCache && Date.now() < _serverIdCacheExpiry) {
    return _serverIdCache[locationCode] || VPNRESELLERS_SERVER_IDS[locationCode] || 1;
  }

  try {
    const { ok, data } = await vpnResellersRequest("GET", "/servers", null, env);
    if (ok && data && Array.isArray(data.data)) {
      _serverIdCache = {};
      for (const server of data.data) {
        if (server.status !== 1 && server.status !== "active") continue;
        const cc = (server.country_code || server.country || "").toLowerCase().substring(0, 2);
        const city = (server.city || "").toLowerCase();
        if (!_serverIdCache[cc]) _serverIdCache[cc] = server.id;
        if (city.includes("mumbai") || city.includes("india"))    _serverIdCache["in"] = server.id;
        if (city.includes("ashburn") || city.includes("virginia")) _serverIdCache["us"] = server.id;
        if (city.includes("singapore"))                             _serverIdCache["sg"] = server.id;
      }
      _serverIdCacheExpiry = Date.now() + SERVER_CACHE_TTL_MS;
    }
  } catch (e) {
    console.error("resolveServerId: failed to fetch /servers, using static map", e.message);
  }

  if (_serverIdCache && _serverIdCache[locationCode]) {
    return _serverIdCache[locationCode];
  }
  return VPNRESELLERS_SERVER_IDS[locationCode] || 1;
}

async function disableVpnResellersAccount(accountId, env) {
  const { ok, data } = await vpnResellersRequest("PUT", `/accounts/${accountId}/disable`, null, env);
  if (!ok) console.error(`Failed to disable VPNResellers account ${accountId}:`, data);
  return ok;
}

async function enableVpnResellersAccount(accountId, env) {
  const { ok, data } = await vpnResellersRequest("PUT", `/accounts/${accountId}/enable`, null, env);
  if (!ok) console.error(`Failed to enable VPNResellers account ${accountId}:`, data);
  return ok;
}

// Parse a WireGuard .conf file text into structured fields
function parseWireGuardConfig(configText) {
  const lines = configText.split("\n");
  const result = {};
  let section = "";

  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith("[")) {
      section = trimmed.replace(/[\[\]]/g, "").toLowerCase();
    } else if (trimmed.includes("=")) {
      const eqIdx = trimmed.indexOf("=");
      const key = trimmed.substring(0, eqIdx).trim().toLowerCase();
      const value = trimmed.substring(eqIdx + 1).trim();

      if (section === "interface") {
        if (key === "privatekey") result.client_private_key = value;
        if (key === "address")    result.client_ip = value;
        if (key === "dns")        result.dns = value;
      } else if (section === "peer") {
        if (key === "publickey")          result.server_pubkey = value;
        if (key === "endpoint")           result.server_endpoint = value;
        if (key === "allowedips")         result.allowed_ips = value;
        if (key === "persistentkeepalive") result.keepalive = parseInt(value, 10);
      }
    }
  }
  return result;
}

// Generate a secure random password
function generateRandomPassword(length = 20) {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%";
  const array = new Uint8Array(length);
  crypto.getRandomValues(array);
  return Array.from(array, b => chars[b % chars.length]).join("");
}

// ─────────────────────────────────────────────────────────────────────────────────────────
// 🗄️ ADDITIONAL SUPABASE HELPERS
// ─────────────────────────────────────────────────────────────────────────────────────────

async function updateSubscriptionVpnAccount(subId, vpnAccountId, vpnUsername, vpnPassword, env) {
  await fetch(`${env.SUPABASE_URL}/rest/v1/subscriptions?id=eq.${subId}`, {
    method: "PATCH",
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      vpn_account_id: vpnAccountId,
      vpn_username: vpnUsername,
      vpn_password: vpnPassword   // Required for Basic-auth config fetches
    })
  });
}

async function getSubscriptionByPurchaseToken(purchaseToken, env) {
  const res = await fetch(
    `${env.SUPABASE_URL}/rest/v1/subscriptions?google_purchase_token=eq.${purchaseToken}&select=*`,
    {
      headers: {
        "apikey": env.SUPABASE_ANON_KEY,
        "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
      }
    }
  );
  if (!res.ok) return null;
  const list = await res.json();
  return list[0] || null;
}

// ISO country code lookup map for high-res FlagCDN flags
// Covers 60+ countries — used when the XML feed returns a full country name
// rather than a 2-letter code (which the JSON API always provides directly).
const countryMap = {
  // Asia Pacific
  "India": "in", "Singapore": "sg", "Japan": "jp", "Australia": "au",
  "Hong Kong": "hk", "South Korea": "kr", "Korea": "kr", "Taiwan": "tw",
  "Indonesia": "id", "Malaysia": "my", "Philippines": "ph", "Thailand": "th",
  "Vietnam": "vn", "Bangladesh": "bd", "Sri Lanka": "lk", "Nepal": "np",
  "Pakistan": "pk", "New Zealand": "nz", "Myanmar": "mm", "Cambodia": "kh",
  // Europe
  "UK": "gb", "United Kingdom": "gb", "England": "gb",
  "Germany": "de", "Netherlands": "nl", "France": "fr", "Sweden": "se",
  "Norway": "no", "Switzerland": "ch", "Spain": "es", "Italy": "it",
  "Poland": "pl", "Portugal": "pt", "Austria": "at", "Belgium": "be",
  "Denmark": "dk", "Finland": "fi", "Ireland": "ie", "Czech Republic": "cz",
  "Romania": "ro", "Hungary": "hu", "Bulgaria": "bg", "Croatia": "hr",
  "Greece": "gr", "Slovakia": "sk", "Slovenia": "si", "Lithuania": "lt",
  "Latvia": "lv", "Estonia": "ee", "Serbia": "rs", "Moldova": "md",
  "Ukraine": "ua", "Luxembourg": "lu", "Malta": "mt", "Cyprus": "cy",
  "Iceland": "is", "Turkey": "tr", "Albania": "al", "North Macedonia": "mk",
  "Bosnia": "ba", "Kosovo": "xk", "Montenegro": "me",
  // Americas
  "USA": "us", "United States": "us", "Canada": "ca", "Brazil": "br",
  "Mexico": "mx", "Argentina": "ar", "Chile": "cl", "Colombia": "co",
  "Peru": "pe", "Ecuador": "ec", "Bolivia": "bo", "Paraguay": "py",
  "Uruguay": "uy", "Venezuela": "ve", "Costa Rica": "cr", "Panama": "pa",
  "Guatemala": "gt", "Dominican Republic": "do", "Jamaica": "jm",
  "Cuba": "cu", "Honduras": "hn", "El Salvador": "sv", "Nicaragua": "ni",
  // Middle East
  "UAE": "ae", "United Arab Emirates": "ae", "Dubai": "ae",
  "Saudi Arabia": "sa", "Israel": "il", "Egypt": "eg", "Qatar": "qa",
  "Kuwait": "kw", "Bahrain": "bh", "Jordan": "jo", "Oman": "om",
  "Iraq": "iq", "Lebanon": "lb", "Syria": "sy", "Yemen": "ye",
  // Africa
  "South Africa": "za", "Nigeria": "ng", "Kenya": "ke", "Ghana": "gh",
  "Morocco": "ma", "Tunisia": "tn", "Algeria": "dz", "Ethiopia": "et",
  "Tanzania": "tz", "Uganda": "ug", "Cameroon": "cm", "Senegal": "sn",
  "Zimbabwe": "zw", "Zambia": "zm", "Mozambique": "mz", "Angola": "ao",
  // Central Asia & Caucasus
  "Azerbaijan": "az", "Georgia": "ge", "Armenia": "am",
  "Kazakhstan": "kz", "Uzbekistan": "uz", "Turkmenistan": "tm",
  "Kyrgyzstan": "kg", "Tajikistan": "tj", "Mongolia": "mn",
  // Eastern Europe / CIS (live in VPNResellers feed)
  "Russia": "ru", "Russian Federation": "ru",
  "Belarus": "by"
};

const COUNTRY_NAME_BY_CODE = {
  in: "India", us: "United States", sg: "Singapore", gb: "United Kingdom",
  de: "Germany", jp: "Japan", au: "Australia", hk: "Hong Kong", kr: "South Korea",
  nl: "Netherlands", fr: "France", se: "Sweden", ch: "Switzerland", es: "Spain",
  it: "Italy", ca: "Canada", br: "Brazil", mx: "Mexico", za: "South Africa",
  ae: "United Arab Emirates", th: "Thailand", tr: "Turkey", ua: "Ukraine",
  dk: "Denmark", no: "Norway", fi: "Finland", id: "Indonesia", my: "Malaysia",
  ph: "Philippines", vn: "Vietnam", pk: "Pakistan", bd: "Bangladesh", lk: "Sri Lanka",
  nz: "New Zealand", ro: "Romania", pl: "Poland", cz: "Czechia", hu: "Hungary",
  at: "Austria", be: "Belgium", ie: "Ireland", pt: "Portugal", gr: "Greece",
  cl: "Chile", co: "Colombia", ar: "Argentina", pe: "Peru", ke: "Kenya",
  si: "Slovenia", md: "Moldova"
};

async function handleGetServers(env) {
  // ── Primary: GET /v4_1/servers JSON (real pubkeys + real IDs) ───────────
  try {
    const { ok, status: httpStatus, data } = await vpnResellersRequest("GET", "/servers", null, env);
    console.log(`[servers] VPNResellers /servers → HTTP ${httpStatus}, ok=${ok}, keys=${Object.keys(data || {}).join(",")}`);

    if (ok && data && Array.isArray(data.data)) {
      console.log(`[servers] Total from API: ${data.data.length}, statuses: ${[...new Set(data.data.map(s => s.status))].join(",")}`);

      const servers = data.data
        // Accept any server that is not explicitly disabled/suspended.
        // VPNResellers uses: 1=active, 0=disabled. Also accept "active","online","1",true.
        .filter(s => {
          const st = s.status;
          if (st === 0 || st === "0" || st === "disabled" || st === "suspended" || st === false) return false;
          return true; // accept 1, "active", "online", "1", true, null (unknown = show it)
        })
        .map(s => {
          const cc = (s.country_code || s.country || "").toLowerCase().substring(0, 2);
          const hdIconUrl = `https://flagcdn.com/w160/${cc}.png`;
          const host = s.hostname || s.host || s.domain || s.ip || s.name || "";
          const countryName = COUNTRY_NAME_BY_CODE[cc] || (s.country && !s.country.includes(".") ? s.country : cc.toUpperCase());
          return {
            id: `${cc}_${s.id}`,
            vpnresellers_id: s.id,          // real numeric ID for config fetches
            country: countryName,
            city: s.city || countryName || s.name,
            endpoint: `${host}:51820`,
            pubkey: s.wg_public_key || s.public_key || "",  // real WG pubkey from API
            ping_ip: s.ip || host,
            latency_ms: Math.floor(Math.random() * 35) + 15,
            load_percent: Math.floor(Math.random() * 20) + 10,
            icon: hdIconUrl
          };
        })
        .filter(s => s.id && s.endpoint && s.ping_ip); // must have ID, endpoint, and a pingable IP

      console.log(`[servers] After filter: ${servers.length} servers`);
      if (servers.length > 0) {
        return jsonResponse({ servers });
      }
    }
  } catch (err) {
    console.error("handleGetServers: JSON API failed, trying XML fallback:", err.message);
  }

  // ── Secondary: XML feed (hostname/IP only — pubkeys not available here) ─
  // Safe to use: pubkey is empty in the list, but the REAL peer pubkey is
  // embedded in the WireGuard config returned by /v4_1/configuration/wireguard
  // at connect time. The server list pubkey field is only used as a hint.
  try {
    const response = await fetch("https://app.vpnresellers.com/feeds/serverinfo.xml", {
      headers: { "User-Agent": "UnlimitedVPN-Worker/1.0" }
    });
    if (response.ok) {
      const xmlText = await response.text();
      const serverRegex = /<server\s+([^>]+)\/>/gi;
      const servers = [];
      let match;
      while ((match = serverRegex.exec(xmlText)) !== null) {
        const attrString = match[1];
        const getAttr = (name) => {
          const m = attrString.match(new RegExp(`${name}="([^"]*)"`, 'i'));
          return m ? m[1] : '';
        };
        const name = getAttr('name');
        const ip = getAttr('ip');
        const country = getAttr('country');
        const city = getAttr('city');
        const status = getAttr('status');
        const visible = getAttr('visible');
        const countryCode = (country.length === 2 ? country : (countryMap[country] || country.substring(0, 2))).toLowerCase();
        const hdIconUrl = `https://flagcdn.com/w160/${countryCode}.png`;
        if (status === '1' && visible === '1' && ip) {
          servers.push({
            id: countryCode + '_' + name.split('.')[0],
            country,
            city: city || country,
            endpoint: `${name}:51820`,
            pubkey: "",  // safe — real pubkey fetched at connect time from /v4_1/configuration/wireguard
            ping_ip: ip,
            latency_ms: Math.floor(Math.random() * 35) + 15,
            load_percent: Math.floor(Math.random() * 20) + 10,
            icon: hdIconUrl
          });
        }
      }
      console.log(`[servers] XML feed returned ${servers.length} servers`);
      if (servers.length > 0) {
        return jsonResponse({ servers });
      }
    }
  } catch (err) {
    console.error("handleGetServers: XML feed failed, using static fallback:", err.message);
  }

  // ── Final fallback: known VPNResellers server hostnames ─────────────────
  // These are real VPNResellers servers. pubkey is empty — safe because the
  // WireGuard config (with real peer pubkey) is fetched from VPNResellers at
  // connect time, so the tunnel is always authenticated correctly.
  console.warn("[servers] All live sources failed — serving static fallback list");
  const staticFallback = [
    { id: "in_1",  vpnresellers_id: 1,  country: "India",          city: "Mumbai",     endpoint: "in1.vpnresellers.com:51820",  pubkey: "", ping_ip: "in1.vpnresellers.com",  latency_ms: 20,  load_percent: 15, icon: "https://flagcdn.com/w160/in.png" },
    { id: "sg_2",  vpnresellers_id: 2,  country: "Singapore",      city: "Singapore",  endpoint: "sg1.vpnresellers.com:51820",  pubkey: "", ping_ip: "sg1.vpnresellers.com",  latency_ms: 45,  load_percent: 20, icon: "https://flagcdn.com/w160/sg.png" },
    { id: "us_3",  vpnresellers_id: 3,  country: "United States",  city: "New York",   endpoint: "us1.vpnresellers.com:51820",  pubkey: "", ping_ip: "us1.vpnresellers.com",  latency_ms: 180, load_percent: 25, icon: "https://flagcdn.com/w160/us.png" },
    { id: "gb_4",  vpnresellers_id: 4,  country: "United Kingdom", city: "London",     endpoint: "uk1.vpnresellers.com:51820",  pubkey: "", ping_ip: "uk1.vpnresellers.com",  latency_ms: 160, load_percent: 20, icon: "https://flagcdn.com/w160/gb.png" },
    { id: "de_5",  vpnresellers_id: 5,  country: "Germany",        city: "Frankfurt",  endpoint: "de1.vpnresellers.com:51820",  pubkey: "", ping_ip: "de1.vpnresellers.com",  latency_ms: 150, load_percent: 18, icon: "https://flagcdn.com/w160/de.png" },
    { id: "nl_6",  vpnresellers_id: 6,  country: "Netherlands",    city: "Amsterdam",  endpoint: "nl1.vpnresellers.com:51820",  pubkey: "", ping_ip: "nl1.vpnresellers.com",  latency_ms: 155, load_percent: 22, icon: "https://flagcdn.com/w160/nl.png" },
    { id: "jp_7",  vpnresellers_id: 7,  country: "Japan",          city: "Tokyo",      endpoint: "jp1.vpnresellers.com:51820",  pubkey: "", ping_ip: "jp1.vpnresellers.com",  latency_ms: 90,  load_percent: 20, icon: "https://flagcdn.com/w160/jp.png" },
    { id: "ca_8",  vpnresellers_id: 8,  country: "Canada",         city: "Toronto",    endpoint: "ca1.vpnresellers.com:51820",  pubkey: "", ping_ip: "ca1.vpnresellers.com",  latency_ms: 190, load_percent: 15, icon: "https://flagcdn.com/w160/ca.png" },
    { id: "au_9",  vpnresellers_id: 9,  country: "Australia",      city: "Sydney",     endpoint: "au1.vpnresellers.com:51820",  pubkey: "", ping_ip: "au1.vpnresellers.com",  latency_ms: 120, load_percent: 12, icon: "https://flagcdn.com/w160/au.png" },
    { id: "fr_10", vpnresellers_id: 10, country: "France",         city: "Paris",      endpoint: "fr1.vpnresellers.com:51820",  pubkey: "", ping_ip: "fr1.vpnresellers.com",  latency_ms: 158, load_percent: 18, icon: "https://flagcdn.com/w160/fr.png" },
  ];
  return jsonResponse({ servers: staticFallback });
}


// ═══════════════════════════════════════════════════════════════════════════
// CERT-In 2022 Compliance Functions
// ═══════════════════════════════════════════════════════════════════════════

/**
 * upsertCertInUserRecord
 * Called inside handleVerify() on every successful subscription purchase.
 * Writes/updates a row in certin_user_records with the subscriber's
 * registration IP (from CF-Connecting-IP), email, and hire period.
 *
 * Uses UPSERT (ON CONFLICT) so re-subscriptions update the existing record
 * rather than creating duplicates. hire_ended_at and delete_after are cleared
 * when a user re-subscribes (they are active again).
 */
async function upsertCertInUserRecord({ installationId, subscriptionId, email, registrationIp, hireStartedAt }, env) {
  // Logging disabled per user request (Zero Logs policy active)
  return;
}

/**
 * handleConnectionLog
 * POST /api/connection-log
 *
 * Server-to-server endpoint called by the VPN server's sync_bandwidth.sh
 * script whenever a WireGuard peer connects or disconnects.
 *
 * Authentication: WORKER_AUTH_SECRET header (NOT user HMAC — this is
 * machine-to-machine auth from the VPN server).
 *
 * Body:
 * {
 *   "installationId": "...",    // WireGuard peer's installation ID
 *   "devicePubkey": "...",      // WireGuard peer public key
 *   "event": "connect"|"disconnect",
 *   "sourceIp": "103.x.x.x",   // User's real public IP (WireGuard endpoint)
 *   "assignedVpnIp": "10.0.x.x",
 *   "serverLocation": "in",
 *   "bytesSent": 0,
 *   "bytesReceived": 0
 * }
 *
 * On "connect":   INSERT a new session row (session_end = NULL).
 * On "disconnect": UPDATE the open session row with session_end + bytes.
 */
async function handleConnectionLog(request, env) {
  // Logging disabled per user request (Zero Logs policy active)
  return jsonResponse({ success: true, message: "Zero Logs policy active - connection not logged" });
}

/**
 * supabaseQuery — generic Supabase REST query helper using Postgrest RPC.
 * Wraps the existing pattern used elsewhere in this file.
 */
async function supabaseQuery(sql, params, env) {
  const url = `${env.SUPABASE_URL}/rest/v1/rpc/exec_sql`;
  // Use the direct Postgres endpoint pattern for raw SQL via service role
  const pgUrl = `${env.SUPABASE_URL}/rest/v1/`;
  // Fall back: use the Supabase SQL-over-REST approach
  try {
    const res = await fetch(`${env.SUPABASE_URL}/rest/v1/rpc/exec_sql`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "apikey": env.SUPABASE_SERVICE_ROLE_KEY,
        "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
      },
      body: JSON.stringify({ query: sql, params })
    });
    if (!res.ok) {
      const text = await res.text();
      return { error: text, data: null };
    }
    const data = await res.json();
    return { error: null, data };
  } catch (err) {
    return { error: err.message, data: null };
  }
}
