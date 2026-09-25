#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$PROJECT/../../../.." && pwd)"
cd "$PROJECT"
source scripts/load-env.sh
cd repos/app
exec "$ROOT/.local/bin/ores-stack" dev
