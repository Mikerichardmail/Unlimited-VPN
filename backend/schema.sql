-- Supabase Database Schema for WireGuard VPN Android App

-- Enable UUID extension if not already enabled
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Subscriptions Table
CREATE TABLE IF NOT EXISTS subscriptions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    installation_id TEXT NOT NULL,
    email TEXT,
    google_purchase_token TEXT UNIQUE NOT NULL,
    plan_type TEXT NOT NULL CHECK (plan_type IN ('monthly', 'yearly', 'three_year')),
    status TEXT NOT NULL CHECK (status IN ('active', 'grace', 'on_hold', 'expired', 'refunded')),
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    grace_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now()
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
    server_location TEXT CHECK (server_location IN ('in', 'us', 'sg')),
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
