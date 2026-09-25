# Scintilla comparison projects

These projects use Scintilla's authored `.scintilla-endpoint.toml` contract and
exercise three supported runtimes while keeping the logical workload aligned
with the other stacks.

- `http-observability` — Node.js.
- `forms-chat-workflow` — Python 3.
- `cached-rpc` — Rust.

Use `scintilla build --project . --out-dir .scintilla --check` and
`scintilla deploy --project . --out-dir .scintilla --dry-run`.
