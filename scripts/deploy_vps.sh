#!/usr/bin/env bash
set -euo pipefail

# Deploy bonding server to Ubuntu 22.04 VPS
# Usage: ./scripts/deploy_vps.sh user@host [domain]

HOST=${1:-}
DOMAIN=${2:-}
if [[ -z "$HOST" ]]; then
  echo "Usage: $0 user@host [domain]" >&2
  exit 1
fi

ssh -o StrictHostKeyChecking=accept-new "$HOST" bash -s <<'REMOTE'
set -euo pipefail
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg lsb-release
# Docker
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
ARCH=$(dpkg --print-architecture)
CODENAME=$(lsb_release -cs)
echo "deb [arch=$ARCH signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $CODENAME stable" | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Create app directory
sudo mkdir -p /opt/dualwan-server
sudo chown "$USER" /opt/dualwan-server
REMOTE

# Build and copy image (local build expected)
docker image inspect bonding-server:release >/dev/null 2>&1 || {
  echo "Building local server image bonding-server:release"
  docker build -t bonding-server:release server
}

# Save image and send to VPS
TMP_IMG=$(mktemp)
docker save bonding-server:release -o "$TMP_IMG"
scp "$TMP_IMG" "$HOST:/tmp/bonding-server.tar"
rm -f "$TMP_IMG"

ssh "$HOST" bash -s <<'REMOTE'
set -euo pipefail
sudo docker load -i /tmp/bonding-server.tar
cat <<'UNIT' | sudo tee /etc/systemd/system/bonding-server.service >/dev/null
[Unit]
Description=Dual-WAN Bonding Server
After=network.target docker.service
Requires=docker.service

[Service]
Restart=always
ExecStart=/usr/bin/docker run --rm --name bonding-server -p 443:443 bonding-server:release
ExecStop=/usr/bin/docker stop bonding-server

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now bonding-server
REMOTE

if [[ -n "$DOMAIN" ]]; then
  echo "For TLS via Let's Encrypt, run scripts/setup_certbot.sh $HOST $DOMAIN" >&2
fi
