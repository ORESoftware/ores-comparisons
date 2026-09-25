# Scintilla form-service

Container/subprocess version of the matched form workload. Endpoint contracts contain environment *names* only; values are resolved by the deployment/runtime secret authority.

```sh
scintilla build --project . --out-dir .scintilla
scintilla deploy --project . --out-dir .scintilla --dry-run
SCINTILLA_BASE_URL=http://127.0.0.1:8080 scintilla dev --project . --once
```
