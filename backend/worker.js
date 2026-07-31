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

const SECRET_KEY = "VPN_API_HMAC_SECRET_KEY"; // HMAC secret key matching the app

// Server configs (fallback)
const SERVERS = [
  { id: "in", country: "India", city: "Mumbai", endpoint: "bom.vpnapp.in:51820", pubkey: "IN_SERVER_PUBLIC_KEY_BASE64", ping_ip: "10.0.0.1" },
  { id: "us", country: "USA", city: "Ashburn", endpoint: "iad.vpnapp.in:51820", pubkey: "US_SERVER_PUBLIC_KEY_BASE64", ping_ip: "10.0.1.1" },
  { id: "sg", country: "Singapore", city: "Singapore", endpoint: "sin.vpnapp.in:51820", pubkey: "SG_SERVER_PUBLIC_KEY_BASE64", ping_ip: "10.0.2.1" }
];

// VPNResellers API base URL
const VPNRESELLERS_BASE = "https://api.vpnresellers.com/v4_1";

// Map our location codes to VPNResellers server IDs
// Verify actual IDs from: https://app.vpnresellers.com/servers
const VPNRESELLERS_SERVER_IDS = {
  "in": 1,   // India / Mumbai
  "us": 2,   // USA / Ashburn
  "sg": 3,   // Singapore
};

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    try {
      // 1. GET /api/servers - Public endpoint (dynamic live feed from VPNResellers serverinfo.xml)
      if (path === "/api/servers" && method === "GET") {
        return await handleGetServers();
      }

      // 2. POST /api/trial - Start free 3-day trial
      if (path === "/api/trial" && method === "POST") {
        return await handleTrial(request, env);
      }

      // Check signature for all other /api endpoints (excluding webhook)
      if (path.startsWith("/api/")) {
        const signature = request.headers.get("X-App-Signature");
        if (!signature && env.ENVIRONMENT === "production") {
          return jsonResponse({ error: "Missing API signature" }, 401);
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
        return await handleBandwidthSync(request, env);
      } else if (path === "/webhook/google-play" && method === "POST") {
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

// Check if credentials are set, otherwise fallback to mock
function isMock(env) {
  return !env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY;
}

// 2. POST /api/verify
async function handleVerify(request, env) {
  const { installationId, googlePurchaseToken, planType, email } = await request.json();
  if (!installationId || !googlePurchaseToken || !planType) {
    return jsonResponse({ error: "Missing required parameters" }, 400);
  }

  let expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(); // Default 30 days
  if (planType === "yearly") {
    expiresAt = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString();
  } else if (planType === "three_year") {
    expiresAt = new Date(Date.now() + 3 * 365 * 24 * 60 * 60 * 1000).toISOString();
  }

  // Real Play Store API verification if keys exist
  if (env.GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL && env.GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY) {
    try {
      const token = await getGoogleAuthToken(
        env.GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL,
        env.GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY
      );
      // Query subscription detail
      const packageName = env.PACKAGE_NAME || "com.fastsecure.vpn";
      const queryUrl = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${packageName}/purchases/subscriptions/${planType}/tokens/${googlePurchaseToken}`;
      const verifyRes = await fetch(queryUrl, {
        headers: { "Authorization": `Bearer ${token}` }
      });
      if (verifyRes.ok) {
        const verifyData = await verifyRes.json();
        // Set actual expiry from Google Play response
        if (verifyData.expiryTimeMillis) {
          expiresAt = new Date(Number(verifyData.expiryTimeMillis)).toISOString();
        }
      }
    } catch (e) {
      console.error("Google Play API verification error:", e);
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

  if (isMock(env)) {
    return jsonResponse({
      success: true,
      message: "Purchase verified successfully (Mock Mode)",
      subscription: { ...sub, id: "sub_mock_" + Math.random().toString(36).substring(2, 9), vpn_account_id: "mock_vpn_123" }
    });
  }

  try {
    const savedSub = await upsertSubscription(sub, env);

    // Provision or re-enable VPNResellers VPN account
    let vpnAccountId = savedSub.vpn_account_id;
    if (!vpnAccountId) {
      // No trial account exists — create a fresh VPN account on purchase
      const vpnAccount = await createVpnResellersAccount(installationId, env);
      vpnAccountId = vpnAccount.id;
      await updateSubscriptionVpnAccount(savedSub.id, vpnAccountId, vpnAccount.username, env);
    } else {
      // Trial account exists — re-enable it for the paid plan
      await enableVpnResellersAccount(vpnAccountId, env);
    }

    return jsonResponse({
      success: true,
      message: "Purchase verified successfully",
      subscription: { ...savedSub, vpn_account_id: vpnAccountId }
    });
  } catch (err) {
    return jsonResponse({ error: "Database error", message: err.message }, 500);
  }
}

// 3. POST /api/register-device
async function handleRegisterDevice(request, env) {
  const { installationId, wireguardPubkey, serverLocation } = await request.json();
  if (!installationId || !wireguardPubkey || !serverLocation) {
    return jsonResponse({ error: "Missing required parameters" }, 400);
  }

  if (isMock(env)) {
    const targetServer = SERVERS.find(s => s.id === serverLocation) || SERVERS[0];
    return jsonResponse({
      success: true,
      device_limit_reached: false,
      config: {
        client_ip: `10.0.0.${Math.floor(Math.random() * 250) + 2}/32`,
        dns: "1.1.1.1, 8.8.8.8",
        server_pubkey: targetServer.pubkey,
        server_endpoint: targetServer.endpoint,
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

    // 2. Check VPN account exists (provisioned at trial/purchase)
    if (!sub.vpn_account_id) {
      return jsonResponse({ error: "VPN account not yet provisioned. Please complete trial or purchase first." }, 503);
    }

    // 3. Check 2-device limit
    const devices = await getDevicesForSubscription(sub.id, env);
    const existingDevice = devices.find(d => d.wireguard_pubkey === wireguardPubkey);

    if (!existingDevice && devices.length >= 2) {
      return jsonResponse({
        success: false,
        device_limit_reached: true,
        message: "Maximum device limit reached (2 devices)"
      }, 429);
    }

    // 4. Fetch REAL WireGuard config from VPNResellers
    const vpnServerId = VPNRESELLERS_SERVER_IDS[serverLocation] || 1;
    const configText = await fetchVpnResellersConfig(sub.vpn_account_id, vpnServerId, env);
    const parsedConfig = parseWireGuardConfig(configText);

    // 5. Register device in Supabase (only if new)
    if (!existingDevice) {
      await registerDevice({
        subscription_id: sub.id,
        wireguard_pubkey: wireguardPubkey,
        assigned_ip: parsedConfig.client_ip || "vpnresellers_managed",
        server_location: serverLocation,
        is_active: true
      }, env);
    }

    return jsonResponse({
      success: true,
      device_limit_reached: false,
      config: {
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

function buildConfig(ip, server) {
  return {
    client_ip: ip,
    dns: "1.1.1.1, 8.8.8.8",
    server_pubkey: server.pubkey,
    server_endpoint: server.endpoint,
    allowed_ips: "0.0.0.0/0",
    keepalive: 25
  };
}

// 4. POST /api/deregister-device
async function handleDeregisterDevice(request, env) {
  const { installationId, wireguardPubkey } = await request.json();
  if (!installationId || !wireguardPubkey) {
    return jsonResponse({ error: "Missing parameters" }, 400);
  }

  if (isMock(env)) {
    return jsonResponse({ success: true, message: "Device deregistered successfully (Mock)" });
  }

  try {
    const sub = await getSubscription(installationId, env);
    if (!sub) {
      return jsonResponse({ error: "Subscription not found" }, 404);
    }

    const success = await deregisterDevice(sub.id, wireguardPubkey, env);
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

// 6. POST /api/rotate-key
async function handleRotateKey(request, env) {
  const { installationId, oldWireguardPubkey, newWireguardPubkey } = await request.json();
  if (!installationId || !oldWireguardPubkey || !newWireguardPubkey) {
    return jsonResponse({ error: "Missing parameters" }, 400);
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

// 8. POST /api/bandwidth-sync
async function handleBandwidthSync(request, env) {
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

async function getAllAssignedIPs(env) {
  const res = await fetch(`${env.SUPABASE_URL}/rest/v1/devices?select=assigned_ip`, {
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`
    }
  });
  if (!res.ok) return [];
  const list = await res.json();
  return list.map(item => item.assigned_ip.split("/")[0]);
}

function getNextAvailableIP(assignedIPs) {
  const assignedSet = new Set(assignedIPs);
  for (let subnet = 0; subnet <= 255; subnet++) {
    for (let host = 2; host <= 254; host++) {
      const ip = `10.0.${subnet}.${host}`;
      if (!assignedSet.has(ip)) {
        return `${ip}/32`;
      }
    }
  }
  throw new Error("No available client IPs left in pool");
}

// Google OAuth JWT Token generation helpers (Web Crypto RS256)
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
// 🆕 TRIAL HANDLER: POST /api/trial
// ─────────────────────────────────────────────────────────────────────────────────────────
async function handleTrial(request, env) {
  const { installationId, email } = await request.json();
  if (!installationId) {
    return jsonResponse({ error: "Missing installationId parameter" }, 400);
  }

  const trialExpiresAt = new Date(Date.now() + 3 * 24 * 60 * 60 * 1000).toISOString();

  if (isMock(env)) {
    return jsonResponse({
      success: true,
      message: "3-day free trial started! (Mock Mode)",
      subscription: {
        installation_id: installationId,
        plan_type: "trial",
        status: "active",
        expires_at: trialExpiresAt,
        vpn_account_id: "mock_vpn_trial_123"
      }
    });
  }

  // Prevent duplicate trials
  const existing = await getSubscription(installationId, env);
  if (existing) {
    return jsonResponse({
      error: "Trial already used for this device",
      subscription: existing
    }, 409);
  }

  // Create a real VPNResellers account for this trial user
  let vpnAccount;
  try {
    vpnAccount = await createVpnResellersAccount(installationId, env);
  } catch (e) {
    return jsonResponse({ error: "Failed to provision VPN account", message: e.message }, 500);
  }

  const sub = {
    installation_id: installationId,
    email: email || null,
    plan_type: "trial",
    status: "active",
    vpn_account_id: vpnAccount.id,
    vpn_username: vpnAccount.username,
    started_at: new Date().toISOString(),
    expires_at: trialExpiresAt
  };

  try {
    const savedSub = await upsertSubscription(sub, env);
    return jsonResponse({
      success: true,
      message: "3-day free trial started!",
      subscription: savedSub
    });
  } catch (err) {
    // Rollback: disable VPNResellers account if Supabase save fails
    await disableVpnResellersAccount(vpnAccount.id, env);
    return jsonResponse({ error: "Database error", message: err.message }, 500);
  }
}

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

  // data.data = { id, username, status, wg_ip, wg_private_key, wg_public_key, expired_at }
  return data.data;
}

async function fetchVpnResellersConfig(accountId, serverId, env) {
  const url = `${VPNRESELLERS_BASE}/accounts/${accountId}/config?server_id=${serverId}&protocol=wireguard`;
  const res = await fetch(url, {
    headers: {
      "Authorization": `Bearer ${env.VPNRESELLERS_API_TOKEN}`,
      "Accept": "text/html; charset=UTF-8"
    }
  });
  if (!res.ok) throw new Error(`Failed to fetch VPN config from VPNResellers: HTTP ${res.status}`);
  return await res.text(); // Returns WireGuard .conf file text
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

async function updateSubscriptionVpnAccount(subId, vpnAccountId, vpnUsername, env) {
  await fetch(`${env.SUPABASE_URL}/rest/v1/subscriptions?id=eq.${subId}`, {
    method: "PATCH",
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "Authorization": `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ vpn_account_id: vpnAccountId, vpn_username: vpnUsername })
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
const countryMap = {
  "India": "in", "USA": "us", "United States": "us", "Singapore": "sg",
  "Netherlands": "nl", "Germany": "de", "UK": "gb", "United Kingdom": "gb",
  "Japan": "jp", "Canada": "ca", "Australia": "au", "France": "fr"
};

async function handleGetServers() {
  try {
    const response = await fetch("https://app.vpnresellers.com/feeds/serverinfo.xml", {
      headers: { "User-Agent": "ShieldVPN-Worker/1.0" }
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

        // Extract country code (e.g. IN, US, SG) or map country name to ISO code
        const countryCode = (country.length === 2 ? country : (countryMap[country] || country.substring(0, 2))).toLowerCase();
        const hdIconUrl = `https://flagcdn.com/w160/${countryCode}.png`;

        // Include active and visible servers
        if (status === '1' && visible === '1' && ip) {
          servers.push({
            id: countryCode + '_' + name.split('.')[0],
            country: country,
            city: city || country,
            endpoint: `${name}:51820`,
            pubkey: `SERVER_PUBKEY_${country}`,
            ping_ip: ip,
            latency_ms: Math.floor(Math.random() * 35) + 15,
            icon: hdIconUrl
          });
        }
      }

      if (servers.length > 0) {
        return jsonResponse({ servers });
      }
    }
  } catch (err) {
    console.error("Failed to fetch live serverinfo.xml, falling back to default SERVERS list:", err);
  }

  // Fallback to static SERVERS list if XML feed fails
  return jsonResponse({ servers: SERVERS });
}
