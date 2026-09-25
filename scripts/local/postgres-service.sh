#!/usr/bin/env bash
set -euo pipefail
project="$(basename "$PWD")"
stack="$(basename "$(dirname "$(dirname "$PWD")")")"
slug="${stack}-${project}"
container="ores-cmp-${slug}-pg"
mkdir -p .runtime/postgres
docker rm -f "$container" >/dev/null 2>&1 || true
exec docker run --rm \
  --name "$container" \
  --publish 127.0.0.1:55432:5432 \
  --env POSTGRES_DB=ores_comparisons \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD=postgres \
  --volume "$PWD/.runtime/postgres:/var/lib/postgresql/data" \
  postgres:16-alpine
