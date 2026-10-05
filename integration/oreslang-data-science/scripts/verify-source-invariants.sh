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

# Oreslang userland is pointer-free. Any ampersand in .ores source is therefore
# suspicious: address-of, &T, and &mut are all forbidden surface syntax.
if grep -nH '&' $files; then
  echo "error: pointer-style '&' syntax found in Oreslang source" >&2
  failed=1
fi

# Catch the compact unary dereference spelling (*value). Numeric multiplication
# in this repository is formatted with whitespace around '*'.
if grep -nHE '\*[A-Za-z_][A-Za-z0-9_]*' $files; then
  echo "error: unary/raw-pointer '*' spelling found in Oreslang source" >&2
  failed=1
fi

# Harden the explicit iterator-binding grammar used by the current compiler
# direction. This catches the stale bare 'for x of y' form without rejecting
# C-style loops.
if grep -nHE '^[[:space:]]*for[[:space:]]+(\[[^]]+\]|[A-Za-z_][A-Za-z0-9_]*)[[:space:]]+of[[:space:]]' $files; then
  echo "error: for-of iterator binding must be declared with const or let" >&2
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  exit 1
fi

echo "Oreslang source invariants OK"
