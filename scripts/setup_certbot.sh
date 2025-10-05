#!/usr/bin/env bash
set -euo pipefail

# Setup Let's Encrypt certificate on VPS and proxy 443 to container
# Usage: ./scripts/setup_certbot.sh user@host example.com

HOST=${1:-}
DOMAIN=${2:-}
if [[ -z "$HOST" || -z "$DOMAIN" ]]; then
  echo "Usage: $0 user@host domain" >&2
  exit 1
fi

ssh "$HOST" bash -s <<REMOTE
set -euo pipefail
sudo snap install core; sudo snap refresh core
sudo snap install --classic certbot
sudo ln -sf /snap/bin/certbot /usr/bin/certbot
# Stop server to bind 443
sudo systemctl stop bonding-server || true
sudo certbot certonly --standalone -d $DOMAIN --non-interactive --agree-tos -m admin@$DOMAIN
# Restart server with mounted certs (update systemd unit accordingly if desired)
echo "Certificates at /etc/letsencrypt/live/$DOMAIN" >&2
sudo systemctl start bonding-server || true
REMOTE
