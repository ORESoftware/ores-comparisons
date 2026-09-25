# Scintilla / forms-chat-workflow

A Scintilla v1 project with an authored `.scintilla-endpoint.toml`. Secret
**names** are declared with `env_from`; their values remain in the runtime
secret authority and never enter `.scintilla/scintilla-project.json`.

The source is deliberately runnable as a normal python3 program as well
as being the endpoint source body, which makes process/container overhead easy
to benchmark outside the platform.

## Run

```sh
scintilla build --project . --out-dir .scintilla --check
scintilla deploy --project . --out-dir .scintilla --dry-run

SCINTILLA_BASE_URL=http://127.0.0.1:8080 scintilla dev --project . --once
```
