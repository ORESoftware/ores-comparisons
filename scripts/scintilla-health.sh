#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
host="$(jq -r '.scintilla_backend.host' "$root/toolchain.lock.json")"
port="$(jq -r '.scintilla_backend.port' "$root/toolchain.lock.json")"
exec curl --fail --silent --show-error "http://${host}:${port}/healthz"
