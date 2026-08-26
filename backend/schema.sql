-- Supabase Database Schema for WireGuard VPN Android App

-- Enable UUID extension if not already enabled
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Subscriptions Table
CREATE TABLE IF NOT EXISTS subscriptions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    installation_id TEXT NOT NULL,
    email TEXT,
    google_purchase_token TEXT UNIQUE NOT NULL,
    -- plan_type matches Google Play SKU IDs: vpn_monthly, vpn_6month, vpn_annual
    plan_type TEXT NOT NULL CHECK (plan_type IN ('vpn_monthly', 'vpn_6month', 'vpn_annual')),
    status TEXT NOT NULL CHECK (status IN ('active', 'grace', 'on_hold', 'expired', 'refunded')),
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    grace_until TIMESTAMPTZ,
    -- VPNResellers account credentials
    -- vpn_account_id: numeric ID from POST /v4_1/accounts
    -- vpn_username / vpn_password: used for Basic-auth config requests
    --   GET /v4_1/configuration/wireguard?server_id={id}
    -- SECURITY: vpn_password is sensitive. Only the Cloudflare Worker
    --   (using SUPABASE_SERVICE_ROLE_KEY) should ever read or write these columns.
    --   Row Level Security must be ON and anon role must NOT have SELECT on these columns.
    vpn_account_id TEXT,
    vpn_username   TEXT,
    vpn_password   TEXT,           -- store securely; never returned to the Android app
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- Indexing for lookup speed
CREATE INDEX IF NOT EXISTS idx_subscriptions_installation_id ON subscriptions(installation_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_status ON subscriptions(status);

-- 2. Devices Table
CREATE TABLE IF NOT EXISTS devices (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subscription_id UUID REFERENCES subscriptions(id) ON DELETE CASCADE,
    wireguard_pubkey TEXT UNIQUE NOT NULL,
    assigned_ip TEXT NOT NULL,
    server_location TEXT,
    registered_at TIMESTAMPTZ DEFAULT now(),
    last_handshake TIMESTAMPTZ,
    is_active BOOLEAN DEFAULT true
);

-- Indexing for wireguard public keys and subscription lookups
CREATE INDEX IF NOT EXISTS idx_devices_subscription_id ON devices(subscription_id);
CREATE INDEX IF NOT EXISTS idx_devices_wireguard_pubkey ON devices(wireguard_pubkey);

-- 3. Bandwidth Usage Table
CREATE TABLE IF NOT EXISTS bandwidth_usage (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subscription_id UUID REFERENCES subscriptions(id) ON DELETE CASCADE,
    device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
    bytes_sent BIGINT DEFAULT 0 CHECK (bytes_sent >= 0),
    bytes_received BIGINT DEFAULT 0 CHECK (bytes_received >= 0),
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bandwidth_subscription ON bandwidth_usage(subscription_id);
CREATE INDEX IF NOT EXISTS idx_bandwidth_device ON bandwidth_usage(device_id);

-- 4. Abuse Flags Table
CREATE TABLE IF NOT EXISTS abuse_flags (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subscription_id UUID REFERENCES subscriptions(id) ON DELETE CASCADE,
    flag_type TEXT NOT NULL CHECK (flag_type IN ('chargeback', 'shared_config', 'bandwidth_abuse')),
    flagged_at TIMESTAMPTZ DEFAULT now(),
    resolved BOOLEAN DEFAULT false
);

CREATE INDEX IF NOT EXISTS idx_abuse_subscription ON abuse_flags(subscription_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- CERT-In 2022 Compliance Tables
-- Required under India's IT (Amendment) Act, S70B — CERT-In Directions 2022
-- VPN providers must retain subscriber info for 5 years post-cancellation
-- and ICT system logs (connection logs) for a rolling 180 days.
-- ═══════════════════════════════════════════════════════════════════════════

-- 5. CERT-In Subscriber Records
-- Populated once per user at /api/verify (subscription purchase).
-- Retained for 5 years after subscription ends (delete_after field).
CREATE TABLE IF NOT EXISTS certin_user_records (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    installation_id TEXT NOT NULL UNIQUE,
    subscription_id UUID REFERENCES subscriptions(id) ON DELETE SET NULL,
    -- Mandatory CERT-In fields
    email TEXT,                            -- validated email (already collected)
    validated_name TEXT,                   -- full name if collected at signup
    phone TEXT,                            -- optional
    physical_address TEXT,                 -- optional
    purpose_of_hire TEXT NOT NULL DEFAULT 'personal_privacy',
    ownership_pattern TEXT NOT NULL DEFAULT 'individual',
    -- Registration context
    registration_ip TEXT NOT NULL,         -- real IP at first signup (from CF-Connecting-IP)
    registration_ts TIMESTAMPTZ NOT NULL DEFAULT now(),
    hire_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    hire_ended_at TIMESTAMPTZ,             -- set when subscription expires/cancelled
    -- Retention: must keep for 5 years after hire_ended_at
    delete_after TIMESTAMPTZ,              -- updated on cancellation: hire_ended_at + 5 years
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_certin_user_installation_id
    ON certin_user_records(installation_id);
CREATE INDEX IF NOT EXISTS idx_certin_user_delete_after
    ON certin_user_records(delete_after)
    WHERE delete_after IS NOT NULL;

-- 6. CERT-In Connection Logs
-- One row per VPN session. Written by VPN server sync script via /api/connection-log.
-- Auto-expires after 180 days via generated column (delete_after).
CREATE TABLE IF NOT EXISTS certin_connection_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    installation_id TEXT NOT NULL,
    subscription_id UUID REFERENCES subscriptions(id) ON DELETE SET NULL,
    device_pubkey TEXT,                    -- WireGuard peer public key
    -- Session details
    session_start TIMESTAMPTZ NOT NULL,
    session_end TIMESTAMPTZ,               -- NULL until disconnect detected
    source_ip TEXT NOT NULL,               -- user's real IP (endpoint seen by WireGuard)
    assigned_vpn_ip TEXT NOT NULL,         -- WireGuard IP assigned (e.g. 10.0.0.5)
    server_location TEXT NOT NULL,         -- server id: 'in', 'us', 'sg', 'cz', etc
    bytes_sent BIGINT NOT NULL DEFAULT 0,
    bytes_received BIGINT NOT NULL DEFAULT 0,
    -- Auto-calculated 180-day expiry (CERT-In ICT log requirement)
    delete_after TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '180 days'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_certin_conn_installation_id
    ON certin_connection_logs(installation_id);
-- Index on date for efficient pruning
CREATE INDEX IF NOT EXISTS connection_logs_date_idx ON certin_connection_logs(created_at);

-- =========================================================================================
-- APP ERROR LOGS (for Android crash reporting and VPN failures)
-- =========================================================================================

CREATE TABLE IF NOT EXISTS app_error_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    installation_id TEXT,
    error_type TEXT NOT NULL,         -- e.g. 'vpn_failure', 'crash'
    error_message TEXT NOT NULL,
    stack_trace TEXT,
    device_info TEXT,                 -- JSON string containing OS version, device model, etc.
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for searching errors by installation ID
CREATE INDEX IF NOT EXISTS idx_app_error_logs_installation_id ON app_error_logs(installation_id);

CREATE INDEX IF NOT EXISTS idx_certin_conn_delete_after
    ON certin_connection_logs(delete_after);
CREATE INDEX IF NOT EXISTS idx_certin_conn_session_start
    ON certin_connection_logs(session_start DESC);
CREATE INDEX IF NOT EXISTS idx_certin_conn_device_pubkey
    ON certin_connection_logs(device_pubkey);

-- Auto-update updated_at on certin_user_records
CREATE OR REPLACE FUNCTION update_certin_user_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER certin_user_updated_at
    BEFORE UPDATE ON certin_user_records
    FOR EACH ROW EXECUTE FUNCTION update_certin_user_updated_at();

-- ── Row Level Security (RLS) ────────────────────────────────────────────────
-- Enable RLS on all tables. Since the Cloudflare Worker uses SUPABASE_SERVICE_ROLE_KEY,
-- it bypasses RLS automatically. Enabling RLS without public policies prevents any
-- direct unauthorized access via the anon key.

ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE bandwidth_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE abuse_flags ENABLE ROW LEVEL SECURITY;
ALTER TABLE certin_user_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE certin_connection_logs ENABLE ROW LEVEL SECURITY;

-- ── pg_cron: Daily cleanup of expired connection logs ──────────────────────
-- Enable pg_cron in Supabase: Dashboard → Database → Extensions → pg_cron
-- Then run this once in the SQL editor:
--
-- SELECT cron.schedule(
--   'certin-log-cleanup',
--   '0 2 * * *',
--   $$DELETE FROM certin_connection_logs WHERE delete_after < now();$$
-- );
--
-- To verify it's scheduled:
-- SELECT * FROM cron.job;

