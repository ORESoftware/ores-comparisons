#!/usr/bin/env bash
set -euo pipefail
project="$(basename "$PWD")"
stack="$(basename "$(dirname "$(dirname "$PWD")")")"
container="ores-cmp-${stack}-${project}-pg"
docker exec "$container" pg_isready -U postgres -d ores_comparisons >/dev/null
