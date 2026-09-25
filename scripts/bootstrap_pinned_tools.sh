#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/tools/toolchain.lock.json"
BIN="$ROOT/.local/bin"
RUNTIMES="$ROOT/.local/runtimes"
mkdir -p "$BIN" "$RUNTIMES"

read_pin() {
  python3 - "$1" "$2" "$LOCK" <<'PY'
import json, sys
name, field, path = sys.argv[1:]
print(json.load(open(path))["tools"][name][field])
PY
}

install_cargo_git() {
  name="$1"
  bin="$2"
  repo="$(read_pin "$name" repository)"
  rev="$(read_pin "$name" commit)"
  root="$ROOT/.local/tools/$name"
  mkdir -p "$root"
  if [[ ! -x "$root/bin/$bin" ]]; then
    CARGO_NET_GIT_FETCH_WITH_CLI=true cargo install --git "$repo" --rev "$rev" --locked --root "$root" --bin "$bin"
  fi
  ln -sfn "$root/bin/$bin" "$BIN/$bin"
}

checkout_runtime() {
  name="$1"
  repo="$(read_pin "$name" repository)"
  rev="$(read_pin "$name" commit)"
  dir="$RUNTIMES/$name"
  if [[ ! -d "$dir/.git" ]]; then
    git clone --no-checkout "$repo.git" "$dir"
  fi
  git -C "$dir" fetch --depth=1 origin "$rev"
  git -C "$dir" checkout --detach "$rev"
}

install_cargo_git ores-compose ores-compose
install_cargo_git bmscl-cli bmscl
install_cargo_git bmscl-compiler bmscl-compiler
install_cargo_git scintilla-cli scintilla
install_cargo_git ores-stack ores-stack

checkout_runtime bmscl-supervisor
checkout_runtime scintilla-runner
checkout_runtime scintilla-backend

TJSV_VERSION="$(read_pin typespec-json-schema-validator version)"
mkdir -p "$ROOT/.local/node-tools"
npm install --prefix "$ROOT/.local/node-tools" --no-save "@oresoftware/typespec-json-schema-validator@$TJSV_VERSION"
ln -sfn "$ROOT/.local/node-tools/node_modules/.bin/tjsv" "$BIN/tjsv"

echo "pinned tools installed in $BIN"
