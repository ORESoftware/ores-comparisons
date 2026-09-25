#!/usr/bin/env bash
set -euo pipefail

project="${1:?usage: bootstrap-env.sh stacks/<stack>/projects/<project>}"
if [[ "$(basename "$project")" == ".github" && "$(basename "$(dirname "$project")")" == "repos" ]]; then
  shared="$project"
else
  shared="$project/repos/.github"
fi
cd "$shared"

for bin in sops python3; do
  command -v "$bin" >/dev/null || { echo "missing $bin; run nix develop" >&2; exit 2; }
done

: "${DEV_AGE_RECIPIENT:?set DEV_AGE_RECIPIENT to a public age1... recipient}"
: "${STAGE_AGE_RECIPIENT:?set STAGE_AGE_RECIPIENT to a public age1... recipient}"
: "${PROD_AGE_RECIPIENT:?set PROD_AGE_RECIPIENT to a public age1... recipient}"
: "${RECOVERY_AGE_RECIPIENT:?set RECOVERY_AGE_RECIPIENT to a public age1... recipient}"

python3 - <<'PY'
from pathlib import Path
import os
p = Path(".sops.yaml")
s = p.read_text()
for key, env in {
    "__DEV_AGE_RECIPIENT__": "DEV_AGE_RECIPIENT",
    "__STAGE_AGE_RECIPIENT__": "STAGE_AGE_RECIPIENT",
    "__PROD_AGE_RECIPIENT__": "PROD_AGE_RECIPIENT",
    "__RECOVERY_AGE_RECIPIENT__": "RECOVERY_AGE_RECIPIENT",
}.items():
    s = s.replace(key, os.environ[env])
p.write_text(s)
PY

mkdir -p env/enc env/dec
chmod 700 env/dec
for env_name in dev stage prod; do
  plain="env/dec/$env_name.env"
  cipher="env/enc/$env_name.env.enc"
  if [[ ! -e "$plain" ]]; then
    cp .env.example "$plain"
    chmod 600 "$plain"
  fi
  sops --encrypt     --input-type dotenv     --output-type dotenv     --filename-override "$cipher"     "$plain" > "$cipher"
done

ln -sfn env/dec/dev.env .env

if command -v ores-sops >/dev/null 2>&1; then
  ores-sops verify
fi

echo "encrypted dev/stage/prod env files created under $PWD/env/enc"
echo "plaintext remains ignored under $PWD/env/dec"
