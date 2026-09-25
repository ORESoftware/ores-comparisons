#!/usr/bin/env bash
set -euo pipefail

project=${1:?usage: materialize-env.sh <project-dir> [environment]}
environment=${2:-dev}
enc="$project/env/enc/$environment.env.yaml"
dec="$project/env/dec/$environment.env"

if [[ ! -f "$enc" ]]; then
  echo "missing encrypted environment: $enc" >&2
  exit 2
fi

if [[ -z "${SOPS_AGE_KEY_FILE:-}" && -z "${SOPS_AGE_KEY:-}" ]]; then
  echo "set SOPS_AGE_KEY_FILE or SOPS_AGE_KEY before decrypting" >&2
  exit 2
fi

mkdir -p "$(dirname "$dec")"
umask 077
sops --decrypt --output-type dotenv "$enc" > "$dec"
printf 'wrote %s\n' "$dec"
