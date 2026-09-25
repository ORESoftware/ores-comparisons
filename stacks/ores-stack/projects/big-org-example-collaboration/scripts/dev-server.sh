#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."; source scripts/load-env.sh; ROOT="$(cd ../../../.. && pwd)"
exec "$ROOT/.local/bin/ores-stack" dev
