#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."; exec pg_isready -h 127.0.0.1 -p "${PGPORT:-55438}" -U postgres -d postgres
