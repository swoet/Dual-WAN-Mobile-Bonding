#!/usr/bin/env bash
set -euo pipefail

# Build and run server locally without TLS (INSECURE)
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$ROOT_DIR/server"

go build -o bonding-server ./cmd/bonding-server
INSECURE=1 LISTEN_ADDR=:8443 METRICS_ADDR=:8080 ./bonding-server
