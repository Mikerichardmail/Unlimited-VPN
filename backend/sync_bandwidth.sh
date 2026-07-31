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
