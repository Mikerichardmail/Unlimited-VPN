#!/bin/bash

# Production Server Setup Script for WireGuard VPN Always Free Oracle VMs
# Runs on Ubuntu 22.04 LTS

set -e

# Ensure running as root
if [ "$EUID" -ne 0 ]; then
  echo "Please run as root (using sudo)"
  exit 1
fi

echo "=================================================="
echo " Starting ShieldVPN Server Setup & Hardening"
echo "=================================================="

# 1. Update and Upgrade System
echo "[1/7] Updating system packages..."
apt-get update -y
apt-get upgrade -y

# 2. Install required packages
echo "[2/7] Installing WireGuard, UFW, Fail2ban, and Unattended-upgrades..."
apt-get install -y wireguard iptables ufw fail2ban unattended-upgrades

# 3. Configure sysctl IPv4 IP Forwarding (required for VPN routing)
echo "[3/7] Enabling IPv4 packet forwarding..."
sysctl_file="/etc/sysctl.conf"
if grep -q "^#net.ipv4.ip_forward=1" "$sysctl_file"; then
  sed -i "s/^#net.ipv4.ip_forward=1/net.ipv4.ip_forward=1/" "$sysctl_file"
elif ! grep -q "^net.ipv4.ip_forward=1" "$sysctl_file"; then
  echo "net.ipv4.ip_forward=1" >> "$sysctl_file"
fi
sysctl -p

# 4. Generate Server WireGuard Keys (if not already existing)
echo "[4/7] Generating WireGuard configuration..."
WG_DIR="/etc/wireguard"
mkdir -p "$WG_DIR"
chmod 700 "$WG_DIR"

if [ ! -f "$WG_DIR/private.key" ]; then
  wg genkey | tee "$WG_DIR/private.key" | wg pubkey > "$WG_DIR/public.key"
  echo "Server keys generated successfully."
fi

SERVER_PRIV_KEY=$(cat "$WG_DIR/private.key")
SERVER_PUB_KEY=$(cat "$WG_DIR/public.key")

# Determine default internet interface (usually eth0 or enp0s3)
NET_INTF=$(ip route | grep default | awk '{print $5}' | head -n 1)
if [ -z "$NET_INTF" ]; then
  NET_INTF="eth0"
fi

# Build baseline wg0.conf configuration
cat <<EOF > "$WG_DIR/wg0.conf"
[Interface]
PrivateKey = $SERVER_PRIV_KEY
Address = 10.0.0.1/16
ListenPort = 51820

# NAT forwarding rules
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o $NET_INTF -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o $NET_INTF -j MASQUERADE
EOF

chmod 600 "$WG_DIR/wg0.conf"

# 5. Enable and start WireGuard Service
echo "[5/7] Enabling and starting WireGuard service..."
systemctl enable wg-quick@wg0
systemctl start wg-quick@wg0

# 6. Setup UFW Firewall
echo "[6/7] Configuring UFW firewall rules..."
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment 'SSH'
ufw allow 51820/udp comment 'WireGuard'
ufw --force enable

# 7. Configure Unattended-upgrades & Fail2ban
echo "[7/7] Enabling security patches & Fail2ban rules..."
# Configure fail2ban jail
cat <<EOF > /etc/fail2ban/jail.local
[sshd]
enabled = true
port = ssh
filter = sshd
logpath = /var/log/auth.log
maxretry = 5
bantime = 86400
EOF
systemctl restart fail2ban

# Configure unattended-upgrades
dpkg-reconfigure -plow unattended-upgrades

echo "=================================================="
echo " Setup Finished!"
echo "=================================================="
echo "Server Public Key (Paste into your Worker config):"
echo "$SERVER_PUB_KEY"
echo "=================================================="
