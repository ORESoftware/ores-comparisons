#!/usr/bin/env bash
set -euo pipefail
readonly repo="https://github.com/ORESoftware/ores-compose"
readonly rev="fbfad966f9770a9a8d3895880523280324b4ddc6"
readonly version="0.1.0"
echo "Installing ores-compose ${version} from ${rev}" >&2
exec cargo install --locked --git "$repo" --rev "$rev" ores-compose
