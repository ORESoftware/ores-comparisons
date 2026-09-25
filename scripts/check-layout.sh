#!/usr/bin/env bash
set -euo pipefail

stacks=(beamscale scintilla-run ores-stack)
projects=(form-service realtime-chat rpc-graphql)

for stack in "${stacks[@]}"; do
  for project in "${projects[@]}"; do
    root="stacks/$stack/projects/$project"
    test -f "$root/README.md"
    test -f "$root/comparison.toml"
    test -f "$root/integrations.toml"
    test -d "$root/env/enc"
    test -d "$root/env/dec"
  done
done

echo "layout ok: 3 stacks x 3 matched projects"
