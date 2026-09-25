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
  package="${3:-}"
  repo="$(read_pin "$name" repository)"
  rev="$(read_pin "$name" commit)"
  root="$ROOT/.local/tools/$name"
  mkdir -p "$root"
  if [[ ! -x "$root/bin/$bin" ]]; then
    args=(cargo install --git "$repo" --rev "$rev" --locked --root "$root" --bin "$bin")
    if [[ -n "$package" ]]; then args+=(--package "$package"); fi
    CARGO_NET_GIT_FETCH_WITH_CLI=true "${args[@]}"
  fi
  ln -sfn "$root/bin/$bin" "$BIN/$bin"
}

checkout_to() {
  name="$1"
  dir="$2"
  repo="$(read_pin "$name" repository)"
  rev="$(read_pin "$name" commit)"
  if [[ ! -d "$dir/.git" ]]; then
    mkdir -p "$(dirname "$dir")"
    git clone --no-checkout "$repo.git" "$dir"
  fi
  git -C "$dir" fetch --depth=1 origin "$rev"
  git -C "$dir" checkout --detach "$rev"
  actual="$(git -C "$dir" rev-parse HEAD)"
  [[ "$actual" == "$rev" ]] || { echo "$name revision mismatch" >&2; exit 1; }
}

checkout_runtime() {
  checkout_to "$1" "$RUNTIMES/$1"
}

install_scintilla_cli() {
  checkout_runtime scintilla-cli
  cli_dir="$RUNTIMES/scintilla-cli"
  dep_dir="$cli_dir/.vendor/.zed/oresoftware/ores-clis-core"
  checkout_to ores-clis-core "$dep_dir"
  root="$ROOT/.local/tools/scintilla-cli"
  mkdir -p "$root"
  if [[ ! -x "$root/bin/scintilla" ]]; then
    CARGO_NET_GIT_FETCH_WITH_CLI=true cargo install       --path "$cli_dir"       --locked       --root "$root"       --bin scintilla
  fi
  ln -sfn "$root/bin/scintilla" "$BIN/scintilla"
}

install_cargo_git ores-compose ores-compose
install_cargo_git bmscl-cli bmscl
install_cargo_git bmscl-compiler bmscl-compiler
install_scintilla_cli
install_cargo_git ores-stack ores-stack ores-stack-cli

checkout_runtime bmscl-supervisor
checkout_runtime scintilla-runner
checkout_runtime scintilla-backend
checkout_runtime typespec-json-schema-validator

TJSV_DIR="$RUNTIMES/typespec-json-schema-validator"
(
  cd "$TJSV_DIR"
  npm ci
)
ln -sfn "$TJSV_DIR/bin/typespec-json-schema-validator.mjs" "$BIN/tjsv"

echo "pinned tools installed in $BIN"
