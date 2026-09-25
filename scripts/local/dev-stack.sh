#!/usr/bin/env bash
set -euo pipefail
project="$(basename "$PWD")"
stack="$(basename "$(dirname "$(dirname "$PWD")")")"
export DATABASE_URL="${DATABASE_URL:-postgresql://postgres:postgres@127.0.0.1:55432/ores_comparisons}"
export ORES_COMPARISON_PROJECT="$project"
case "$stack" in
  beamscale)
    exec bmscl dev .
    ;;
  scintilla-run)
    exec scintilla dev --project .
    ;;
  ores-stack)
    exec ores-stack dev
    ;;
  *)
    echo "unsupported comparison stack: $stack" >&2
    exit 64
    ;;
esac
