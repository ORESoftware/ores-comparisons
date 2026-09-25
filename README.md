# ores-comparisons

Runnable comparison projects for three ORES deployment stacks:

- `beamscale` — Erlang/OTP actor lambdas with P1/P2/P3 lifecycle and hot generation activation.
- `scintilla-run` — container/bare-process lambdas and subprocess-oriented execution.
- `ores-stack` — Rust/WASM servers and lambdas with `api-docs`-driven RPC/GraphQL/page generation.

Each stack contains the same three organization-scale scenarios under `stacks/<stack>/projects/`:

1. `big-org-example-commerce` — quote/order/customer flows.
2. `big-org-example-collaboration` — forms, chat, conversations, presence, and sync.
3. `big-org-example-operations` — internal workflows, audit/telemetry, rate limiting, caching, and RPC.

Every project declares the same integration surface in `comparison.toml`:

- `github.com/ores-otel`
- `github.com/ores-forms`
- `github.com/opto-sync`
- `github.com/ores-chat`
- `github.com/ores-convo`
- `github.com/ores-rate-limit`
- `github.com/oresoftware/ores-middleware`
- `github.com/ores-redis-lru-cache`
- `github.com/oresoftware/api-docs`
- `github.com/oresoftware/ores-sops`
- `github.com/ores-sops`

## Secrets

No plaintext environment files are committed. Each project owns:

```text
env/
  enc/   # SOPS ciphertext committed to git
  dec/   # local decrypted material, ignored except README/.gitkeep
```

Use the Nix development shell so `sops` and `age` are pinned with the stack toolchains:

```sh
nix develop
export SOPS_AGE_KEY_FILE="$HOME/.config/sops/age/keys.txt"
./scripts/materialize-env.sh stacks/beamscale/projects/big-org-example-commerce dev
```

Encryption is deliberately recipient-driven; the repository does not invent or commit an organization age private key.

## Comparison contract

The scenarios intentionally keep application behavior comparable while preserving each platform's native deployment contract. BeamScale projects use nested `lambdas/` and `middleware/` units and `bmscl build/deploy`. Scintilla projects use `.scintilla-endpoint.toml` and `scintilla build/deploy`. ORES Stack projects use `.ores-stack.toml`, Rust/WASM pages, and `api-docs` route/RPC authority.

Run the repository layout/security gate with:

```sh
./scripts/verify-layout.sh
```
