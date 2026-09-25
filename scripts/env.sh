#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <encrypt|decrypt> <project-dir> <name>" >&2
  exit 2
}

[[ $# -eq 3 ]] || usage
op=$1
project=$2
name=$3
enc="$project/env/enc/$name.sops.yaml"
dec="$project/env/dec/$name.yaml"

case "$op" in
  encrypt)
    [[ -n "${SOPS_AGE_RECIPIENTS:-}" ]] || { echo "SOPS_AGE_RECIPIENTS is required" >&2; exit 1; }
    [[ -f "$dec" ]] || { echo "missing plaintext input: $dec" >&2; exit 1; }
    mkdir -p "$(dirname "$enc")"
    sops --encrypt --age "$SOPS_AGE_RECIPIENTS" "$dec" > "$enc"
    ;;
  decrypt)
    [[ -f "$enc" ]] || { echo "missing ciphertext input: $enc" >&2; exit 1; }
    mkdir -p "$(dirname "$dec")"
    umask 077
    sops --decrypt "$enc" > "$dec"
    ;;
  *) usage ;;
esac
