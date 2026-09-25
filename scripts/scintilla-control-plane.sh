#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(jq -r '.scintilla_backend.repository' "$root/toolchain.lock.json")"
rev="$(jq -r '.scintilla_backend.rev' "$root/toolchain.lock.json")"
host="$(jq -r '.scintilla_backend.host' "$root/toolchain.lock.json")"
port="$(jq -r '.scintilla_backend.port' "$root/toolchain.lock.json")"
cache_root="${ORES_COMPARISONS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/ores-comparisons}"
checkout="$cache_root/scintilla-backend-$rev"
if [[ ! -d "$checkout/.git" ]]; then
  mkdir -p "$cache_root"
  git clone --filter=blob:none "$repo" "$checkout"
fi
git -C "$checkout" fetch --depth 1 origin "$rev"
git -C "$checkout" checkout --detach "$rev"
export HOST="$host" PORT="$port" SCINTILLA_REQUIRE_DATABASE=false SCINTILLA_REQUIRE_SUPABASE_AUTH=false
exec cargo run --locked --manifest-path "$checkout/Cargo.toml"
