-- Migration 001: Add VPNResellers account credential columns to subscriptions
-- Run once in Supabase SQL Editor for existing deployments.
-- New deployments: schema.sql already includes these columns.

-- 1. Add columns (safe to run multiple times due to IF NOT EXISTS guards)
ALTER TABLE subscriptions
  ADD COLUMN IF NOT EXISTS vpn_account_id TEXT,
  ADD COLUMN IF NOT EXISTS vpn_username   TEXT,
  ADD COLUMN IF NOT EXISTS vpn_password   TEXT;

-- 2. Enable Row Level Security on subscriptions if not already enabled
ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;

-- 3. Drop any overly-permissive anon policies
DROP POLICY IF EXISTS "anon_read_subscriptions" ON subscriptions;

-- 4. Service-role-only policy: the Cloudflare Worker uses the service role key,
--    which bypasses RLS by default in Supabase. No explicit policy needed for it.
--    We just ensure the anon role has NO access at all.
REVOKE SELECT, INSERT, UPDATE, DELETE ON subscriptions FROM anon;
REVOKE SELECT, INSERT, UPDATE, DELETE ON subscriptions FROM authenticated;

-- 5. Comment for audit trail
COMMENT ON COLUMN subscriptions.vpn_password IS
  'VPNResellers account password for Basic-auth config fetches. '
  'Never returned to the Android app. Service role access only.';
