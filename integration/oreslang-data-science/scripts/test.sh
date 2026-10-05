#!/usr/bin/env sh
set -eu

audit_source() {
  borrow_violations="$(
    find src tests examples vendor -type f -name '*.ores' \
      -exec grep -nEH '(^|[(,=])[[:space:]]*&[[:space:]]*(mut[[:space:]]+)?[A-Za-z_(]|return[[:space:]]+&[[:space:]]*(mut[[:space:]]+)?[A-Za-z_(]' {} + \
      || true
  )"
  if [ -n "$borrow_violations" ]; then
    echo "explicit source-level borrow markers are forbidden in Oreslang reference code:" >&2
    echo "$borrow_violations" >&2
    exit 1
  fi

  escaped_newline_violations="$(
    grep -nH '\\n' README.md ROADMAP.md docs/*.md 2>/dev/null || true
  )"
  if [ -n "$escaped_newline_violations" ]; then
    echo "literal \\n sequences found in prose documentation:" >&2
    echo "$escaped_newline_violations" >&2
    exit 1
  fi
}

run_suites() {
  bin="$1"
  "$bin" tests/main.ores
  "$bin" tests/embeddings.ores
}

audit_source

if [ -n "${ORESLANG_BIN:-}" ]; then
  run_suites "$ORESLANG_BIN"
  exit 0
fi

if command -v oreslang >/dev/null 2>&1; then
  run_suites oreslang
  exit 0
fi

echo "oreslang compiler/runtime not found; set ORESLANG_BIN or install the oreslang CLI" >&2
exit 2
