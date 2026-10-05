#!/usr/bin/env sh
set -eu

roots=""
for dir in src tests examples vendor; do
  if [ -d "$dir" ]; then
    roots="$roots $dir"
  fi
done

if [ -z "$roots" ]; then
  echo "no Oreslang source roots found" >&2
  exit 1
fi

files="$(find $roots -type f -name '*.ores' -print | sort)"
if [ -z "$files" ]; then
  echo "no .ores source files found" >&2
  exit 1
fi

failed=0

# Ordinary Oreslang source is pointer-free. Reject unary/type borrow/address
# spellings without rejecting the legitimate binary bitwise '&' operator.
pointer_ampersands="$(
  grep -nHE '(^|[(:,=\[])\s*&\s*(mut\s+)?[A-Za-z_(]|return\s+&\s*(mut\s+)?[A-Za-z_(]|(->|:)\s*&\s*(mut\s+)?[A-Za-z_]' $files \
    || true
)"
if [ -n "$pointer_ampersands" ]; then
  echo "error: pointer/borrow-style '&' syntax found in Oreslang source" >&2
  echo "$pointer_ampersands" >&2
  failed=1
fi

# Reject unary raw-pointer dereference spellings while allowing compact
# multiplication such as a*b.
pointer_stars="$(
  grep -nHE '(^|[(:,=\[])\s*\*\s*[A-Za-z_(]|return\s+\*\s*[A-Za-z_(]' $files \
    || true
)"
if [ -n "$pointer_stars" ]; then
  echo "error: unary/raw-pointer '*' syntax found in Oreslang source" >&2
  echo "$pointer_stars" >&2
  failed=1
fi

# Harden the explicit iterator-binding grammar used by the current compiler
# direction. This catches stale bare 'for x of y' without rejecting C-style
# loops.
bare_iterators="$(
  grep -nHE '^[[:space:]]*for[[:space:]]+(\[[^]]+\]|[A-Za-z_][A-Za-z0-9_]*)[[:space:]]+of[[:space:]]' $files \
    || true
)"
if [ -n "$bare_iterators" ]; then
  echo "error: for-of iterator binding must be declared with const or let" >&2
  echo "$bare_iterators" >&2
  failed=1
fi

# Markdown should contain real line breaks, not escaped newline artifacts
# introduced by generated/editing patches.
doc_newlines="$(
  grep -nH '\\n' README.md ROADMAP.md docs/*.md 2>/dev/null || true
)"
if [ -n "$doc_newlines" ]; then
  echo "error: literal \\n sequence found in prose documentation" >&2
  echo "$doc_newlines" >&2
  failed=1
fi

# Multi-name imports are explicit selections. Bare comma-separated imports are
# ambiguous with the single-name grammar and must use braces.
if grep -nHE '^[[:space:]]*import[[:space:]]+(class|fnc|module|actor)[[:space:]]+[^{*][^;]*,[^;]*[[:space:]]+from[[:space:]]' $files; then
  echo "error: multi-name imports must use brace selections" >&2
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  exit 1
fi

echo "Oreslang source invariants OK"
