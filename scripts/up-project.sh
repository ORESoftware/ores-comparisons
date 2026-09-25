#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "${1:?project root required}" && pwd)"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bin="${ORES_COMPOSE_BIN:-$repo_root/.tools/ores-compose/bin/ores-compose}"
if [[ ! -x "$bin" ]]; then
  echo "pinned ores-compose is not installed at $bin" >&2
  echo "run: ./scripts/install-ores-compose.sh" >&2
  exit 2
fi
cd "$project_root"
exec "$bin" --skip-zed-pkg --skip-rpc-gen up .ores-compose.yaml
