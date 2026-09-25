# ores-comparisons

Runnable comparison projects for three ORES deployment stacks:

- `beamscale` — Erlang/OTP actor lambdas with P1/P2/P3 lifecycle and hot generation activation.
- `scintilla-run` — container/bare-process lambdas and subprocess-oriented execution.
- `ores-stack` — Rust/WASM-capable standalone servers and lambdas with `api-docs`-driven RPC/GraphQL/page contracts.

Each stack contains the same three organization-scale scenarios under `stacks/<stack>/projects/`:

1. `big-org-example-commerce` — quote/order/customer flows.
2. `big-org-example-collaboration` — forms, chat, conversations, presence, and sync.
3. `big-org-example-operations` — internal workflows, audit/telemetry, rate limiting, caching, and RPC.

Every project references the canonical `integrations.toml` surface and enables the same feature gates in `comparison.toml`:

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

No plaintext environment files are committed. Each stack owns one encrypted/decrypted boundary shared by its three scenarios:

```text
stacks/<stack>/env/
  enc/   # SOPS ciphertext committed to git
  dec/   # local decrypted material, ignored except documentation
```

Use the Nix development shell so `sops` and `age` are pinned with the BEAM/Rust/Node toolchains:

```sh
nix develop
export SOPS_AGE_KEY_FILE="$HOME/.config/sops/age/keys.txt"
./scripts/materialize-env.sh stacks/beamscale/projects/big-org-example-commerce dev
```

The helper reads `stacks/beamscale/env/enc/dev.env.yaml` and writes a project-qualified decrypted file under `env/dec/`. Encryption is deliberately recipient-driven; the repository does not invent or commit an organization age private key.

## Comparison contract

The scenarios keep application behavior comparable while preserving each platform's native deployment contract. BeamScale projects use nested hosted `lambdas/` and `bmscl build/deploy`; external ORES integrations remain behind the trusted P2 capability boundary. Scintilla projects use `.scintilla-endpoint.toml`, containerized subprocesses, and `scintilla build/deploy`. ORES Stack projects use `.ores-stack.toml`, pinned `TypedModule` contracts, native Rust servers, and `api-docs` route maps; `src/pages/` is reserved for the MASH/Leptos/Dioxus WASM/SSR benchmark variants.

`repos/` under each stack is reserved for pinned git submodules when a benchmark needs source snapshots for a whole GitHub organization. Normal builds should prefer released or immutable-pinned SDK dependencies rather than mutable default branches.

Run the repository layout/security gate with:

```sh
./scripts/verify-layout.sh
```
