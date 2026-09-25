#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .local
rm -f .local/db-bootstrap.ready
touch .local/db-bootstrap.ready
trap 'rm -f .local/db-bootstrap.ready' EXIT INT TERM
while :; do sleep 3600; done
