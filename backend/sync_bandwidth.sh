#!/bin/bash

# Throttling and peer sync script for ShieldVPN Always Free VMs
# Runs as a cron job every 5 minutes

set -e

# Configuration
SERVER_ID="in" # Change per VM (in, us, sg)
API_URL="https://api.vpnapp.in/api/bandwidth-sync"
SECRET_KEY="VPN_API_HMAC_SECRET_KEY"

# Ensure run as root
if [ "$EUID" -ne 0 ]; then
  echo "Please run as root (using sudo)"
  exit 1
fi

# Ensure jq is installed
if ! command -v jq &> /dev/null; then
  echo "Installing jq dependency..."
  apt-get update -y && apt-get install -y jq
fi

# 1. Fetch raw WireGuard dump
DUMP=$(wg show wg0 dump)

# Format payload to JSON
PEERS_JSON="[]"

while read -r line; do
  [ -z "$line" ] && continue
  
  PUBKEY=$(echo "$line" | awk '{print $1}')
  IP=$(echo "$line" | awk '{print $4}' | cut -d',' -f1) # Get first IP
  RX=$(echo "$line" | awk '{print $6}')
  TX=$(echo "$line" | awk '{print $7}')
  
  # Skip server interface row (it doesn't have numeric rx/tx)
  if [[ ! "$RX" =~ ^[0-9]+$ ]]; then
    continue
  fi
  
  PEER_OBJ=$(cat <<EOF
{
  "public_key": "$PUBKEY",
  "client_ip": "$IP",
  "rx_bytes": $RX,
  "tx_bytes": $TX
}
EOF
)
  PEERS_JSON=$(echo "$PEERS_JSON" | jq ". += [$PEER_OBJ]")
done <<< "$DUMP"

PAYLOAD=$(cat <<EOF
{
  "server_id": "$SERVER_ID",
  "peers": $PEERS_JSON
}
EOF
)

# 2. Generate HMAC signature matching app validation
SIGNATURE=$(echo -n "$PAYLOAD" | openssl dgst -sha256 -hmac "$SECRET_KEY" -binary | openssl enc -base64 -A)

# 3. Post logs to Worker API and get directives
RESPONSE=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -H "X-App-Signature: $SIGNATURE" \
  -d "$PAYLOAD" \
  "$API_URL")

# Check if response has errors
if echo "$RESPONSE" | jq -e '.error' >/dev/null; then
  echo "API error: $(echo "$RESPONSE" | jq -r '.message')"
  exit 1
fi

# 4. Setup tc disciplines if not initialized
if ! tc qdisc show dev wg0 | grep -q "htb"; then
  tc qdisc add dev wg0 root handle 1: htb default 10
  tc class add dev wg0 parent 1: classid 1:10 htb rate 1gbit
  tc class add dev wg0 parent 1: classid 1:20 htb rate 1mbit
  tc class add dev wg0 parent 1: classid 1:30 htb rate 512kbit
fi

# 5. Process directives
DIRECTIVES=$(echo "$RESPONSE" | jq -c '.directives[]')

while read -r directive; do
  if [ -z "$directive" ]; then
    continue
  fi
  
  PUBKEY=$(echo "$directive" | jq -r '.public_key')
  ACTION=$(echo "$directive" | jq -r '.action')
  IP=$(echo "$directive" | jq -r '.ip')
  
  # Remove filters for this IP first to prevent duplicates
  if [ -n "$IP" ] && [ "$IP" != "null" ]; then
    # We find filter handles matching IP destination and delete them
    FILTER_HANDLES=$(tc filter show dev wg0 | grep -B 1 "match $IP" | grep "filter" | awk '{print $10}' || true)
    for handle in $FILTER_HANDLES; do
      if [ -n "$handle" ]; then
        tc filter del dev wg0 parent 1: protocol ip prio 1 handle "$handle" u32
      fi
    done
  fi

  if [ "$ACTION" = "remove" ]; then
    echo "De-authorizing peer: $PUBKEY"
    wg set wg0 peer "$PUBKEY" remove
  elif [ "$ACTION" = "throttle_1m" ]; then
    echo "Throttling peer $IP to 1Mbps"
    tc filter add dev wg0 parent 1: protocol ip prio 1 u32 match ip dst "$IP" flowid 1:20
  elif [ "$ACTION" = "throttle_512k" ]; then
    echo "Throttling peer $IP to 512Kbps"
    tc filter add dev wg0 parent 1: protocol ip prio 1 u32 match ip dst "$IP" flowid 1:30
  else
    echo "Allowing peer $IP at normal speed"
  fi
done <<< "$DIRECTIVES"

echo "Bandwidth sync and throttling update complete."

