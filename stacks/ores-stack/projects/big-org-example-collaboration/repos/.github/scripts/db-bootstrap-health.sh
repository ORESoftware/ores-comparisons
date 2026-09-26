#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test -f .local/db-bootstrap.ready
