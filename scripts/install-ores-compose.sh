#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rev="$(jq -r '.ores_compose.rev' "$root/toolchain.lock.json")"
repo="$(jq -r '.ores_compose.repository' "$root/toolchain.lock.json")"
install_root="${ORES_COMPOSE_INSTALL_ROOT:-$root/.tools/ores-compose}"
mkdir -p "$install_root"
exec cargo install --git "$repo" --rev "$rev" --locked --root "$install_root" ores-compose
