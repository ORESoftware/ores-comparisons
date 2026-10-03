#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZED_BIN="${ZED_BIN:-$ROOT/.local/bin/zed}"
LEDGER="$ROOT/shared/dummy-org-gitlinks.json"

if [[ ! -x "$ZED_BIN" ]]; then
  echo "zed binary not found or not executable: $ZED_BIN" >&2
  exit 1
fi

REMOTE_URL="$(git -C "$ROOT" config --get remote.origin.url)"
HEAD_SHA="$(git -C "$ROOT" rev-parse HEAD)"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/ores-comparisons-fresh-clone.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
clone="$tmp/repo"

git clone --quiet --no-checkout "$REMOTE_URL" "$clone"
git -C "$clone" fetch --quiet origin "$HEAD_SHA"
git -C "$clone" checkout --quiet --detach "$HEAD_SHA"

verify_gitlinks() {
  python3 - "$clone" <<'PY'
import json
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])
ledger = json.loads((root / "shared/dummy-org-gitlinks.json").read_text())
entries = ledger["entries"]
errors = []

for entry in entries:
    path = root / entry["path"]
    if not path.is_dir():
        errors.append(f"{entry['path']}: submodule checkout missing")
        continue
    actual = subprocess.run(
        ["git", "-C", str(path), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()
    if actual != entry["commit"]:
        errors.append(
            f"{entry['path']}: checked out {actual}, expected {entry['commit']}"
        )

if errors:
    print("fresh-clone gitlink verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(f"fresh-clone gitlink verification OK: {len(entries)} exact submodules")
PY
}

(
  cd "$clone"
  "$ZED_BIN" install --git-submodules
)

status="$(git -C "$clone" submodule status --recursive)"
if grep -Eq '^[+-U]' <<<"$status"; then
  echo "fresh-clone submodule status contains uninitialized/drifted/conflicted entries" >&2
  printf '%s\n' "$status" >&2
  exit 1
fi

verify_gitlinks

probe_path="$(
  python3 - "$LEDGER" <<'PY'
import json
import sys
entries = json.load(open(sys.argv[1]))["entries"]
for entry in entries:
    if entry["repo"] == "app":
        print(entry["path"])
        break
else:
    raise SystemExit("ledger has no app repository to use as recovery probe")
PY
)"

git -C "$clone" submodule deinit --force -- "$probe_path"
if [[ -e "$clone/$probe_path/.git" ]]; then
  echo "recovery probe did not deinitialize $probe_path" >&2
  exit 1
fi

(
  cd "$clone"
  "$ZED_BIN" install --git-submodules
)

verify_gitlinks

actual_probe="$(git -C "$clone/$probe_path" rev-parse HEAD)"
expected_probe="$(
  python3 - "$LEDGER" "$probe_path" <<'PY'
import json
import sys
ledger, path = sys.argv[1:]
for entry in json.load(open(ledger))["entries"]:
    if entry["path"] == path:
        print(entry["commit"])
        break
else:
    raise SystemExit(f"missing ledger entry for {path}")
PY
)"

if [[ "$actual_probe" != "$expected_probe" ]]; then
  echo "zed recovery restored $probe_path at $actual_probe, expected $expected_probe" >&2
  exit 1
fi

echo "fresh-clone Zed submodule recovery OK: $probe_path -> $actual_probe"
