#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${PGPORT:-55434}"
exec pg_isready -h 127.0.0.1 -p "$PORT" -U postgres -d postgres
