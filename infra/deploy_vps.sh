#!/usr/bin/env bash
set -euo pipefail

# Thin wrapper that calls scripts/deploy_vps.sh
DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
"$DIR"/../scripts/deploy_vps.sh "$@"