# ═══════════════════════════════════════════════════════════════════════════
# CERT-In 2022 Compliance — Connection Event Logging
# Detects when WireGuard peers connect and disconnect, then POSTs session
# events to /api/connection-log for storage in Supabase (certin_connection_logs).
#
# State file: /tmp/certin_peer_state
#   Format per line: PUBKEY|LAST_HANDSHAKE|STATUS
#   STATUS: "connected" or "disconnected"
#
# CERT-In requires all ICT system logs retained for 180 days.
# The Cloudflare Worker handles the actual database write and retention.
# ═══════════════════════════════════════════════════════════════════════════

CONNECTION_LOG_URL="https://api.vpnapp.in/api/connection-log"
WORKER_AUTH_SECRET="VPN_WORKER_AUTH_SECRET"  # must match WORKER_AUTH_SECRET in Cloudflare
STATE_FILE="/tmp/certin_peer_state"
STALE_THRESHOLD=180   # seconds — if last_handshake older than 3 min, peer is disconnected

# Read the full WireGuard dump (includes endpoint/source IP)
FULL_DUMP=$(wg show wg0 dump)

touch "$STATE_FILE"

while IFS= read -r line; do
  [ -z "$line" ] && continue

  PUBKEY=$(echo "$line"        | awk '{print $1}')
  ENDPOINT=$(echo "$line"      | awk '{print $3}')   # source IP:port (user's real IP)
  ALLOWED_IP=$(echo "$line"    | awk '{print $4}' | cut -d'/' -f1)  # assigned VPN IP
  LAST_HANDSHAKE=$(echo "$line"| awk '{print $5}')   # epoch seconds, 0 if never

  # Skip interface row (no numeric handshake)
  [[ ! "$LAST_HANDSHAKE" =~ ^[0-9]+$ ]] && continue
  [ "$PUBKEY" = "(none)" ] && continue

  SOURCE_IP=$(echo "$ENDPOINT" | cut -d':' -f1)
  NOW=$(date +%s)
  AGE=$(( NOW - LAST_HANDSHAKE ))

  # Read previous state for this peer
  PREV_STATUS=$(grep "^${PUBKEY}|" "$STATE_FILE" | cut -d'|' -f3)
  PREV_HANDSHAKE=$(grep "^${PUBKEY}|" "$STATE_FILE" | cut -d'|' -f2)

  # ── Determine current connectivity ────────────────────────────────────────
  if [ "$LAST_HANDSHAKE" -gt 0 ] && [ "$AGE" -lt "$STALE_THRESHOLD" ]; then
    CURRENT_STATUS="connected"
  else
    CURRENT_STATUS="disconnected"
  fi

  # ── Fire events on state transitions ──────────────────────────────────────
  if [ "$CURRENT_STATUS" = "connected" ] && [ "$PREV_STATUS" != "connected" ]; then
    # CONNECT event: peer just became active
    PAYLOAD=$(cat <<EOF
{
  "installationId": "$PUBKEY",
  "devicePubkey": "$PUBKEY",
  "event": "connect",
  "sourceIp": "$SOURCE_IP",
  "assignedVpnIp": "$ALLOWED_IP",
  "serverLocation": "$SERVER_ID",
  "bytesSent": 0,
  "bytesReceived": 0
}
EOF
)
    curl -sf -X POST \
      -H "Content-Type: application/json" \
      -H "X-Worker-Auth: $WORKER_AUTH_SECRET" \
      -d "$PAYLOAD" \
      "$CONNECTION_LOG_URL" \
      > /dev/null 2>&1 || true
    echo "[CERT-In] Connect logged for $PUBKEY from $SOURCE_IP"

  elif [ "$CURRENT_STATUS" = "disconnected" ] && [ "$PREV_STATUS" = "connected" ]; then
    # DISCONNECT event: peer went stale
    # Include the final byte counters from the current dump
    RX=$(echo "$line" | awk '{print $6}')
    TX=$(echo "$line" | awk '{print $7}')

    PAYLOAD=$(cat <<EOF
{
  "installationId": "$PUBKEY",
  "devicePubkey": "$PUBKEY",
  "event": "disconnect",
  "sourceIp": "$SOURCE_IP",
  "assignedVpnIp": "$ALLOWED_IP",
  "serverLocation": "$SERVER_ID",
  "bytesSent": ${TX:-0},
  "bytesReceived": ${RX:-0}
}
EOF
)
    curl -sf -X POST \
      -H "Content-Type: application/json" \
      -H "X-Worker-Auth: $WORKER_AUTH_SECRET" \
      -d "$PAYLOAD" \
      "$CONNECTION_LOG_URL" \
      > /dev/null 2>&1 || true
    echo "[CERT-In] Disconnect logged for $PUBKEY"
  fi

  # ── Update state file ──────────────────────────────────────────────────────
  # Remove old entry for this peer, then append updated entry
  sed -i "/^${PUBKEY}|/d" "$STATE_FILE"
  echo "${PUBKEY}|${LAST_HANDSHAKE}|${CURRENT_STATUS}" >> "$STATE_FILE"

done <<< "$FULL_DUMP"

echo "CERT-In connection logging complete."
